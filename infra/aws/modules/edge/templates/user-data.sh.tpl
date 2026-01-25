#!/bin/bash
set -euo pipefail

DNF_BIN="$(command -v dnf || true)"
YUM_BIN="$(command -v yum || true)"

if [[ -n "$DNF_BIN" ]]; then
  # Amazon Linux 2023 uses dnf.
  dnf install -y nginx openssl httpd-tools awscli amazon-ssm-agent
else
  # Fallback for older images using yum.
  yum install -y nginx openssl httpd-tools awscli amazon-ssm-agent
fi

systemctl enable --now amazon-ssm-agent

mkdir -p /etc/nginx/ssl

# Generate a self-signed certificate for the edge hostname.
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout /etc/nginx/ssl/edge.key \
  -out /etc/nginx/ssl/edge.crt \
  -subj "/CN=${server_name}"

# Pull the Basic Auth password from SSM Parameter Store.
basic_auth_password=""
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
htpasswd -bc /etc/nginx/.htpasswd "${basic_auth_user}" "$basic_auth_password"

# Write the templated Nginx config.
cat > /etc/nginx/conf.d/edge.conf <<'NGINXCONF'
${nginx_conf}
NGINXCONF

# Validate and launch Nginx.
nginx -t
systemctl enable --now nginx
systemctl is-enabled --quiet nginx
systemctl is-active --quiet nginx
