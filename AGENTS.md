# AGENTS.md

Guidance for future Codex work in this repository.

## Repository

This repo builds the eXist-db Algolia index plugin:

- Main package: `org.humanistika.exist.index.algolia`
- Version source: `version.sbt`
- Assembly artifact: `target/scala-2.13/exist-algolia-index-assembly-<version>.jar`
- Important runtime state: `algolia-index/` under the eXist data directory
- Deployment status file: `algolia-index/status.json`

Do not print secrets from `.env`, eXist `conf.xml`, SSH commands, Docker env, or Algolia API headers in final answers. If command output includes secrets, summarize without repeating them.

## Core Commands

Run these from this repo unless noted.

```sh
sbt test
sbt assembly
git diff --check
./scripts/test-indexing-status-verifier.sh
```

Useful script syntax checks:

```sh
bash -n scripts/exist-common.sh scripts/exist-local.sh scripts/exist-stage.sh scripts/exist-stage-remote.sh
```

The main plugin install/test helpers are:

- `scripts/exist-local.sh`
- `scripts/exist-stage.sh`
- `scripts/exist-stage-remote.sh`
- `scripts/exist-production-hotpatch.sh`

`scripts/exist-stage.sh` is the local SSH wrapper. It uploads the assembly and helper scripts, then runs `exist-stage-remote.sh` on the staging host.

Common staging commands:

```sh
./scripts/exist-stage.sh run
./scripts/exist-stage.sh run --skip-build
./scripts/exist-stage.sh run --skip-reindex
./scripts/exist-stage.sh reindex-collection /db/apps/raskovnik-data/data
./scripts/exist-stage.sh reindex-collection /db/apps/raskovnik-data/data/MBRT.RDG
```

## Indexing Status

The plugin writes deployment-readable state to:

```text
<exist-data-dir>/algolia-index/status.json
```

States:

- `current`: indexing for that index/collection is healthy.
- `degraded`: Algolia write/delete failed; retry/reindex is needed.
- `stale_local_store`: the plugin could not derive collection-delete object IDs from the local store.

Local and staging helper verification should fail when `status.json` contains `degraded` or `stale_local_store`. Missing `status.json` is intentionally treated as OK for first installs and older deployments.

## Raskovnik Staging Notes

Staging uses SSH and a Dockerized eXist container. The relevant env vars live in this repo’s `.env`, with names like:

- `EXIST_STAGE_HOST`
- `EXIST_STAGE_SSH_USER`
- `EXIST_STAGE_CONTAINER_NAME`
- `EXIST_STAGE_ADMIN_PASSWORD`
- `EXIST_STAGE_ALGOLIA_APPLICATION_ID`
- `EXIST_STAGE_ALGOLIA_ADMIN_API_KEY`
- `EXIST_STAGE_REINDEX_COLLECTION`

Do not echo these values.

Known staging caveat from the 1.1.3 pre-release test:

- The container runtime `CLASSPATH` pointed at `/exist/lib/exist-algolia-index-assembly-1.1.2.jar`.
- The staging helper copied the new `1.1.3-SNAPSHOT` jar bytes both to the new versioned filename and to the runtime classpath path, then verified byte equality.
- This means the runtime path name may look old while the file contents are the current build.

Do not use directory existence checks that copy large container directories. The staging image is minimal and may not have a shell or standard tools. For file checks, `docker cp container:/path tmpfile` is fine. For known directories such as `/exist/lib` or `/exist/data`, prefer configured/default paths over probing by copying.

Recent successful staging validation for the 1.1.3-SNAPSHOT candidate on 2026-05-03:

- Installed current assembly into `existdb-stage`.
- Restarted staging eXist.
- Smoke reindex wrote local store JSON and reached Algolia.
- Smoke Algolia index was deleted afterward.
- Full reindex of `/db/apps/raskovnik-data/data` completed in about 6 minutes.
- Final status verification passed: `OK (3 record(s) checked)`.
- `ras` index was queryable afterward and reported 84,846 hits.
- The full reindex produced zero new `ras` additions; all real-index batches were `additions=0 updates=0 deletions=0`.

## Raskovnik Orchestration

From this repo, the current Raskovnik full stack lives under:

```text
../../ttasovac/
```

Main repos:

- Backend/eXist data deploy repo: `../../ttasovac/raskovnik-backend`
- Laravel frontend repo: `../../ttasovac/raskovnik-frontend`

Useful operator shortcut:

- The dotfiles repo has a fish function named `infra` at `../../ttasovac/dotfiles/fish/functions/infra.fish`. Check it when looking for local infrastructure shortcuts or host/container command scaffolding around the Raskovnik stack.

### raskovnik-backend

This repo builds and deploys Raskovnik eXist packages and dictionary XML resources.

Important files:

