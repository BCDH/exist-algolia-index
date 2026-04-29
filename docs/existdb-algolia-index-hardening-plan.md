# eXist Algolia Index Remaining Hardening Plan

## Summary

The original local-store and collection-path hardening work has mostly been implemented. The remaining risks are now centered on Algolia operation safety during large dictionary replacement, especially for the Raskovnik production model where each dictionary lives under:

```text
/db/apps/raskovnik-data/data/<DICT_ID>
```

The immediate production failure mode was observed while replacing `VSK.SR`: collection deletion was submitted, but the subsequent add of 46,967 records failed with an Algolia record-quota error. The dashboard still showed the old `VSK.SR` records afterwards. That strongly suggests the plugin needs stronger sequencing, smaller batches, and better propagation of Algolia failures.

## Remaining Work

### 1. Chunk Algolia batch writes

Current behavior sends all additions, updates, and deletions for a document/change set in one `Index.batch(...)` call.

Required changes:

- Add a global configurable Algolia batch size.
- Default the global batch size to `1000` operations per request.
- Add an optional per-index batch-size override for indexes that need smaller or larger chunks.
- Split additions, updates, and objectID deletions into bounded batch requests.
- Wait for each Algolia task to complete before treating that chunk as successful.
- Log per-chunk progress with enough context to diagnose failures:
  - index name
  - document id
  - chunk number and total chunks
  - additions, updates, deletions in the chunk
  - Algolia task id if available
- Preserve the existing no-op behavior for empty change sets.

Tests:

- Unit-test chunk planning for large change sets.
- Actor-test that a large change set is sent as multiple bounded batches.
- Actor-test that a chunk failure stops or marks the overall change set as failed.

### 2. Wait for collection deletes to finish

Current behavior submits `deleteByQuery` for collection removal and logs success once the request is accepted. It does not prove Algolia has completed the delete task.

Required changes:

- Call `waitForCompletion()` or the equivalent task-wait API on collection `deleteByQuery` results.
- Keep using exact filtering on the stored `collection` field.
- Continue deleting exactly the requested collection tree in local store, without spilling into sibling dictionaries.
- Include Algolia task ids in logs if exposed by the client.
- Treat timeout or wait failure as a real delete failure.

Tests:

- Actor-test that collection removal waits for Algolia completion before success is reported.
- Actor-test that Algolia delete failure is logged and surfaced as failed work.
- Test that exact collection filters remain escaped correctly.

### 3. Serialize delete-then-add for dictionary replacement

During package replacement, a dictionary collection can be removed and then re-added quickly. The current actor flow can submit asynchronous Algolia delete work while local-store cleanup and later add work continue independently.

Required changes:

- Introduce an explicit per-index operation queue/barrier:
  - submit collection delete
  - wait for Algolia delete completion
  - clean or update local-store state only after successful delete
  - allow new additions for that collection after the delete barrier clears
- Ensure a dictionary-only replacement of `/db/apps/raskovnik-data/data/<DICT_ID>` cannot race old-record deletion against new-record addition.
- Serialize Algolia mutations within each target Algolia index. For Raskovnik, that means operations against the shared `ras` index run one at a time.
- Preserve parallelism across unrelated Algolia indexes if the actor model can do so cleanly.
- Defer per-collection concurrency within a single Algolia index until staging measurements show per-index serialization is too slow.
- If per-collection concurrency is added later, keep strict ordering within each collection path and add a global Algolia request throttle.

Tests:

- Actor/integration test for remove-then-add ordering for the same collection.
- Integration test for replacing only `/db/apps/raskovnik-data/data/VSK.SR`.
- Integration test proving sibling dictionaries under `/db/apps/raskovnik-data/data/<OTHER_DICT>` are not deleted or republished during a one-dictionary replacement.

### 4. Coordinate local-store cleanup with Algolia success

Current local-store collection removal can delete local root-object JSON before Algolia has definitely removed the corresponding remote records. If the remote delete fails or does not complete, the local store can lose the state needed to reason about stale Algolia records.

Required changes:

