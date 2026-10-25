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
      allow read: if request.auth != null &&
        exists(/databases/$(database)/documents/groups/$(gid)/members/$(request.auth.uid));
      allow create: if request.auth != null &&
        request.resource.data.creatorUid == request.auth.uid;
      allow update: if request.auth != null &&
        resource.data.creatorUid == request.auth.uid;
      allow delete: if false; // creator delete is Phase 3; deny in v1
    }

    match /groups/{gid}/members/{uid} {
      // A member may read the member list; a user may always read their own doc.
      allow read: if request.auth != null && (
        uid == request.auth.uid ||
        exists(/databases/$(database)/documents/groups/$(gid)/members/$(request.auth.uid)));
      // Join: any signed-in user may write their OWN membership doc.
      // The join code is the gate (shared secret, by design — see limitation).
      allow create: if request.auth != null && uid == request.auth.uid;
      allow delete: if false; // leave/remove is Phase 3; deny in v1
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
      allow delete: if false; // no revocation until Phase 3
    }

    // ---- Per-user group membership mirror ----
    match /users/{uid}/groupMemberships/{gid} {
      allow read, write: if request.auth != null && uid == request.auth.uid;
    }
  }
}
```

## Honest limitation

A join code is a shared secret, not a cryptographic capability. Anyone who
learns the code can join — that is what the CR wants for a class. The
rules above cannot verify "this user knows the code" without a Cloud
Function; that check is intentionally deferred. Do not claim otherwise
in the privacy policy.

## Pre-release rules test checklist

1. Signed-out user: group read, code read, join write → all DENIED.
2. Non-member signed-in user: group doc read → DENIED; code read → ALLOWED
   (needed for join); item read → DENIED.
3. Member: group + items read → ALLOWED; item create/update/delete → DENIED.
4. Creator: item create/update/delete → ALLOWED; group update → ALLOWED.
5. Member's membership mirror: own uid → ALLOWED; another uid → DENIED.
6. Join flow: member doc + mirror written in one batch → succeeds; a
   second join (already a member) → no-op success in the app, no rule error.
7. Personal collections: existing per-user rules still pass (no regression).

## Play Data safety / privacy policy

User-to-user sharing changes the Data safety answers and the privacy
policy page (`tanvir-durlove.github.io/nudgy/`). Both must be reworked
before this version ships — a missed update is a policy risk, not a
code risk. Group names, codes, and item contents are visible only to
members; a member's display name is visible to fellow group members.
