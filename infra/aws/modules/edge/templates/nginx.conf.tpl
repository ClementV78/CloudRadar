upstream dashboard_upstream {
  # Dashboard service running behind the edge proxy.
  server ${upstream_host}:${dashboard_upstream_port};
}

upstream api_upstream {
  # API service running behind the edge proxy.
  server ${upstream_host}:${api_upstream_port};
}

upstream health_upstream {
  # Health service running behind the edge proxy.
  server ${upstream_host}:${health_upstream_port};
}

upstream admin_upstream {
  # Admin scale service running behind the edge proxy.
  server ${upstream_host}:${admin_upstream_port};
}

upstream prometheus_upstream {
  # Prometheus service exposed via Traefik/NodePort inside the cluster.
  server ${upstream_host}:${prometheus_upstream_port};
}

upstream grafana_upstream {
  # Grafana service exposed via Traefik/NodePort inside the cluster.
  server ${upstream_host}:${grafana_upstream_port};
}

server {
  listen 443 ssl;
  server_name ${server_name};

  # Self-signed certificate created at boot time.
  ssl_certificate     /etc/nginx/ssl/edge.crt;
  ssl_certificate_key /etc/nginx/ssl/edge.key;

  # Basic auth protects the public entrypoint.
  auth_basic "CloudRadar";
  auth_basic_user_file /etc/nginx/.htpasswd;

  location = /healthz {
    # Route health checks to the private backend.
    proxy_pass http://health_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location /admin/ {
    # Route admin scale traffic to the private backend.
    proxy_pass http://admin_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Internal-Token "__ADMIN_INTERNAL_TOKEN__";
  }

  location /api/ {
    # Route API traffic to the private backend.
    proxy_pass http://api_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # TODO: Re-introduce WebSocket headers if/when the API requires them.
  }

  location / {
    # Route dashboard traffic to the private backend.
    proxy_pass http://dashboard_upstream;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # TODO: Re-introduce WebSocket headers if/when the dashboard requires them.
  }

  location /prometheus/ {
    # Route Prometheus UI/API through edge; Basic Auth enforced at edge.
    proxy_pass http://prometheus_upstream/;
    proxy_http_version 1.1;
    proxy_set_header Host prometheus.cloudradar.local;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location /grafana/ {
    # Route Grafana UI through edge; Basic Auth enforced at edge.
    proxy_pass http://grafana_upstream/;
    proxy_http_version 1.1;
    proxy_set_header Host grafana.cloudradar.local;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}

server {
  listen 80;
  server_name ${server_name};

  set $http_redirect "${enable_http_redirect}";
  if ($http_redirect = 1) {
    # Redirect cleartext HTTP to HTTPS when enabled.
    return 301 https://$host$request_uri;
  }

  # Default to no HTTP exposure when redirect is disabled.
  return 404;
}
