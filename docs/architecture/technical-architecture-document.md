# CloudRadar — Technical Architecture Document (DAT)

**Version**: v1-mvp  
**Date**: 2026-02-13  
**Status**: Current

---

## 1. System Overview

### 1.1 Context Diagram

```mermaid
C4Context
    title CloudRadar Platform Context

    Person(user, "DevOps Engineer", "Monitors flight telemetry")
    System(cloudradar, "CloudRadar Platform", "Event-driven telemetry processing on AWS/k3s")
    System_Ext(opensky, "OpenSky API", "Flight position data source")
    System_Ext(github, "GitHub", "CI/CD & GitOps source")
    System_Ext(aws, "AWS Services", "VPC, EC2, S3, SSM, IAM")

    Rel(user, cloudradar, "Views dashboards", "HTTPS")
    Rel(cloudradar, opensky, "Fetches states", "REST/OAuth2")
    Rel(github, cloudradar, "Deploys via", "Actions/OIDC")
    Rel(cloudradar, aws, "Runs on", "Terraform/k8s")
```

### 1.2 Technology Stack

```mermaid
graph TB
    subgraph Infrastructure["☁️ Infrastructure"]
        AWS[AWS us-east-1]
        TF[Terraform 1.5+]
        VPC[VPC + Security Groups]
    end

    subgraph Platform["🔧 Platform"]
        K3S[k3s 1.28+]
        ARGO[ArgoCD]
        ESO[External Secrets Operator]
        PROM[Prometheus/Grafana]
    end

    subgraph Application["⚙️ Application"]
        JAVA[Java 17 + Spring Boot 3.x]
        REDIS[Redis 7.x]
        SQLITE[SQLite 3.x]
        PYTHON[Python 3.11]
    end

    subgraph CI["🚀 CI/CD"]
        GHA[GitHub Actions]
        GHCR[GitHub Container Registry]
        OIDC[OIDC for AWS]
    end

    Infrastructure --> Platform
    Platform --> Application
    CI --> Infrastructure
    CI --> Application
```

---

## 2. Infrastructure Architecture

### 2.1 Network Topology

```mermaid
graph TB
    subgraph Internet["🌐 Internet"]
        USERS[Users]
        OPENSKY[OpenSky API]
        GITHUB[GitHub]
    end

    subgraph AWS["AWS VPC 10.0.0.0/16"]
        subgraph PublicSubnet["Public Subnet 10.0.1.0/24"]
            IGW[Internet Gateway]
            EDGE[Edge EC2<br/>Nginx + Basic Auth]
            NAT[NAT Instance<br/>t3.nano]
        end

        subgraph PrivateSubnet["Private Subnet 10.0.101.0/24"]
            K3S_SERVER[k3s Server<br/>t3a.medium]
            K3S_WORKERS[k3s Workers ASG<br/>t3a.medium]
        end

        subgraph Endpoints["VPC Endpoints"]
            SSM_EP[SSM/KMS<br/>Interface Endpoints]
            S3_EP[S3 Gateway<br/>Endpoint]
        end
    end

    USERS -->|HTTPS 443| EDGE
    EDGE -->|NodePort 30080/30081| K3S_SERVER
    K3S_SERVER -->|Cluster| K3S_WORKERS
    K3S_WORKERS -->|0.0.0.0/0| NAT
    NAT -->|Egress| IGW
    IGW -->|Fetch states| OPENSKY
    EDGE -.->|SSM| SSM_EP
    K3S_SERVER -.->|SSM| SSM_EP
    K3S_WORKERS -.->|S3| S3_EP
    GITHUB -->|CI/CD via SSM| K3S_SERVER
```

### 2.2 Security Groups

```mermaid
graph LR
    subgraph "Security Group Rules"
        EDGE_SG[Edge SG<br/>cloudradar-dev-edge]
        K3S_SG[k3s SG<br/>cloudradar-dev-k3s-nodes]
        NAT_SG[NAT SG<br/>cloudradar-dev-nat]
    end

    INTERNET[Internet<br/>0.0.0.0/0]
    EDGE_ALLOWED[Allowed CIDRs<br/>configurable]

    EDGE_ALLOWED -->|443/TCP| EDGE_SG
    EDGE_SG -->|30080,30081/TCP| K3S_SG
    K3S_SG -->|6443,10250/TCP<br/>8472/UDP| K3S_SG
    K3S_SG -->|All| NAT_SG
    NAT_SG -->|All| INTERNET
```

### 2.3 IAM & Access

