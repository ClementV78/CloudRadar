# Runbook: SonarCloud quality gate

This runbook explains how CloudRadar uses SonarCloud for PR quality-gate checks and how to configure the required GitHub/SonarCloud integration.

## Scope

- Workflow: `.github/workflows/sonarcloud.yml`
- Sonar config: `sonar-project.properties`
- Coverage sources:
  - Frontend: `src/frontend/coverage/lcov.info` (Vitest)
  - Java:
    - `src/dashboard/target/site/jacoco/jacoco.xml`
    - `src/ingester/target/site/jacoco/jacoco.xml`
    - `src/processor/target/site/jacoco/jacoco.xml`
- Java analyzer mode:
  - Default: optimized (`sonar.java.skipUnchanged=true`) to keep CI fast.
  - Manual first/full baseline: run `workflow_dispatch` with `full_java_scan=true`.

## Workflow diagram

```mermaid
flowchart LR
  A[workflow_dispatch or PR/main push] --> B[checkout]
  B --> C[setup java 17]
  C --> D[mvn verify dashboard/ingester/processor]
  D --> E[setup node 20]
  E --> F[npm ci + vitest coverage]
  F --> G{coverage reports exist?}
  G -->|No| H[fail with missing report error]
  G -->|Yes| I{SONAR_TOKEN set?}
  I -->|No| J[fail with explicit error]
  I -->|Yes| K[sonar scan]
  K --> L[quality gate wait]
  L --> M{gate status}
  M -->|Green| N[check passes]
  M -->|Red| O[check fails]
```

## Triggers

- `workflow_dispatch` (manual validation)
- `pull_request` to `main`
- `push` to `main`

`workflow_dispatch` input:
- `full_java_scan` (boolean, default `false`)
  - `true`: force full Java analysis (including unchanged Java files)
  - `false`: optimized mode (skip unchanged Java files)

Path filters:
- `src/**`
- `sonar-project.properties`
- `.github/workflows/sonarcloud.yml`

## One-time setup (required)

1. Import the repository in SonarCloud:
- Sign in to SonarCloud with GitHub
- Import `ClementV78/CloudRadar`
- Confirm organization + project key match `sonar-project.properties`

2. Disable SonarCloud automatic analysis:
- Project Settings -> Analysis Method -> disable automatic analysis
- Keep CI-based analysis only (GitHub Actions)

3. Create GitHub repository secret:
- Name: `SONAR_TOKEN`
- Value: SonarCloud token generated from your SonarCloud account

4. Verify project identifiers:
- `sonar.organization=clementv78`
- `sonar.projectKey=ClementV78_CloudRadar`

If your SonarCloud project key/org differs, update `sonar-project.properties` accordingly.

## Custom quality profile — not available on free plan

SonarCloud free tier only allows the default "Sonar Way" profile and quality gate.
Custom profiles (e.g. activating `java:S6539` God Class) require a paid plan.

**Mitigation:** structural quality rules are enforced locally via Maven plugins:
- **PMD** (`config/quality/pmd-ruleset.xml`): GodClass, TooManyMethods, CyclomaticComplexity, CouplingBetweenObjects
- **Checkstyle** (`config/quality/checkstyle.xml`): FileLength, MethodCount, CyclomaticComplexity, ClassFanOutComplexity
- **ArchUnit** (`ArchitectureTest.java` per service): no ConcurrentHashMap in @Component, config ↛ service

SonarCloud still provides value for: coverage tracking, bug detection, security
vulnerabilities, and duplication analysis — all included in "Sonar Way".

## SARIF upload to GitHub Code Scanning

The `sonarcloud.yml` workflow converts PMD and Checkstyle XML reports to SARIF
format (`scripts/ci/quality-to-sarif.py`) and uploads them to **GitHub Code
Scanning** via `github/codeql-action/upload-sarif@v3`.

This makes PMD and Checkstyle findings visible in the repository **Security tab**
alongside Trivy CVE alerts — no need to dig into CI logs.

The SARIF steps run with `if: always()` + `continue-on-error: true` to never
block the rest of the workflow.

Note: `build-and-push.yml` also runs `mvn verify` (PMD/Checkstyle/ArchUnit) but
does not upload SARIF — violations there fail the build directly.

If the plan is upgraded later, follow these steps to create a custom profile:

