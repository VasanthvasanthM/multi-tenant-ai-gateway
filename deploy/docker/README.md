# Container images

## Registries

| Registry | Role | Authentication |
|---|---|---|
| `ghcr.io/<owner>/ai-gateway-<service>` | Primary | Built-in `GITHUB_TOKEN` — nothing to manage |
| `docker.io/<namespace>/ai-gateway-<service>` | Secondary | `DOCKERHUB_USERNAME` + `DOCKERHUB_TOKEN` secrets |

GHCR is primary because CI authenticates to it with the token GitHub already
provides, and it imposes no pull-rate limit on builds. Docker Hub is the
public-facing mirror.

## Enabling the Docker Hub push

Nothing is hardcoded and no credential belongs in this repository. In
**GitHub → Settings → Secrets and variables → Actions**:

| Kind | Name | Value |
|---|---|---|
| Variable | `DOCKERHUB_NAMESPACE` | your Docker Hub username |
| Secret | `DOCKERHUB_USERNAME` | the same username |
| Secret | `DOCKERHUB_TOKEN` | an **access token**, not your password |

Create the token at Docker Hub → Account Settings → Personal access tokens, with
**Read & Write** scope. Use a token rather than your password: it can be scoped,
listed, and revoked individually without changing your login.

The push steps are guarded on `DOCKERHUB_NAMESPACE` being set, so leaving these
unset simply skips them — a fork is never broken by a secret it cannot have.

## Building locally

```bash
./mvnw package -DskipTests
docker build -f deploy/docker/Dockerfile --build-arg SERVICE=gateway -t ai-gateway/gateway .
```

`SERVICE` accepts `gateway`, `control-plane`, `metering`, `indexer` or `audit`.

## Why this Dockerfile is hand-written

`spring-boot:build-image` (Paketo buildpacks) produces a good image but cannot
drop files from a dependency. The ONNX Runtime jar ships natives for five
platforms; a Linux container can load exactly one of them:

| Platform | Size |
|---|---|
| win-x64 (incl. CUDA/TensorRT) | 352 MB |
| osx-x64 | 104 MB |
| osx-aarch64 | 99 MB |
| **linux-x64** | **20 MB** |
| linux-aarch64 | 17 MB |

`strip-native-libs.sh` keeps only the target platform, taking that dependency
from **130 MB to 7 MB** compressed. Measured final image: **309 MB**, running in
**263 MB** of a 512Mi limit.

Two traps the script and Dockerfile encode, both of which fail only at container
start rather than at build time:

- The nested jar must be repacked **STORED, not deflated**. Spring Boot's loader
  maps nested jars directly out of the outer archive; a compressed entry gives
  `Unable to open nested entry`.
- Boot 3.3+ replaced the `layertools` jarmode with `tools`, which extracts a thin
  `app.jar` plus `lib/` rather than a `BOOT-INF` tree. The entrypoint is
  `java -jar app.jar`, **not** `JarLauncher`.
