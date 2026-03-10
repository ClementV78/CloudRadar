# Kubernetes manifests layout

This folder is split between **applications** and **platform** concerns. ArgoCD tracks both trees using two root Applications created by the bootstrap scripts.

## Structure (v1)
```
k8s/
├── apps/                # Product/application workloads (one folder per app or domain)
│   ├── kustomization.yaml
│   ├── namespace.yaml   # apps namespace
│   ├── ebs-csi-application.yaml  # CSI driver Application
│   ├── admin-scale/     # API to scale ingester replicas
│   ├── frontend/        # React/Vite Leaflet dashboard UI
│   ├── dashboard/       # Java dashboard API (flights + details + KPIs)
│   ├── health/          # simple health endpoint
│   ├── ingester/        # Java ingest service
│   ├── processor/       # Java processor service
│   ├── redis/           # Redis buffer
│   ├── monitoring/      # Prometheus/Grafana Applications
│   └── external-secrets/# SecretStore + ExternalSecrets (SSM -> K8s Secrets, managed by platform app)
└── platform/            # Shared platform components managed by ArgoCD
    ├── kustomization.yaml
    └── external-secrets/
        ├── helmrelease.yaml         # ArgoCD Application installing ESO chart (sync wave 0)
        └── config-application.yaml  # ArgoCD Application applying SecretStore/ExternalSecrets (wave 1)
```

## Conventions
- **ArgoCD roots** (bootstrap) create two Applications: `cloudradar-platform` (k8s/platform) and `cloudradar` (k8s/apps).
- **Apps tree**: business/observability workloads and their Applications. Each subfolder should have its own kustomization when it contains multiple resources. External Secrets manifests are managed via the `external-secrets-config` Application (platform), so the `external-secrets` folder is *not* referenced from `k8s/apps/kustomization.yaml`.
- **Platform tree**: cluster-level operators or shared components (e.g., ESO). Use ArgoCD sync waves/dependsOn when CRDs are involved.
- **Namespaces**: `apps` for workloads, `monitoring` for Prom/Grafana, `external-secrets` for ESO controllers; SecretStore is cluster-scoped, ExternalSecrets live in `default` unless specified.
- **Ordering for ESO**: Operator installs CRDs first (wave 0), config applies SecretStore/ExternalSecrets second (wave 1) with `SkipDryRunOnMissingResource=true`.

## ArgoCD Applications vs Kubernetes workloads

In this repository, an **ArgoCD Application** is a GitOps controller object (kind `Application`) that points to a Git path or Helm chart.  
It is **not** the runtime workload itself. Runtime objects are Kubernetes resources (Deployments, StatefulSets, Services, Ingresses, CRs) created by ArgoCD reconciliation.

| ArgoCD Application | Source (path/chart) | Target namespace | Workloads created | Notes |
|---|---|---|---|---|
| `cloudradar-platform` (root, bootstrap-created) | Git path `k8s/platform` | `argocd` | Platform baseline resources + child apps (`external-secrets-operator`, `external-secrets-config`) | Root app created by `scripts/bootstrap-argocd-app.sh`; owns platform layer ordering |
| `external-secrets-operator` | Helm `charts.external-secrets.io/external-secrets` (`0.10.2`) | `external-secrets` | ESO controller resources (Deployment/ServiceAccount/Service) + ESO CRDs | Sync wave `0` (CRDs first) |
| `external-secrets-config` | Git path `k8s/apps/external-secrets` | `external-secrets` | `ClusterSecretStore` + `ExternalSecret` objects (`grafana-admin`, `grafana-domain`, `opensky-secret`, `processor-aircraft-db`, `cloudradar-alertmanager-config`) | Sync wave `1`; depends on ESO CRDs |
| `cloudradar` (root, bootstrap-created) | Git path `k8s/apps` | `cloudradar` | Core app workloads (`admin-scale`, `frontend`, `health`, `dashboard`, `ingester`, `processor`), data workloads (`redis`, `redis-exporter`), monitoring manifests, storage class, and child apps (`ebs-csi-driver`, `prometheus`, `grafana`) | Root app for product workloads; may include `ignoreDifferences` on `ingester.spec.replicas` |
| `ebs-csi-driver` | Helm `kubernetes-sigs/aws-ebs-csi-driver` (`2.34.0`) | `kube-system` | EBS CSI controller/node resources and related RBAC/CSI objects | Enables dynamic PVC provisioning for stateful workloads |
| `prometheus` | Helm `prometheus-community/kube-prometheus-stack` (`81.4.2`) | `monitoring` | Prometheus Operator stack (Prometheus/Alertmanager/ServiceMonitors/PodMonitors/Rules and related resources) | `ServerSideApply=true` enabled in app sync options |
| `grafana` | Helm `grafana-community/grafana` (`10.5.15`) | `monitoring` | Grafana Deployment/Service + datasources/dashboard provisioning | Grafana admin/domain secrets are provided by ESO |

## How to add a new app
1) Create a subfolder under `k8s/apps/<app>` with its manifests and a `kustomization.yaml`.
2) Reference the folder in `k8s/apps/kustomization.yaml`.
3) If the app is deployed via Helm/ArgoCD Application, keep the Application manifest in the app folder.
4) Keep docs updated (architecture/runbooks) when adding new components.
