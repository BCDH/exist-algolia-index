# eXist Algolia Index Hardening Plan

## Summary

This plan addresses the failure modes observed during a staging deployment of `exist-algolia-index`:

- missing local index-store directories causing `NoSuchFileException`
- noisy and misleading collection-removal error logging
- brittle collection-wide Algolia deletes
- fallback node-id behavior that is necessary but not sufficiently observable
- missing tests for the above behaviors

The goal is to preserve the current incremental indexing design while making it robust under package replacement, reindexing, and partial prior-state loss.

This should explicitly hold for the current Raskovnik deployment model, where:

- dictionaries live under `/db/apps/raskovnik-data/data/<DICT_ID>`
- operators may replace one dictionary without touching sibling dictionaries
- release-set deploys may selectively replace only the changed dictionaries

## Key Changes

### 1. Harden local store handling

- Make `IndexLocalStoreDocumentActor.getLatestTimestampDir` return `None` when the target directory does not exist or is not a directory.
- Make `findPreviousDir` treat missing prior state as normal.
- Make `RemoveDocument` idempotent:
  - missing document directory or timestamp directory should be treated as already removed
  - still emit `RemovedDocument`
  - log at `debug` or `trace`, not `error`
- Make `FindChanges` tolerant of absent prior state:
  - no previous state means current files are all additions
  - missing current state should not throw

### 2. Tighten collection removal behavior

- In `AlgoliaIndexWorker.removeCollection`, if a collection has no Algolia config, do nothing and log at `trace` or `debug`.
- Keep the current `reindex=true` skip behavior.
- Replace collection removal by free-text query with exact filtering on the collection-path field already stored in indexed records.
- Make sure collection-level delete logic behaves correctly for child collections such as `/db/apps/raskovnik-data/data/MBRT.RDG` and does not spill into sibling dictionary collections.
- Keep collection delete failures at `error` only when the Algolia API call itself fails.

### 3. Keep fallback node ids, improve observability

- Preserve fallback behavior when `userSpecifiedNodeId` is missing.
- Keep the current fallback object-id shape for now:
  - `collectionId/documentId/nodeId`
- Do not add new config flags in this patch.
- Improve logging when fallback is used:
  - one summary warning per document/index/path grouping
  - include index name, collection path, document id, configured node-id path, and affected root-object count
- Fix the misleading `visibleBy` warning text so it no longer refers to “document id”.

### 4. Add missing tests

- Add actor-level tests for:
  - `getLatestTimestampDir` on missing directories
  - `RemoveDocument` on missing prior state
  - `FindChanges` with no previous state
- Add tests for collection removal with no Algolia config.
- Add tests for exact collection-path delete behavior.
- Add integration coverage for missing `userSpecifiedNodeId` where fallback remains enabled.
- Add integration coverage for dictionary-only replacement of `/db/apps/raskovnik-data/data/<DICT_ID>`.
- Add integration coverage for release-set partial-change behavior where one dictionary is replaced and unchanged sibling dictionaries are left alone.

## Test Plan

- Unit tests for the local-store actor missing-directory and no-op removal cases.
- Unit or actor tests for exact collection-path delete behavior.
- Integration test that verifies missing node-id paths still index with fallback IDs.
- Integration test that verifies targeted reindex of `/db/apps/raskovnik-data/data/<DICT_ID>` updates only that dictionary's records.
- Integration test that verifies one-dictionary replacement does not force republish of unchanged sibling dictionaries.
- Manual staging validation:
  - no `NoSuchFileException` for `/exist/data/algolia-index/indexes/ras/<doc-id>`
  - no `Cannot remove Algolia indexes ... no collection config found!` at error level
  - fallback node-id warnings remain visible but are summarized and actionable
  - unchanged records are not resent merely because prior local-store directories were absent
  - dictionary-only backend deploys can be followed by targeted backfill of `/db/apps/raskovnik-data/data/<DICT_ID>` without republishing sibling dictionaries

## Assumptions

- The current fallback object-id scheme remains acceptable for now.
- The dominant staging failure is local-store missing-state handling, not malformed TEI alone.
- The existing collection-path field is sufficient to support exact collection deletes.
- Child collection paths for individual dictionaries are part of the supported deployment model and must be treated as first-class collection targets.
- This patch set should improve diagnostics through logging and tests, not by adding new operator-facing configuration.
