import http from "k6/http";
import { check } from "k6";

export const options = {
  vus: Number(__ENV.K6_VUS || 10),
  duration: __ENV.K6_DURATION || "30s",
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1500"],
    checks: ["rate>0.95"],
  },
};

function buildRequestParams() {
  const user = __ENV.K6_BASIC_AUTH_USER;
  const password = __ENV.K6_BASIC_AUTH_PASSWORD;

  const params = {
    headers: {
      Accept: "application/json",
    },
  };

  if (user && password) {
    params.auth = `${user}:${password}`;
  }

  return params;
}

export default function () {
  const baseUrl = (__ENV.TARGET_BASE_URL || "").trim();
  if (!baseUrl) {
    throw new Error("TARGET_BASE_URL is required");
  }

  const url = `${baseUrl}/api/flights?limit=100&sort=lastSeen&order=desc`;
  const res = http.get(url, buildRequestParams());

  let parsed = null;
  try {
    parsed = JSON.parse(res.body);
  } catch (err) {
    parsed = null;
  }

  check(res, {
    "status is 200": (r) => r.status === 200,
    "response is JSON object": () => parsed !== null && typeof parsed === "object" && !Array.isArray(parsed),
    "items is array": () => parsed !== null && Array.isArray(parsed.items),
    "count is numeric": () => parsed !== null && Number.isFinite(Number(parsed.count)),
  });
}