```mermaid
graph TB
    subgraph "IAM Roles"
        OIDC[GitHub OIDC Role<br/>Terraform plan/apply<br/>Docker push]
        K3S_ROLE[k3s Node Role<br/>SSM + EBS CSI + S3]
        EDGE_ROLE[Edge Role<br/>SSM + SSM Parameter read]
    end

    subgraph "Access Patterns"
        GHA[GitHub Actions]
        ADMIN[Admin User]
        NODE[k3s Node]
    end

    GHA -->|AssumeRoleWithWebIdentity| OIDC
    ADMIN -->|SSM Session Manager| K3S_ROLE
    ADMIN -->|SSM Session Manager| EDGE_ROLE
    NODE -->|Instance Profile| K3S_ROLE

    style OIDC fill:#e1f5e1
    style K3S_ROLE fill:#e1f5e1
    style EDGE_ROLE fill:#e1f5e1
```

---

## 3. Application Architecture

### 3.1 Service Topology

```mermaid
graph TB
    subgraph "External"
        OPENSKY[OpenSky API]
        USER[Frontend User]
    end

    subgraph "apps namespace"
        INGESTER[Ingester<br/>Java/Spring Boot<br/>replicas: 0-2]
        PROCESSOR[Processor<br/>Java/Spring Boot<br/>replicas: 1]
        DASHBOARD[Dashboard API<br/>Java/Spring Boot<br/>replicas: 1+]
        HEALTH[Health<br/>Python<br/>replicas: 1]
        ADMIN[Admin-Scale<br/>Python<br/>replicas: 1]
    end

    subgraph "data namespace"
        REDIS[Redis<br/>StatefulSet<br/>replicas: 1<br/>PVC: local-path]
    end

    subgraph "monitoring namespace"
        PROM[Prometheus<br/>PVC: ebs-gp3 5GB]
        GRAF[Grafana]
    end

    subgraph "Storage"
        SQLITE[(SQLite DB<br/>Aircraft metadata<br/>read-only)]
        S3[(S3 Bucket<br/>SQLite backups)]
    end

    OPENSKY -->|10s poll| INGESTER
    INGESTER -->|LPUSH events| REDIS
    REDIS -->|BLPOP| PROCESSOR
    PROCESSOR -->|HSET/LPUSH/SADD| REDIS
    DASHBOARD -->|HGETALL/LRANGE| REDIS
    DASHBOARD -.->|Optional lookup| SQLITE
    USER -->|GET /api/flights*| DASHBOARD
    
    INGESTER -->|/metrics| PROM
    PROCESSOR -->|/metrics| PROM
    DASHBOARD -->|/metrics| PROM
    GRAF -->|Query| PROM
    
    ADMIN -->|PATCH k8s| INGESTER
    HEALTH -->|k8s API check| PROM

    style REDIS fill:#ffecb3
    style SQLITE fill:#b3e5fc
```

### 3.2 Event Flow

```mermaid
sequenceDiagram
    participant O as OpenSky API
    participant I as Ingester
    participant Q as Redis Queue
    participant P as Processor
    participant A as Redis Aggregates
    participant D as Dashboard API
    participant U as User/Frontend

    I->>O: GET /states/all (bbox)
    I->>I: OAuth2 token (cached 10m)
    I->>I: Parse FlightState objects
    I->>Q: LPUSH cloudradar:ingest:queue
    
    loop Every 2s timeout
        P->>Q: BLPOP cloudradar:ingest:queue
    end
    
    P->>P: Parse PositionEvent
    P->>A: HSET cloudradar:aircraft:last
    P->>A: LPUSH cloudradar:aircraft:track:{icao24}
    P->>A: LTRIM ... 0 179
    P->>A: SADD cloudradar:aircraft:in_bbox

    U->>D: GET /api/flights?bbox=...
    D->>A: HGETALL cloudradar:aircraft:last
    D->>D: Filter + Sort + Enrich
    D->>U: JSON response (FlightMapItem[])

    U->>D: GET /api/flights/{icao24}?include=track
    D->>A: HGET cloudradar:aircraft:last {icao24}
    D->>A: LRANGE cloudradar:aircraft:track:{icao24} 0 119
    D->>D: Enrich with SQLite metadata
    D->>U: JSON response (FlightDetailResponse)
```

### 3.3 Data Contracts

```mermaid
classDiagram
    class PositionEvent {
        +String icao24
        +String callsign
        +Double lat
        +Double lon
        +Double heading
        +Double altitude
        +Double velocity
        +Double verticalRate
        +Long lastContact
        +Boolean onGround
    }

    class FlightMapItem {
        +String icao24
        +String callsign
        +Double lat
        +Double lon
        +Double heading
        +Long lastSeen
        +Double speed
        +Double altitude
    }

    class FlightDetailResponse {
        +String icao24
        +String callsign
        +String registration
        +String manufacturer
        +String model
        +String typecode
        +String category
        +Double lat, lon, heading
        +Double altitude, velocity
        +String country
        +Boolean militaryHint
        +FlightTrackPoint[] track
    }

    class AircraftMetadata {
        +String icao24
        +String country
        +String category
        +String manufacturerName
        +String model
        +String registration
        +String typecode
        +Boolean militaryHint
        +Integer yearBuilt
        +String ownerOperator
    }

    PositionEvent --> FlightMapItem : maps to
    PositionEvent --> FlightDetailResponse : enriches
    AircraftMetadata --> FlightDetailResponse : enriches
```

