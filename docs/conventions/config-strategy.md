# MateCloud Configuration Strategy

## Goals

1. **Minimal service bootstrap** — each service's `application.yml` is ~15 lines.
2. **One shared Nacos file per environment** — `mate-infra-${profile}.yml`.
3. **Framework constants never leave the jar** — Dubbo/Jackson/Sa-Token/MyBatis Plus defaults ship inside mate-base.
4. **Env-specific infra in Nacos** — MySQL/Redis/RabbitMQ/MinIO/Seata/XXL-Job/Sentinel endpoints.
5. **Secrets in env vars** — api-keys and passwords come from environment variables, never from Git.
6. **Per-service override is optional** — services work without any Nacos config at all.

## The four layers

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. Env vars / JVM system properties          (highest priority) │
│    NACOS_SERVER_ADDR, MYSQL_HOST, ANTHROPIC_API_KEY, ...        │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. Nacos: ${service}-${profile}.yml    (optional per-service)   │
│    Only service-unique settings. Usually empty.                 │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. Nacos: mate-infra-${profile}.yml    ← THE shared config      │
│    MySQL / Redis / RabbitMQ / MinIO / Seata / XXL-Job / Sentinel│
│    / Dubbo registry / Sa-Token Redis / Spring AI api-keys       │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. classpath:mate-defaults.yml         (packaged in mate-base)  │
│    Dubbo protocol / Jackson / Actuator / Logging / Sa-Token     │
│    / MyBatis Plus / Spring AI provider defaults / mate.*        │
└─────────────────────────────────────────────────────────────────┘
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. Service application.yml              (~15 lines, entry point)│
│    spring.application.name + server.port + spring.config.import │
└─────────────────────────────────────────────────────────────────┘
```

## What lives where — full audit

| Module / Feature | Classpath (`mate-defaults.yml`) | Nacos (`mate-infra-${profile}.yml`) |
|------------------|--------------------------------|-------------------------------------|
| **Nacos itself** | `spring.cloud.nacos.{discovery,config}` with env-var placeholders | Full auth overrides when needed |
| **MyBatis Plus** | `mybatis-plus.global-config.*`, `mybatis-plus.configuration.*`, `mapper-locations`, `type-aliases-package` | (empty — ORM config is framework-constant) |
| **Druid DataSource** | (empty) | `spring.datasource.url/username/password`, `druid.initial-size/max-active/filters/stat-view-servlet/web-stat-filter/connection-properties` |
| **Redis / Redisson** | (empty — connection is env-specific) | `spring.data.redis.host/port/password/database/lettuce.pool.*` |
| **RabbitMQ** | (empty) | `spring.rabbitmq.host/port/username/password/virtual-host/publisher-confirm-type/publisher-returns/listener.simple.*` |
| **MinIO** | `minio.bucket-name=matecloud` | `minio.endpoint/access-key/secret-key` |
| **Dubbo protocol** | `dubbo.application/protocol/consumer/provider/scan/metadata-report` | (empty) |
| **Dubbo registry** | `dubbo.registry.address=nacos://${NACOS_SERVER_ADDR}` + parameters | Optional overrides (group, register-consumer-url) |
| **Sa-Token** | `sa-token.token-name/token-prefix/timeout/active-timeout/is-concurrent/token-style/is-read-cookie/is-read-header/is-log/jwt-secret-key` | `sa-token.alone-redis.*` (optional isolated session redis) |
| **XXL-Job** | `xxl.job.port=9999`, `log-retention-days=30` | `xxl.job.admin-addresses`, `access-token`, `log-path` |
| **Seata** | `seata.enabled=false`, `application-id` | `tx-service-group`, `service.vgroup-mapping`, `service.grouplist`, `config.nacos.*`, `registry.nacos.*` |
| **Sentinel** | (empty — disabled by default) | `spring.cloud.sentinel.enabled`, `transport.dashboard`, `transport.port`, `datasource.nacos.*` |
| **Actuator / Prometheus** | `management.endpoints.web.exposure.include`, `management.endpoint.health.*`, `management.tracing.sampling.probability`, `management.metrics.tags` | (empty) |
| **Logging** | pattern + framework levels (`org.apache.dubbo=WARN` etc.) | per-env root/business levels |
| **Jackson** | `spring.jackson.*` (date format, timezone, inclusions) | (empty) |
| **Servlet multipart** | `spring.servlet.multipart.*` | (empty) |
| **Spring AI providers** | All 6 providers' default endpoints + model names | Optional api-keys (prefer env vars) |
| **MCP server** | `spring.ai.mcp.server.enabled=false` | Enable per env: `spring.ai.mcp.server.enabled=true` |
| **mate.* feature flags** | `mate.ai.*`, `mate.idempotent.*`, `mate.tenant.*`, `mate.doc.*`, `mate.devtools.*` | (override per env if needed) |

## Example: minimum service application.yml

```yaml
spring:
  application:
    name: mate-system
  config:
    import:
      - classpath:mate-defaults.yml
      - optional:nacos:mate-infra-${spring.profiles.active:dev}.yml
      - optional:nacos:${spring.application.name}-${spring.profiles.active:dev}.yml

server:
  port: 9030
```

That's it. **Everything else is inherited.**

## Running it all

```bash
# 1. Start infra (MySQL/Redis/RabbitMQ/Nacos/MinIO)
make infra-up

# 2. Bootstrap Nacos with the shared dev config (one command)
java -jar mate-cli/target/mate-cli.jar config init
#    → publishes mate-infra-dev.yml from packaged template

# 3. Start the services
make up

# 4. Sanity check
java -jar mate-cli/target/mate-cli.jar status

# 5. Chat with the cluster
export ANTHROPIC_API_KEY=sk-ant-xxx    # or OPENAI_API_KEY / DEEPSEEK_API_KEY
java -jar mate-cli/target/mate-cli.jar ai chat "今天注册了多少用户?"
```

## Promoting dev config to prod

```bash
# 1. Review the prod template
vi docs/nacos-templates/mate-infra-prod.yml
# Replace TODO placeholders with your prod MySQL/Redis/RabbitMQ endpoints.

# 2. Publish to the prod namespace
NACOS_NAMESPACE=prod \
  java -jar mate-cli/target/mate-cli.jar config push mate-infra-prod.yml \
    --file docs/nacos-templates/mate-infra-prod.yml

# 3. Start services with SPRING_PROFILES_ACTIVE=prod
SPRING_PROFILES_ACTIVE=prod \
NACOS_NAMESPACE=prod \
  java -jar mate-system/target/mate-system.jar
```

## Secrets handling

Never put real secrets (api-keys, DB passwords, JWT keys) directly in
Nacos — Nacos is often unencrypted. Instead, write them as env var
placeholders in `mate-infra-${profile}.yml`:

```yaml
spring:
  datasource:
    password: ${MYSQL_PASSWORD}           # ← resolves at runtime
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}      # ← resolves at runtime
```

Then inject the env vars via:
- **Local dev**: `.env` file loaded by `make infra-up` / `docker-compose up`
- **K8s**: a `Secret` mounted as env vars in the Deployment
- **Vault**: sidecar / CSI driver populating env vars at pod start
