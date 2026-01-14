map $http_upgrade $connection_upgrade {
  default upgrade;
  ''      close;
}

upstream dashboard_upstream {
  server ${upstream_host}:${dashboard_upstream_port};
}

upstream api_upstream {
  server ${upstream_host}:${api_upstream_port};
}

server {
  listen 443 ssl;
  server_name ${server_name};

  ssl_certificate     /etc/nginx/ssl/edge.crt;
  ssl_certificate_key /etc/nginx/ssl/edge.key;

  auth_basic "CloudRadar";
  auth_basic_user_file /etc/nginx/.htpasswd;

  location /api/ {
    proxy_pass http://api_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
  }

  location / {
    proxy_pass http://dashboard_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
  }
}

server {
  listen 80;
  server_name ${server_name};

  if (${enable_http_redirect}) {
    return 301 https://$host$request_uri;
  }

  return 404;
}