---

## 4. Data Architecture

### 4.1 Redis Schema

```mermaid
graph TB
    subgraph "Redis Structures"
        subgraph "List (Queue)"
            QUEUE["cloudradar:ingest:queue<br/>List of JSON events<br/>Producer: Ingester<br/>Consumer: Processor"]
        end

        subgraph "Hash (Last Positions)"
            LAST["cloudradar:aircraft:last<br/>Hash: icao24 → PositionEvent JSON<br/>Updated by: Processor<br/>Read by: Dashboard"]
        end

        subgraph "Lists (Tracks)"
            TRACK["cloudradar:aircraft:track:{icao24}<br/>List of PositionEvent JSON<br/>LTRIM 0 179 (max 180 points)<br/>Updated by: Processor<br/>Read by: Dashboard"]
        end

        subgraph "Set (Bbox Membership)"
            BBOX["cloudradar:aircraft:in_bbox<br/>Set of icao24 strings<br/>Updated by: Processor<br/>Read by: Processor (metrics)"]
        end
    end

    QUEUE -->|LPUSH| INGESTER[Ingester]
    QUEUE -->|BLPOP| PROCESSOR[Processor]
    PROCESSOR -->|HSET| LAST
    PROCESSOR -->|LPUSH/LTRIM| TRACK
    PROCESSOR -->|SADD/SREM| BBOX
    DASHBOARD[Dashboard] -->|HGETALL| LAST
    DASHBOARD -->|LRANGE| TRACK
```

### 4.2 SQLite Schema

```mermaid
erDiagram
    AIRCRAFT {
        string icao24 PK
        string country
        string category_description
        string icao_aircraft_class
        string manufacturer_icao
        string manufacturer_name
        string model
        string registration
        string typecode
        boolean military_hint
        integer year_built
        string owner_operator
    }

    AIRCRAFT ||--o{ POSITION_EVENT : enriches
    
    note "Read-only mode (?mode=ro)"
    note "In-memory LRU cache (50k entries)"
    note "Distributed via S3 (versioned)"
```

### 4.3 Storage Strategy

```mermaid
graph LR
    subgraph "Stateful Storage"
        REDIS_PVC[Redis PVC<br/>local-path<br/>5Gi<br/>AOF enabled]
        PROM_PVC[Prometheus PVC<br/>ebs-gp3<br/>5Gi<br/>7d retention]
        SQLITE_VOL[SQLite EmptyDir<br/>Downloaded at startup<br/>Verified SHA256]
    end

    subgraph "External Storage"
        S3_BACKUP[S3 Backup Bucket<br/>SQLite snapshots<br/>Versioned + SSE]
        S3_REFDATA[S3 Reference Data<br/>aircraft.db<br/>Versioned by SHA256]
    end

    REDIS_PVC -.->|Manual backup| S3_BACKUP
    S3_REFDATA -->|InitContainer download| SQLITE_VOL

    style REDIS_PVC fill:#ffecb3
    style PROM_PVC fill:#c8e6c9
    style S3_BACKUP fill:#e1bee7
    style S3_REFDATA fill:#e1bee7
```

---

## 5. Deployment Architecture

### 5.1 GitOps Flow

```mermaid
graph TB
    subgraph "Source Control"
        GIT[Git Repository<br/>k8s/apps/<br/>k8s/platform/]
    end

    subgraph "CI/CD"
        GHA[GitHub Actions<br/>Build images<br/>Push to GHCR]
        GHCR[GitHub Container<br/>Registry]
    end

    subgraph "GitOps Controller"
        ARGO[ArgoCD<br/>Auto-sync enabled<br/>Sync waves 0,1]
    end

    subgraph "k3s Cluster"
        PLATFORM[Platform Apps<br/>ESO, CRDs]
        APPS[Application Pods<br/>ingester, processor, dashboard]
    end

    GIT -->|Commit| GHA
    GHA -->|Push| GHCR
    GIT -->|Watch| ARGO
    ARGO -->|Apply wave 0| PLATFORM
    ARGO -->|Apply wave 1| APPS
    GHCR -->|Pull| APPS

    style ARGO fill:#f4511e,color:#fff
```

### 5.2 ArgoCD Bootstrap

