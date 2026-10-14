# Fix Plan: Comprehensive 18-Bug Resolution & System Hardening

Coordinated overhaul resolving all 18 identified bugs across background audio services, authentication lifecycle, alarm scheduling and reboot recovery, calendar sync, cloud persistence, and UI performance in Nudgy.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed during initial clarification and are locked into this plan:
> - **Execution Strategy**: Coordinated single-pass fix covering all Critical, Major, and Minor bugs simultaneously.
> - **Account Deletion Protocol**: Complete purge of local SQLite database, temporary audio cache, and recursive deletion of all Firestore subcollections (`tasks`, `memories`, `chat_messages`).
> - **Cloud Synchronization Trigger**: Immediate trigger on task/event mutations with automatic batch reconciliation on app resume.

- **Confirmed Decision 1**: Foreground service start on Android 14+ will use `ServiceCompat.startForeground` specifying `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- **Confirmed Decision 2**: Sign-out persistence will be enforced with a dedicated persistent flag (`explicitly_signed_out`) preventing auto-sign-in until manual user action.
- **Confirmed Decision 3**: Exact alarms will detect Android 12+ `canScheduleExactAlarms()` status, falling back to windowed alarms (`setAndAllowWhileIdle`) when restricted so reminders are never lost.

---

## 1. Overview & Core Concept

- **What It Does**: Hardens Nudgy's core voice-enabled productivity engine, ensuring voice interactions, background wake-word detection, alarm reminders, Google Calendar bidirectional sync, and multi-user data isolation work seamlessly without crashes, state leaks, or lost reminders.
- **Target Audience / Persona**: Users relying on voice-first task management, timely proactive nudges, and calendar synchronization across mobile devices.
- **Key Value**: Zero crashes on modern Android versions (API 34+), zero phantom data leaks across sign-in/out boundaries, reliable alarms that survive device reboots and permission constraints, and genuine cloud sync.

---

## 2. Technical Architecture & System Data Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  MainActivity / HomeScreen / TasksScreen / ChatScreen / YourNudgySheet │
│  (Lifecycle-aware collectAsStateWithLifecycle, LazyColumn lists)       │
└───────────────────▲───────────────────────────────▲────────────────────┘
                    │                               │
        Voice / Chat UI Events             State & Task Observers
                    │                               │
┌───────────────────▼───────────────────────────────┴────────────────────┐
│                           SERVICES & MANAGERS                          │
│  ┌───────────────────────┐  ┌────────────────────────────────────────┐ │
│  │ WakeWordForegroundSvc │  │ ReminderManager & BootReceiver         │ │
│  │ API 34+ ServiceCompat │  │ exact alarm check + inexact fallback   │ │
│  │ mic permission gate   │  │ collision-resistant request codes       │ │
│  └───────────────────────┘  └────────────────────────────────────────┘ │
│  ┌───────────────────────┐  ┌────────────────────────────────────────┐ │
│  │ AuthManager           │  │ VoiceAssistantManager                  │ │
│  │ sign-out flag check   │  │ device-locale aware language (bn/en)   │ │
│  │ teardown background   │  │ safe Gemini & pre-filter error guards  │ │
│  └───────────────────────┘  └────────────────────────────────────────┘ │
└───────────────────▲───────────────────────────────▲────────────────────┘
                    │                               │
        Isolated Data Mutations           Sync Triggers on Mutation/Resume
                    │                               │
┌───────────────────▼───────────────────────────────▼────────────────────┐
│                            DATA LAYER                                  │
│  ┌───────────────────────────────────┐ ┌─────────────────────────────┐ │
│  │ NudgyDatabase (Room)              │ │ Cloud Sync & Remote API     │ │
│  │ - Scoped queries (userId + id)    │ │ - TaskRepository dirty sync │ │
│  │ - Non-destructive migrations      │ │ - Calendar mirror delete    │ │
│  │ - Database purge on deleteAccount │ │ - Firestore subcol purge    │ │
│  └───────────────────────────────────┘ └─────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Key Product Decisions & Bug Resolution Breakdown

### Phase 1: Critical Fixes (Crash Prevention & Auth Integrity)

#### Bug 1: `WakeWordForegroundService.kt` Android 14+ Crash
- **Problem**: Calling `startForeground(NOTIFICATION_ID, notification)` without `foregroundServiceType` throws `MissingForegroundServiceTypeException` on Android 14+ (API 34+).
- **Fix**: Use `ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)` guarded by SDK version check. Ensure `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE` are verified prior to start.

#### Bug 2: `AuthManager.kt` Persistent Sign-Out
- **Problem**: On app restart, `attemptSilentSignIn` invokes Credential Manager with `setAutoSelectEnabled(true)`, which automatically re-authenticates the account even after the user tapped Sign Out.
- **Fix**:
  - Add an `EXPLICITLY_SIGNED_OUT` boolean flag in `SharedPreferences`.
  - In `signOut()`, set `EXPLICITLY_SIGNED_OUT = true`, stop `WakeWordForegroundService`, and reset in-memory states.
  - In `attemptSilentSignIn()`, immediately return if `EXPLICITLY_SIGNED_OUT` is true.
  - When the user manually clicks "Sign In With Google", clear `EXPLICITLY_SIGNED_OUT = false`.

---

### Phase 2: Major Fixes (System Services, Alarms & Persistence)

#### Bug 3: `ReminderManager.kt` Exact Alarm Permission Handling
- **Problem**: When `SCHEDULE_EXACT_ALARM` permission is denied on Android 12+, calling `setExactAndAllowWhileIdle` triggers a `SecurityException`, leaving tasks marked as having active reminders that never trigger.
- **Fix**:
  - Check `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()`.
  - When restricted, fall back gracefully to `alarmManager.setAndAllowWhileIdle(...)` (inexact window alarm) so the reminder reliably rings, and log/report the fallback status.

#### Bug 4: `AndroidManifest.xml` & `BootReceiver` Reboot Survival
- **Problem**: No `RECEIVE_BOOT_COMPLETED` permission or receiver exists; all scheduled alarms are lost when the device restarts.
- **Fix**:
  - Declare `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` in `AndroidManifest.xml`.
  - Implement `BootReceiver : BroadcastReceiver` listening for `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, and `ACTION_MY_PACKAGE_REPLACED`.
  - Query all pending future reminders from Room (`dueDateEpochMillis > now` and `reminderSet == true`) and reschedule each through `ReminderManager`.

