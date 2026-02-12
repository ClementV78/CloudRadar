# Code Review: `infra/aws/live` + `infra/aws/modules`

**Date:** 11 février 2026  
**Reviewer:** Codex  
**Scope:** Architecture Terraform, sécurité, coûts, patterns IaC, observabilité infra

---

## Contexte

**Stack décidé:** Terraform (remote state S3 + DynamoDB lock), k3s sur EC2, NAT instance, edge Nginx (ADR-0002, ADR-0008, ADR-0010)  
**Environnements:** `live/dev` (stack complet déployé), `live/prod` (baseline VPC uniquement)  
**Modules:** `vpc`, `nat-instance`, `k3s`, `edge`, `backup-bucket`  
**Modules stub:** `eks`, `msk`, `rds` (réservés pour v2, contiennent uniquement `.gitkeep`)

---

## 🟢 Points Forts

### 1. Architecture Réseau — Séparation Public/Privé

Le modèle réseau est solide et bien pensé pour un MVP cost-aware :

- **Public subnet** : Edge Nginx + NAT instance uniquement. Surface d'attaque minimale.
- **Private subnet** : k3s nodes (server + workers). Aucun accès direct depuis internet.
- **NAT instance au lieu de NAT Gateway** : économie significative (~$32/mois vs ~$0.50/mois pour t3.nano). Choix cohérent avec l'ADR-0008 et la philosophie FinOps du projet.
- **VPC endpoints conditionnels** pour SSM/KMS/S3 : feature-flaggables, permettent de couper les coûts quand non nécessaires.
- **S3 gateway endpoint** : Trafic S3 ne transite pas par le NAT (0 coût data transfer).

```terraform
resource "aws_vpc_endpoint" "s3_gateway" {
  count = var.edge_ssm_vpc_endpoints_enabled ? 1 : 0
  // Route S3 via gateway, pas via NAT
  route_table_ids = [module.vpc.public_route_table_id, module.vpc.private_route_table_id]
}
```

### 2. Sécurité — Defense in Depth

Plusieurs couches de sécurité bien appliquées :

- **IMDSv2 obligatoire** sur toutes les instances (`http_tokens = "required"`). Protège contre les SSRF exploitant l'IMDS.
- **Security Groups self-référencés** pour k3s (6443/10250/8472) : seuls les nodes du cluster communiquent entre eux.
- **NodePorts dédupliqués dynamiquement** pour éviter les règles SG redondantes quand Traefik partage un même port :

```terraform
local.edge_nodeport_ports = distinct([
  for _, port in local.edge_nodeport_rules : port
  if port != null && port != 80 && port != 443
])
```

- **Edge egress restreinte** : Seul le trafic vers le private subnet est autorisé en sortie (+ SSM conditionnel).
- **SSM au lieu de SSH** : Pas de port 22 ouvert, pas de clé SSH à gérer. Accès via Session Manager.
- **S3 backup bucket** avec public access block total (4 flags) + versioning + SSE AES256.
- **Secrets en SSM Parameter Store** (SecureString pour passwords, String pour config). Jamais en clair dans le state ou le code.
- **IAM least-privilege** : Chaque composant a son propre role avec des policies scoped :
  - k3s nodes : SSM core + SSM secrets (scoped `/cloudradar/*`) + EBS CSI (conditionnel) + backup bucket S3 (conditionnel) + CloudWatch read (conditionnel)
  - Edge : SSM core + lecture de 2 paramètres SSM spécifiques uniquement
  - NAT : Pas de role IAM (pas besoin)

### 3. Modularité Terraform — Clean Separation

La structure de modules est bien découpée et réutilisable :

| Module | Responsabilité | Couplage |
| --- | --- | --- |
| `vpc` | Réseau pur (VPC, subnets, route tables, flow logs) | Zéro dépendance externe |
| `nat-instance` | NAT EC2 (SG, route, user-data) | Dépend de VPC outputs |
| `k3s` | Cluster k3s (server EC2, worker ASG, IAM, SG) | Dépend de VPC outputs |
| `edge` | Reverse proxy Nginx (EC2, SG, IAM, templates) | Dépend de VPC + k3s IP |
| `backup-bucket` | S3 sécurisé (versioning, SSE, public block) | Standalone |

**Points forts de la modularité :**
- Chaque module a ses propres `variables.tf`, `outputs.tf`, `versions.tf`
- Les modules sont composables : le live root assemble les modules et câble les cross-references (SG rules inter-modules dans `main.tf`)
- Les règles SG inter-modules (edge → k3s NodePorts) sont dans le live root, pas dans les modules eux-mêmes → bon découplage