```mermaid
sequenceDiagram
    participant CI as GitHub Actions
    participant TF as Terraform
    participant AWS as AWS (k3s node)
    participant SSM as AWS SSM
    participant K3S as k3s Cluster
    participant ARGO as ArgoCD

    CI->>TF: terraform apply
    TF->>AWS: Provision VPC, k3s nodes
    TF->>SSM: Store ArgoCD values in SSM
    TF->>CI: Apply complete
    
    CI->>SSM: Send-Command (bootstrap-argocd.sh)
    SSM->>AWS: Execute on k3s server
    AWS->>AWS: helm install argocd
    AWS->>K3S: ArgoCD deployed
    AWS->>K3S: Create root Applications
    
    ARGO->>ARGO: Sync k8s/platform (wave 0)
    ARGO->>ARGO: Sync k8s/apps (wave 1)
    ARGO->>K3S: Deploy all workloads
```

### 5.3 Resource Allocation

> **Note**: This diagram shows a logical distribution of workloads. Actual pod placement is dynamic and managed by k8s scheduler based on resource availability and constraints.

```mermaid
graph TB
    subgraph "k3s Server (t3a.medium: 2 vCPU, 4GB RAM)"
        CONTROL[Control Plane<br/>etcd, API server<br/>scheduler, controller]
        TRAEFIK[Traefik Ingress<br/>requests: 50m/128Mi<br/>limits: 250m/512Mi]
    end

    subgraph "k3s Worker ASG (t3a.medium, desired: 1)"
        subgraph "Typical Pod Distribution"
            INGESTER_POD[Ingester<br/>requests: 250m/512Mi<br/>limits: 500m/1Gi]
            PROCESSOR_POD[Processor<br/>requests: 250m/512Mi<br/>limits: 500m/1Gi]
            DASHBOARD_POD[Dashboard<br/>requests: 250m/512Mi<br/>limits: 500m/1Gi]
            REDIS_POD[Redis<br/>requests: 50m/128Mi<br/>limits: 250m/256Mi]
            HEALTH_POD[Health<br/>requests: 10m/32Mi<br/>limits: 50m/64Mi]
            PROM_POD[Prometheus<br/>requests: 500m/1Gi<br/>limits: 1000m/2Gi]
            GRAF_POD[Grafana<br/>requests: 100m/256Mi<br/>limits: 200m/512Mi]
        end
    end

    style CONTROL fill:#e3f2fd
    style INGESTER_POD fill:#fff3e0
    style PROCESSOR_POD fill:#fff3e0
    style DASHBOARD_POD fill:#fff3e0
    style REDIS_POD fill:#ffecb3
    style PROM_POD fill:#c8e6c9
```

---

## 6. Observability Architecture

### 6.1 Metrics Pipeline

```mermaid
graph TB
    subgraph "Application Pods"
        INGESTER[Ingester<br/>/metrics]
        PROCESSOR[Processor<br/>/metrics]
        DASHBOARD[Dashboard<br/>/metrics/prometheus]
        REDIS[Redis<br/>/metrics via exporter]
    end

    subgraph "System Metrics"
        NODE_EXP[node-exporter<br/>Host metrics]
        KUBE_STATE[kube-state-metrics<br/>k8s objects]
    end

    subgraph "Monitoring"
        PROM[Prometheus<br/>Scrape interval: 30s<br/>Retention: 7d<br/>Storage: 5GB PVC]
        GRAF[Grafana<br/>Datasources:<br/>Prometheus, CloudWatch]
    end

    INGESTER -->|Scrape :8080/metrics| PROM
    PROCESSOR -->|Scrape :8080/metrics| PROM
    DASHBOARD -->|Scrape :8080/metrics| PROM
    REDIS -->|Scrape| PROM
    NODE_EXP -->|Scrape| PROM
    KUBE_STATE -->|Scrape| PROM
    
    PROM -->|Query| GRAF
    
    style PROM fill:#c8e6c9
    style GRAF fill:#90caf9
```

### 6.2 Health Checks

```mermaid
graph TB
    subgraph "Kubernetes Probes"
        LIVENESS[Liveness Probe<br/>/healthz<br/>Restart on failure]
        READINESS[Readiness Probe<br/>/healthz or /readyz<br/>Remove from Service]
    end

    subgraph "Service Endpoints"
        INGESTER_H[Ingester<br/>GET /healthz<br/>200 OK]
        PROCESSOR_H[Processor<br/>GET /healthz<br/>200 OK]
        DASHBOARD_H[Dashboard<br/>GET /healthz<br/>200 OK]
        HEALTH_H[Health<br/>GET /healthz<br/>Checks k8s API]
    end

    subgraph "Edge Health"
        EDGE[Edge Nginx<br/>Upstream health<br/>Via Health service]
    end

    LIVENESS -->|HTTP GET| INGESTER_H
    LIVENESS -->|HTTP GET| PROCESSOR_H
    LIVENESS -->|HTTP GET| DASHBOARD_H
    READINESS -->|HTTP GET| HEALTH_H
    EDGE -->|Proxy pass| HEALTH_H

    style LIVENESS fill:#ffcdd2
    style READINESS fill:#c5e1a5
```

