# eXist Algolia Index Operator Runbook

This runbook covers the automated operator flows for installing and backfilling the `exist-algolia-index` plugin in Raskovnik environments.

Use this document for:

- local operator installs against a real eXist instance
- staging installs against the Dockerized staging eXist instance
- post-install backfill of already-present dictionary data into Algolia

## Local Operator Flow

From the `exist-algolia-index` checkout:

```bash
./scripts/exist-local.sh run
```

For local Raskovnik data, the normal backfill target should be:

```bash
EXIST_REINDEX_COLLECTION=/db/apps/raskovnik-data/data
```

Use `run --skip-reindex` only if you deliberately want to deploy the plugin without backfilling existing data immediately:

```bash
./scripts/exist-local.sh run --skip-reindex
```

If you need to backfill separately later:

```bash
./scripts/exist-local.sh reindex-collection /db/apps/raskovnik-data/data
```

## Staging Operator Flow

For staging, use the dedicated runbook in [staging-existdb-algolia-index-runbook.md](staging-existdb-algolia-index-runbook.md).

That runbook covers:

- the Docker `CLASSPATH` requirement for the official `existdb/existdb` image
- required staging env vars
- the correct Raskovnik backfill target
- install, smoke verification, and backfill steps

## Important Backfill Target

For Raskovnik, the real dictionary XML lives under:

```text
/db/apps/raskovnik-data/data
```

Do not use `/db/apps/raskovnik-data` as the default reindex target. That package-level collection also contains non-dictionary files such as `repo.xml`.
