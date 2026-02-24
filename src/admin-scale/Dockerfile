FROM python:3.11-slim

# hadolint ignore=DL3008
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates \
  && rm -rf /var/lib/apt/lists/*

# hadolint ignore=DL3013
RUN pip install --no-cache-dir boto3

WORKDIR /app
COPY app.py /app/app.py

ENV PORT=8080

USER 10001
EXPOSE 8080

CMD ["python", "/app/app.py"]
