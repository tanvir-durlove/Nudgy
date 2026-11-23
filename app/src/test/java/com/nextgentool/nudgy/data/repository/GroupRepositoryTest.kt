package com.nextgentool.nudgy.data.repository

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sprint 5-0 — GroupRepository tests against [FakeGroupRemoteGateway]
 * (no FirebaseApp, no live project). In-memory Room for the local side.
 *
 * The repository launches listener attach on Dispatchers.IO
 * (startGroupSync), so tests that create/join wait for the fake to
 * record the registration before asserting or tearing down — otherwise
 * the attach coroutine could still be running when the database closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var fake: FakeGroupRemoteGateway
    private lateinit var repo: GroupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fake = FakeGroupRemoteGateway()
        repo = GroupRepository(context, db, fake)
    }

    @After
    fun tearDown() {
        repo.stopGroupSync()
        db.close()
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000L, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) {
                error("condition not met within ${timeoutMs}ms")
            }
            delay(25)
        }
    }

    private suspend fun createTestGroup(
        creatorId: String = "user-1",
        name: String = "DIU 41"
    ): GroupEntity {
        val result = repo.createGroup(creatorId, "Creator", name)
        val entity = (result as GroupResult.Ok).value
        awaitCondition { fake.itemListenerCount(entity.groupId) == 1 }
        return entity
    }

    // ---------------------------------------------------------------- create

    @Test
    fun `createGroup rejects a guest and a blank name without touching the gateway`() = runTest {
        assertTrue(repo.createGroup(AuthManager.GUEST_USER_ID, "G", "DIU") is GroupResult.Err)
        assertTrue(repo.createGroup("user-1", "Tanvir", "   ") is GroupResult.Err)
        assertEquals(0, fake.createGroupDocsCalls)
    }

    @Test
    fun `createGroup writes group, code, member and mirror docs and a local row`() = runTest {
        val entity = createTestGroup()
        assertEquals("DIU 41", entity.name)
        assertEquals("user-1", entity.creatorUid)
        assertEquals(6, entity.code.length)
        assertTrue(entity.code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        val stored = fake.groups[entity.groupId]
        checkNotNull(stored)
        assertEquals("DIU 41", stored["name"])
        assertEquals(entity.code, stored["code"])
        assertEquals(entity.groupId, fake.codes[entity.code]?.get("groupId"))
        assertEquals("Creator", fake.members[entity.groupId]?.get("user-1")?.get("displayName"))
        assertEquals("DIU 41", fake.mirrors["user-1"]?.get(entity.groupId)?.get("groupName"))
        checkNotNull(db.groupDao().getGroupById(entity.groupId))
    }

    @Test
    fun `createGroup retries once when the first code collides`() = runTest {
        fake.collideFirstCodeCheck = true
        val entity = createTestGroup()
        assertEquals(1, fake.createGroupDocsCalls)
        assertEquals(entity.groupId, fake.codes[entity.code]?.get("groupId"))
    }

    // ---------------------------------------------------------------- join

    @Test
    fun `joinGroup rejects a guest, a blank code and an unknown code`() = runTest {
        assertTrue(repo.joinGroup(AuthManager.GUEST_USER_ID, "G", "ABC123") is GroupResult.Err)
        assertTrue(repo.joinGroup("user-2", "Bob", "  ") is GroupResult.Err)
        assertTrue(repo.joinGroup("user-2", "Bob", "NOPE99") is GroupResult.Err)
        assertEquals(0, fake.joinMemberDocsCalls)
    }

    @Test
    fun `joinGroup writes member and mirror docs and a local row`() = runTest {
        fake.seedGroup(
            "g-1",
            mapOf("name" to "DIU 41", "code" to "ABC123", "creatorUid" to "user-1")
        )
        fake.codes["ABC123"] = mutableMapOf("groupId" to "g-1")
        val result = repo.joinGroup("user-2", "Bob", "abc123")
        val entity = (result as GroupResult.Ok).value
        assertEquals("g-1", entity.groupId)
        assertEquals("DIU 41", entity.name)
        assertEquals("Bob", fake.members["g-1"]?.get("user-2")?.get("displayName"))
        assertEquals("ABC123", fake.members["g-1"]?.get("user-2")?.get("code"))
        assertEquals("g-1", fake.mirrors["user-2"]?.get("g-1")?.get("groupId"))
        checkNotNull(db.groupDao().getGroupById("g-1"))
        awaitCondition { fake.itemListenerCount("g-1") == 1 }
    }

    @Test
    fun `joinGroup is a no-op success when already a member`() = runTest {
        db.groupDao().insertGroup(
            GroupEntity("g-1", "DIU 41", "ABC123", "user-1")
        )
        fake.codes["ABC123"] = mutableMapOf("groupId" to "g-1")
        val result = repo.joinGroup("user-1", "Creator", "ABC123")
        assertTrue(result is GroupResult.Ok)
        assertEquals(0, fake.joinMemberDocsCalls)
    }

    // ---------------------------------------------------------------- members

    @Test
    fun `refreshGroupMembers replaces the cache and survives a failed refresh`() = runTest {
        val group = createTestGroup()
        fake.members[group.groupId] = mutableMapOf(
            "user-2" to mutableMapOf("uid" to "user-2", "displayName" to "Bob"),
            "user-1" to mutableMapOf("uid" to "user-1", "displayName" to "Amy")
        )
        val fresh = repo.refreshGroupMembers(group.groupId)
        assertEquals(listOf("Amy", "Bob"), fresh.map { it.displayName })
        assertEquals(2, db.groupMemberDao().getMembersOnce(group.groupId).size)
        fake.failNextWith = RuntimeException("network down")
        val cached = repo.refreshGroupMembers(group.groupId)
        assertEquals(listOf("Amy", "Bob"), cached.map { it.displayName })
    }

    // ---------------------------------------------------------------- completion

    @Test
    fun `setGroupTaskCompleted toggles shared completion and the local flag`() = runTest {
        val group = createTestGroup()
        db.taskDao().insertTask(
            TaskEntity(id = "i-1", userId = "user-1", title = "Homework", groupId = group.groupId)
        )
        fake.seedItem(
            group.groupId, "i-1",
            mapOf(
                "kind" to "TASK", "title" to "Homework",
                "dueDateEpochMillis" to 2000L, "priority" to "MEDIUM",
                "category" to "WORK", "reminderSet" to false
            )
        )
        val on = repo.setGroupTaskCompleted(group.groupId, "i-1", "user-1", true)
        val completed = (on as GroupResult.Ok).value
        assertEquals(listOf("user-1"), completed.completedBy)
        assertEquals(1, completed.completedCount)
        assertEquals(listOf("user-1"), fake.items[group.groupId]?.get("i-1")?.get("completedBy"))
        assertEquals(1, fake.items[group.groupId]?.get("i-1")?.get("completedCount"))
        val local = db.taskDao().getTaskById("i-1")
        checkNotNull(local)
        assertTrue(local.isCompleted)
        assertEquals(TaskStatus.DONE, local.status)

        // Completing twice changes nothing — one member counted once.
        val again = repo.setGroupTaskCompleted(group.groupId, "i-1", "user-1", true)
        assertEquals(1, (again as GroupResult.Ok).value.completedCount)

        val off = repo.setGroupTaskCompleted(group.groupId, "i-1", "user-1", false)
        val reopened = (off as GroupResult.Ok).value
        assertEquals(emptyList<String>(), reopened.completedBy)
        assertEquals(0, reopened.completedCount)
        val localAfter = db.taskDao().getTaskById("i-1")
        checkNotNull(localAfter)
        assertTrue(!localAfter.isCompleted)
        assertEquals(TaskStatus.TODO, localAfter.status)
    }

    @Test
    fun `setGroupTaskCompleted on a missing item is an Err`() = runTest {
        val group = createTestGroup()
        val result = repo.setGroupTaskCompleted(group.groupId, "no-such-item", "user-1", true)
        assertTrue(result is GroupResult.Err)
    }

    // ---------------------------------------------------------------- notices

    @Test
    fun `createGroupNotice is creator-only and validates text`() = runTest {
        val group = createTestGroup()
        assertTrue(
            repo.createGroupNotice(
                group.groupId, AuthManager.GUEST_USER_ID, "G", "Hello"
            ) is GroupResult.Err
        )
        assertTrue(
            repo.createGroupNotice(group.groupId, "user-1", "Creator", "   ") is GroupResult.Err
        )
        assertTrue(
            repo.createGroupNotice(group.groupId, "user-1", "Creator", "x".repeat(2001))
                    is GroupResult.Err
        )
        assertTrue(
            repo.createGroupNotice(group.groupId, "user-2", "Bob", "Hello") is GroupResult.Err
        )
        val result = repo.createGroupNotice(group.groupId, "user-1", "Creator", "Class at 10")
        val notice = (result as GroupResult.Ok).value
        assertEquals("Class at 10", notice.text)
        assertEquals("Class at 10", fake.notices[group.groupId]?.get(notice.id)?.get("text"))
        val listed = repo.getGroupNotices(group.groupId)
        assertEquals(listOf("Class at 10"), listed.map { it.text })
    }

    @Test
    fun `getGroupNotices returns empty on a failed read`() = runTest {
        val group = createTestGroup()
        fake.failNextWith = RuntimeException("network down")
        assertEquals(emptyList<GroupNotice>(), repo.getGroupNotices(group.groupId))
    }

    // ---------------------------------------------------------------- notices (S5-3: live flow + poll query)

    @Test
    fun `notices listener feeds the flow and replaces the list on every snapshot`() = runTest {
        val group = createTestGroup()
        awaitCondition { fake.noticeListenerCount(group.groupId) == 1 }
        val first = listOf(
            GroupNotice("n-1", group.groupId, "user-1", "Creator", "Class at 10", 2_000L)
        )
        fake.emitNotices(group.groupId, first)
        assertEquals(first, repo.observeNotices(group.groupId).first())
        val second = listOf(
            GroupNotice("n-2", group.groupId, "user-1", "Creator", "Moved to 11", 3_000L),
            GroupNotice("n-1", group.groupId, "user-1", "Creator", "Class at 10", 2_000L)
        )
        fake.emitNotices(group.groupId, second)
        assertEquals(second, repo.observeNotices(group.groupId).first())
    }

    @Test
    fun `refreshNotices feeds the flow and returns the fresh list`() = runTest {
        val group = createTestGroup()
        awaitCondition { fake.noticeListenerCount(group.groupId) == 1 }
        fake.seedNotice(
            group.groupId, "n-1",
            mapOf("authorUid" to "user-1", "authorName" to "Creator",
                "text" to "Class at 10", "createdAt" to 2_000L)
        )
        val refreshed = repo.refreshNotices(group.groupId)
        assertEquals(listOf("Class at 10"), refreshed.map { it.text })
        assertEquals(listOf("Class at 10"), repo.observeNotices(group.groupId).first().map { it.text })
    }

    @Test
    fun `createGroupNotice appears in the flow at once`() = runTest {
        val group = createTestGroup()
        awaitCondition { fake.noticeListenerCount(group.groupId) == 1 }
        val result = repo.createGroupNotice(group.groupId, "user-1", "Creator", "Class at 10")
        assertTrue(result is GroupResult.Ok)
        assertEquals(
            listOf("Class at 10"),
            repo.observeNotices(group.groupId).first().map { it.text }
        )
    }

    @Test
    fun `purgeLocalGroup drops the group's notices from the flow`() = runTest {
        val group = createTestGroup()
        awaitCondition { fake.noticeListenerCount(group.groupId) == 1 }
        repo.createGroupNotice(group.groupId, "user-1", "Creator", "Class at 10")
        assertEquals(1, repo.observeNotices(group.groupId).first().size)
        repo.purgeLocalGroup("user-1", group.groupId)
        assertEquals(emptyList<GroupNotice>(), repo.observeNotices(group.groupId).first())
    }

    @Test
    fun `queryGroupNoticesSince returns only notices past the watermark window`() = runTest {
        val group = createTestGroup()
        fake.seedNotice(
            group.groupId, "n-old",
            mapOf("authorUid" to "user-1", "authorName" to "Creator",
                "text" to "Old", "createdAt" to 1_000_000L)
        )
        fake.seedNotice(
            group.groupId, "n-new",
            mapOf("authorUid" to "user-1", "authorName" to "Creator",
                "text" to "New", "createdAt" to 2_000_000L)
        )
        val found = repo.queryGroupNoticesSince(group.groupId, 1_900_000L)
        assertEquals(listOf("n-new"), found.map { it.id })
    }

    // ---------------------------------------------------------------- leave / delete

    @Test
    fun `leaveGroup removes membership and purges locally, creator cannot leave`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        awaitCondition { fake.itemListenerCount(group.groupId) == 1 }
        val left = repo.leaveGroup("user-2", group.groupId)
        assertTrue(left is GroupResult.Ok)
        assertNull(fake.members[group.groupId]?.get("user-2"))
        assertNull(fake.mirrors["user-2"]?.get(group.groupId))
        assertNull(db.groupDao().getGroupById(group.groupId))
        assertTrue(repo.leaveGroup("user-1", group.groupId) is GroupResult.Err)
    }

    @Test
    fun `deleteGroup removes everything remotely and locally, non-creator cannot delete`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        fake.seedItem(
            group.groupId, "i-1",
            mapOf("kind" to "TASK", "title" to "Homework")
        )
        assertTrue(repo.deleteGroup("user-2", group.groupId) is GroupResult.Err)
        val deleted = repo.deleteGroup("user-1", group.groupId)
        assertTrue(deleted is GroupResult.Ok)
        assertTrue(fake.groups.isEmpty())
        assertTrue(fake.codes.isEmpty())
        assertTrue(fake.members[group.groupId].isNullOrEmpty())
        assertNull(fake.mirrors["user-2"]?.get(group.groupId))
        assertNull(db.groupDao().getGroupById(group.groupId))
    }

    // ---------------------------------------------------------------- S5-2: creator-only writes (Q3=(b))

    @Test
    fun `createGroupTask is creator-only and writes the shared doc plus the local row`() = runTest {
        val group = createTestGroup()
        val memberResult = repo.createGroupTask(
            group.groupId, "user-2", "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        assertTrue(memberResult is GroupResult.Err)
        assertEquals(
            "Only the creator can change group tasks.",
            (memberResult as GroupResult.Err).message
        )
        assertTrue(fake.items[group.groupId].isNullOrEmpty())

        val blankResult = repo.createGroupTask(
            group.groupId, "user-1", "   ", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        assertTrue(blankResult is GroupResult.Err)

        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", "Chapter 3",
            5000L, Priority.HIGH, TaskCategory.WORK, true
        )
        val task = (created as GroupResult.Ok).value
        assertEquals("Homework", task.title)
        assertEquals(group.groupId, task.groupId)
        assertEquals("TASK", fake.items[group.groupId]?.get(task.id)?.get("kind"))
        assertEquals("Homework", fake.items[group.groupId]?.get(task.id)?.get("title"))
        checkNotNull(db.taskDao().getTaskById(task.id))
    }

    @Test
    fun `createGroupSchedule is creator-only`() = runTest {
        val group = createTestGroup()
        val memberResult = repo.createGroupSchedule(
            group.groupId, "user-2", "Class", 5000L, 6000L, null, "EVENT"
        )
        assertTrue(memberResult is GroupResult.Err)
        assertTrue(fake.items[group.groupId].isNullOrEmpty())

        val created = repo.createGroupSchedule(
            group.groupId, "user-1", "Class", 5000L, 6000L, null, "EVENT"
        )
        val event = (created as GroupResult.Ok).value
        assertEquals("Class", event.title)
        assertEquals(group.groupId, event.groupId)
        assertEquals("SCHEDULE", fake.items[group.groupId]?.get(event.id)?.get("kind"))
    }

    @Test
    fun `updateGroupTask is creator-only and preserves local completion`() = runTest {
        val group = createTestGroup()
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (created as GroupResult.Ok).value
        // Complete it locally first (as the member would see it).
        db.taskDao().insertTask(task.copy(isCompleted = true, status = TaskStatus.DONE))

        val memberResult = repo.updateGroupTask(
            group.groupId, "user-2", task.copy(title = "Hijacked")
        )
        assertTrue(memberResult is GroupResult.Err)
        assertEquals("Homework", fake.items[group.groupId]?.get(task.id)?.get("title"))

        val updated = repo.updateGroupTask(
            group.groupId, "user-1", task.copy(title = "Homework (revised)")
        )
        assertTrue(updated is GroupResult.Ok)
        assertEquals(
            "Homework (revised)",
            fake.items[group.groupId]?.get(task.id)?.get("title")
        )
        val local = db.taskDao().getTaskById(task.id)
        checkNotNull(local)
        assertTrue(local.isCompleted)
        assertEquals(TaskStatus.DONE, local.status)
    }

    @Test
    fun `deleteGroupItem is creator-only`() = runTest {
        val group = createTestGroup()
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (created as GroupResult.Ok).value

        val memberResult = repo.deleteGroupItem(group.groupId, "user-2", task.id, true)
        assertTrue(memberResult is GroupResult.Err)
        checkNotNull(fake.items[group.groupId]?.get(task.id))

        val deleted = repo.deleteGroupItem(group.groupId, "user-1", task.id, true)
        assertTrue(deleted is GroupResult.Ok)
        assertNull(fake.items[group.groupId]?.get(task.id))
        assertNull(db.taskDao().getTaskById(task.id))
    }

    // ---------------------------------------------------------------- S5-2: completion flow + leave sweep (Q6)

    @Test
    fun `listener upsert feeds the shared completion flow`() = runTest {
        val group = createTestGroup()
        fake.emitItemChanges(
            group.groupId,
            listOf(
                GroupItemChange.Upsert(
                    GroupItemDoc(
                        id = "i-1", kind = "TASK", title = "Homework",
                        dueDateEpochMillis = 5000L,
                        completedBy = listOf("user-1", "user-2"), completedCount = 2
                    )
                )
            )
        )
        val map = repo.observeCompletions().first { it["i-1"]?.completedCount == 2 }
        assertEquals(listOf("user-1", "user-2"), map["i-1"]?.completedBy)
        // The task row lands locally through the same upsert — but the
        // flow updates BEFORE the row insert in applyGroupTaskDoc, so
        // await the row (it guarantees the flow is already set) rather
        // than asserting it straight after the flow emission.
        awaitCondition { db.taskDao().getTaskById("i-1") != null }
        checkNotNull(db.taskDao().getTaskById("i-1"))
    }

    @Test
    fun `setGroupTaskCompleted updates the shared completion flow`() = runTest {
        val group = createTestGroup()
        db.taskDao().insertTask(
            TaskEntity(id = "i-1", userId = "user-1", title = "Homework", groupId = group.groupId)
        )
        fake.seedItem(
            group.groupId, "i-1",
            mapOf(
                "kind" to "TASK", "title" to "Homework",
                "dueDateEpochMillis" to 2000L, "priority" to "MEDIUM",
                "category" to "WORK", "reminderSet" to false
            )
        )
        val result = repo.setGroupTaskCompleted(group.groupId, "i-1", "user-1", true)
        assertTrue(result is GroupResult.Ok)
        val map = repo.observeCompletions().first { it["i-1"]?.completedCount == 1 }
        assertEquals(listOf("user-1"), map["i-1"]?.completedBy)
    }

    @Test
    fun `leaveGroup sweeps the leaver out of the shared counts`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        fake.seedItem(
            group.groupId, "i-1",
            mapOf(
                "kind" to "TASK", "title" to "Homework",
                "completedBy" to listOf("user-1", "user-2"), "completedCount" to 2,
                "updatedAt" to System.currentTimeMillis()
            )
        )
        fake.seedItem(
            group.groupId, "i-2",
            mapOf(
                "kind" to "TASK", "title" to "Reading",
                "completedBy" to listOf("user-1"), "completedCount" to 1,
                "updatedAt" to System.currentTimeMillis()
            )
        )
        val left = repo.leaveGroup("user-2", group.groupId)
        assertTrue(left is GroupResult.Ok)
        // Only the item user-2 had completed changes; the other is untouched.
        assertEquals(listOf("user-1"), fake.items[group.groupId]?.get("i-1")?.get("completedBy"))
        assertEquals(1, fake.items[group.groupId]?.get("i-1")?.get("completedCount"))
        assertEquals(listOf("user-1"), fake.items[group.groupId]?.get("i-2")?.get("completedBy"))
        assertEquals(1, fake.items[group.groupId]?.get("i-2")?.get("completedCount"))
    }

    // ---------------------------------------------------------------- listeners + poll

    @Test
    fun `listener upsert lands a group task locally, group delete purges it`() = runTest {
        val group = createTestGroup()
        fake.emitItemChanges(
            group.groupId,
            listOf(
                GroupItemChange.Upsert(
                    GroupItemDoc(
                        id = "i-1", kind = "TASK", title = "Homework",
                        dueDateEpochMillis = 5000L
                    )
                )
            )
        )
        awaitCondition { db.taskDao().getTaskById("i-1") != null }
        val landed = db.taskDao().getTaskById("i-1")
        checkNotNull(landed)
        assertEquals(group.groupId, landed.groupId)
        fake.emitGroupDeleted(group.groupId)
        awaitCondition { db.groupDao().getGroupById(group.groupId) == null }
        assertNull(db.taskDao().getTaskById("i-1"))
    }

    @Test
    fun `applyPolledDocs inserts new items and preserves local completion`() = runTest {
        val group = createTestGroup()
        val doc = GroupItemDoc(
            id = "i-9", kind = "TASK", title = "Polled",
            dueDateEpochMillis = 5000L
        )
        assertEquals(1, repo.applyPolledDocs("user-1", group.groupId, listOf(doc)))
        assertEquals(0, repo.applyPolledDocs("user-1", group.groupId, listOf(doc)))
        val completed = db.taskDao().getTaskById("i-9")!!.copy(
            isCompleted = true, status = TaskStatus.DONE
        )
        db.taskDao().insertTask(completed)
        assertEquals(
            0,
            repo.applyPolledDocs(
                "user-1", group.groupId,
                listOf(doc.copy(title = "Polled (edited)"))
            )
        )
        val kept = db.taskDao().getTaskById("i-9")
        checkNotNull(kept)
        assertTrue(kept.isCompleted)
        assertEquals("Polled (edited)", kept.title)
    }

    @Test
    fun `reconciliation preserves a locally planned date and start`() = runTest {
        val group = createTestGroup()
        val doc = GroupItemDoc(
            id = "i-plan", kind = "TASK", title = "Planned",
            dueDateEpochMillis = 5000L
        )
        assertEquals(1, repo.applyPolledDocs("user-1", group.groupId, listOf(doc)))
        // "Start my day" stamps the plan onto the local row only.
        val planned = db.taskDao().getTaskById("i-plan")!!.copy(
            plannedDate = "2026-11-23",
            plannedStartEpochMillis = 9 * 3_600_000L,
            carriedOverFromDate = "2026-11-22",
            // The existing local-state preservation, asserted together
            // so a future reconcile change cannot silently drop it.
            isCompleted = true, status = TaskStatus.DONE, boardOrder = 7
        )
        db.taskDao().insertTask(planned)
        // A later snapshot re-applies the doc (edited title still lands).
        assertEquals(
            0,
            repo.applyPolledDocs(
                "user-1", group.groupId,
                listOf(doc.copy(title = "Planned (edited)"))
            )
        )
        val kept = db.taskDao().getTaskById("i-plan")
        checkNotNull(kept)
        assertEquals("Planned (edited)", kept.title)
        assertEquals("2026-11-23", kept.plannedDate)
        assertEquals(9 * 3_600_000L, kept.plannedStartEpochMillis)
        assertEquals("2026-11-22", kept.carriedOverFromDate)
        assertTrue(kept.isCompleted)
        assertEquals(TaskStatus.DONE, kept.status)
        assertEquals(7, kept.boardOrder)
    }

    @Test
    fun `reconciliation leaves planned fields null on a never-planned row`() = runTest {
        val group = createTestGroup()
        val doc = GroupItemDoc(
            id = "i-unplanned", kind = "TASK", title = "Unplanned",
            dueDateEpochMillis = 5000L
        )
        assertEquals(1, repo.applyPolledDocs("user-1", group.groupId, listOf(doc)))
        assertEquals(
            0,
            repo.applyPolledDocs(
                "user-1", group.groupId,
                listOf(doc.copy(title = "Unplanned (edited)"))
            )
        )
        val kept = db.taskDao().getTaskById("i-unplanned")
        checkNotNull(kept)
        assertEquals("Unplanned (edited)", kept.title)
        assertNull(kept.plannedDate)
        assertNull(kept.plannedStartEpochMillis)
        assertNull(kept.carriedOverFromDate)
    }

    @Test
    fun `getGroupDeleteSignal returns the stored signal and false for a missing group`() = runTest {
        fake.seedGroup(
            "g-1",
            mapOf("name" to "DIU 41", "lastDeleteAt" to 12345L)
        )
        assertEquals(true to 12345L, repo.getGroupDeleteSignal("g-1"))
        assertEquals(false to -1L, repo.getGroupDeleteSignal("no-such-group"))
    }

    @Test
    fun `restoreGroupsFromMirror restores membership and purges stale local groups`() = runTest {
        fake.seedGroup(
            "g-1",
            mapOf("name" to "DIU 41", "code" to "ABC123", "creatorUid" to "user-1")
        )
        fake.mirrors["user-1"] = mutableMapOf(
            "g-1" to mutableMapOf(
                "groupId" to "g-1", "groupName" to "DIU 41", "code" to "ABC123"
            )
        )
        db.groupDao().insertGroup(GroupEntity("g-stale", "Old", "ZZZ999", "user-9"))
        repo.restoreGroupsFromMirror("user-1")
        checkNotNull(db.groupDao().getGroupById("g-1"))
        assertNull(db.groupDao().getGroupById("g-stale"))
    }

    // ---------------------------------------------------------------- S5-4: leave / rejoin + delete cascade + exact alarm (Q9)

    @Test
    fun `leaveGroup then rejoin with the same code succeeds and restores membership`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        awaitCondition { fake.itemListenerCount(group.groupId) == 1 }
        assertTrue(repo.leaveGroup("user-2", group.groupId) is GroupResult.Ok)
        assertNull(fake.members[group.groupId]?.get("user-2"))
        assertNull(fake.mirrors["user-2"]?.get(group.groupId))
        assertNull(db.groupDao().getGroupById(group.groupId))

        val rejoined = repo.joinGroup("user-2", "Bob", group.code)
        assertTrue(rejoined is GroupResult.Ok)
        assertEquals(group.groupId, (rejoined as GroupResult.Ok).value.groupId)
        assertEquals("Bob", fake.members[group.groupId]?.get("user-2")?.get("displayName"))
        assertEquals(group.groupId, fake.mirrors["user-2"]?.keys?.singleOrNull())
        checkNotNull(db.groupDao().getGroupById(group.groupId))
        awaitCondition { fake.itemListenerCount(group.groupId) == 1 }
    }

    @Test
    fun `deleteGroup removes notices too`() = runTest {
        val group = createTestGroup()
        val notice = repo.createGroupNotice(group.groupId, "user-1", "Creator", "Exam on Friday")
        assertTrue(notice is GroupResult.Ok)
        assertEquals(1, fake.notices[group.groupId]?.size)
        assertTrue(repo.deleteGroup("user-1", group.groupId) is GroupResult.Ok)
        assertTrue(fake.notices[group.groupId].isNullOrEmpty())
        assertTrue(fake.groups.isEmpty())
        assertTrue(fake.codes.isEmpty())
    }

    /**
     * Alarm assertions go through Robolectric's shadow AlarmManager —
     * the repository builds its own ReminderManager on the application
     * context, so the scheduled alarms are visible here. Each test uses
     * a distinct due offset so its trigger can never collide with
     * another test's alarm (±5s tolerance for the ms between the test
     * and the repository computing the trigger).
     */
    private fun scheduledTriggers(): List<Long> {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(am).scheduledAlarms.map { it.triggerAtMs }
    }

    private fun assertAlarmScheduled(expectedTrigger: Long) {
        assertTrue(
            "expected an alarm at ~$expectedTrigger, got ${scheduledTriggers()}",
            scheduledTriggers().any { abs(it - expectedTrigger) < 5_000L }
        )
    }

    private fun assertAlarmAbsent(expectedTrigger: Long) {
        assertTrue(
            "expected no alarm at ~$expectedTrigger, got ${scheduledTriggers()}",
            scheduledTriggers().none { abs(it - expectedTrigger) < 5_000L }
        )
    }

    @Test
    fun `createGroupTask with a reminder arms an exact alarm on the creator phone`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val due = now + 10 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        assertTrue(created is GroupResult.Ok)
        // The 2h-before-due lead: trigger = due - 2h.
        assertAlarmScheduled(due - 2 * 3_600_000L)
    }

    @Test
    fun `a group task arriving by poll arms its alarm`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val doc = GroupItemDoc(
            id = "t-arrive", kind = "TASK", title = "Arrived",
            dueDateEpochMillis = now + 11 * 3_600_000L,
            reminderSet = true,
            reminderEpochMillis = now + 9 * 3_600_000L
        )
        repo.applyPolledDocs("user-1", group.groupId, listOf(doc))
        checkNotNull(db.taskDao().getTaskById("t-arrive"))
        assertAlarmScheduled(now + 9 * 3_600_000L)
    }

    @Test
    fun `turning a group task reminder off cancels its alarm`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val due = now + 12 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        val task = (created as GroupResult.Ok).value
        assertAlarmScheduled(due - 2 * 3_600_000L)

        val updated = repo.updateGroupTask(
            group.groupId, "user-1", task.copy(reminderSet = false, reminderEpochMillis = null)
        )
        assertTrue(updated is GroupResult.Ok)
        assertAlarmAbsent(due - 2 * 3_600_000L)
    }

    @Test
    fun `a reminder-off arrival cancels the armed alarm`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val due = now + 13 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        val task = (created as GroupResult.Ok).value
        assertAlarmScheduled(due - 2 * 3_600_000L)

        val doc = GroupItemDoc(
            id = task.id, kind = "TASK", title = "Homework",
            dueDateEpochMillis = due, reminderSet = false, reminderEpochMillis = null
        )
        repo.applyPolledDocs("user-1", group.groupId, listOf(doc))
        assertAlarmAbsent(due - 2 * 3_600_000L)
    }

    @Test
    fun `completing a group task cancels its alarm and reopening re-arms it`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val due = now + 14 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        val task = (created as GroupResult.Ok).value
        assertAlarmScheduled(due - 2 * 3_600_000L)

        assertTrue(repo.setGroupTaskCompleted(group.groupId, task.id, "user-1", true) is GroupResult.Ok)
        assertAlarmAbsent(due - 2 * 3_600_000L)

        assertTrue(repo.setGroupTaskCompleted(group.groupId, task.id, "user-1", false) is GroupResult.Ok)
        assertAlarmScheduled(due - 2 * 3_600_000L)
    }

    @Test
    fun `reconcileGroupItems with an empty set cancels the group alarms`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val due = now + 15 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        assertTrue(created is GroupResult.Ok)
        assertAlarmScheduled(due - 2 * 3_600_000L)

        repo.reconcileGroupItems("user-1", group.groupId, emptySet())
        assertNull(db.taskDao().getTaskById((created as GroupResult.Ok).value.id))
        assertAlarmAbsent(due - 2 * 3_600_000L)
    }

    // ---------------------------------------------------------------- S5-5: assign + activity history (feature 36, Q10=(a))

    private suspend fun createTaskWithMember(
        group: GroupEntity,
        title: String = "Homework"
    ): TaskEntity {
        // One repo instance plays both users here: drop the local row
        // first, so user-2's join exercises the real remote path —
        // otherwise joinGroup's re-join no-op returns the cached row
        // and user-2 never becomes a remote member (the S5-1 VM-test
        // pattern). The join re-inserts the row (same groupId,
        // creatorUid from the remote fields), so the creator checks
        // below still see user-1 as creator.
        db.groupDao().deleteGroupById(group.groupId)
        repo.joinGroup("user-2", "Bob", group.code)
        repo.refreshGroupMembers(group.groupId)
        val created = repo.createGroupTask(
            group.groupId, "user-1", title, null,
            System.currentTimeMillis() + 10 * 3_600_000L,
            Priority.MEDIUM, TaskCategory.WORK, false
        )
        return (created as GroupResult.Ok).value
    }

    @Test
    fun `assignGroupTask as creator writes the remote doc and the local row and records history`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        val result = repo.assignGroupTask(group.groupId, "user-1", task.id, "user-2")
        assertTrue(result is GroupResult.Ok)
        val remote = fake.items[group.groupId]?.get(task.id)
        checkNotNull(remote)
        assertEquals("user-2", remote["assigneeUid"])
        assertEquals("Bob", remote["assigneeName"])
        val local = db.taskDao().getTaskById(task.id)
        checkNotNull(local)
        assertEquals("user-2", local.assigneeUid)
        assertEquals("Bob", local.assigneeName)
        val history = repo.refreshActivity(group.groupId)
        assertTrue(history.any {
            it.type == GroupActivityType.TASK_ASSIGNED &&
                it.itemId == task.id && it.assigneeUid == "user-2" &&
                it.assigneeName == "Bob"
        })
    }

    @Test
    fun `assignGroupTask as a member is denied and nothing changes`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        val result = repo.assignGroupTask(group.groupId, "user-2", task.id, "user-2")
        assertTrue(result is GroupResult.Err)
        assertNull(fake.items[group.groupId]?.get(task.id)?.get("assigneeUid"))
        assertNull(db.taskDao().getTaskById(task.id)?.assigneeUid)
    }

    @Test
    fun `assignGroupTask to a non-member is denied`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        val result = repo.assignGroupTask(group.groupId, "user-1", task.id, "user-9")
        assertTrue(result is GroupResult.Err)
        assertNull(db.taskDao().getTaskById(task.id)?.assigneeUid)
    }

    @Test
    fun `assignGroupTask on a missing task is denied`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        val result = repo.assignGroupTask(group.groupId, "user-1", "no-such-task", "user-2")
        assertTrue(result is GroupResult.Err)
    }

    @Test
    fun `assignGroupTask with null unassigns and records TASK_UNASSIGNED`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        assertTrue(repo.assignGroupTask(group.groupId, "user-1", task.id, "user-2") is GroupResult.Ok)
        val unassigned = repo.assignGroupTask(group.groupId, "user-1", task.id, null)
        assertTrue(unassigned is GroupResult.Ok)
        val doc = groupItemDocFromFields(
            task.id, fake.items[group.groupId]?.get(task.id) ?: emptyMap()
        )
        checkNotNull(doc)
        assertNull(doc.assigneeUid)
        assertNull(doc.assigneeName)
        val local = db.taskDao().getTaskById(task.id)
        checkNotNull(local)
        assertNull(local.assigneeUid)
        assertNull(local.assigneeName)
        val history = repo.refreshActivity(group.groupId)
        assertTrue(history.any { it.type == GroupActivityType.TASK_UNASSIGNED && it.itemId == task.id })
    }

    @Test
    fun `assignGroupTask preserves the local completion and never re-arms the alarm`() = runTest {
        val group = createTestGroup()
        // Real remote join (see createTaskWithMember): the local row
        // from createTestGroup would make joinGroup no-op.
        db.groupDao().deleteGroupById(group.groupId)
        repo.joinGroup("user-2", "Bob", group.code)
        repo.refreshGroupMembers(group.groupId)
        val now = System.currentTimeMillis()
        val due = now + 12 * 3_600_000L
        val created = repo.createGroupTask(
            group.groupId, "user-1", "Homework", null,
            due, Priority.MEDIUM, TaskCategory.WORK, true
        )
        val task = (created as GroupResult.Ok).value
        assertTrue(repo.setGroupTaskCompleted(group.groupId, task.id, "user-1", true) is GroupResult.Ok)
        assertAlarmAbsent(due - 2 * 3_600_000L)
        assertTrue(repo.assignGroupTask(group.groupId, "user-1", task.id, "user-2") is GroupResult.Ok)
        val local = db.taskDao().getTaskById(task.id)
        checkNotNull(local)
        assertTrue(local.isCompleted)
        assertEquals(TaskStatus.DONE, local.status)
        assertEquals("user-2", local.assigneeUid)
        assertAlarmAbsent(due - 2 * 3_600_000L)
    }

    @Test
    fun `creating a group and joining record GROUP_CREATED and MEMBER_JOINED`() = runTest {
        val group = createTestGroup()
        // Same one-instance-two-users trap as createTaskWithMember:
        // drop the local row so the join takes the real remote path.
        db.groupDao().deleteGroupById(group.groupId)
        repo.joinGroup("user-2", "Bob", group.code)
        val history = repo.refreshActivity(group.groupId)
        val types = history.map { it.type }.toSet()
        assertTrue(types.contains(GroupActivityType.GROUP_CREATED))
        assertTrue(types.contains(GroupActivityType.MEMBER_JOINED))
        val joined = history.first { it.type == GroupActivityType.MEMBER_JOINED }
        assertEquals("user-2", joined.actorUid)
        assertEquals("Bob", joined.actorName)
    }

    @Test
    fun `completing and reopening record TASK_COMPLETED and TASK_REOPENED`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        assertTrue(repo.setGroupTaskCompleted(group.groupId, task.id, "user-2", true) is GroupResult.Ok)
        assertTrue(repo.setGroupTaskCompleted(group.groupId, task.id, "user-2", false) is GroupResult.Ok)
        val history = repo.refreshActivity(group.groupId)
        assertTrue(history.any { it.type == GroupActivityType.TASK_COMPLETED && it.actorUid == "user-2" })
        assertTrue(history.any { it.type == GroupActivityType.TASK_REOPENED && it.actorUid == "user-2" })
    }

    @Test
    fun `sending a notice records NOTICE_SENT and creating a task records TASK_CREATED`() = runTest {
        val group = createTestGroup()
        val task = createTaskWithMember(group)
        assertTrue(
            repo.createGroupNotice(group.groupId, "user-1", "Creator", "Exam on Friday")
                is GroupResult.Ok
        )
        val history = repo.refreshActivity(group.groupId)
        assertTrue(history.any { it.type == GroupActivityType.TASK_CREATED && it.itemId == task.id })
        assertTrue(history.any { it.type == GroupActivityType.NOTICE_SENT && it.itemTitle == "Exam on Friday" })
    }

    @Test
    fun `leaving records MEMBER_LEFT and the entry survives the local purge`() = runTest {
        val group = createTestGroup()
        repo.joinGroup("user-2", "Bob", group.code)
        assertTrue(repo.leaveGroup("user-2", group.groupId) is GroupResult.Ok)
        val history = repo.refreshActivity(group.groupId)
        assertTrue(history.any {
            it.type == GroupActivityType.MEMBER_LEFT && it.actorUid == "user-2"
        })
    }

    @Test
    fun `deleteGroup removes the activity history too`() = runTest {
        val group = createTestGroup()
        createTaskWithMember(group)
        assertTrue(repo.refreshActivity(group.groupId).isNotEmpty())
        assertTrue(repo.deleteGroup("user-1", group.groupId) is GroupResult.Ok)
        assertTrue(fake.activity[group.groupId].isNullOrEmpty())
        assertTrue(repo.getGroupActivity(group.groupId).isEmpty())
    }

    @Test
    fun `activity listener feeds the flow on attach and on emit`() = runTest {
        val group = createTestGroup()
        awaitCondition { fake.activityListenerCount(group.groupId) == 1 }
        // The GROUP_CREATED entry recorded during createGroup arrives
        // with the listener's initial snapshot.
        val initial = repo.observeActivity(group.groupId).first()
        assertTrue(initial.any { it.type == GroupActivityType.GROUP_CREATED })
        val entry = GroupActivityEntry(
            id = "a-live", groupId = group.groupId, actorUid = "user-2",
            actorName = "Bob", type = GroupActivityType.TASK_COMPLETED,
            itemId = "t-1", itemTitle = "Homework",
            createdAtEpochMillis = System.currentTimeMillis()
        )
        fake.emitActivity(group.groupId, listOf(entry))
        awaitCondition {
            repo.observeActivity(group.groupId).first().any { it.id == "a-live" }
        }
    }

    @Test
    fun `purgeLocalGroup drops the activity flow entry`() = runTest {
        val group = createTestGroup()
        createTaskWithMember(group)
        awaitCondition { fake.activityListenerCount(group.groupId) == 1 }
        assertTrue(repo.observeActivity(group.groupId).first().isNotEmpty())
        repo.purgeLocalGroup("user-1", group.groupId)
        assertTrue(repo.observeActivity(group.groupId).first().isEmpty())
    }

    @Test
    fun `a polled task doc carries the assignee onto the local row`() = runTest {
        val group = createTestGroup()
        val now = System.currentTimeMillis()
        val doc = GroupItemDoc(
            id = "t-arrive", kind = "TASK", title = "Arrived",
            dueDateEpochMillis = now + 11 * 3_600_000L,
            assigneeUid = "user-2", assigneeName = "Bob"
        )
        repo.applyPolledDocs("user-1", group.groupId, listOf(doc))
        val local = db.taskDao().getTaskById("t-arrive")
        checkNotNull(local)
        assertEquals("user-2", local.assigneeUid)
        assertEquals("Bob", local.assigneeName)
    }
}
