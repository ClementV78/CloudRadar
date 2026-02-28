#!/bin/bash
set -euo pipefail

DNF_BIN="$(command -v dnf || true)"
YUM_BIN="$(command -v yum || true)"

if [[ -n "$DNF_BIN" ]]; then
  # Amazon Linux 2023 uses dnf.
  dnf install -y nginx openssl httpd-tools awscli jq amazon-ssm-agent
else
  # Fallback for older images using yum.
  yum install -y nginx openssl httpd-tools awscli jq amazon-ssm-agent
fi

systemctl enable --now amazon-ssm-agent

mkdir -p /etc/nginx/ssl

# Pull the Basic Auth password from SSM Parameter Store.
basic_auth_password=""
admin_internal_token=""
tls_fullchain=""
tls_privkey=""

fetch_ssm_parameter() {
  local param_name="$1"
  local output_file="$2"
  local stderr_file="$3"
  local json_file="$4"

  if ! aws ssm get-parameter --name "$param_name" --with-decryption --region "${aws_region}" --output json >"$json_file" 2>"$stderr_file"; then
    return 1
  fi

  jq -e -r '.Parameter.Value' "$json_file" >"$output_file"
}

for attempt in {1..6}; do
  basic_auth_password="$(aws ssm get-parameter --name "${basic_auth_ssm_parameter_name}" --with-decryption --region "${aws_region}" --query 'Parameter.Value' --output text 2>/tmp/ssm-basic-auth.err || true)"
  if [[ -n "$basic_auth_password" && "$basic_auth_password" != "None" ]]; then
    basic_auth_password="$(printf '%s' "$basic_auth_password" | tr -d '\r\n')"
    break
  fi
  echo "SSM basic auth parameter not available yet (attempt $${attempt}/6)."
  sleep 10
done

if [[ -z "$basic_auth_password" || "$basic_auth_password" == "None" ]]; then
  echo "Failed to fetch SSM basic auth parameter after 6 attempts." >&2
  cat /tmp/ssm-basic-auth.err >&2 || true
  exit 1
fi

for attempt in {1..6}; do
  admin_internal_token="$(aws ssm get-parameter --name "${admin_token_ssm_parameter_name}" --with-decryption --region "${aws_region}" --query 'Parameter.Value' --output text 2>/tmp/ssm-admin-token.err || true)"
  if [[ -n "$admin_internal_token" && "$admin_internal_token" != "None" ]]; then
    admin_internal_token="$(printf '%s' "$admin_internal_token" | tr -d '\r\n')"
    break
  fi
  echo "SSM admin token parameter not available yet (attempt $${attempt}/6)."
  sleep 10
done

if [[ -z "$admin_internal_token" || "$admin_internal_token" == "None" ]]; then
  echo "Failed to fetch SSM admin token parameter after 6 attempts." >&2
  cat /tmp/ssm-admin-token.err >&2 || true
  exit 1
fi

for attempt in {1..6}; do
  if fetch_ssm_parameter "${tls_fullchain_ssm_parameter_name}" "/etc/nginx/ssl/edge.crt" "/tmp/ssm-tls-fullchain.err" "/tmp/ssm-tls-fullchain.json"; then
    tls_fullchain="ok"
    break
  fi
  echo "SSM TLS fullchain parameter not available yet (attempt $${attempt}/6)." >&2
  sleep 10
done

if [[ -z "$tls_fullchain" ]]; then
  echo "Failed to fetch SSM TLS fullchain parameter after 6 attempts." >&2
  cat /tmp/ssm-tls-fullchain.err >&2 || true
  exit 1
fi

for attempt in {1..6}; do
  if fetch_ssm_parameter "${tls_privkey_ssm_parameter_name}" "/etc/nginx/ssl/edge.key" "/tmp/ssm-tls-privkey.err" "/tmp/ssm-tls-privkey.json"; then
    tls_privkey="ok"
    break
  fi
  echo "SSM TLS private key parameter not available yet (attempt $${attempt}/6)." >&2
  sleep 10
done

if [[ -z "$tls_privkey" ]]; then
  echo "Failed to fetch SSM TLS private key parameter after 6 attempts." >&2
  cat /tmp/ssm-tls-privkey.err >&2 || true
  exit 1
fi

chmod 600 /etc/nginx/ssl/edge.key
chmod 644 /etc/nginx/ssl/edge.crt

openssl x509 -in /etc/nginx/ssl/edge.crt -noout -checkend 0 >/dev/null

cert_md5="$(openssl x509 -in /etc/nginx/ssl/edge.crt -noout -modulus | openssl md5)"
key_md5="$(openssl rsa -in /etc/nginx/ssl/edge.key -noout -modulus | openssl md5)"
if [[ "$${cert_md5}" != "$${key_md5}" ]]; then
  echo "TLS certificate and private key do not match." >&2
  exit 1
fi

htpasswd -bc /etc/nginx/.htpasswd "${basic_auth_user}" "$basic_auth_password"

# Write the templated Nginx config.
cat > /etc/nginx/conf.d/edge.conf <<'NGINXCONF'
${nginx_conf}
NGINXCONF

admin_internal_token_escaped="$(printf '%s' "$admin_internal_token" | sed 's/[&|]/\\&/g')"
sed -i "s|__ADMIN_INTERNAL_TOKEN__|$admin_internal_token_escaped|g" /etc/nginx/conf.d/edge.conf

# Validate and launch Nginx.
nginx -t
systemctl enable --now nginx
systemctl is-enabled --quiet nginx
systemctl is-active --quiet nginx
