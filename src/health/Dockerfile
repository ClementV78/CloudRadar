FROM python:3.11-slim

RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app.py /app/app.py

ENV PORT=8080

USER 10001
EXPOSE 8080

CMD ["python", "/app/app.py"]
