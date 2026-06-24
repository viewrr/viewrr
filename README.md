# viewrr

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

* [Ktor Documentation](https://ktor.io/docs/home.html)
* [Ktor GitHub page](https://github.com/ktorio/ktor)
* [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name                                                                                            | Description                                                                                             |
|-------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation)               | Provides automatic content conversion according to Content-Type and Accept headers                      |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization)           | Handles JSON serialization using kotlinx.serialization library                                          |
| [CORS](https://start.ktor.io/p/io.ktor/server-cors)                                             | Enables Cross-Origin Resource Sharing (CORS)                                                            |
| [WebSockets](https://start.ktor.io/p/io.ktor/server-websockets)                                 | Adds WebSocket protocol support for bidirectional client connections                                    |
| [Server-Sent Events (SSE)](https://start.ktor.io/p/io.ktor/server-sse)                          | Support for server push events                                                                          |
| [Static Content](https://start.ktor.io/p/io.ktor/server-static-content)                         | Serves static files from defined locations                                                              |
| [AutoHeadResponse](https://start.ktor.io/p/io.ktor/server-auto-head-response)                   | Provides automatic responses for HEAD requests                                                          |
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth)                                   | Provides extension point for handling the Authorization header                                          |
| [Authentication JWT](https://start.ktor.io/p/io.ktor/server-auth-jwt)                           | Handles JSON Web Token (JWT) bearer authentication scheme                                               |
| [Sessions](https://start.ktor.io/p/io.ktor/server-sessions)                                     | Adds support for persistent sessions through cookies or headers                                         |
| [Compression](https://start.ktor.io/p/io.ktor/server-compression)                               | Compresses responses using encoding algorithms like GZIP                                                |
| [Rate Limiting](https://start.ktor.io/p/io.github.flaxoos/server-rate-limiting)                 | Manage request rate limiting as you see fit                                                             |
| [Request Validation](https://start.ktor.io/p/io.ktor/server-request-validation)                 | Adds validation for incoming requests                                                                   |
| [Forwarded Headers](https://start.ktor.io/p/io.ktor/server-forwarded-header-support)            | Allows handling proxied headers (X-Forwarded-*)                                                         |
| [Call Logging](https://start.ktor.io/p/io.ktor/server-call-logging)                             | Logs client requests                                                                                    |
| [OpenTelemetry](https://start.ktor.io/p/io.opentelemetry.instrumentation/server-open-telemetry) | Instruments applications with distributed tracing, metrics, and logging for comprehensive observability |
| [Micrometer Metrics](https://start.ktor.io/p/io.ktor/server-metrics-micrometer)                 | Enables Micrometer metrics in your Ktor server application.                                             |
| [Call ID](https://start.ktor.io/p/io.ktor/server-callid)                                        | Allows to identify a request/call.                                                                      |
| [Exposed](https://start.ktor.io/p/org.jetbrains/server-exposed)                                 | Adds Exposed database to your application                                                               |
| [PostgreSQL](https://start.ktor.io/p/org.jetbrains/server-postgres)                             | Adds Postgres database support                                                                          |
| [gRPC](https://start.ktor.io/p/org.jetbrains/kotlinx-rpc-grpc)                                  | Adds gRPC services to your Ktor server with kotlinx.rpc                                                 |
| [Koin](https://start.ktor.io/p/io.insert-koin/server-koin)                                      | Provides dependency injection                                                                           |

## Structure

This project includes the following modules:

| Path   | Description |
|--------|-------------|
|        | null        |
| client | null        |
| core   | null        |
| server | null        |

## Configuration & Secrets

Environment is managed with [varlock](https://varlock.dev). `.env.schema` (committed)
declares every variable, its type, and which are secret; real values live in an
uncommitted `.env` (already git-ignored).

```sh
brew install dmno-dev/tap/varlock   # or: curl -sSfL https://varlock.dev/install.sh | sh
cp .env.schema .env                 # then fill in real secrets (DB_PASSWORD, JWT_SECRET, TMDB_API_KEY, ...)
varlock load                        # validate; prints resolved env (secrets redacted)
```

Defaults in `.env.schema` mirror `application.yaml`, so dev runs work without a `.env`.
`TMDB_API_KEY` blank = poster/overview enrichment disabled (scan falls back to filename
metadata). Get a key at <https://www.themoviedb.org/settings/api>.

## Building & Running

Prefix any task with `varlock run --` to inject validated env into the JVM:

| Task                                       | Description       |
|--------------------------------------------|-------------------|
| `varlock run -- ./gradlew :server:test`    | Run the tests     |
| `varlock run -- ./gradlew :server:build`   | Build the project |
| `varlock run -- ./gradlew :server:run`     | Run the server    |

(Bare `./gradlew :server:run` still works — it just relies on shell env / yaml defaults
instead of schema-validated values.)

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