#### Bug 5: `MainActivity.kt` Chat Error Handling & Forever "Thinking..."
- **Problem**: `handleUserChat` launches a coroutine with no `try-catch` around `preFilter.tryHandle` and `geminiClient.generateNudgyResponse`. An API timeout or network failure locks `isThinking = true` indefinitely.
- **Fix**:
  - Wrap the entire execution block in `try-catch-finally`.
  - In `finally`, ensure `isThinking = false`.
  - On error, post an assistant chat message explaining the issue and offering quick-retry prompts.

#### Bug 6: `MainActivity.kt` Speech Recognition Language Selection
- **Problem**: Recognition language is decided by inspecting Bengali Unicode characters in `user.displayName`.
- **Fix**:
  - Base the language code primarily on device locale (`Locale.getDefault().language == "bn" ? "bn-BD" : "en-US"`) or the user's explicit profile setting `user.preferredLanguage`.

#### Bug 7: `CalendarRepository.kt` Google Calendar Delete Synchronization
- **Problem**: `deleteEvent(eventId)` passes an empty string `""` to `getAllEventsFlow("")`, causing `target` to always evaluate to `null` and skipping deletion in `CalendarContract.Events`.
- **Fix**:
  - Implement a direct query `calendarDao.getEventById(eventId)` (or pass `userId` to retrieve the event).
  - Extract `googleCalendarEventId` and invoke `contentResolver.delete(...)` with the content URI before removing the local record from Room.

#### Bug 8 & 9: Microphone Permissions in `YourNudgySheet.kt` & `ChatScreen.kt`
- **Problem**:
  - `YourNudgySheet.kt` toggles `WakeWordForegroundService` without checking `RECORD_AUDIO` permission.
  - `ChatScreen.kt` launches the chat voice recorder instead of the wake word service when permission is granted from the wake word pill.
- **Fix**:
  - Add explicit runtime permission launcher in `YourNudgySheet.kt` before starting the service.
  - In `ChatScreen.kt`, separate the permission callbacks into distinct handlers: one for one-shot voice input and one for wake-word activation.

