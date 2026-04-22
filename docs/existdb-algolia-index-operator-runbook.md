# eXist Algolia Index Operator Runbook

This runbook covers the automated operator flows for installing and backfilling the `exist-algolia-index` plugin for Raskovnik.

It covers:

- local installs against a real eXist instance
- staging installs against the Dockerized staging eXist-db instance
- post-install backfill of already-present dictionary data into Algolia

It assumes the current `raskovnik-backend` deployment architecture:

- `raskovnik-data-core` owns the shared data root under `/db/apps/raskovnik-data`
- each dictionary is installed independently under `/db/apps/raskovnik-data/data/<DICT_ID>`
- backend operators may deploy either one dictionary at a time or a full release set
- release-set deploys may skip unchanged dictionaries and replace only the components that changed

## Backfill Scope

For Raskovnik, the full dictionary corpus lives under:

```text
/db/apps/raskovnik-data/data
```

Do not use `/db/apps/raskovnik-data` as the default reindex target. That package-level collection also contains non-dictionary files such as `repo.xml`.

For targeted reindex after a one-dictionary backend deploy, use the dictionary subcollection:

```text
/db/apps/raskovnik-data/data/<DICT_ID>
```

In practice:

- after a full backend deploy or release-set deploy, the normal backfill target is `/db/apps/raskovnik-data/data`
- after a dictionary-only backend deploy, the normal backfill target is `/db/apps/raskovnik-data/data/<DICT_ID>`
- if the plugin was just installed for the first time, prefer a full backfill of `/db/apps/raskovnik-data/data`
- if you changed collection config or are unsure about prior local index-store state, prefer a full backfill

## Local Operator Flow

From the `exist-algolia-index` checkout:

```bash
./scripts/exist-local.sh run
```

For local Raskovnik data, the normal backfill target should be:

```bash
EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data
```

Use `run --skip-reindex` only if you deliberately want to deploy the plugin without immediately backfilling existing data:

```bash
./scripts/exist-local.sh run --skip-reindex
```

If you need to backfill separately later:

```bash
./scripts/exist-local.sh reindex-collection /db/apps/raskovnik-data/data
./scripts/exist-local.sh reindex-collection /db/apps/raskovnik-data/data/MBRT.RDG
```

## Staging Operator Flow

This staging flow assumes:

- the Dockerized staging eXist-db service is already provisioned
- the `raskovnik-backend` application and dictionary data are already installed in staging eXist
- the operator has valid Algolia credentials
- the backend may have been deployed either as a full release set or as a one-dictionary update

### Important Docker Constraint

For the official `existdb/existdb` image:

- eXist starts from `exist.uber.jar`
- patching `startup.xml` alone is not enough for a custom plugin JAR
- the plugin JAR must already be present in the container and included on `CLASSPATH` when the container starts

If the container does not satisfy that condition, the plugin install script should fail early rather than silently "succeed" without actually loading the plugin.

### Required Staging Settings

Set at least:

```bash
EXIST_STAGE_HOST=your-staging-host
EXIST_STAGE_SSH_USER=your-deploy-user
EXIST_STAGE_ADMIN_PASSWORD=your-staging-admin-password
EXISTDB_CONTAINER_NAME=existdb-stage
ALGOLIA_APPLICATION_ID=your-algolia-application-id
ALGOLIA_ADMIN_API_KEY=your-algolia-admin-api-key
EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data
```

For a staging run that follows a one-dictionary backend deploy, you can narrow the reindex target before running the plugin helper:

```bash
EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data/MBRT.RDG
```

### Install and Verify

From the `exist-algolia-index` checkout:

```bash
./scripts/exist-stage.sh run
```

What this should do:

1. build the assembly JAR
2. upload the JAR and helper scripts
3. install the plugin JAR into the running eXist container
4. patch `conf.xml`
5. patch `startup.xml`
6. restart the eXist container
7. run the smoke verification
8. reindex `/db/apps/raskovnik-data/data` by default

If you need to deploy without immediately backfilling:

```bash
./scripts/exist-stage.sh run --skip-reindex
```

### Backfill Existing Data

Installing the plugin does not by itself guarantee that already-present dictionary data has been uploaded to Algolia. The required backfill step is a reindex of the configured collection.

For a full Raskovnik staging backfill:

```bash
./scripts/exist-stage.sh reindex-collection /db/apps/raskovnik-data/data
```

For a one-dictionary staging backfill after a targeted backend deploy:

```bash
./scripts/exist-stage.sh reindex-collection /db/apps/raskovnik-data/data/MBRT.RDG
```

If `EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data` is already set, a normal `run` should perform that backfill automatically after a successful install and smoke verification.

### Expected Result

After a successful install and backfill:

- the plugin JAR exists in the container
- `conf.xml` contains the Algolia module stanza
- `startup.xml` contains the plugin dependency entry
- the smoke verification reaches Algolia successfully
- the real `ras` index is populated either from `/db/apps/raskovnik-data/data` or from the selected dictionary subcollection under `/db/apps/raskovnik-data/data/<DICT_ID>`

### Troubleshooting

- If restart appears to work but the plugin is not active, first verify that the container starts with the plugin JAR on `CLASSPATH`.
- If backfill is unexpectedly broad, check that `EXIST_REINDEX_COLLECTION` points either to `/db/apps/raskovnik-data/data` or to the intended `/db/apps/raskovnik-data/data/<DICT_ID>`, not `/db` or `/db/apps/raskovnik-data`.
- If a one-dictionary backend deploy causes sibling dictionaries to be republished, treat that as a regression. Targeted backend replacement should allow targeted Algolia backfill.
- If an unchanged full reindex starts republishing records to Algolia again, treat that as a regression in the local diff logic and inspect the plugin version actually installed in the container.
