# Kubernetes manifests layout

This folder is split between **applications** and **platform** concerns. ArgoCD tracks both trees from the root Application created by the bootstrap script.

## Structure (v1)
```
k8s/
├── apps/                # Product/application workloads (one folder per app or domain)
│   ├── kustomization.yaml
│   ├── namespace.yaml   # apps namespace
│   ├── ebs-csi-application.yaml  # CSI driver Application
│   ├── admin-scale/     # API to scale ingester replicas
│   ├── health/          # simple health endpoint
│   ├── ingester/        # Java ingest service
│   ├── processor/       # Java processor service
│   ├── redis/           # Redis buffer
│   ├── monitoring/      # Prometheus/Grafana Applications
│   └── external-secrets/# SecretStore + ExternalSecrets (SSM -> K8s Secrets)
└── platform/            # Shared platform components managed by ArgoCD
    ├── kustomization.yaml
    └── external-secrets/
        ├── helmrelease.yaml         # ArgoCD Application installing ESO chart (sync wave 0)
        └── config-application.yaml  # ArgoCD Application applying SecretStore/ExternalSecrets (wave 1)
```

## Conventions
- **ArgoCD root** (bootstrap) watches two sources: `k8s/apps` and `k8s/platform`.
- **Apps tree**: business/observability workloads and their Applications. Each subfolder should have its own kustomization when it contains multiple resources.
- **Platform tree**: cluster-level operators or shared components (e.g., ESO). Use ArgoCD sync waves/dependsOn when CRDs are involved.
- **Namespaces**: `apps` for workloads, `monitoring` for Prom/Grafana, `external-secrets` for ESO controllers; SecretStore/ExternalSecrets are under `default` unless specified.
- **Ordering for ESO**: Operator installs CRDs first (wave 0), config applies SecretStore/ExternalSecrets second (wave 1) with `SkipDryRunOnMissingResource=true`.

## How to add a new app
1) Create a subfolder under `k8s/apps/<app>` with its manifests and a `kustomization.yaml`.
2) Reference the folder in `k8s/apps/kustomization.yaml`.
3) If the app is deployed via Helm/ArgoCD Application, keep the Application manifest in the app folder.
4) Keep docs updated (architecture/runbooks) when adding new components.