---

## 7. Security Architecture

### 7.1 Secrets Management

```mermaid
graph TB
    subgraph "Source of Truth"
        SSM[AWS SSM<br/>Parameter Store<br/>/cloudradar/*]
    end

    subgraph "External Secrets Operator"
        ESO[ESO Controller<br/>ClusterSecretStore]
        ES[ExternalSecret<br/>Sync SSM → K8s Secret]
    end

    subgraph "Kubernetes Secrets"
        K8S_SECRET[K8s Secret<br/>base64 encoded<br/>Mounted as env/volume]
    end

    subgraph "Application"
        INGESTER_APP[Ingester<br/>OpenSky OAuth2]
        ADMIN_APP[Admin-Scale<br/>HMAC secret]
    end

    SSM -->|Read via IAM| ESO
    ESO -->|Create| ES
    ES -->|Sync| K8S_SECRET
    K8S_SECRET -->|Mount| INGESTER_APP
    K8S_SECRET -->|Mount| ADMIN_APP

    style SSM fill:#e1bee7
    style ESO fill:#f4511e,color:#fff
    style K8S_SECRET fill:#ffecb3
```

### 7.2 Network Security

```mermaid
graph TB
    subgraph "Internet"
        ATTACKER[Potential Attacker]
        USER[Legitimate User]
    end

    subgraph "Edge Layer"
        EDGE[Edge Nginx<br/>Basic Auth<br/>HTTPS only<br/>Allowed CIDRs]
    end

    subgraph "Private Network"
        K3S[k3s Nodes<br/>No public IPs<br/>SG: self-ref only]
        REDIS[Redis<br/>ClusterIP<br/>No auth<br/>Internal only]
    end

    ATTACKER -.->|Blocked by SG| EDGE
    USER -->|HTTPS 443| EDGE
    EDGE -->|Basic Auth| EDGE
    EDGE -->|NodePort| K3S
    K3S -->|ClusterIP| REDIS

    style EDGE fill:#ffcdd2
    style K3S fill:#c8e6c9
    style REDIS fill:#ffecb3
```

### 7.3 IAM Policy Summary

```mermaid
graph LR
    subgraph "GitHub OIDC Role"
        OIDC_TF[Terraform Plan/Apply<br/>VPC, EC2, IAM RO]
        OIDC_ECR[GHCR Push<br/>Container registry write]
        OIDC_SSM[SSM Send-Command<br/>ArgoCD bootstrap]
    end

    subgraph "k3s Node Role"
        NODE_SSM[SSM Managed Policy<br/>Session Manager]
        NODE_EBS[EBS CSI Inline Policy<br/>Volume attach/detach]
        NODE_S3[S3 Backup Policy<br/>GetObject/PutObject]
    end

    subgraph "Edge Role"
        EDGE_SSM[SSM Managed Policy<br/>Session Manager]
        EDGE_PARAM[SSM Parameter Read<br/>/cloudradar/edge/*]
    end

    style OIDC_TF fill:#e1f5e1
    style NODE_SSM fill:#e1f5e1
    style EDGE_SSM fill:#e1f5e1
```

---

## 8. Failure Scenarios

### 8.1 Component Failure Matrix

```mermaid
graph TB
    subgraph "Component Failures"
        F1[Redis Pod Crash]
        F2[Processor Pod Crash]
        F3[Ingester Backoff]
        F4[NAT Instance Down]
        F5[k3s Worker Node Down]
    end

    subgraph "Impact"
        I1[Buffer + Aggregates Lost<br/>RTO: ~10min repopulate]
        I2[Event processing stopped<br/>Queue builds up<br/>Auto-restart]
        I3[No new events ingested<br/>Exponential backoff<br/>Up to 1h]
        I4[No outbound connectivity<br/>Ingester cannot fetch<br/>Manual TF re-apply]
        I5[Pods rescheduled<br/>with local-path PVC<br/>loss]
    end

    F1 --> I1
    F2 --> I2
    F3 --> I3
    F4 --> I4
    F5 --> I5

    style I1 fill:#ffcdd2
    style I2 fill:#fff9c4
    style I3 fill:#fff9c4
    style I4 fill:#ffcdd2
    style I5 fill:#ffcdd2
```

### 8.2 Recovery Procedures

