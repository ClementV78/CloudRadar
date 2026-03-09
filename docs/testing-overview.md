# CloudRadar — Testing & Quality Assurance Overview

> Vue consolidée de la stratégie de tests, des pipelines de qualité et des pratiques DevSecOps du projet CloudRadar.
> Objectif : montrer une approche structurée, multi-couche, conforme aux bonnes pratiques Cloud Architecture et DevSecOps.

---

## Table des matières

- [CloudRadar — Testing \& Quality Assurance Overview](#cloudradar--testing--quality-assurance-overview)
  - [Table des matières](#table-des-matières)
  - [1. Big Picture](#1-big-picture)
    - [Chiffres clés](#chiffres-clés)
  - [2. Shift-Left Testing](#2-shift-left-testing)
  - [3. Les 9 catégories de tests](#3-les-9-catégories-de-tests)
    - [Matrice catégorie × couverture](#matrice-catégorie--couverture)
  - [4. Pipelines CI/CD](#4-pipelines-cicd)
  - [5. Couverture par service](#5-couverture-par-service)
  - [6. Améliorations possibles](#6-améliorations-possibles)

---

## 1. Big Picture

CloudRadar est composé de **6 microservices** écrits en 4 langages (Java, TypeScript, Python, Shell), communiquant via **Redis** comme bus de données. Cette diversité technique impose une stratégie de tests multi-couche : on ne peut pas tout couvrir avec un seul framework. La pyramide ci-dessous montre comment les tests sont empilés, du plus rapide (unitaire) au plus lent (performance).

```mermaid
block-beta
  columns 2

  P["🏔️ Performance<br>tenue en charge"]:1 Pd["k6 nightly · 10 VUs · p95 < 1500ms"]:1
  E["🌐 E2E Smoke<br>santé après déploiement"]:1 Ed["/healthz · /api/flights · /grafana"]:1
  D["🔗 Integration<br>services + vraie base Redis"]:1 Dd["Redis Testcontainers · 3 services"]:1
  C["📝 Contract<br>format d'échange entre services"]:1 Cd["JSON parsing · Redis keys"]:1
  S["🚀 Context Smoke<br>chaque service démarre"]:1 Sd["@SpringBootTest · contextLoads"]:1
  U["🧪 Unit / Slice<br>logique métier isolée"]:1 Ud["JUnit · Mockito · @WebMvcTest · Vitest"]:1
  SA["🔍 Qualité du code — Hadolint, SonarCloud, terraform fmt"]:2
  SC["🛡️ Sécurité des dépendances — Trivy CVE, GitGuardian secrets"]:2

  style P fill:#e1bee7,color:#000
  style Pd fill:#f3e5f5,color:#000
  style E fill:#ce93d8,color:#000
  style Ed fill:#e1bee7,color:#000
  style D fill:#ba68c8,color:#fff
  style Dd fill:#ce93d8,color:#000
  style C fill:#ab47bc,color:#fff
  style Cd fill:#ba68c8,color:#fff
  style S fill:#9c27b0,color:#fff
  style Sd fill:#ab47bc,color:#fff
  style U fill:#7b1fa2,color:#fff
  style Ud fill:#9c27b0,color:#fff
  style SA fill:#37474f,color:#fff
  style SC fill:#263238,color:#fff
```

> Lecture de bas en haut. Les couches basses sont rapides (< 1 min) et nombreuses. Plus on monte, plus les tests sont lents et ciblés. Les deux barres sombres sont **transversales** : elles tournent en parallèle de tout le reste.

| Couche | Ce qu'elle protège | Exemple concret |
|---|---|---|
| Unit / Slice | La logique métier isolée | "le parsing d'un vol OpenSky retourne les bons champs" |
| Context Smoke | Le démarrage de chaque service | "Spring Boot démarre sans erreur de configuration" |
| Contract | Le format d'échange entre services | "le JSON écrit par l'ingester est lisible par le processor" |
| Integration | Le fonctionnement avec une vraie base Redis | "l'ingester écrit dans Redis, le dashboard relit correctement" |
| E2E Smoke | L'application déployée en conditions réelles | "après deploy, /api/flights retourne du JSON valide" |
| Performance | La tenue en charge | "10 utilisateurs simultanés, temps de réponse < 1.5s" |
| Qualité du code | Le respect des standards et bonnes pratiques | "les Dockerfiles suivent les best-practices, l'IaC est sécurisée" |
| Sécurité dépendances | L'absence de failles connues dans les librairies | "aucune CVE critique dans les dépendances Maven/npm" |

### Chiffres clés

| Indicateur | Valeur |
|---|---|
| Tests automatisés | **52 tests** (15 fichiers, 4 langages) |
| Catégories de tests couvertes | **9** (unit, slice, integration, contract, smoke, security, quality, infra, perf) |
| Workflows GitHub Actions | **9** (dont 5 liés aux tests/qualité) |
| Services avec tests | **4/6** (ingester, processor, dashboard, frontend) |
| Ratio pyramide (unit / integ / E2E) | ~70% / 20% / 10% |

---

## 2. Shift-Left Testing

### Le concept

Dans une approche classique, les tests de sécurité, de qualité et d'infrastructure sont exécutés **tard** dans le cycle : en staging, voire en production. On découvre les problèmes après avoir déployé, quand corriger coûte cher en temps et en énergie.

Le **Shift-Left** inverse cette logique : on **décale les vérifications vers la gauche** de la timeline (= vers le début), pour attraper les erreurs le plus tôt possible — idéalement dès que le développeur pousse son code.

```mermaid
flowchart LR
  subgraph TRAD["❌ Approche traditionnelle"]
    direction LR
    T1["Dev"] --> T2["Build"] --> T3["Deploy staging"] --> T4["Tests sécu + qualité"] --> T5["😱 Bug trouvé tard"]
  end

  subgraph SHIFT["✅ Shift-Left (CloudRadar)"]
    direction LR
    S1["Dev"] --> S2["PR : tests + sécu + qualité"] --> S3["Build"] --> S4["Deploy"] --> S5["✅ Confiance"]
  end

  style TRAD fill:#ffebee,stroke:#e53935,color:#000
  style SHIFT fill:#e8f5e9,stroke:#43a047,color:#000
  style T5 fill:#e53935,color:#fff
  style S2 fill:#43a047,color:#fff
  style S5 fill:#43a047,color:#fff
```

### Ce que CloudRadar applique

Dans ce projet, **8 vérifications automatiques sur 10 tournent avant le merge**. Le développeur obtient un retour en quelques minutes, pas après un déploiement raté.

Concrètement, quand un développeur ouvre une Pull Request sur CloudRadar, **4 workflows GitHub Actions se lancent en parallèle** : les tests applicatifs (Java + React), la validation Kubernetes, la vérification Terraform, et l'analyse SonarCloud. Le tout prend environ 5 minutes. Si un seul échoue, le merge est bloqué — impossible de casser `main` par accident.

```mermaid
flowchart LR
  DEV["🧑‍💻 Dev local<br>tests · lint · format"] -->|"< 5 min"| PR["🔀 Pull Request<br>8 gates bloquantes"]
  PR -->|"tout vert"| MERGE["✅ Build & Push<br>6 images Docker"]
  MERGE --> DEPLOY["🚀 Deploy<br>infra + app + smoke"]
  DEPLOY -.-> NIGHT["🌙 Nightly<br>test de charge"]

  style DEV fill:#c8e6c9,color:#000
  style PR fill:#bbdefb,color:#000
  style MERGE fill:#fff3e0,color:#000
  style DEPLOY fill:#fce4ec,color:#000
  style NIGHT fill:#f3e5f5,color:#000
```

> **80% des checks** se jouent sur les deux premières étapes (Dev + PR). Les 20% restants (build d'images, deploy, perf) ne tournent qu'après le merge ou en nightly.

**Pourquoi c'est du Shift-Left ?** Traditionnellement, les tests de sécurité, de qualité et d'infrastructure se font tard (en staging ou en prod). Ici, ils sont tous exécutés **sur chaque Pull Request**, avant le merge :

| Gate PR (bloquant) | Ce qu'on vérifie | Temps |
|---|---|---|
| Tests Java (3 services) | Le code métier fonctionne | 1–4 min |
| Tests Frontend (Vitest) | L'interface s'affiche correctement | 20–60s |
| Hadolint (6 Dockerfiles) | Les images Docker suivent les bonnes pratiques | 20–60s |
| Trivy (dépendances) | Aucune faille de sécurité connue (CVE) | 30–120s |
| kubeconform (manifests k8s) | Les fichiers Kubernetes sont valides | 3–5s |
| tfsec (Terraform) | L'infrastructure as code est sécurisée | 10–30s |
| Terraform plan | L'infra peut être appliquée sans erreur | 1–3 min |
| SonarCloud | La qualité globale du code est maintenue | 2–4 min |

---

## 3. Les 9 catégories de tests

Pour couvrir à la fois le code applicatif, l'infrastructure cloud (Terraform, Kubernetes) et la sécurité des dépendances, CloudRadar utilise **9 catégories de tests** réparties en 3 familles. Cette organisation garantit que chaque type de changement — qu'il touche du Java, un manifest k8s ou un module Terraform — est validé par des checks adaptés.

```mermaid
block-beta
  columns 3

  APP["🟢 Code applicatif"]:3
  U["🧪 Unit / Slice<br>logique métier isolée"]:1
  I["🔗 Integration<br>services + Redis réel"]:1
  C["📝 Contract<br>format JSON entre services"]:1

  INFRA["🔵 Infrastructure & déploiement"]:3
  IV["⚙️ Infra Validation<br>Terraform + manifests k8s"]:1
  E["🌐 E2E / Smoke<br>santé post-déploiement"]:1
  P["🏔️ Performance<br>tenue en charge (k6)"]:1

  CROSS["🟠 Transversal (toutes les PR)"]:3
  S["🔒 Sécurité<br>CVE · secrets · IaC"]:1
  Q["📏 Qualité<br>lint · coverage · smells"]:1
  UI["🖥️ UI<br>rendu composants React"]:1

  style APP fill:#43a047,color:#fff
  style U fill:#e8f5e9,color:#000
  style I fill:#c8e6c9,color:#000
  style C fill:#a5d6a7,color:#000

  style INFRA fill:#1976d2,color:#fff
  style IV fill:#e3f2fd,color:#000
  style E fill:#bbdefb,color:#000
  style P fill:#90caf9,color:#000

  style CROSS fill:#ef6c00,color:#fff
  style S fill:#fff3e0,color:#000
  style Q fill:#ffe0b2,color:#000
  style UI fill:#ffcc80,color:#000
```

> **3 familles** : les tests **verts** valident le code applicatif (Java/React), les tests **bleus** valident l'infrastructure et le déploiement, les tests **oranges** sont transversaux et s'exécutent sur chaque PR quelle que soit la nature du changement.

### Matrice catégorie × couverture

| Catégorie | Quoi | Quand | Où | Statut |
|---|---|---|---|---|
| 🧪 **Unit / Slice** | Business logic, contrôleurs, parsing | PR | `build-and-push` | ✅ Implémenté |
| 🔗 **Integration** | Context Spring Boot + data-path Redis | PR | `build-and-push` | ✅ Implémenté |
| 📝 **Contract** | JSON serialization, Redis key format | PR | `build-and-push` | ✅ Implémenté |
| 🌐 **E2E / Smoke** | Health + data pipeline post-deploy | Dispatch | `ci-infra` | ✅ Implémenté |
| 🔒 **Security** | Dépendances CVE, secrets, IaC | PR | `build-and-push` + `ci-infra` | ✅ Implémenté |
| 📏 **Code Quality** | Smells, duplication, coverage trends | PR | `sonarcloud` + `build-and-push` | ✅ Implémenté |
| ⚙️ **Infra Validation** | Terraform + k8s manifest schemas | PR | `ci-infra` + `ci-k8s` | ✅ Implémenté |
| 🏔️ **Performance** | Latence p95, taux d'erreur | Nightly / dispatch | `k6-nightly-baseline` | ✅ Implémenté |
| 🖥️ **UI** | Render smoke composants React | PR | `build-and-push` | ✅ Implémenté |

---

## 4. Pipelines CI/CD

Les 9 workflows GitHub Actions de CloudRadar sont organisés pour **tourner en parallèle** et donner un feedback rapide. Chaque workflow a un périmètre clair et un déclencheur précis. L'authentification AWS se fait par **OIDC** (pas de clés stockées), et les builds Docker utilisent une **matrice** pour construire les 6 images en parallèle.

Voici **qui vérifie quoi, et quand** :

```mermaid
block-beta
  columns 2

  PR["🔀 Sur chaque Pull Request"]:2
  BAP["🏗️ build-and-push<br>Compile, teste et scanne les 6 services"]:1
  SQG["📊 sonarcloud<br>Dette technique, couverture, duplication"]:1
  CIK["☸️ ci-k8s<br>Manifests Kubernetes valides ?"]:1
  CII["🔒 ci-infra<br>Terraform sûr et déployable ?"]:1

  POST["Après merge"]:2
  CIID["🌍 ci-infra dispatch<br>Deploy complet + smoke tests"]:1
  K6["⚡ k6 nightly<br>10 utilisateurs — l'app tient la charge ?"]:1

  style PR fill:#1976d2,color:#fff
  style BAP fill:#bbdefb,color:#000
  style SQG fill:#bbdefb,color:#000
  style CIK fill:#bbdefb,color:#000
  style CII fill:#bbdefb,color:#000
  style POST fill:#757575,color:#fff
  style CIID fill:#ffe0b2,color:#000
  style K6 fill:#e1bee7,color:#000
```

| Workflow | Rôle en une phrase | Vérifie | Temps |
|---|---|---|---|
| **build-and-push** | Compiler et tester les 6 services | Tests Java ×3, React, lint Dockerfiles ×6, scan CVE | 2–5 min |
| **sonarcloud** | Surveiller la dette technique | Quality gate, coverage, code smells, duplication | 2–4 min |
| **ci-k8s** | Valider les fichiers Kubernetes | Schemas kubeconform, sync versions, noms d'images | < 1 min |
| **ci-infra** (PR) | Vérifier l'infra avant déploiement | terraform fmt/validate/plan, tfsec sécurité | 1–3 min |
| **ci-infra** (dispatch) | Déployer et vérifier en conditions réelles | Terraform apply → ArgoCD sync → smoke tests | 5–15 min |
| **k6-nightly** | Mesurer la performance chaque nuit | p95 < 1.5s, taux d'erreur < 5%, checks > 95% | ~1 min |

> Détails de chaque workflow : voir `docs/runbooks/ci-cd/`.

---

## 5. Couverture par service

CloudRadar a 6 microservices. Les 3 services Java (ingester, processor, dashboard) concentrent la majorité des tests car ils portent la logique métier — ingestion OpenSky, agrégation Redis, API REST. Le frontend React a des tests de rendu (Vitest). Les 2 services Python (health, admin-scale) sont des utilitaires légers sans tests pour l'instant.

```mermaid
block-beta
  columns 6
  header["Couverture tests par service"]:6
  space:6
  A["dashboard"] B["ingester"] C["processor"] D["frontend"] E["health"] F["admin-scale"]
  A1["37 tests"] B1["3 tests"] C1["7 tests"] D1["5 tests"] E1["0 test"] F1["0 test"]

  style A1 fill:#4caf50,color:#fff
  style B1 fill:#4caf50,color:#fff
  style C1 fill:#4caf50,color:#fff
  style D1 fill:#4caf50,color:#fff
  style E1 fill:#f44336,color:#fff
  style F1 fill:#f44336,color:#fff
```

4 services sur 6 ont des tests automatisés (52 tests, 15 fichiers). Les 3 services Java couvrent les 3 niveaux de la pyramide : unitaire (Mockito, @WebMvcTest), intégration (Redis Testcontainers), et context smoke (@SpringBootTest). Le frontend couvre le rendu composant (Vitest + Testing Library).

Les contrats inter-services (clés Redis, format JSON) sont validés par des tests Testcontainers dédiés dans chaque service — documentés dans `docs/events-schemas/redis-keys.md`.

SonarCloud ingère la couverture Java (JaCoCo) et frontend (lcov) pour un suivi de tendance unifié.

---

## 6. Workflow × catégorie : qui vérifie quoi ?

Cette matrice croise les 6 workflows avec les 9 catégories de tests. Elle permet de vérifier d'un coup d'œil qu'**aucune catégorie n'est orpheline** — chaque type de vérification est porté par au moins un workflow.

| Catégorie | build-and-push | sonarcloud | ci-k8s | ci-infra PR | ci-infra deploy | k6 nightly |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| 🧪 Unit | 🟢 | | | | | |
| 🔗 Integ | 🟢 | | | | | |
| 📝 Contract | 🟢 | | | | | |
| 🖥️ UI | 🟢 | | | | | |
| 🔒 Sécu | 🟢 | | | 🟢 | | |
| 📏 Qualité | | 🟢 | | | | |
| ⚙️ Infra | | | 🟢 | 🟢 | | |
| 🌐 E2E | | | | | 🟢 | |
| 🏔️ Perf | | | | | | 🟢 |

> `build-and-push` porte **5/9 catégories**. Toutes les catégories sont couvertes par au moins un workflow.

---

## 7. Améliorations possibles

```mermaid
quadrantChart
  title Rapport effort / impact des améliorations
  x-axis "Effort faible" --> "Effort élevé"
  y-axis "Impact faible" --> "Impact élevé"

  "Dependabot": [0.08, 0.80]
  "SpotBugs": [0.15, 0.70]
  "ESLint + Prettier": [0.15, 0.60]
  "Trivy image": [0.08, 0.65]
  "Tests Python": [0.35, 0.55]
  "HTTP contracts": [0.50, 0.55]
  "Coverage gate": [0.05, 0.50]
  "Rollback validation": [0.25, 0.45]
  "Frontend E2E": [0.65, 0.40]
  "Mutation testing": [0.40, 0.30]
  "Chaos testing": [0.85, 0.25]
```

| Priorité | Amélioration | Pourquoi | Effort |
|---|---|---|---|
| 🔴 Haute | **Dependabot** | PR automatiques de mise à jour dépendances (Maven, npm, Actions) | ~15 min |
| 🔴 Haute | **SpotBugs** | Détection statique NPE, concurrence, anti-patterns Java | ~30 min |
| 🔴 Haute | **ESLint + Prettier** | Aucun linting TypeScript/React en CI | ~30 min |
| 🔴 Haute | **Trivy image** | Scan couches OS/runtime des images Docker (seul Trivy fs existe) | ~15 min |
| 🟡 Moyenne | **Tests Python** | 2/6 services sans tests (health, admin-scale) | ~2h |
| 🟡 Moyenne | **HTTP contract tests** | Parsing OpenSky et payload `/api/flights` testés sans serveur HTTP mock | ~3h |
| 🟡 Moyenne | **Coverage enforcement** | Activer le seuil bloquant SonarCloud sur le nouveau code | ~10 min |
| 🔵 Basse | **Rollback validation** | Aucune vérification de la capacité de rollback ArgoCD | ~1h |
| 🔵 Basse | **Frontend E2E** | Pas de test navigateur réel (Playwright/Cypress) | ~4h |
| 🔵 Basse | **Mutation testing** | Vérifier que les tests détectent réellement les régressions (PIT) | ~2h |