### 4. Cloud-init & Bootstrap

Le provisioning EC2 est robuste :

- **k3s server** : cloud-init installe SSM agent, crée un swap 2G, déploie une HelmChartConfig Traefik (NodePort 30080/30443 + Prometheus metrics), puis installe k3s avec `--secrets-encryption`.
- **k3s agent** : même pattern (SSM agent + swap), puis rejoint le server via l'IP privée et le token généré par Terraform.
- **Edge** : user-data complet avec retry loop (6 tentatives × 10s) pour récupérer les credentials SSM, génère un certificat self-signed, installe Nginx via template.
- **Token k3s** : `random_password` Terraform (32 chars, no special) — jamais exposé en clair.

```terraform
resource "random_password" "k3s_token" {
  length  = 32
  special = false
}
```

### 5. Observabilité Infra

- **VPC Flow Logs** feature-flaggables avec rotation configurable (défaut 3 jours, FinOps-friendly).
- **IAM role dédié** pour VPC Flow Logs (least-privilege : `CreateLogStream` + `PutLogEvents` uniquement sur le log group spécifique).
- **Grafana CloudWatch datasource** : Policy IAM read-only attachée conditionnellement au role k3s, pas de clé statique.
- **Logs operations scoped** : `FilterLogEvents`/`GetLogEvents` limités à `arn:aws:logs:*:<account>:log-group:/cloudradar/*`.
- **Node-exporter port** (9100) ouvert en intra-cluster pour Prometheus scraping.

### 6. Configuration & Paramétrage

- **terraform.tfvars.example** fourni avec tous les paramètres commentés et documentés — facilite l'onboarding.
- **backend.hcl.example** fourni pour la config S3 backend — pas de valeurs réelles committées.
- **Validations sur les variables** :
  - SHA256 validé par regex (`^[A-Fa-f0-9]{64}$`)
  - Cross-validation : `processor_aircraft_db_s3_uri` requis si `processor_aircraft_db_enabled = true`
  - AZs, subnet CIDRs : validation de longueur cohérente

```terraform
validation {
  condition     = length(var.public_subnet_cidrs) == length(var.azs)
  error_message = "public_subnet_cidrs must match the number of azs."
}
```

- **Sentinels SSM** : Quand une valeur SSM String est désactivée, un sentinel `__disabled__` / `__none__` est utilisé car SSM n'accepte pas les valeurs vides. Design pragmatique et documenté.

### 7. Tagging Cohérent

Toutes les ressources reçoivent des tags Project + Environment + Name via un pattern `merge(var.tags, { ... })` consistent. Facilite l'inventaire, le cost allocation et le debugging dans la console AWS.

### 8. Edge Nginx — Reverse Proxy Complet