```mermaid
stateDiagram-v2
    [*] --> Normal: System operational

    Normal --> RedisFailure: Redis pod crash
    Normal --> ProcessorFailure: Processor crash
    Normal --> IngesterBackoff: API degradation

    RedisFailure --> RedisRestart: k8s restarts pod
    RedisRestart --> RedisRecover: AOF replay
    RedisRecover --> WaitRepopulate: Wait ~10min
    WaitRepopulate --> Normal: Ingester/Processor repopulate

    ProcessorFailure --> ProcessorRestart: k8s restarts pod
    ProcessorRestart --> Normal: Resume BLPOP

    IngesterBackoff --> BackoffLevel: Exponential backoff
    BackoffLevel --> APIRecovery: API available again
    APIRecovery --> Normal: Reset backoff

    Normal --> [*]
```

---

## 9. Scalability Patterns

### 9.1 Current Scaling Limits

```mermaid
graph TB
    subgraph "Stateless (Horizontal Scale)"
        INGESTER[Ingester<br/>Current: 0-2<br/>Scale: Admin-Scale API<br/>Limit: API rate limit]
        DASHBOARD[Dashboard<br/>Current: 1<br/>Scale: HPA on CPU/Memory<br/>Limit: Redis HGETALL]
    end

    subgraph "Stateful (Vertical Scale Only)"
        PROCESSOR[Processor<br/>Current: 1<br/>Scale: Vertical only<br/>Bottleneck: Single BLPOP]
        REDIS[Redis<br/>Current: 1 replica<br/>Scale: None (SPOF)<br/>Bottleneck: Memory]
    end

    style INGESTER fill:#c8e6c9
    style DASHBOARD fill:#c8e6c9
    style PROCESSOR fill:#ffecb3
    style REDIS fill:#ffcdd2
```

### 9.2 Future Scaling Strategy

```mermaid
graph TB
    subgraph "v1-mvp (Current)"
        V1_REDIS[Redis<br/>Single replica<br/>local-path PVC]
        V1_PROC[Processor<br/>Single consumer<br/>BLPOP]
    end

    subgraph "v1.1 (Planned)"
        V11_REDIS[Redis<br/>Single replica<br/>ebs-gp3 PVC<br/>Node-portable]
    end

    subgraph "v2 (Future)"
        V2_REDIS[Redis Sentinel<br/>3 replicas<br/>Automatic failover]
        V2_PROC[Processor<br/>Partitioned queues<br/>Multiple consumers]
        V2_KAFKA[Kafka<br/>Topic partitions<br/>Strong durability]
    end

    V1_REDIS --> V11_REDIS
    V11_REDIS --> V2_REDIS
    V1_PROC --> V2_PROC
    V2_REDIS --> V2_KAFKA

    style V1_REDIS fill:#ffcdd2
    style V11_REDIS fill:#fff9c4
    style V2_REDIS fill:#c8e6c9
    style V2_KAFKA fill:#c8e6c9
```

---

## 10. Cost Breakdown

### 10.1 Monthly Cost Estimate (v1-mvp)

> **Based on terraform.tfvars**: 1 server + 1 worker (desired), not 2 workers

```mermaid
pie title Monthly AWS Cost (~$78-82)
    "k3s Server (t3a.medium)" : 34
    "k3s Worker (1x t3a.medium)" : 34
    "EBS Volumes (~133GB gp3)" : 13
    "Edge (t3.micro)" : 9
    "NAT Instance (t3.nano)" : 5
    "S3 Storage + Transfer" : 5
```

#### Detailed Cost Breakdown

| Component | Spec | Unit Price | Quantity | Monthly Cost |
|-----------|------|------------|----------|--------------|
| **k3s Server** | t3a.medium | $0.0376/h | 730h | **$27.45** |
| **k3s Worker** | t3a.medium | $0.0376/h | 730h | **$27.45** |
| **Edge** | t3.micro | $0.0104/h | 730h | **$7.59** |
| **NAT Instance** | t3.nano | $0.0052/h | 730h | **$3.80** |
| **EBS k3s Server** | gp3 40GB | $0.08/GB/mo | 40GB | $3.20 |
| **EBS k3s Worker** | gp3 40GB | $0.08/GB/mo | 40GB | $3.20 |
| **EBS Edge** | gp3 40GB | $0.08/GB/mo | 40GB | $3.20 |
| **EBS NAT** | gp3 8GB | $0.08/GB/mo | 8GB | $0.64 |
| **EBS Prometheus** | gp3 5GB | $0.08/GB/mo | 5GB | $0.40 |
| **Total EBS** | | | 133GB | **$10.64** |
| **S3 Storage** | SQLite backups | $0.023/GB | ~10GB | $0.23 |
| **S3 Requests** | GET/PUT | Variable | Low usage | $0.10 |
| **Data Transfer** | Egress (OpenSky, packages) | $0.09/GB (first 10TB) | ~5-10GB/mo | **$0.50-1.00** |
| | | | **Total** | **~$78.00** |

