# Scripts

Utility scripts for local workflows and bootstrap tasks.

## get-aws-kubeconfig.sh

Fetches the k3s kubeconfig via SSM, rewrites the API endpoint for a local
port-forward tunnel, and runs `kubectl get nodes`.

Usage:

```bash
source scripts/get-aws-kubeconfig.sh <instance-id> [local-port]
```

Notes:
- Default local port is `16443`.
- Override kubeconfig path with `KUBECONFIG_PATH=/tmp/k3s-aws.yaml`.
