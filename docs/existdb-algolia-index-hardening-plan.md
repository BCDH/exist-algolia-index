# eXist Algolia Index Remaining Hardening Plan

## Summary

The core Algolia operation hardening has now been implemented:

- global and per-index batch size configuration
- bounded batch writes with per-chunk task waits
- per-index operation serialization
- collection/document/index delete task waits
- Algolia-compatible `deleteBy` filters for collection and document removal
- local-store collection cleanup after successful remote collection delete
- bounded retry/backoff for retryable Algolia failures
- explicit degraded log markers for Algolia operation failures
- `DropIndex` acknowledgement after remote delete completion
- continued support for fallback object IDs in current production data

Staging validation on the Dockerized Raskovnik eXist instance confirmed:

- the hotpatched plugin loads after container restart
- smoke add/delete reaches Algolia successfully
- full `/db/apps/raskovnik-data/data` reindex completes without degraded Algolia status
- fallback object ID warnings are visible for data that still lacks configured node IDs

The remaining work is now about production observability, deployment-scenario coverage, and validating a real large-change workload that exercises chunked writes.

## Remaining Work

### 1. Surface indexing status to deploy tooling

Current failures produce explicit log markers such as:

```text
ALGOLIA_INDEXING_STATUS status=degraded ...
```

That is useful for operators watching logs, but deploy tooling still has no structured status source it can query after a backend deployment.

Required changes:

- Track latest indexing status per index and collection.
- Record terminal failures from:
  - batch writes
  - document deletes
  - collection deletes
  - index drops
- Expose status through a lightweight eXist endpoint, management query, or other deployment-checkable mechanism.
- Keep the status degraded until a successful targeted reindex or follow-up indexing operation clears it.
- Make deployment summaries distinguish:
  - deployment failed
  - deployed with search indexing current
  - deployed with search indexing degraded
- Include enough status detail for operators:
  - index name
  - collection path
  - operation type
  - failure class/message
  - timestamp
  - retryable vs terminal, if known

Tests:

- Actor-test that Algolia failures update status.
- Smoke/integration test that a simulated Algolia failure is visible to deployment verification.

### 2. Add Raskovnik deployment-scenario coverage

The backend now packages dictionaries separately. The plugin should have tests for the deployment behaviors that matter to that model, not just unit coverage of individual actors.

Required tests:

- Dictionary-only replacement:
  - replace `/db/apps/raskovnik-data/data/VSK.SR`
  - verify only `VSK.SR` collection records are removed/reindexed
  - verify sibling dictionary records are not touched
- Release-set partial change:
  - simulate one changed dictionary and one unchanged sibling dictionary
  - verify unchanged sibling data is not republished merely because a release set was deployed
- Targeted reindex:
  - reindex `/db/apps/raskovnik-data/data/<DICT_ID>`
  - verify the operation is scoped to that dictionary collection tree

These tests can use small fixture dictionaries; they do not need full production-sized XML.

### 3. Validate a real large-change workload

The current staging reindex completed cleanly, but it mostly produced zero-change batches. That validates installation, smoke add/delete, delete filter compatibility, and unchanged-data behavior. It does not fully stress the chunked-write path that was added for dictionary replacement.

Manual validation before production image rollout:

- Replace or force-reindex one dictionary with enough changed records to produce multi-chunk Algolia writes.
- Confirm additions are sent in bounded chunks.
- Confirm no batch request contains tens of thousands of operations.
- Confirm collection delete completes before new additions for that collection begin.
- Confirm stale records for the replaced dictionary are gone before new records are added.
- Confirm sibling dictionary record counts and object IDs are unchanged.
- Confirm logs contain no local-store `key not found` errors.

### 4. Improve fallback object ID reporting

Fallback object IDs remain acceptable for production dictionaries for now because indexing current data has higher priority than forcing TEI cleanup first. The plugin already logs when configured user-specified node IDs are missing.

Remaining improvements:

- Add an operator-facing summary or report for fallback usage by index, collection, and document.
- Keep fallback warnings non-fatal.
- Preserve enough diagnostic detail to drive later TEI data cleanup.

Tests:

- Keep existing missing-nodeId fallback integration coverage.
- Add assertions around summary logging or status reporting if status tracking is introduced.

### 5. Production hotpatch discipline

`scripts/exist-production-hotpatch.sh` exists for testing a plugin build on the production eXist container before baking it into the default Docker image. It should remain an operational escape hatch, not the steady-state deployment model.

Required practices:

- Use production-specific `EXIST_PRODUCTION_*` variables, not staging variables.
- Treat `run` as a live production restart plus smoke verification plus reindex.
- Use `--skip-reindex` only as an explicit opt-out.
- After a successful hotpatch, bake the plugin version into the production image before relying on container recreation.
- Observe production logs during the hotpatch and reindex.

## Open Design Decisions

None at this time.
