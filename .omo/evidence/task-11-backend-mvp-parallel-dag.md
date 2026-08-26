# Task 11 evidence

Code under test: `dd1367755c7db9782017cc85a2340232632f91e8`

## Focused acceptance

- Scenario: fresh V002 migration creates editable draft and immutable revision tables.
  Invocation: `./gradlew test --tests 'com.uniton.backend.persistence.PlanDraftMigrationTest'`.
  Binary observable: exit 0 and `BUILD SUCCESSFUL in 5s`.
  Artifact: `.omo/evidence/task-11-artifacts/final-plan-draft-migration.log`.
- Scenario: valid 1800-second blueprint persists; missing owner, duration 899/5401, empty/nonpositive/out-of-order/mismatched agenda, incomplete criterion coverage, and late due date reject without revision rows.
  Invocation: `./gradlew test --tests 'com.uniton.backend.planning.BlueprintConstraintTest'`.
  Binary observable: exit 0 and `BUILD SUCCESSFUL in 8s`.
  Artifact: `.omo/evidence/task-11-artifacts/final-blueprint-constraint.log`.
- Scenario: exact revision/hash owner and part-lead approvals persist; invalid/duplicate/stale/concurrent approvals reject or leave one durable scope record.
  Invocation: `./gradlew test --tests 'com.uniton.backend.planning.PlanDraftApprovalMigrationTest'`.
  Binary observable: exit 0 and `BUILD SUCCESSFUL in 10s`.
  Artifact: `.omo/evidence/task-11-artifacts/final-plan-draft-approval.log`.
- Scenario: derived candidate/needs-selection pair, revision/hash-scoped provenance, unique template keys, stale/duplicate/unknown selection, model-field exclusion, and malformed-input atomicity.
  Invocation: `./gradlew test --tests 'com.uniton.backend.planning.TemplateOwnerSelectionMigrationTest'`.
  Binary observable: exit 0 and `BUILD SUCCESSFUL in 6s`.
  Artifact: `.omo/evidence/task-11-artifacts/final-template-owner-selection.log`.
- Scenario: full repository regression gate.
  Invocation: `./gradlew check`.
  Binary observable: exit 0 and `BUILD SUCCESSFUL in 26s`.
  Artifact: `.omo/evidence/task-11-artifacts/full-check.log`.

## Red then green

- Scenario: null template `dueAt` throws a runtime boundary failure after earlier hierarchy inserts; revision must remain absent.
  Red invocation: `./gradlew test --tests 'com.uniton.backend.planning.TemplateOwnerSelectionMigrationTest.rejectsMalformedTemplateWithoutPartialRevision'` before rollback fix.
  Red observable: test failed at `TemplateOwnerSelectionMigrationTest.java:90` because partial revision remained.
  Red artifact: `.omo/evidence/task-11-artifacts/red-malformed-runtime-atomicity.log`.
- Green invocation: same selector after `PlanDraftRepository` rolls back `SQLException | RuntimeException`.
  Green observable: exit 0 and `BUILD SUCCESSFUL in 6s`.
  Green artifact: `.omo/evidence/task-11-artifacts/green-malformed-runtime-atomicity.log`.

## Manual QA: PostgreSQL/Testcontainers

Invocation:

`./gradlew test --info --tests 'com.uniton.backend.planning.BlueprintConstraintTest.persistsDefaultBoundedBlueprintWithExactOrderedAgenda' --tests 'com.uniton.backend.planning.BlueprintConstraintTest.rejectsEmptyNonpositiveOutOfOrderAndMismatchedAgendaAtomically' --tests 'com.uniton.backend.planning.PlanDraftApprovalMigrationTest.laterRevisionInvalidatesPriorApprovalAndRejectsStaleSubmission' --tests 'com.uniton.backend.planning.TemplateOwnerSelectionMigrationTest.rejectsNullFalseAndNonnullTrueSelectionPairsWithoutPartialRevision' --tests 'com.uniton.backend.planning.TemplateOwnerSelectionMigrationTest.rejectsMissingDuplicateAndUnknownTemplateKeysAtomically'`

Binary observables:

- PostgreSQL `16.14`; Flyway applies V001 then V002 to a freshly cleaned schema before every case.
- Valid revision: `DATA_SURFACE blueprint_duration=1800 agenda_count=2 agenda_total=1800 revision_rows=1`.
- Stale approval: `DATA_SURFACE stale_revision_approval=rejected inherited_eligibility=false`.
- Invalid pair: `DATA_SURFACE null_false=rejected nonnull_true=rejected leaked_revisions=0`.
- Duplicate template key and agenda-total mismatch methods complete with zero failures; each method asserts `draft_revisions` remains zero after rejection.
- Run exits 0 with `BUILD SUCCESSFUL in 11s`.

Artifact: `.omo/evidence/task-11-artifacts/manual-qa-testcontainers.log`.

## Adversarial probes

- Concurrent duplicate/stale: `./gradlew test --info --rerun-tasks --tests 'com.uniton.backend.planning.PlanDraftApprovalMigrationTest.concurrentDuplicateScopeProducesOneDurableApproval' --tests 'com.uniton.backend.planning.PlanDraftApprovalMigrationTest.concurrentApplyRejectsApprovalForRevisionItSupersedes'`; observables `durable_scope_records=1`, `stale_approval_revision=1 committed=false`, exit 0. Artifact `.omo/evidence/task-11-artifacts/concurrency-rerun.log`.
- Repeated execution/isolation: focused suites ran before and after change, full check ran, and manual QA re-cleans/remigrates a real container per method. No test-order state retained.
- Misleading success output: one Gradle daemon was externally stopped while output piped through `tee`; pipeline initially reported 0. Failure was rejected, then exact selector reran with `set -o pipefail` and passed. Final artifact contains only successful rerun.
- Cleanup: test JVMs exited; no Task 11 `Gradle Test Executor`, stale-approval session, PostgreSQL Testcontainers container, or Ryuk container remained after validation. Gradle daemon was left running because stopping it could disrupt other worktrees.
- Malformed/schema input: null runtime field red/green above; SQL constraint cases cover missing/blank keys, null-state pair, duration/order/total, coverage, and FK/hash scope.
- Dirty/stale branch: worktree began clean; `git fetch origin main feat/impl-t11-draft-hierarchy-migration` confirmed base `3d47c0746975316831ddf30143991693ecfacf3d`, upstream head `b0316bb824b7fd68cc9787b5cb2d969858b3dc00`, and no remote divergence before fix.
- Non-applicable: public route/auth/deployment/network-input probes; Task 11 adds no route, deployment, or external adapter.

## Scope and size

- Java pure LOC after split: `PlanDraftRepository.java` 39, `PlanDraftHierarchyWriter.java` 153, `PlanDraftContentWriter.java` 170, changed test 212.
- `V002__plan_draft_hierarchy.sql` is 618 pure LOC. `SIZE_OK`: genuine single-version SQL DDL/data-table migration; splitting a published Flyway version would change migration identity/order. No V002 line changed in atomicity fix.
- No active-plan snapshot, candidate officialization, meeting/risk runtime table, public route, or model-supplied selection-state surface added.
