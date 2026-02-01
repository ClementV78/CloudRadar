#!/usr/bin/env python3
import json
import os
import ssl
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
K8S_HOST = "https://kubernetes.default.svc"
PORT = int(os.getenv("PORT", "8080"))


def _load_token():
  if not os.path.exists(TOKEN_PATH):
    return None
  with open(TOKEN_PATH, "r", encoding="utf-8") as handle:
    return handle.read().strip()


def _parse_quantity(value):
  if value is None:
    return 0
  if value.endswith("n"):
    return float(value[:-1]) / 1_000_000.0
  if value.endswith("m"):
    return float(value[:-1])

  units = {
    "Ki": 1024,
    "Mi": 1024**2,
    "Gi": 1024**3,
    "Ti": 1024**4,
    "Pi": 1024**5,
    "Ei": 1024**6,
    "K": 1000,
    "M": 1000**2,
    "G": 1000**3,
    "T": 1000**4,
    "P": 1000**5,
    "E": 1000**6,
  }

  for suffix, multiplier in units.items():
    if value.endswith(suffix):
      return float(value[:-len(suffix)]) * multiplier
  return float(value)


def _k8s_get_json(token, path, timeout=2):
  url = f"{K8S_HOST}{path}"
  req = urllib.request.Request(url)
  req.add_header("Authorization", f"Bearer {token}")
  req.add_header("Accept", "application/json")
  context = ssl.create_default_context(cafile=CA_PATH)
  with urllib.request.urlopen(req, context=context, timeout=timeout) as resp:
    return json.loads(resp.read().decode("utf-8"))


def collect_cluster_status():
  token = _load_token()
  if not token:
    return {
      "status": "degraded",
      "errors": ["serviceaccount token not found"],
    }

  errors = []
  nodes_ready = 0
  nodes_total = 0
  deployments_total = 0
  pods_total = 0
  metrics = {"available": False}

  try:
    nodes = _k8s_get_json(token, "/api/v1/nodes")
    nodes_total = len(nodes.get("items", []))
    for node in nodes.get("items", []):
      conditions = node.get("status", {}).get("conditions", [])
      for condition in conditions:
        if condition.get("type") == "Ready" and condition.get("status") == "True":
          nodes_ready += 1
          break
  except Exception as exc:  # noqa: BLE001
    errors.append(f"nodes: {exc}")

  try:
    deployments = _k8s_get_json(token, "/apis/apps/v1/deployments")
    deployments_total = len(deployments.get("items", []))
  except Exception as exc:  # noqa: BLE001
    errors.append(f"deployments: {exc}")

  try:
    pods = _k8s_get_json(token, "/api/v1/pods")
    pods_total = len(pods.get("items", []))
  except Exception as exc:  # noqa: BLE001
    errors.append(f"pods: {exc}")

  try:
    metrics_data = _k8s_get_json(token, "/apis/metrics.k8s.io/v1beta1/nodes")
    cpu_mcores = 0.0
    mem_bytes = 0.0
    for item in metrics_data.get("items", []):
      usage = item.get("usage", {})
      cpu_mcores += _parse_quantity(usage.get("cpu", "0m"))
      mem_bytes += _parse_quantity(usage.get("memory", "0Mi"))
    metrics = {
      "available": True,
      "cpu_mcores": round(cpu_mcores, 1),
      "memory_bytes": int(mem_bytes),
    }
  except Exception as exc:  # noqa: BLE001
    errors.append(f"metrics: {exc}")

  status = "ok" if not errors else "degraded"
  return {
    "status": status,
    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "cluster": {"nodes_ready": nodes_ready, "nodes_total": nodes_total},
    "workloads": {"deployments": deployments_total, "pods": pods_total},
    "metrics": metrics,
    "errors": errors,
  }


class HealthHandler(BaseHTTPRequestHandler):
  def do_GET(self):  # noqa: N802
    if self.path == "/readyz":
      payload = {
        "status": "ready",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
      }
      body = json.dumps(payload).encode("utf-8")

      self.send_response(200)
      self.send_header("Content-Type", "application/json")
      self.send_header("Content-Length", str(len(body)))
      self.end_headers()
      self.wfile.write(body)
      return

    if self.path != "/healthz":
      self.send_response(404)
      self.end_headers()
      return

    payload = collect_cluster_status()
    body = json.dumps(payload).encode("utf-8")

    self.send_response(200 if payload["status"] == "ok" else 503)
    self.send_header("Content-Type", "application/json")
    self.send_header("Content-Length", str(len(body)))
    self.end_headers()
    self.wfile.write(body)

  def log_message(self, format, *args):  # noqa: A002
    return


if __name__ == "__main__":
  server = HTTPServer(("0.0.0.0", PORT), HealthHandler)
  server.serve_forever()