**Notes**:
- Pricing based on AWS us-east-1 on-demand rates (Feb 2026)
- 730 hours/month average (365 days / 12 months × 24h)
- EBS gp3 baseline: $0.08/GB/month (no IOPS/throughput charges at baseline)
- VPC Flow Logs to CloudWatch: ~$0.50/month (3-day retention, minimal traffic)
- Data transfer within same AZ: free (k3s nodes, Redis, Prometheus)
- Data transfer ingress: free (OpenSky responses)

#### FinOps Optimization Opportunities

| Optimization | Current | Optimized | Savings/mo | Trade-offs |
|--------------|---------|-----------|------------|------------|
| **Spot Instances (workers)** | On-demand t3a.medium | Spot t3a.medium (~70% discount) | **-$19.20** | Risk of interruption, need graceful node drain |
| **Instance Sizing** | t3a.medium (2 vCPU/4GB) | t3a.small (2 vCPU/2GB) | **-$13.70/node** | Less headroom, tighter resource limits |
| **EBS Volume Reduction** | 40GB root volumes | 20GB root volumes | **-$4.80** | Requires disk cleanup, less buffer |
| **Dev Shutdown Schedule** | 24/7 | 10h/day (workweeks only) | **-$43.00** | Dev downtime nights/weekends |
| **Prometheus Retention** | 7d (5GB PVC) | 3d (2GB PVC) | **-$0.24** | Less historical data |
| **S3 Lifecycle Policies** | No expiration | 30d→IA, 90d→Glacier | **-$0.10** | Slower restore for old backups |
| **Single-AZ Strategy** | Already single-AZ | ✅ Optimized | $0 | Lower availability vs multi-AZ |
| **NAT Instance vs Gateway** | Already NAT instance | ✅ Optimized | $0 | Saves ~$28/mo vs NAT Gateway |
| **k3s vs EKS** | Already k3s | ✅ Optimized | $0 | Saves ~$73/mo vs EKS control plane |

**Recommended Quick Wins (Low Risk)**:
1. **Reduce EBS volumes to 20GB** (-$4.80/mo) — 40GB is generous for minimal OS + Docker
2. **Spot instances for workers** (-$19.20/mo) — k3s tolerates node churn well, stateful workloads on server
3. **Prometheus 3d retention** (-$0.24/mo) — 3 days sufficient for MVP observability
4. **Total easy savings: ~$24/mo → New total: ~$54/mo**

**Aggressive Optimization (Higher Risk)**:
- Dev shutdown schedule (nights/weekends): -$43/mo → **$35/mo total**
- Downsize to t3a.small: additional -$27.40/mo → **$26/mo total**
- Trade-off: resource pressure, potential OOM, tighter limits

**Not Recommended** (Already Optimized):
- ❌ Remove NAT instance (no egress connectivity)
- ❌ Switch to EKS (increases cost by $73/mo)
- ❌ Multi-AZ deployment (increases costs significantly for marginal availability gain in portfolio context)

### 10.2 Cost Optimization Decisions

```mermaid
graph LR
    subgraph "MVP Choices (Low Cost)"
        K3S[k3s on EC2<br/>~$20/month]
        NAT_INST[NAT Instance<br/>~$4/month]
        REDIS_LOCAL[Redis in-cluster<br/>$0 + PVC]
        PROM_LOCAL[Prometheus in-cluster<br/>~$0.50/month]
    end

    subgraph "Enterprise Alternatives (High Cost)"
        EKS[EKS<br/>~$73/month control plane<br/>+ worker nodes]
        NAT_GW[NAT Gateway<br/>~$32/month]
        REDIS_ELASTIC[ElastiCache Redis<br/>~$15-50/month]
        CLOUDWATCH[CloudWatch Metrics<br/>~$10-30/month]
    end

    K3S -.->|Savings: ~$53/month| EKS
    NAT_INST -.->|Savings: ~$28/month| NAT_GW
    REDIS_LOCAL -.->|Savings: ~$15-50/month| REDIS_ELASTIC
    PROM_LOCAL -.->|Savings: ~$10-30/month| CLOUDWATCH

    style K3S fill:#c8e6c9
    style NAT_INST fill:#c8e6c9
    style REDIS_LOCAL fill:#c8e6c9
    style PROM_LOCAL fill:#c8e6c9
    style EKS fill:#ffcdd2
    style NAT_GW fill:#ffcdd2
    style REDIS_ELASTIC fill:#ffcdd2
    style CLOUDWATCH fill:#ffcdd2
```

---

## 11. Evolution Roadmap

### 11.1 Version Timeline