- Do not permanently remove local-store records for a collection until the corresponding Algolia delete succeeds.
- Do not build a full local transaction system in this hardening pass.
- Keep cleanup and indexing operations idempotent enough that a targeted reindex can repair interrupted work.
- If a process crash interrupts delete/add work, expose enough degraded status or logging for operators to know which index and collection need targeted reindexing.
- Keep `RemoveDocument` idempotent for absent local state.

Tests:

- Actor-test that local-store cleanup is not committed if Algolia collection delete fails.
- Recovery test showing missing or partial local-store state can be repaired by targeted reindex.
- Regression test for the prior `key not found` local-store failures seen during replacement.

### 5. Surface indexing failures to deploy tooling

Current Algolia failures are logged, but eXist package deployment and backend deployment verification can still complete successfully. That makes production deployment look green while the search index is stale.

Required changes:

- Track the latest indexing operation status per index and collection.
- Record terminal failures from:
  - batch writes
  - document deletes
  - collection deletes
  - index drops
- Expose status through a lightweight eXist endpoint, log marker, or management query that deploy tooling can check.
- Do not fail an otherwise successful eXist package deployment solely because Algolia indexing failed.
- Produce an explicit degraded deployment/indexing status when Algolia indexing fails.
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

### 6. Add retry and backoff for transient Algolia errors

Large replacement operations can hit network failures, temporary Algolia service errors, or rate limits.

Required changes:

- Add bounded retry with exponential backoff for retryable failures.
- Treat likely retryable responses separately from terminal failures:
  - retry `429`
  - retry selected `5xx`
  - evaluate whether any `403` responses are retryable in this context; quota-related `403` should usually be terminal
- Log every retry attempt with operation context and delay.
- Stop retrying before eXist or deploy timeouts make the process opaque.

Tests:

- Unit-test retry classification.
- Actor-test successful retry after transient failure.
- Actor-test terminal quota failure is not retried indefinitely.

### 7. Keep fallback object IDs for current production data

Fallback object IDs are still intentionally supported, but they are less stable than user-specified node IDs. They remain acceptable for production dictionaries for now because indexing the current data has higher priority than forcing TEI cleanup first. Missing configured node IDs can be fixed in the data later.

Required changes:

- Keep the current fallback shape for compatibility:

```text
collectionId/documentId/nodeId
```

- Add diagnostics that make fallback usage easy to count per document and per index.
- Consider an operator-facing report for documents that index with fallback IDs.
- Do not fail production indexing solely because fallback object IDs were used.
- Preserve enough diagnostics to support later data cleanup.

Tests:

- Keep existing missing-nodeId fallback integration coverage.
- Add assertions around summary logging or status reporting if status tracking is introduced.

### 8. Improve DropIndex acknowledgement behavior

`DropIndex` currently sends `DroppedIndex` immediately after starting the async delete and can send another acknowledgement after completion. That is inconsistent with the stronger task-completion semantics needed elsewhere.

Required changes:

- Send `DroppedIndex` only after Algolia confirms index deletion.
- On failure, log and surface failure instead of acknowledging success.
- Stop the per-index actor only after final success or after a clearly handled terminal failure.

Tests:

- Actor-test that `DroppedIndex` is emitted only after delete completion.
- Actor-test that delete failure does not emit a success acknowledgement.

### 9. Add Raskovnik deployment-scenario coverage

The backend now packages dictionaries separately, but the plugin should still test the behaviors that matter to that deployment model.

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

## Manual Validation Before Production Use

Before deploying a fixed plugin to production, validate against staging with a dictionary-sized workload:

- Replace one dictionary collection.
- Confirm Algolia delete task completes before additions begin.
- Confirm additions are sent in bounded chunks.
- Confirm no batch request contains tens of thousands of operations.
- Confirm stale records for the replaced dictionary are gone before new records are added.
- Confirm sibling dictionary record counts and objectIDs are unchanged.
- Confirm deployment tooling reports Algolia failure if a simulated batch or delete fails.
- Confirm logs contain no local-store `key not found` errors.

## Open Design Decisions

None at this time.
