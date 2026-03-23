# Staging eXist Algolia Index Runbook

This runbook covers the staging operator steps for installing and backfilling the `exist-algolia-index` plugin against the Dockerized staging eXist-db instance used by Raskovnik.

It assumes:

- the Dockerized staging eXist-db service is already provisioned
- the `raskovnik-backend` application and dictionary data are already installed in staging eXist
- the operator has valid Algolia credentials

## Important Docker Constraint

For the official `existdb/existdb` image:

- eXist starts from `exist.uber.jar`
- patching `startup.xml` alone is not enough for a custom plugin JAR
- the plugin JAR must already be present in the container and included on `CLASSPATH` when the container starts

If the container does not satisfy that condition, the plugin install script should fail early rather than silently "succeed" without actually loading the plugin.

## Required Operator Settings

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

That collection path is important. For Raskovnik, the real dictionary XML lives under:

```text
/db/apps/raskovnik-data/data
```

Do not use `/db/apps/raskovnik-data` as the default backfill target. That is the package-level collection and includes non-dictionary files such as `repo.xml`.

## Install and Verify

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

## Backfill Existing Data

Installing the plugin does not by itself guarantee that already-present dictionary data has been uploaded to Algolia. The required backfill step is a reindex of the configured collection.

For Raskovnik staging, the backfill command is:

```bash
./scripts/exist-stage.sh reindex-collection /db/apps/raskovnik-data/data
```

If `EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data` is already set, a normal `run` should perform that backfill automatically after a successful install and smoke verification.

## Expected Result

After a successful install and backfill:

- the plugin JAR exists in the container
- `conf.xml` contains the Algolia module stanza
- `startup.xml` contains the plugin dependency entry
- the smoke verification reaches Algolia successfully
- the real `ras` index is populated from `/db/apps/raskovnik-data/data`

## Troubleshooting

- If restart appears to work but the plugin is not active, first verify that the container starts with the plugin JAR on `CLASSPATH`.
- If backfill is unexpectedly broad, check that `EXIST_REINDEX_COLLECTION` points to `/db/apps/raskovnik-data/data`, not `/db` or `/db/apps/raskovnik-data`.
- If an unchanged full reindex starts republishing records to Algolia again, treat that as a regression in the local diff logic and inspect the plugin version actually installed in the container.
