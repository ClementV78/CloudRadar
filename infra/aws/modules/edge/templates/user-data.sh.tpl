#!/bin/bash
set -euo pipefail

DNF_BIN="$(command -v dnf || true)"
YUM_BIN="$(command -v yum || true)"

if [[ -n "${DNF_BIN}" ]]; then
  dnf install -y nginx openssl httpd-tools awscli
else
  yum install -y nginx openssl httpd-tools awscli
fi

mkdir -p /etc/nginx/ssl

openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout /etc/nginx/ssl/edge.key \
  -out /etc/nginx/ssl/edge.crt \
  -subj "/CN=${server_name}"

basic_auth_password="$(aws ssm get-parameter --name \"${basic_auth_ssm_parameter_name}\" --with-decryption --region \"${aws_region}\" --query 'Parameter.Value' --output text)"
htpasswd -bc /etc/nginx/.htpasswd "${basic_auth_user}" "${basic_auth_password}"

cat > /etc/nginx/conf.d/edge.conf <<'NGINXCONF'
${nginx_conf}
NGINXCONF

nginx -t
systemctl enable nginx
systemctl restart nginx