- `.env`: local/staging/production deploy configuration and secrets. Do not print values.
- `scripts/exist-common.sh`: shared deploy helpers, manifest reading, status checks, smoke tests.
- `scripts/exist-local.sh`: local eXist deployment.
- `scripts/exist-remote.sh`: local operator entrypoint for staging/production over SSH.
- `scripts/exist-remote-helper.sh`: server-side helper uploaded and invoked by `exist-remote.sh`.
- `metadata/dictionary-version-manifest.json`: dictionary versions and hashes.
- `metadata/component-version-manifest.json`: app/data-core versions.
- `dictionaries/<DICT>/src/main/xar-resources/<DICT>.pcified.xml`: dictionary XML resources.
- `data-core/src/main/xar-resources/collection.xconf`: collection indexing config.

Common backend commands:

```sh
./scripts/exist-local.sh run
./scripts/exist-local.sh verify-state
./scripts/exist-local.sh --dict MBRT.RDG run
./scripts/exist-local.sh --all-dicts run
./scripts/exist-remote.sh verify-state --env staging
./scripts/exist-remote.sh run --env staging
./scripts/exist-remote.sh --dict MBRT.RDG run --env staging
./scripts/exist-remote.sh --all-dicts run --env staging
./scripts/exist-remote.sh run --env staging --release-set latest
```

Backend behavior:

- Dictionary deploys update XML resources in existing collections under `/db/apps/raskovnik-data/data/<DICT_ID>`.
- This avoids package removal and prevents eXist/Algolia from seeing a collection deletion.
- `data-core` config updates update `collection.xconf` and then reindex `/db/apps/raskovnik-data/data`.
- Full `verify-state` checks app/data-core versions, dictionary package presence, dictionary collections, resource hashes, API user/group, installed manifests, and Algolia indexing status.
- `--dict ... verify-state` checks only selected dictionaries.

Important recent backend helper fixes:

- Remote `verify-state` must not require `jq` or `shasum` on the staging host.
- It should use `python3` for JSON parsing when checking Algolia status.
- The `exist-remote.sh` wrapper must preserve failing remote helper exit codes.
- Dictionary hash grep checks must not span multiple XML lines; each dictionary line must match on its own.

Staging state observed during the 2026-05-03 pre-release test:

- Backend `verify-state` runs on staging after those helper fixes.
- It correctly fails against the current dirty backend working tree because `GE.RKMD` and `VSK.SR` resource hashes differ from staging.
- This mismatch is a backend content-state issue, not an Algolia plugin reindex failure.

### raskovnik-frontend

This is the Laravel frontend/admin app.

Important files and concepts:

- `.env`: frontend runtime config and deployment-status endpoints/tokens. Do not print values.
- `.env.example`: documents deployment-status config keys.
- `php artisan restxq:cache-clear`: clears only the RESTXQ cache for the active Laravel environment.
- `php artisan deployment-status:write-local`: writes local deployment status.
- `php artisan deployment-status:refresh --target=<env>`: refreshes cached deployment-status data.
- Deployment status endpoint: `/internal/deployment-status`.
- Admin dashboard includes deployment status cards.

Relevant frontend implementation areas:

- `app/Console/Commands/WriteLocalDeploymentStatus.php`
- `app/Http/Controllers/DeploymentStatusController.php`
- `app/Services/DeploymentStatus/*`
- `config/deployment-status.php`
- `resources/views/admin/parts/deployment-status-card.blade.php`
- tests named `DeploymentStatus*` and `LocalDeploymentStatusCollectorTest`

Backend scripts call frontend hooks:

- Local backend deploys default to sibling `../raskovnik-frontend`.
- Remote backend deploys use `RASKOVNIK_REMOTE_<ENV>_FRONTEND_DIR`.
- After backend deploy verification, remote hooks run:
  - `php artisan restxq:cache-clear`
  - `php artisan deployment-status:write-local`
  - `php artisan deployment-status:refresh --target=<env>`

Frontend staging deploy is artifact-based; server deploy normally unpacks a release tarball, links shared state, runs Laravel cache/migrate steps, and switches the `current` symlink. Do not assume editing frontend files locally changes staging.

## Working Tree Discipline

The sibling `raskovnik-backend` and `raskovnik-frontend` worktrees are often dirty when we work on multiple strands at the same time. Do not reset or checkout files there unless explicitly asked. If a temporary dictionary edit is needed for an indexing test, copy the exact file bytes to a temp backup first and restore them exactly afterward.

Before release work, check:

```sh
git status --short --branch
git -C ../../ttasovac/raskovnik-backend status --short --branch
git -C ../../ttasovac/raskovnik-frontend status --short --branch
```

## Release Guardrails

Do not proceed with Sonatype publishing, release tags, or release commits until:

- `git diff --check` passes.
- `sbt test` passes.
- `sbt assembly` passes.
- `./scripts/test-indexing-status-verifier.sh` passes.
- Staging smoke install/reindex passes when requested.
- `status.json` verification reports no `degraded` or `stale_local_store` records.

## Release Procedure

There are no repo-local `prepare-release.sh` or `prepare-next-snapshot.sh` scripts. Release mechanics are driven by sbt/sbt-release settings in `build.sbt` and the version in `version.sbt`.

Version source:

```text
version.sbt
```

Example format:

```scala
ThisBuild / version := "1.1.3-SNAPSHOT"
```

Publishing configuration:

