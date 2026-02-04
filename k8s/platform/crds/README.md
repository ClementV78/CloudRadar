# Platform CRDs

This directory stores Kubernetes CRDs that must exist **before** ArgoCD syncs workloads.

## Prometheus Operator CRDs

- Source: `prometheus-community/kube-prometheus-stack` chart (`81.4.2`).
- Location: `k8s/platform/crds/prometheus/`.
- Applied pre-ArgoCD via `scripts/bootstrap-prometheus-crds.sh` and the `ci-infra` workflow.

### Update workflow

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community
helm show crds prometheus-community/kube-prometheus-stack --version 81.4.2 > /tmp/kps-crds.yaml

# Split and copy CRDs into the repo (one file per CRD).
awk 'BEGIN{doc="";count=0} /^---$/{if(doc!=""){count++; print doc > "/tmp/kps-crd-" count ".yaml"; doc=""} next} {doc=doc $0 "\n"} END{if(doc!=""){count++; print doc > "/tmp/kps-crd-" count ".yaml"}}' /tmp/kps-crds.yaml
for f in /tmp/kps-crd-*.yaml; do
  name=$(awk '/^  name:/{print $2; exit}' "$f")
  [ -z "$name" ] && continue
  cp "$f" "k8s/platform/crds/prometheus/${name}.yaml"
done
```

Apply to a cluster (server-side, no client-side annotations):

```bash
scripts/bootstrap-prometheus-crds.sh <instance-id> us-east-1
```