Le template Nginx est bien structuré :
- **6 upstreams** distinct (dashboard, API, health, admin, prometheus, grafana) avec ports configurables
- **Basic auth** partout (htpasswd via SSM)
- **Admin token interne** injecté par sed dans la config (protège l'API admin)
- **HTTP → HTTPS redirect** configurable
- **Subpath routing** correct pour Grafana/Prometheus (`X-Forwarded-Prefix`)
- **Self-signed TLS** pour dev (suffisant avant CloudFront)

### 9. Prod — Baseline Minimal

`live/prod` ne déploie que le VPC (pas de k3s/edge/NAT) : approche correcte pour un MVP. Les ressources seront ajoutées progressivement. Les fichiers `monitoring.tf`, `processor.tf` et `dns.tf` sont déjà en place (copie de dev) pour faciliter l'extension.

---

## 🟡 Observations & Améliorations

### 1. NAT Instance — Pas de Haute Disponibilité ni de Healthcheck

```terraform
resource "aws_instance" "nat" {
  // ...
  source_dest_check = false
  user_data = <<-EOF
    #!/bin/bash
    sysctl -w net.ipv4.ip_forward=1
    iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
  EOF
}
```

**Observation:** Le NAT instance est un single point of failure. Si l'instance tombe, tout le private subnet perd l'accès internet (k3s ne peut plus pull d'images, pas de mises à jour, etc.).

**Ce qui manque :**
- Pas de health check (ASG ou CloudWatch alarm + auto-recover)
- Pas de persistence iptables (`service iptables save || true` ne fonctionne pas sur AL2 sans iptables-services)
- Pas d'EIP attachée (l'IP publique change au reboot → casse les éventuels allowlists)

**Trade-off accepté :** Pour un projet MVP solo, c'est raisonnable. Mais si l'instance NAT crashe en production, le cluster est isolé jusqu'à intervention manuelle.

**Recommandation :** Ajouter un auto-recovery CloudWatch alarm (`StatusCheckFailed_System`) ou migrer vers un ASG min=max=1. Coût : ~$0.

---

### 2. k3s — AMI latest non pinnée

```terraform
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-minimal-*-x86_64"]
  }
}
```

**Observation:** L'AMI sélectionnée est toujours la `most_recent`. Un `terraform apply` peut upgrader l'AMI et déclencher un remplacement d'instance (via `user_data_replace_on_change = true`), ce qui détruirait le server k3s et toutes les données PVC attachées.

**Risque réel :** En pratique, les AMI Amazon changent toutes les ~2 semaines. Un `plan` inattendu peut proposer de détruire le node k3s.

**Recommandation :** Pinner l'AMI dans `terraform.tfvars` (comme déjà prévu pour edge via `edge_ami_id`) ou ajouter une variable `k3s_ami_id` avec le même pattern optional.

---

### 3. Duplication Code Dev/Prod

Les fichiers `monitoring.tf`, `processor.tf` et `dns.tf` sont identiques entre `live/dev` et `live/prod`. Si une modification est faite dans un seul des deux, l'autre se désynchronise.

**Recommandation :** Acceptable pour un projet MVP solo. Pour une v2, considérer Terragrunt ou un module wrapper qui encapsule la logique commune (monitoring + processor SSM + DNS).

---

### 4. Modules Stub Inutilisés

Les répertoires `infra/aws/modules/eks`, `infra/aws/modules/msk`, `infra/aws/modules/rds` contiennent uniquement un `.gitkeep`.

**Observation:** Ce sont des placeholders pour v2 (EKS migration, Kafka, RDS). Pas un problème, mais potentiellement confus pour un nouveau contributeur.

**Recommandation :** Ajouter un `README.md` d'une ligne dans chaque module stub (e.g., "Reserved for v2 — not yet implemented") ou supprimer les stubs jusqu'à utilisation effective.

---

### 5. Edge — `edge_allowed_cidrs = ["0.0.0.0/0"]`

```terraform
edge_allowed_cidrs = ["0.0.0.0/0"]
```

**Observation:** L'edge est ouvert à tout internet en dev. Pour un portfolio showcase, c'est intentionnel (démonstration). Mais en prod, un filtrage par IP serait souhaitable si le projet est accessible publiquement.

**Contexte :** Basic auth est en place, donc le risque est atténué. Mais l'exposition publique du port 443 à `0.0.0.0/0` combinée à un certificat self-signed peut attirer des scanners.

**Recommandation :** Documenter explicitement que c'est un choix conscient pour le portfolio (déjà couvert par l'intent du projet). Pas de changement nécessaire.

---

### 6. Prod — Variables Inutilisées

`live/prod/variables.tf` déclare `grafana_admin_password`, `processor_aircraft_db_*`, etc. mais `main.tf` n'utilise que le module VPC.

**Observation:** Les SSM parameters dans `processor.tf` et `monitoring.tf` sont créés en prod mais n'ont pas de consumer (pas de k3s ni d'edge en prod). Ce n'est pas un bug (SSM est cheap), mais c'est de la configuration orpheline.

**Recommandation :** Acceptable pour préparer la promotion dev → prod. Documenter dans un commentaire ou le README infra.

---

## 🔴 Issues Critiques

**Aucune.** L'infra est **solide pour un MVP** :
- Pas de credentials exposés (SSM + OIDC)
- Pas de ports inutiles ouverts
- Pas de ressources publiques non protégées
- IAM least-privilege respecté

---

## Résumé

L'infrastructure Terraform est **bien structurée** et reflète une **maîtrise des bonnes pratiques AWS/IaC** :

| Aspect | Verdict |
| --- | --- |
| Architecture Réseau | ✅ Séparation public/privé, NAT cost-optimized |
| Sécurité | ✅ IMDSv2, SSM-only, SG scoped, S3 public block |
| IAM | ✅ Least-privilege, policies conditionnelles |
| Modularité Terraform | ✅ Modules découplés, composables |
| Observabilité Infrastructure | ✅ VPC Flow Logs, CloudWatch, node-exporter |
| FinOps | ✅ NAT instance, VPC endpoints conditionnels, free-tier first |
| Configuration | ✅ Examples fournis, validations, sentinels SSM |
| Bootstrap EC2 | ✅ Cloud-init robuste, retry loops, swap mgmt |
| Production-ready (MVP) | ✅ Oui |

**Recommandation :** Utilisable tel quel. Les améliorations (NAT recovery, AMI pinning, déduplication dev/prod) sont pertinentes pour une v1.1+ mais non bloquantes pour le MVP.