1. Go to **Quality Profiles** → Java → copy "Sonar Way" → name it `CloudRadar`
2. Activate: `java:S6539` (God Class), `java:S1200` (coupling)
3. Lower `java:S3776` (Cognitive Complexity) threshold from 15 → 10
4. Add `sonar.qualityprofile=CloudRadar` to `sonar-project.properties`
5. Export profile XML for backup:
   ```bash
   curl -s "https://sonarcloud.io/api/qualityprofiles/backup?qualityProfile=CloudRadar&language=java" \
     -H "Authorization: Bearer $SONAR_TOKEN" \
     > docs/quality/sonarcloud-profile-java.xml
   ```

## Local validation before CI

```bash
for svc in dashboard ingester processor; do
  mvn -B -f "src/${svc}/pom.xml" verify
done

cd src/frontend
npm ci
npm run test:coverage

ls -l \
  coverage/lcov.info \
  ../dashboard/target/site/jacoco/jacoco.xml \
  ../ingester/target/site/jacoco/jacoco.xml \
  ../processor/target/site/jacoco/jacoco.xml
```

Expected:
- tests pass
- `coverage/lcov.info` exists
- JaCoCo XML reports exist for all Java services

## Run manually

1. Open GitHub Actions
2. Select `SonarCloud Quality Gate`
3. Set `full_java_scan=true` for the first full Java baseline (or leave `false` for normal runs)
4. Click `Run workflow`

Expected results:
- `Run Java tests with JaCoCo XML reports` succeeds
- `Run frontend tests with coverage (lcov)` succeeds
- `SonarCloud scan and quality gate` succeeds
- PR check appears as green quality gate

## Troubleshooting

### `SONAR_TOKEN is not configured`

Cause:
- missing repository secret

Fix:
- add `SONAR_TOKEN` in GitHub repository secrets
- re-run workflow

### Sonar scan succeeds but coverage is `0.0%`

Cause:
- lcov and/or JaCoCo reports missing, wrong path, or not mapped to scanned sources

Fix:
- ensure `src/frontend/coverage/lcov.info` is generated
- verify `sonar.javascript.lcov.reportPaths=src/frontend/coverage/lcov.info`
- ensure Java reports are generated:
  - `src/dashboard/target/site/jacoco/jacoco.xml`
  - `src/ingester/target/site/jacoco/jacoco.xml`
  - `src/processor/target/site/jacoco/jacoco.xml`
- verify Sonar properties:
  - `sonar.coverage.jacoco.xmlReportPaths=...`
  - `sonar.java.binaries=...`
  - `sonar.java.libraries=...`

### Warning `sonar.java.libraries is empty`

Cause:
- Java analyzer cannot resolve external classpath dependencies, so some source analysis rules run with reduced precision.

Fix:
- keep the Sonar workflow step that prepares Java dependencies:
  - `mvn -B -f src/<service>/pom.xml -DskipTests dependency:copy-dependencies -DincludeScope=test`
- ensure Sonar properties include:
  - `sonar.java.libraries=src/dashboard/target/dependency/*.jar,src/ingester/target/dependency/*.jar,src/processor/target/dependency/*.jar`

### Warning `sonar.java.test.libraries is empty`

Cause:
- Java analyzer cannot resolve test-scope dependencies, reducing precision on test file analysis.

Fix:
- prepare dependencies with test scope in the Sonar workflow:
  - `mvn -B -f src/<service>/pom.xml -DskipTests dependency:copy-dependencies -DincludeScope=test`
- ensure Sonar properties include:
  - `sonar.java.test.libraries=src/dashboard/target/dependency/*.jar,src/ingester/target/dependency/*.jar,src/processor/target/dependency/*.jar`

### PR shows `0 source files analyzed` for Java

Cause:
- Java unchanged-file optimization is enabled, so Java files outside PR diff can be skipped.

Fix:
- run `workflow_dispatch` with `full_java_scan=true` to force full Java analysis
- re-run the `SonarCloud Quality Gate` workflow

### Quality gate remains red

Cause:
- code smells/bugs/vulnerabilities or insufficient coverage against active quality profile

Fix:
- open SonarCloud project dashboard
- review New Code issues and Coverage conditions
- fix and re-run workflow

## Related

- App pipeline runbook: `docs/runbooks/ci-cd/ci-app.md`
- Frontend runbook: `docs/runbooks/operations/frontend.md`
- Issue: #507
- Issue: #514