- Snapshots publish to Sonatype snapshots: `https://oss.sonatype.org/content/repositories/snapshots/`
- Releases publish to Sonatype staging deploy: `https://oss.sonatype.org/service/local/staging/deploy/maven2/`
- Credentials are read from `~/.ivy2/.credentials`.
- Release artifact publishing uses `PgpKeys.publishSigned.value`, so local PGP signing must be configured.
- Assembly artifacts are attached with classifier `assembly`.

Release bump policy:

- `build.sbt` sets `releaseVersionBump := sbtrelease.Version.Bump.Bugfix`.
- For a `1.1.3` release, sbt-release should propose the next snapshot as `1.1.4-SNAPSHOT`.
- Still read release prompts carefully and enter the intended version explicitly if the proposal is not what the maintainer wants.

### Pre-Release Checklist

1. Inspect all relevant worktrees and identify which changes are expected:

   ```sh
   git status --short --branch
   git -C ../../ttasovac/raskovnik-backend status --short --branch
   git -C ../../ttasovac/raskovnik-frontend status --short --branch
   ```

2. Confirm `version.sbt` is still on the snapshot for the release candidate, for example `1.1.3-SNAPSHOT`.

3. Run local verification:

   ```sh
   git diff --check
   bash -n scripts/exist-common.sh scripts/exist-local.sh scripts/exist-stage.sh scripts/exist-stage-remote.sh
   ./scripts/test-indexing-status-verifier.sh
   sbt test
   sbt assembly
   ```

4. Run staging validation when the release affects runtime behavior:

   ```sh
   ./scripts/exist-stage.sh run
   ```

   If the assembly was already built and only helper scripts changed:

   ```sh
   ./scripts/exist-stage.sh run --skip-build
   ```

5. Confirm staging reindex/status outcome:
   - Smoke record reaches Algolia.
   - Smoke index is deleted afterward.
   - Target reindex completes.
   - Final status verification says `OK`.
   - No `degraded` or `stale_local_store` record remains in `status.json`.

6. If using Raskovnik backend verification as an additional deployment check, run it from the sibling backend repo:

   ```sh
   cd ../../ttasovac/raskovnik-backend
   ./scripts/exist-remote.sh verify-state --env staging
   ```

   A failure caused by dirty backend dictionary resource hashes is a backend content-state mismatch, not automatically an Algolia plugin failure. Read the XML output before drawing conclusions.

### Commit Before Release

After fixes and validation pass, commit the plugin repo changes before running the release. Keep the commit focused and do not include unrelated sibling-repo changes.

```sh
git status --short
git add <intended files>
git commit -m "Prepare Algolia index release"
```

Use a more specific commit message when appropriate. If the user asked to commit, do it; otherwise confirm before creating release commits/tags.

### sbt-release Flow

The normal release command is:

```sh
sbt release
```

Expected sbt-release behavior:

- Checks for snapshot dependencies.
- Asks for the release version, usually current snapshot without `-SNAPSHOT`.
- Edits `version.sbt` to the release version.
- Runs the configured test process.
- Commits the release version.
- Tags the release.
- Runs signed publish.
- Asks for the next snapshot version.
- Edits `version.sbt` to the next snapshot.
- Commits the next snapshot.

Use the prompts deliberately. For release `1.1.3` from `1.1.3-SNAPSHOT`:

- Release version: `1.1.3`
- Next version: normally `1.1.4-SNAPSHOT`.

Do not use unattended/default release prompts unless the proposed release and next-snapshot versions are correct.

If publishing should not happen yet, do not run `sbt release`. Instead, make a normal commit and stop before tagging/publishing.

### Manual Version Flow

Use this only if the maintainer explicitly wants manual control instead of sbt-release.

1. Change `version.sbt` from `X.Y.Z-SNAPSHOT` to `X.Y.Z`.
2. Run:

   ```sh
   git diff --check
   sbt test
   sbt assembly
   ```

3. Commit the release version:

   ```sh
   git add version.sbt
   git commit -m "Release X.Y.Z"
   git tag -a "vX.Y.Z" -m "Release X.Y.Z"
   ```

4. Publish signed artifacts only when credentials and signing are ready:

   ```sh
   sbt publishSigned
   ```

5. Complete Sonatype staging close/release outside this repo if required by the maintainer’s Sonatype workflow.

6. Change `version.sbt` to the next snapshot, for example `X.Y.(Z+1)-SNAPSHOT`.

7. Commit the next snapshot:

   ```sh
   git add version.sbt
   git commit -m "Prepare next snapshot"
   ```

### Post-Release Checks

After release/tag/publish:

```sh
git status --short --branch
git tag --points-at HEAD
```

Confirm whether `HEAD` is the release commit or the next-snapshot commit. With sbt-release, the tag usually points to the release commit, while `HEAD` ends at the next-snapshot commit.

Build the next snapshot once if requested:

```sh
sbt assembly
```

Do not hotpatch production with staging variables. Production hotpatching goes through `scripts/exist-production-hotpatch.sh` with `EXIST_PRODUCTION_*` variables, and the durable fix is to bake the released plugin into the production image.
