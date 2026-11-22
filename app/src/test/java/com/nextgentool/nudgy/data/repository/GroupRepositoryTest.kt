package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun awaitCondition(timeoutMs: Long = 5_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) {
                error("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(25)
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
}