```mermaid
timeline
    title CloudRadar Evolution Timeline
    
    v1-mvp (Current) : Infrastructure IaC
                     : k3s + ArgoCD
                     : Event pipeline
                     : Dashboard API
                     : Observability
    
    v1.1 (Q1 2026) : Frontend React/Vite
                   : Redis ebs-gp3 PVC
                   : Alerting rules
                   : API documentation
    
    v2 (Q2 2026) : Redis HA (Sentinel)
                 : Processor partitioning
                 : Loki logging
                 : Chaos testing
                 : Multi-AZ
```

### 11.2 Technical Debt

```mermaid
graph TB
    subgraph "High Priority"
        TD1[Redis SPOF<br/>Migration: ebs-gp3 + Sentinel]
        TD2[Dashboard HGETALL<br/>Refactor: Filter-before-load]
        TD3[Integration Tests<br/>Add: Service-level tests]
    end

    subgraph "Medium Priority"
        TD4[Processor Scale-Out<br/>Design: Queue partitioning]
        TD5[Centralized Logging<br/>Deploy: Loki + Promtail]
        TD6[Secret Rotation<br/>Automate: Lambda + SSM]
    end

    subgraph "Low Priority"
        TD7[Multi-AZ Deployment<br/>Redesign: Network + Data]
        TD8[EKS Migration<br/>Migrate: Control plane]
        TD9[Chaos Engineering<br/>Implement: Chaos Mesh]
    end

    TD1 -.->|Blocks v1.1| TD4
    TD2 -.->|Enables 10k+ aircraft| Frontend[Frontend v1.1]

    style TD1 fill:#ffcdd2
    style TD2 fill:#ffcdd2
    style TD3 fill:#ffcdd2
```

---

## 12. References

### 12.1 Documentation Index

| Document | Purpose | Location |
|----------|---------|----------|
| Infrastructure Overview | Network, compute, storage | `docs/architecture/infrastructure.md` |
| Application Architecture | Service details, data flow | `docs/architecture/application-architecture.md` |
| Architecture Review | Quality assessment | `docs/code-reviews/architecture-review.md` |
| ADRs (19 total) | Decision rationale | `docs/architecture/decisions/` |
| Runbooks | Operational procedures | `docs/runbooks/` |
| IAM Inventory | Roles, policies, access | `docs/iam/inventory.md` |

### 12.2 Key Architecture Decision Records

```mermaid
timeline
    title Key ADR Timeline
    ADR-0001 (2026-01-08) : AWS Region (us-east-1)
    ADR-0002 (2026-01-08) : k3s on EC2
    ADR-0003 (2026-01-08) : Redis Buffer
    ADR-0008 (2026-01-08) : VPC + NAT Instance
    ADR-0009 (2026-01-08) : Security Baseline
    ADR-0010 (2026-01-08) : Terraform + OIDC
    ADR-0013 (2026-01-17) : ArgoCD via SSM
    ADR-0014 (2026-01-19) : Java/Spring Boot
    ADR-0018 (2026-02-10) : SQLite + S3 Distribution
```

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **ADR** | Architecture Decision Record — documented design choice with rationale |
| **AOF** | Append-Only File — Redis persistence mechanism |
| **ArgoCD** | GitOps continuous delivery tool for Kubernetes |
| **BLPOP** | Blocking List Pop — Redis command for consuming queue events |
| **EBS CSI** | Elastic Block Store Container Storage Interface — k8s persistent volumes |
| **ESO** | External Secrets Operator — sync external secrets to k8s |
| **HGETALL** | Redis command to retrieve all fields from a hash |
| **k3s** | Lightweight Kubernetes distribution optimized for resource-constrained environments |
| **NAT Instance** | EC2 instance acting as Network Address Translation gateway |
| **OIDC** | OpenID Connect — authentication protocol used for GitHub Actions AWS access |
| **SSM** | AWS Systems Manager — parameter store and session manager |
| **Sync Wave** | ArgoCD ordering mechanism for resource dependencies |

---

## Appendix B: Network Ports Reference

| Service | Port | Protocol | Purpose |
|---------|------|----------|---------|
| Edge → Internet | 443 | TCP | HTTPS ingress |
| Edge → k3s | 30080, 30081 | TCP | NodePort to Traefik |
| k3s API | 6443 | TCP | Kubernetes API server |
| k3s Kubelet | 10250 | TCP | Node metrics |
| k3s Flannel | 8472 | UDP | Pod network (VXLAN) |
| Redis | 6379 | TCP | Redis protocol (ClusterIP) |
| Prometheus | 9090 | TCP | Metrics query API |
| Grafana | 3000 | TCP | Dashboard UI |
| Application /metrics | 8080 | TCP | Prometheus scrape target |

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-13  
**Maintained By**: CloudRadar Team  
**Review Cycle**: Quarterly or on major architecture changes
