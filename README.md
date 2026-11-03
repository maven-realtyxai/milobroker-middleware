# milobroker-middleware

Java 25 microservices for the MiloBroker platform. This file helps you
understand each service, then build, compile, and run everything locally.

## Services

| Service | Port | Database (user) | What it does |
| --- | ---: | --- | --- |
| Milo Concierge | 8081 | `conciergedb` (`concierge_app`) | WhatsApp webhook + onboarding Flows, input/output guardrails, routing to Marketplace/Atlas, model-execution audit |
| Milo Marketplace | 8082 | `bizdb` (`bizdb_app`) | User records, property listings, moderation, search, consent-controlled leads |
| Milo Atlas | 8083 | `staticdb` (`staticdb_app`) | Static reference data: countries, states, cities, WhatsApp contact lookup |

Rules that shape the whole repo:

* Services talk to each other through HTTP APIs only, never by reading one
  another's tables. Concierge calls Marketplace at `127.0.0.1:8082` and Atlas
  at `127.0.0.1:8083`.
* Each database has exactly one app user with `CONNECT`. A role that works on
  one database is rejected on the others — that is intentional, not an error.
* Schema is applied with manual SQL from `docs/`. There is no migration
  runner: Flyway was removed and Hibernate runs with `ddl-auto: none`, so
  startup never creates or validates tables. Hibernate only manages
  connections and the pool.
* Meta model output is untrusted input: only Concierge invokes models,
  validates the result, and routes an allowed typed command.

Key endpoints:

* Concierge: `POST /api/v1/whatsapp/webhook`, `POST /api/v1/flows/onboarding`
* Marketplace: `/api/v1/onboarding/profiles`, `POST /api/v1/search`
* Atlas: `/api/v1/geo/countries`, `/api/v1/geo/states?countryIso2=IN`,
  `/api/v1/geo/cities?state=KA`, `/api/contact?countryIso2=IN`
* Health: `/actuator/health` on each service's port.

## Prerequisites

* Java 25 and Maven 3.9+ (`mvn -version` should show both).
* Docker, for local PostgreSQL and for the EC2-style container deploy.
* AWS CLI v2 with SSO, only if you connect to the shared EC2 database
  through the SSM tunnel.

## Configure

Each service reads `application.yaml` defaults, overridden by a profile file
in the backend root. Full details are in
[environment configuration](docs/environment-configuration.md).

| Profile flag | File loaded |
| --- | --- |
| `local` (default) | `.env.local` |
| `dev` / `stage` / `prod` | `.env.dev` / `.env.stage` / `.env.prod` |

Committed `.example` files are templates. Copy one to `.env.local` and fill
in real values. The local file points at the EC2 tunnel on `127.0.0.1:5433`;
for local Docker PostgreSQL, use port `5432` and password
`milobroker-local`. Example Atlas settings:

```properties
ATLAS_DB_URL=jdbc:postgresql://127.0.0.1:5433/staticdb
ATLAS_DB_USERNAME=staticdb_app
ATLAS_DB_PASSWORD=your-actual-password
ATLAS_PORT=8083
```

Marketplace reads `MARKETPLACE_*`, Concierge reads `CONCIERGE_*`,
`WHATSAPP_*`, and the downstream service URLs from the same file.

## Database setup

Start local PostgreSQL and create the roles and databases:

```powershell
docker compose up -d postgres
```

Apply each SQL file once, as the database owner, in this order:

```powershell
psql -h localhost -U concierge_app -d conciergedb -f docs/V1_conciergedb.sql
psql -h localhost -U staticdb_app -d staticdb -f docs/V1_staticdb.sql
psql -h localhost -U staticdb_app -d staticdb -f docs/V2_staticdb_whatsapp_contact.sql
psql -h localhost -U bizdb_app -d bizdb -f docs/V2_bizdb.sql
psql -h localhost -U bizdb_app -d bizdb -f docs/V3_bizdb_property_listings.sql
```

Local bootstrap credentials are user `milobroker`, password
`milobroker-local` (see [compose.yaml](compose.yaml) and
[infra/postgres/init.sql](infra/postgres/init.sql)).

To use the shared EC2 database instead, keep the SSM tunnel open in its own
terminal, then point the `.env.*` URLs at `127.0.0.1:5433`:

```powershell
.\start-db-tunnel.ps1
```

Always connect with `127.0.0.1`, never bare `localhost`: the tunnel listens
on IPv4 only, and `localhost` may resolve to `::1` first and time out.

For reaching the shared EC2 database — SSH access, Session Manager shell,
and pgAdmin over the SSM tunnel — see [EC2 access](docs/ec2-access.md).

## Build and compile

```powershell
mvn clean verify
```

This compiles all three services and runs the test suite (unit and slice
tests, no database required). To compile without running tests:

```powershell
mvn clean package -DskipTests
```

To build one service and its dependencies only:

```powershell
mvn -pl milo-atlas -am package -DskipTests
```

## Run locally

Run each service in its own terminal from the backend root:

```powershell
mvn -pl milo-atlas spring-boot:run "-Dspring-boot.run.profiles=local"
mvn -pl milo-marketplace spring-boot:run "-Dspring-boot.run.profiles=local"
mvn -pl milo-concierge spring-boot:run "-Dspring-boot.run.profiles=local"
```

Omit the profile flag for `local`. Inside a module directory, drop `-pl`:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Stop with Ctrl+C and restart after editing a configuration file.

Check that each service is up:

```powershell
curl.exe -i http://localhost:8081/actuator/health
curl.exe -i http://localhost:8082/actuator/health
curl.exe -i http://localhost:8083/actuator/health
curl.exe -i "http://localhost:8083/api/v1/geo/states?countryIso2=IN"
curl.exe -i "http://localhost:8083/api/contact?countryIso2=IN"
```

Logs go to the console and to rolling files at `logs/milo-<service>.log`
under the working directory (10 MB per file, 30 days, 1 GB total). In the
container deploy they live on per-service Docker volumes at `/app/logs` (see
[infra/middleware.compose.yaml](infra/middleware.compose.yaml)).

## Deploy to EC2

For the shared HTTPS endpoint and routing to all three services, see
[api.milobroker.com deployment](docs/api-domain-deploy.md).

Deploy steps, the `../.env` keys, the S3 media-bucket stack, and reverse-proxy
notes are in [middleware deploy](docs/middleware-deploy.md). EC2 database
passwords come from SSM SecureString parameters `/milobroker/mb_*_pw`.

## Troubleshooting

* `permission denied for database "X"` — you connected with the wrong role.
  Every database accepts only its own user (`concierge_app`,
  `bizdb_app`, `staticdb_app`). In pgAdmin, register one server per
  database, each with its own user and password.
* `could not initiate GSSAPI security context` — no password was sent.
  Set the password (env file or `PGPASSWORD`) instead of relying on OS auth.
* `localhost:5433` times out but `127.0.0.1:5433` works — expected. The
  SSM tunnel binds IPv4 only.
* Hibernate `SchemaManagementException` on startup — the schema SQL above
  was not applied. Startup never creates tables; apply the SQL and restart.
* `mvn` fails to download dependencies — the build needs Maven Central.
  Check network/proxy settings and retry.
>>>>>>> devn
