import hashlib
import json
import os
import re
import time
from typing import Any

try:
  import boto3
  from botocore.exceptions import ClientError
except ImportError:  # pragma: no cover - local unit-test fallback
  boto3 = None

  class ClientError(Exception):
    pass

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
NAME_RE = re.compile(r"^[A-Za-zÀ-ÖØ-öø-ÿ0-9 .,'-]{2,80}$")

TABLE_NAME = os.environ["RATE_LIMIT_TABLE_NAME"]
RECIPIENT_EMAIL = os.environ["RECIPIENT_EMAIL"]
SENDER_EMAIL = os.environ["SENDER_EMAIL"]
RATE_LIMIT_WINDOW_SECONDS = int(os.environ.get("RATE_LIMIT_WINDOW_SECONDS", "900"))
RATE_LIMIT_MAX_HITS = int(os.environ.get("RATE_LIMIT_MAX_HITS", "3"))

_dynamodb = boto3.client("dynamodb") if boto3 else None
_ses = boto3.client("ses") if boto3 else None


def _json_response(status_code: int, body: dict[str, Any]) -> dict[str, Any]:
  return {
      "statusCode": status_code,
      "headers": {
          "Content-Type": "application/json",
          "Cache-Control": "no-store"
      },
      "body": json.dumps(body)
  }


def _parse_body(event: dict[str, Any]) -> dict[str, str]:
  raw_body = event.get("body") or "{}"
  try:
    body = json.loads(raw_body)
  except json.JSONDecodeError:
    raise ValueError("Invalid JSON payload")

  if not isinstance(body, dict):
    raise ValueError("Invalid JSON payload")

  payload = {
      "name": str(body.get("name", "")).strip(),
      "email": str(body.get("email", "")).strip(),
      "message": str(body.get("message", "")).strip(),
      "honeypot": str(body.get("honeypot", "")).strip(),
      "page": str(body.get("page", "")).strip(),
      "userAgent": str(body.get("userAgent", "")).strip()
  }
  return payload


def _validate(payload: dict[str, str]) -> None:
  if payload["honeypot"]:
    raise PermissionError("Invalid request")

  if not NAME_RE.match(payload["name"]):
    raise ValueError("Invalid name")

  email = payload["email"]
  if len(email) > 254 or not EMAIL_RE.match(email):
    raise ValueError("Invalid email")

  message = payload["message"]
  if len(message) < 10 or len(message) > 2000:
    raise ValueError("Invalid message")


def _source_ip(event: dict[str, Any]) -> str:
  return (
      event.get("requestContext", {})
      .get("http", {})
      .get("sourceIp", "unknown")
  )


def _apply_rate_limit(ip: str) -> None:
  now = int(time.time())
  window = now // RATE_LIMIT_WINDOW_SECONDS
  digest = hashlib.sha256(ip.encode("utf-8")).hexdigest()
  key = f"{digest}#{window}"

  expires_at = now + RATE_LIMIT_WINDOW_SECONDS + 120
  try:
    _dynamodb.update_item(
        TableName=TABLE_NAME,
        Key={"pk": {"S": key}},
        UpdateExpression="ADD hit_count :one SET expires_at = :expires_at",
        ExpressionAttributeValues={
            ":one": {"N": "1"},
            ":max": {"N": str(RATE_LIMIT_MAX_HITS)},
            ":expires_at": {"N": str(expires_at)}
        },
        ConditionExpression="attribute_not_exists(hit_count) OR hit_count < :max"
    )
  except ClientError as exc:
    code = exc.response.get("Error", {}).get("Code", "")
    if code == "ConditionalCheckFailedException":
      raise TimeoutError("Too many requests")
    raise


def _send_email(payload: dict[str, str], ip: str) -> None:
  subject = f"[CloudRadar Demo] Request from {payload['name']}"
  text_body = (
      "CloudRadar demo request\n\n"
      f"Name: {payload['name']}\n"
      f"Email: {payload['email']}\n"
      f"Source IP: {ip}\n"
      f"Page: {payload['page']}\n"
      f"User-Agent: {payload['userAgent']}\n\n"
      "Message:\n"
      f"{payload['message']}\n"
  )

  _ses.send_email(
      Source=SENDER_EMAIL,
      Destination={"ToAddresses": [RECIPIENT_EMAIL]},
      Message={
          "Subject": {"Data": subject, "Charset": "UTF-8"},
          "Body": {
              "Text": {"Data": text_body, "Charset": "UTF-8"}
          }
      },
      ReplyToAddresses=[payload["email"]]
  )


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
  method = event.get("requestContext", {}).get("http", {}).get("method", "")
  if method != "POST":
    return _json_response(405, {"error": "Method not allowed"})

  try:
    payload = _parse_body(event)
    _validate(payload)
    ip = _source_ip(event)
    _apply_rate_limit(ip)
    _send_email(payload, ip)
    return _json_response(200, {"ok": True})
  except PermissionError:
    return _json_response(400, {"error": "Invalid request"})
  except ValueError as exc:
    return _json_response(400, {"error": str(exc)})
  except TimeoutError:
    return _json_response(429, {"error": "Too many requests, please retry later"})
  except Exception:
    return _json_response(500, {"error": "Internal error"})
