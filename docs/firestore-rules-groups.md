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
      // item write performs in the same batch (see GroupRepository).
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
      // Join: any signed-in user may write their OWN membership doc.
      // The join code is the gate (shared secret, by design — see limitation).
      allow create: if request.auth != null && uid == request.auth.uid;
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
      // Required for join lookup: any signed-in user may read a code mapping.
      allow read: if request.auth != null;
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

The app deletes in this order: items → members → mirrors → code doc → group doc LAST. The code-doc and mirror delete rules read the group doc with get(), so deleting the group doc first would make the rest of the cascade fail. The published rules must match this doc before device-testing delete/leave — the older published rules deny all three deletes.

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
8. Personal collections: existing per-user rules still pass (no regression).
9. Creator delete: group doc + items + members + code doc + every mirror delete → all ALLOWED; a non-creator member attempting group delete → DENIED.
10. Leave: member deletes own member doc + own mirror → ALLOWED; deleting another member's doc or mirror → DENIED.
11. Cascade order: mirror/code deletes succeed only while the group doc still exists (their rules read it) — the app deletes the group doc last.
12. Deleted group: a former member's group-doc listener/poll sees the missing doc and purges local rows; rejoin with the same code recreates membership (no stale local row, fresh poll baseline).

## Play Data safety / privacy policy

User-to-user sharing changes the Data safety answers and the privacy
policy page (`tanvir-durlove.github.io/nudgy/`). Both must be reworked
before this version ships — a missed update is a policy risk, not a
code risk. Group names, codes, and item contents are visible only to
members; a member's display name is visible to fellow group members.
