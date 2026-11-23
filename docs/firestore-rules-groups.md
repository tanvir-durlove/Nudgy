# Firestore rules for the Nudgy group feature (Phase 1)

> IMPORTANT: These rules are NOT applied from this repo. There is no rules
> file deployed by CI. Apply them in the Firebase console
> (Firestore Database → Rules) for project `nudgy-8bd76`, database
> `(default)`, and publish. Test every case in the checklist below before
> the version ships. The app code in this repo assumes these rules.

## Rules (adapt to the existing rules file's syntax)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ---- Existing personal collections: UNCHANGED ----
    // (keep the existing per-user rules for users/{uid}/tasks,
    //  users/{uid}/events, users/{uid}/memories, users/{uid}/chats)

    // ---- Groups ----
    match /groups/{gid} {
      // Single-doc get is open to any signed-in user: the join flow MUST
      // read the group doc (name, creatorUid) BEFORE the membership doc
      // exists, or join is impossible. This is safe because the gid is
      // only learnable through the code doc, which requires knowing the
      // code — and list is denied, so groups cannot be enumerated.
      allow get: if request.auth != null;
      allow list: if false;
      allow create: if request.auth != null &&
        request.resource.data.creatorUid == request.auth.uid;
      // Creator-only update: covers the lastUpdatedAt bump that every
      // item write performs in the same batch, and the lastDeleteAt bump
      // that every item delete performs in the same batch (minimization:
      // the poll worker gates its full reconcile on lastDeleteAt). The
      // rule is field-agnostic, so no rule change was needed for the
      // new field — only this comment (see GroupRepository).
      allow update: if request.auth != null &&
        resource.data.creatorUid == request.auth.uid;
      allow delete: if request.auth != null &&
        resource.data.creatorUid == request.auth.uid; // creator-only delete (Phase 3)
    }

    match /groups/{gid}/members/{uid} {
      // A member may read the member list; a user may always read their own doc.
      allow read: if request.auth != null && (
        uid == request.auth.uid ||
        exists(/databases/$(database)/documents/groups/$(gid)/members/$(request.auth.uid)));
      // Join (B3): membership requires proof of the join code. The
      // member-doc write must carry the code, verified against the
      // groupCodes doc (groupCodes/{code}.groupId == gid). Both
      // joinGroup() and createGroup() (creator's first member write)
      // include "code" in the payload; a write without a matching
      // code is denied, so the code can no longer be bypassed by
      // writing the member doc directly.
      allow create: if request.auth != null && uid == request.auth.uid
        && 'code' in request.resource.data
        && request.resource.data.code is string
        && get(/databases/$(database)/documents/groupCodes/$(request.resource.data.code)).data.groupId == gid;
      // Leave: a member may delete their OWN membership doc. Creator
      // leave is not a thing in v1 — the creator deletes the group.
      allow delete: if request.auth != null && uid == request.auth.uid;
    }

    match /groups/{gid}/items/{itemId} {
      allow read: if request.auth != null &&
        exists(/databases/$(database)/documents/groups/$(gid)/members/$(request.auth.uid));
      allow create, update, delete: if request.auth != null &&
        get(/databases/$(database)/documents/groups/$(gid)).data.creatorUid == request.auth.uid;
    }

    // ---- Join codes: the code IS the document id (atomic uniqueness) ----
    match /groupCodes/{code} {
      // B3: get only — `allow read` also granted `list`, letting any
      // signed-in user enumerate every join code. Nothing in the app
      // lists this collection (join does a single doc get).
      allow get: if request.auth != null;
      allow create: if request.auth != null &&
        get(/databases/$(database)/documents/groups/$(request.resource.data.groupId)).data.creatorUid == request.auth.uid;
      // Creator-only delete (group delete cascade; the group doc must
      // still exist when this runs — the cascade deletes it LAST).
      allow delete: if request.auth != null &&
        get(/databases/$(database)/documents/groups/$(resource.data.groupId)).data.creatorUid == request.auth.uid;
    }

    // ---- Per-user group membership mirror ----
    match /users/{uid}/groupMemberships/{gid} {
      allow read, write: if request.auth != null && uid == request.auth.uid;
      // Delete additionally allows the group's creator, so the delete
      // cascade can remove every member's mirror (the group doc must
      // still exist when this runs — the cascade deletes it LAST).
      allow delete: if request.auth != null && (
        uid == request.auth.uid ||
        get(/databases/$(database)/documents/groups/$(gid)).data.creatorUid == request.auth.uid);
    }
  }
}
```

## Delete cascade order (Phase 3)

The app deletes in this order: items → notices → members → mirrors → code doc → group doc LAST. The code-doc and mirror delete rules read the group doc with get(), so deleting the group doc first would make the rest of the cascade fail. The published rules must match this doc before device-testing delete/leave — the older published rules deny all three deletes.

## Honest limitation

A join code is a shared secret, not a cryptographic capability. Anyone who
learns the code can join — that is what the CR wants for a class. The
rules above cannot verify "this user knows the code" without a Cloud
Function; that check is intentionally deferred. Do not claim otherwise
in the privacy policy.

## Pre-release rules test checklist

1. Signed-out user: group read, code read, join write → all DENIED.
2. Non-member signed-in user: group doc single get → ALLOWED (required for
   the pre-membership join read); group doc list → DENIED; code read →
   ALLOWED (needed for join); item read → DENIED.
3. Member: group + items read → ALLOWED; item create/update/delete → DENIED.
4. Creator: item create/update/delete → ALLOWED; group update → ALLOWED.
5. Member's membership mirror: own uid → ALLOWED; another uid → DENIED.
6. Join flow: code read → group doc get → member doc + mirror written in
   one batch → all succeed; a second join (already a member) → no-op
   success in the app, no rule error.
7. Creator posts an item: item write + group lastUpdatedAt bump in one
   batch → succeeds. Non-creator member attempts the same → DENIED
   (both the item write and the group update fail).
7b. Creator deletes an item: item delete + group lastDeleteAt bump in one
    batch → succeeds (minimization: reconcile gate signal). Non-creator
    member attempts the same → DENIED.
8. Personal collections: existing per-user rules still pass (no regression).
9. Creator delete: group doc + items + notices + members + code doc + every mirror delete → all ALLOWED; a non-creator member attempting group delete → DENIED. (Sprint 5-4: the v1 cascade left notices orphaned — they are deleted with the group now.)
10. Leave: member deletes own member doc + own mirror → ALLOWED; deleting another member's doc or mirror → DENIED.
11. Cascade order: mirror/code deletes succeed only while the group doc still exists (their rules read it) — the app deletes the group doc last.
12. Deleted group: a former member's group-doc listener/poll sees the missing doc and purges local rows; rejoin with the same code recreates membership (no stale local row, fresh poll baseline).

## Play Data safety / privacy policy

User-to-user sharing changes the Data safety answers and the privacy
policy page (`tanvir-durlove.github.io/nudgy/`). Both must be reworked
before this version ships — a missed update is a policy risk, not a
code risk. Group names, codes, and item contents are visible only to
members; a member's display name is visible to fellow group members.

---

# Sprint 5-0 additions (2026-11-22, spec §3 — NOT deployed yet)

The rules in `firestore.rules` were extended additively. Every v1 rule above
still holds; the additions below are the only changes. Do NOT deploy until
the S5-0 checklist at the end of this section is verified against the
Firestore emulator (no emulator is available on the build VM — the rules
were reviewed by hand and the app-side mapping is unit-tested; live
deployment needs a deliberate decision, never a PR side effect).

## What changed and why

1. **Any member may create an item** (Q3=(a)). The v1 `items` create was
   creator-only. The create rule now requires membership + a non-empty
   title (`kind` must be a string). The creator keeps full update/delete.
2. **Member completion-only update** (feature 31). A member may update an
   item doc ONLY to toggle their own completion: the changed keys must be
   exactly `completedBy` + `completedCount`, the count must equal the
   array size, and the only UID added or removed must be the caller's own
   (`isCompletionOnlyUpdate`). A member editing a title, due date or
   priority is denied. Item docs written before S5-0 have no
   `completedBy` — the rule treats a missing array as empty.
3. **Member lastUpdatedAt-only group update.** Every item write bumps the
   group doc's `lastUpdatedAt` in the same batch. With member creates,
   that bump would fail under the creator-only group update — so a member
   may now update ONLY `lastUpdatedAt` on the group doc (checked with
   `diff().affectedKeys().hasOnly(['lastUpdatedAt'])`). A member changing
   the group name or code is denied.
4. **Notices subcollection** (`groups/{gid}/notices/{noticeId}`, feature 32,
   text-only). Read: members. Create: creator only, `authorUid` must be
   the caller, text 1–2000 chars. Update: never. Delete: creator only.

## Backward compatibility with the 1.0.5 production app

The 1.0.5 app shares project `nudgy-8bd76` and its Firestore. All four
changes are widenings, not narrowings: creators can still do everything
they could; members gain create + completion-only update + the
lastUpdatedAt bump. The 1.0.5 UI never exposes member item creation, so
its behaviour does not change. Item docs without `completedBy` keep
working (read as empty completion).

## S5-0 rules test checklist (emulator — run before deploying)

13. Non-creator member creates an item (+ group lastUpdatedAt bump in the
    same batch) → ALLOWED. The same member editing the item's title →
    DENIED.
14. Member toggles own completion (completedBy +[uid], completedCount
    +1) → ALLOWED. Member toggling ANOTHER member's UID → DENIED.
    Member writing completedCount != completedBy.size() → DENIED.
    Member completing an item twice (no change) → ALLOWED (no-op).
15. Member updating ONLY lastUpdatedAt on the group doc → ALLOWED.
    Member changing the group name → DENIED.
16. Creator sends a notice (text 1–2000, authorUid == caller) →
    ALLOWED. Member sending a notice → DENIED. Notice with empty text
    or >2000 chars → DENIED. Member editing a notice → DENIED.
17. All v1 checklist items 1–12 still pass (no regression).

---

# Sprint 5-2 additions (2026-11-22, spec §5 Q3=(b) — NOT deployed yet)

## What changed and why

1. **Item create is creator-only again (Q3=(b) REVERT).** The S5-0
   widening (any member could create an item) is reverted: the create
   rule now requires the caller's UID to equal the group doc's
   `creatorUid` (plus the kind/title validation). The repository
   enforces the same check locally (`requireCreator`) before writing.
   The S5-0 checklist item 13 (member create → ALLOWED) is now
   SUPERSEDED: a member creating an item → DENIED.
2. **Completion toggle bumps `updatedAt` (Q7=(a) offline toggle).** The
   toggle write changed from a transaction to arrayUnion/arrayRemove +
   increment, which resolves against the local cache while offline and
   syncs on reconnect. The write also bumps the item doc's `updatedAt`
   (server timestamp) so the poll query sees the change.
   `isCompletionOnlyUpdate` now allows exactly one extra key:
   `updatedAt`, and requires it to be a timestamp. A member changing
   any other field is still denied. (A toggle that does not bump
   `updatedAt` still passes — the field already exists as a timestamp
   on every item doc.)
3. **Member lastUpdatedAt-only group update: kept, now unused.** No
   current member write touches the group doc (the toggle bumps only
   the item doc's `updatedAt`). The branch stays as a narrow,
   harmless allowance; the S5-0 comment in the rules is corrected.

## Backward compatibility with the 1.0.5 production app

The 1.0.5 app never writes `completedBy`/`completedCount`, so the
`updatedAt` allowance does not affect it. The create narrowing
restores the v1 rule the 1.0.5 app already lived under — its
behaviour does not change. Members of 1.0.5-era groups lose nothing:
the 1.0.5 UI never exposed member item creation.

## S5-2 rules test checklist (emulator — run before deploying)

18. Non-creator member creates an item → DENIED (S5-0 item 13 is
    superseded). Creator creates an item → ALLOWED.
19. Member toggles own completion (completedBy +[uid],
    completedCount +1, updatedAt bumped) → ALLOWED. Same toggle
    WITHOUT the updatedAt bump → ALLOWED (field already a timestamp).
    Toggle with updatedAt set to a non-timestamp → DENIED. Member
    toggling ANOTHER member's UID → DENIED. Member writing
    completedCount != completedBy.size() → DENIED.
20. All S5-0 checklist items 14–17 still pass (no regression).

## S5-3 — no rules change

S5-3 (notices UI + polling notifications) needs NO rules change:
the notices rules from S5-0 already say exactly what the feature
needs — creator-only create/delete, members read, no update, text
1–2000 chars. The poll worker only READS (items since a watermark,
notices since a watermark) through the member's own membership, so
the existing read rules cover it. The notification tap is local
(MainActivity → NudgyApp → Groups detail) — no Firestore write.
No new checklist items; items 1–20 still apply unchanged.

## S5-5 additions (2026-11-22, spec §8 S5-5 — NOT deployed yet)

## What changed and why

1. **New `groups/{gid}/activity/{activityId}` match block (feature 36).**
   The only rules change in S5-5. Read: any member. Create: the writer
   must be a member, the entry's `actorUid` must be their own UID, the
   `type` must be one of the 12 fixed types, and `createdAt` must be a
   timestamp. The four member-action types (`member_joined`,
   `member_left`, `task_completed`, `task_reopened`) pass on that alone;
   the other eight (group/task/schedule/notice writes + assign) additionally
   require the actor to be the group's creator — mirroring the action
   rules, so a member can never record a task creation or an assignment
   they did not (and could not) perform. Update: never (append-only).
   Delete: creator only (the S5-4 cascade deletes the activity
   subcollection with the group).
2. **No items-rules change for assign.** Assign writes `assigneeUid` /
   `assigneeName` on the item doc through the creator's unrestricted
   update branch — no new rule needed. A member cannot smuggle an
   assign through the completion path: `isCompletionOnlyUpdate`
   allows exactly {completedBy, completedCount, updatedAt}. The item
   create rule does not validate the assignee fields (assign-at-create
   is not a UI flow; extra fields are unchecked on create, same as
   `description`).

## Backward compatibility with the 1.0.5 production app

The 1.0.5 app never reads or writes `activity` — the new block is
additive and invisible to it. The 1.0.5 app never writes `assigneeUid` /
`assigneeName` — the items rules are unchanged, so its behaviour does
not change.

## S5-5 rules test checklist (emulator — run before deploying)

21. Member records a completion entry (actorUid == own UID, type
    `task_completed`, createdAt timestamp) → ALLOWED. Same entry with
    another member's UID as actorUid → DENIED. Entry with a type
    outside the 12 → DENIED. Entry with createdAt as a number, not a
    timestamp → DENIED.
22. Member records a `task_created` entry → DENIED (creator-only
    type). Creator records it → ALLOWED. Member records
    `task_assigned` → DENIED. Creator records it → ALLOWED.
23. Non-member reads the activity subcollection → DENIED. Member
    updates an activity doc → DENIED. Member deletes one → DENIED.
    Creator deletes one → ALLOWED.
24. All S5-0/S5-2 checklist items 1–20 still pass (no regression).