#### Bug 10: Background Audio Teardown on Sign-Out
- **Problem**: Signing out leaves `WakeWordForegroundService` running and continues background audio capture under a phantom session.
- **Fix**: Explicitly invoke `WakeWordForegroundService.stop(context)` in `AuthManager.signOut()`.

#### Bug 11: `NudgyDatabase.kt` Non-Destructive Migrations
- **Problem**: `fallbackToDestructiveMigration()` wipes user data on schema version changes.
- **Fix**: Replace destructive fallback with explicit Room `Migration` objects or remove reckless destructive fallback in favor of schema auto-migrations.

#### Bug 12: `AuthManager.kt` Placeholder User Overwrite
- **Problem**: `syncUserToFirestore` defaults missing `displayName` to `"Arif Rahman"` and `email` to `"arif@example.com"`, corrupting actual user records.
- **Fix**: Remove hardcoded dummy values; fallback to clean generic defaults (`"User"` and empty string/omitted field) or derive from Firebase user identity.

#### Bug 13: `AuthManager.kt` Recursive Subcollection & Local Storage Purge
- **Problem**: `deleteAccount` only deletes `users/{uid}`, leaving Firestore subcollections (`tasks`, `memories`) and local SQLite tables orphaned.
- **Fix**:
  - Iterate and delete all documents in `users/{uid}/tasks` and `users/{uid}/memories` before deleting `users/{uid}`.
  - Clear all local Room database tables (`database.clearAllTables()`).
  - Purge voice audio cache directory (`context.cacheDir`).

#### Bug 14: `TaskRepository.kt` Active Cloud Sync Integration
- **Problem**: `syncDirtyTasksToCloud` is implemented but has 0 call sites across the application.
- **Fix**:
  - Trigger `syncDirtyTasksToCloud(userId, scope)` immediately after task creation, task edit, completion toggle, and deletion.
  - Wire an app-resume observer in `MainActivity` to sync dirty tasks whenever the app returns to the foreground.

#### Bug 15: `Daos.kt` User-Scoped Mutation Queries
- **Problem**: Queries such as `DELETE FROM tasks WHERE id = :id` and `UPDATE tasks SET ... WHERE id = :id` are not scoped to `userId`.
- **Fix**: Update all update/delete queries to include `AND userId = :userId` (`DELETE FROM tasks WHERE id = :id AND userId = :userId`), preventing accidental cross-user mutation.

---

### Phase 3: Minor Fixes & UI Polish

#### Bug 16: `TasksScreen.kt` Lazy Loading Optimization
- **Problem**: `Column` + `forEach` inside `verticalScroll` creates high composition overhead for large lists.
- **Fix**: Migrate the task list to `LazyColumn` (or embed items inside a unified `LazyColumn`), ensuring memory reuse and smooth 60fps scrolling.

#### Bug 17: `ReminderManager.kt` Alarm Request Code Collision
- **Problem**: `taskId.hashCode()` can collide across multiple tasks.
- **Fix**: Use collision-resistant positive bitmasking (`taskId.hashCode() and 0x7FFFFFFF`) combined with a deterministic integer mapping or table index.

#### Bug 18: Seed Task Alarm Scheduling
- **Problem**: `seed_task_2` is inserted with `reminderSet = true` but `scheduleReminder` is never invoked.
- **Fix**: After inserting seed tasks, check for tasks with `reminderSet == true && dueDateEpochMillis > now` and invoke `reminderManager.scheduleReminder()`.

#### Bug 19: Lifecycle-Aware Flow Collection
- **Problem**: `ChatScreen.kt` and `YourNudgySheet.kt` use `collectAsState()` for `isWakeWordActive`.
- **Fix**: Update all occurrences to `collectAsStateWithLifecycle()`.

---

## 4. Verification & Testing Strategy

1. **Compilation Check**: Run `compile_applet` to ensure full Kotlin compilation, zero missing symbols, and strict type safety across all updated services, DAOs, and screens.
2. **Foreground Service Android 14+ Verification**: Verify `ServiceCompat.startForeground` parameters match `FOREGROUND_SERVICE_TYPE_MICROPHONE` and manifest declarations.
3. **Reboot Receiver Verification**: Inspect `AndroidManifest.xml` intent-filter registrations and `BootReceiver` implementation.
4. **Auth Flow & Data Deletion Verification**: Verify `deleteAccount` cleans Room tables, clears preferences, stops services, and queries Firestore subcollections for batch removal.
