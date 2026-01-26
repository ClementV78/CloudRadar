#!/usr/bin/env python3
import hmac
import json
import os
import ssl
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

import boto3
from botocore.exceptions import BotoCoreError, ClientError

TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
K8S_HOST = "https://kubernetes.default.svc"
PORT = int(os.getenv("PORT", "8080"))
NAMESPACE = os.getenv("TARGET_NAMESPACE", "cloudradar")
DEPLOYMENT = os.getenv("TARGET_DEPLOYMENT", "ingester")
SSM_TOKEN_NAME = os.getenv("ADMIN_TOKEN_SSM_NAME", "/cloudradar/admin/k8s-admin-api-token")
AWS_REGION = os.getenv("AWS_REGION", "us-east-1")
TOKEN_CACHE_SECONDS = int(os.getenv("ADMIN_TOKEN_CACHE_SECONDS", "300"))
ALLOWED_REPLICAS = os.getenv("ALLOWED_REPLICAS", "0,1,2")
_TOKEN_CACHE = {"value": None, "ts": 0.0}


def _load_token():
  if not os.path.exists(TOKEN_PATH):
    return None
  with open(TOKEN_PATH, "r", encoding="utf-8") as handle:
    return handle.read().strip()


def _k8s_patch_json(token, path, payload, timeout=3):
  url = f"{K8S_HOST}{path}"
  data = json.dumps(payload).encode("utf-8")
  req = urllib.request.Request(url, data=data, method="PATCH")
  req.add_header("Authorization", f"Bearer {token}")
  req.add_header("Accept", "application/json")
  req.add_header("Content-Type", "application/merge-patch+json")
  context = ssl.create_default_context(cafile=CA_PATH)
  with urllib.request.urlopen(req, context=context, timeout=timeout) as resp:
    return json.loads(resp.read().decode("utf-8"))


def _allowed_replicas():
  values = []
  for raw in ALLOWED_REPLICAS.split(","):
    raw = raw.strip()
    if not raw:
      continue
    try:
      values.append(int(raw))
    except ValueError:
      continue
  return set(values)


def _unauthorized(handler):
  handler.send_response(401)
  handler.end_headers()


def _json_response(handler, status_code, payload):
  body = json.dumps(payload).encode("utf-8")
  handler.send_response(status_code)
  handler.send_header("Content-Type", "application/json")
  handler.send_header("Content-Length", str(len(body)))
  handler.end_headers()
  handler.wfile.write(body)


def _get_internal_token():
  now = time.time()
  cached = _TOKEN_CACHE.get("value")
  if cached and (now - _TOKEN_CACHE.get("ts", 0.0) < TOKEN_CACHE_SECONDS):
    return cached

  try:
    client = boto3.client("ssm", region_name=AWS_REGION)
    resp = client.get_parameter(Name=SSM_TOKEN_NAME, WithDecryption=True)
    token = resp.get("Parameter", {}).get("Value", "").strip()
  except (BotoCoreError, ClientError):
    return None

  if not token:
    return None

  _TOKEN_CACHE["value"] = token
  _TOKEN_CACHE["ts"] = now
  return token


class AdminHandler(BaseHTTPRequestHandler):
  def do_GET(self):  # noqa: N802
    if self.path != "/healthz":
      self.send_response(404)
      self.end_headers()
      return

    _json_response(self, 200, {"status": "ok", "timestamp": _utc_now()})

  def do_POST(self):  # noqa: N802
    if self.path != "/admin/ingester/scale":
      self.send_response(404)
      self.end_headers()
      return

    token = _get_internal_token()
    if not token:
      _json_response(self, 502, {"error": "admin token unavailable from ssm"})
      return

    if not _is_authorized(self.headers.get("X-Internal-Token", ""), token):
      _unauthorized(self)
      return

    content_length = int(self.headers.get("Content-Length", "0"))
    if content_length <= 0:
      _json_response(self, 400, {"error": "missing request body"})
      return

    try:
      body = self.rfile.read(content_length).decode("utf-8")
      payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
      _json_response(self, 400, {"error": "invalid json"})
      return

    replicas = payload.get("replicas")
    if not isinstance(replicas, int):
      _json_response(self, 400, {"error": "replicas must be an integer"})
      return

    allowed = _allowed_replicas()
    if allowed and replicas not in allowed:
      _json_response(self, 400, {"error": f"replicas must be one of {sorted(allowed)}"})
      return

    token = _load_token()
    if not token:
      _json_response(self, 500, {"error": "serviceaccount token not found"})
      return

    try:
      result = _k8s_patch_json(
        token,
        f"/apis/apps/v1/namespaces/{NAMESPACE}/deployments/{DEPLOYMENT}/scale",
        {"spec": {"replicas": replicas}},
      )
    except urllib.error.HTTPError as exc:
      _json_response(self, 502, {"error": f"k8s api error: {exc.code}"})
      return
    except Exception as exc:  # noqa: BLE001
      _json_response(self, 502, {"error": f"k8s api error: {exc}"})
      return

    status = result.get("status", {})
    _json_response(self, 200, {
      "status": "ok",
      "deployment": DEPLOYMENT,
      "replicas": status.get("replicas", replicas),
      "available": status.get("availableReplicas", 0),
      "updated": status.get("updatedReplicas", 0),
      "timestamp": _utc_now(),
    })

  def log_message(self, format, *args):  # noqa: A002
    return


def _is_authorized(token, expected):
  return hmac.compare_digest(token, expected)


def _utc_now():
  return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


if __name__ == "__main__":
  server = HTTPServer(("0.0.0.0", PORT), AdminHandler)
  server.serve_forever()
