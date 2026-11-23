package com.nextgentool.nudgy.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 5-0 — pure group-model mapping tests (plain JUnit: this file
 * never touches Firestore types — the gateway normalizes those at the
 * boundary, so the mapping is testable on plain JVM).
 */
class GroupModelsTest {

    // ---------------------------------------------------------------- completion toggle

    @Test
    fun `toggleCompletion adds the uid once and sets count to the array size`() {
        val updated = toggleCompletion(GroupCompletion(), "u-1", true)
        assertEquals(listOf("u-1"), updated.completedBy)
        assertEquals(1, updated.completedCount)
    }

    @Test
    fun `toggleCompletion completing twice is a no-op — one member counted once`() {
        val once = toggleCompletion(GroupCompletion(), "u-1", true)
        val twice = toggleCompletion(once, "u-1", true)
        assertEquals(listOf("u-1"), twice.completedBy)
        assertEquals(1, twice.completedCount)
    }

    @Test
    fun `toggleCompletion removes only that uid and drops the count`() {
        val start = GroupCompletion(listOf("u-1", "u-2", "u-3"), 3)
        val updated = toggleCompletion(start, "u-2", false)
        assertEquals(listOf("u-1", "u-3"), updated.completedBy)
        assertEquals(2, updated.completedCount)
    }

    @Test
    fun `toggleCompletion un-completing an absent uid is a no-op`() {
        val start = GroupCompletion(listOf("u-1"), 1)
        val updated = toggleCompletion(start, "u-9", false)
        assertEquals(listOf("u-1"), updated.completedBy)
        assertEquals(1, updated.completedCount)
    }

    @Test
    fun `toggleCompletion count always equals the array size`() {
        var state = GroupCompletion()
        state = toggleCompletion(state, "a", true)
        state = toggleCompletion(state, "b", true)
        state = toggleCompletion(state, "a", false)
        state = toggleCompletion(state, "c", true)
        assertEquals(state.completedBy.size, state.completedCount)
        assertEquals(listOf("b", "c"), state.completedBy)
    }

    // ---------------------------------------------------------------- completion fields

    @Test
    fun `completionFromFields reads the array and trusts the count only when it matches`() {
        val read = completionFromFields(
            mapOf("completedBy" to listOf("u-1", "u-2"), "completedCount" to 2)
        )
        assertEquals(listOf("u-1", "u-2"), read.completedBy)
        assertEquals(2, read.completedCount)
    }

    @Test
    fun `completionFromFields derives the count from the array when the count is wrong`() {
        val read = completionFromFields(
            mapOf("completedBy" to listOf("u-1"), "completedCount" to 99)
        )
        assertEquals(1, read.completedCount)
    }

    @Test
    fun `completionFromFields on a pre-S5-0 doc with no completion fields reads empty`() {
        val read = completionFromFields(mapOf("title" to "Homework", "kind" to "TASK"))
        assertEquals(emptyList<String>(), read.completedBy)
        assertEquals(0, read.completedCount)
    }

    @Test
    fun `completionFromFields drops duplicates and non-strings`() {
        val read = completionFromFields(
            mapOf("completedBy" to listOf("u-1", "u-1", 42, null))
        )
        assertEquals(listOf("u-1"), read.completedBy)
        assertEquals(1, read.completedCount)
    }

    @Test
    fun `completionToFields round-trips through completionFromFields`() {
        val original = GroupCompletion(listOf("u-2", "u-1"), 2)
        val read = completionFromFields(completionToFields(original))
        assertEquals(original.completedBy, read.completedBy)
        assertEquals(original.completedCount, read.completedCount)
    }

    // ---------------------------------------------------------------- S5-2: effective completion (Q6)

    @Test
    fun `effectiveCompletion drops a leaver and keeps current members`() {
        val stored = GroupCompletion(listOf("u-1", "u-2", "u-3"), 3)
        val effective = effectiveCompletion(stored, setOf("u-1", "u-3"))
        assertEquals(listOf("u-1", "u-3"), effective.completedBy)
        assertEquals(2, effective.completedCount)
    }

    @Test
    fun `effectiveCompletion with no current members is empty`() {
        val stored = GroupCompletion(listOf("u-1", "u-2"), 2)
        val effective = effectiveCompletion(stored, emptySet())
        assertEquals(emptyList<String>(), effective.completedBy)
        assertEquals(0, effective.completedCount)
    }

    @Test
    fun `effectiveCompletion keeps order and drops duplicates`() {
        val stored = GroupCompletion(listOf("u-2", "u-1", "u-2"), 3)
        val effective = effectiveCompletion(stored, setOf("u-1", "u-2"))
        assertEquals(listOf("u-2", "u-1"), effective.completedBy)
        assertEquals(2, effective.completedCount)
    }

    // ---------------------------------------------------------------- S5-2: count label

    @Test
    fun `toBengaliDigits maps 0-9 and multi-digit counts`() {
        assertEquals("০", 0.toBengaliDigits())
        assertEquals("৩", 3.toBengaliDigits())
        assertEquals("১২", 12.toBengaliDigits())
        assertEquals("১০০", 100.toBengaliDigits())
    }

    @Test
    fun `formatCompletionCount writes the spec label in Bengali digits`() {
        assertEquals("৩/৫ করেছে", formatCompletionCount(3, 5))
        assertEquals("০/২ করেছে", formatCompletionCount(0, 2))
        assertEquals("১২/১২ করেছে", formatCompletionCount(12, 12))
    }

    // ---------------------------------------------------------------- group / member / code docs

    @Test
    fun `groupEntityFromDoc maps a valid doc and rejects one without a name`() {
        val entity = groupEntityFromDoc(
            "g-1",
            mapOf("name" to "DIU 41", "code" to "ABC123", "creatorUid" to "u-1")
        )
        checkNotNull(entity)
        assertEquals("g-1", entity.groupId)
        assertEquals("DIU 41", entity.name)
        assertEquals("ABC123", entity.code)
        assertEquals("u-1", entity.creatorUid)
        assertNull(
            groupEntityFromDoc("g-2", mapOf("code" to "ABC123", "creatorUid" to "u-1"))
        )
    }

    @Test
    fun `groupCreateFields carries the v1 field names`() {
        val fields = groupCreateFields("DIU 41", "ABC123", "u-1", "Tanvir")
        assertEquals("DIU 41", fields["name"])
        assertEquals("ABC123", fields["code"])
        assertEquals("u-1", fields["creatorUid"])
        assertEquals("Tanvir", fields["creatorName"])
    }

    @Test
    fun `memberFromDoc defaults a missing display name to Member`() {
        assertEquals("Asha", memberFromDoc("u-1", mapOf("displayName" to "Asha")).displayName)
        assertEquals("Member", memberFromDoc("u-2", emptyMap()).displayName)
    }

    @Test
    fun `memberCreateFields carries the B3 code proof`() {
        val fields = memberCreateFields("u-1", "Tanvir", "ABC123")
        assertEquals("u-1", fields["uid"])
        assertEquals("Tanvir", fields["displayName"])
        assertEquals("ABC123", fields["code"])
    }

    @Test
    fun `code doc mapping round-trips the group id`() {
        assertEquals("g-1", codeDocGroupId(codeDocFields("g-1")))
        assertNull(codeDocGroupId(emptyMap()))
    }

    @Test
    fun `mirrorFields carries group id, name and code`() {
        val fields = mirrorFields("g-1", "DIU 41", "ABC123")
        assertEquals("g-1", fields["groupId"])
        assertEquals("DIU 41", fields["groupName"])
        assertEquals("ABC123", fields["code"])
    }

    // ---------------------------------------------------------------- notices

    @Test
    fun `noticeFromDoc maps a valid notice and reads a Long createdAt`() {
        val notice = noticeFromDoc(
            "n-1", "g-1",
            mapOf(
                "authorUid" to "u-1", "authorName" to "Tanvir",
                "text" to "Class at 10", "createdAt" to 1_779_900_000_000L
            )
        )
        checkNotNull(notice)
        assertEquals("n-1", notice.id)
        assertEquals("g-1", notice.groupId)
        assertEquals("Class at 10", notice.text)
        assertEquals(1_779_900_000_000L, notice.createdAtEpochMillis)
    }

    @Test
    fun `noticeFromDoc rejects a notice without text or author`() {
        assertNull(noticeFromDoc("n-1", "g-1", mapOf("authorUid" to "u-1")))
        assertNull(
            noticeFromDoc(
                "n-1", "g-1",
                mapOf("authorUid" to "u-1", "text" to "   ")
            )
        )
        assertNull(
            noticeFromDoc("n-1", "g-1", mapOf("text" to "Class at 10"))
        )
    }

    @Test
    fun `noticeToFields carries author and text, no id or timestamp`() {
        val fields = noticeToFields("u-1", "Tanvir", "Class at 10")
        assertEquals("u-1", fields["authorUid"])
        assertEquals("Tanvir", fields["authorName"])
        assertEquals("Class at 10", fields["text"])
        assertTrue("id" !in fields)
        assertTrue("createdAt" !in fields)
    }

    // ---------------------------------------------------------------- poll (S5-3)

    private fun notice(id: String, createdAt: Long) =
        GroupNotice(id, "g-1", "u-1", "Ayesha", "n-$id", createdAt)

    @Test
    fun `filterNewNotices keeps a notice past the watermark`() {
        val found = filterNewNotices(
            listOf(notice("n-1", 2_000L)), lastSeenMs = 1_000L, seenIds = emptySet()
        )
        assertEquals(listOf("n-1"), found.map { it.id })
    }

    @Test
    fun `filterNewNotices keeps an unseen notice inside the overlap window`() {
        val found = filterNewNotices(
            listOf(notice("n-1", 990L)), lastSeenMs = 1_000L, seenIds = emptySet()
        )
        assertEquals(listOf("n-1"), found.map { it.id })
    }

    @Test
    fun `filterNewNotices drops a notice already seen inside the overlap window`() {
        val found = filterNewNotices(
            listOf(notice("n-1", 990L)), lastSeenMs = 1_000L, seenIds = setOf("n-1")
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `filterNewNotices drops a stale notice at or before the window even when unseen`() {
        // The window is (lastSeen - 60s, lastSeen]: 39_000 is before
        // it, 40_000 sits exactly on its edge (not past it) — both
        // stale, even though neither id was ever seen.
        val found = filterNewNotices(
            listOf(notice("n-1", 39_000L), notice("n-2", 40_000L)),
            lastSeenMs = 100_000L, seenIds = emptySet()
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `groupPollSummaryText covers items only, notices only, both, and neither`() {
        assertEquals("1 new item", groupPollSummaryText(1, 0))
        assertEquals("2 new items", groupPollSummaryText(2, 0))
        assertEquals("1 new notice", groupPollSummaryText(0, 1))
        assertEquals("3 new notices", groupPollSummaryText(0, 3))
        assertEquals("1 new item · 2 new notices", groupPollSummaryText(1, 2))
        assertEquals("2 new items · 1 new notice", groupPollSummaryText(2, 1))
        assertEquals("", groupPollSummaryText(0, 0))
    }

    // ---------------------------------------------------------------- item docs

    @Test
    fun `groupItemDocFromFields maps a task doc including completion`() {
        val doc = groupItemDocFromFields(
            "i-1",
            mapOf(
                "kind" to "TASK", "title" to "Homework",
                "dueDateEpochMillis" to 2000L, "priority" to "HIGH",
                "reminderSet" to true, "reminderEpochMillis" to 1500L,
                "completedBy" to listOf("u-1"), "completedCount" to 1,
                "updatedAt" to 3000L
            )
        )
        checkNotNull(doc)
        assertEquals("i-1", doc.id)
        assertEquals("TASK", doc.kind)
        assertEquals("Homework", doc.title)
        assertEquals(2000L, doc.dueDateEpochMillis)
        assertEquals(listOf("u-1"), doc.completedBy)
        assertEquals(1, doc.completedCount)
        assertEquals(3000L, doc.updatedAtEpochMillis)
    }

    @Test
    fun `groupItemDocFromFields rejects a doc without a title`() {
        assertNull(groupItemDocFromFields("i-1", mapOf("kind" to "TASK")))
        assertNull(
            groupItemDocFromFields("i-1", mapOf("kind" to "TASK", "title" to "  "))
        )
    }

    @Test
    fun `groupItemDocFromFields defaults a pre-S5-0 doc to empty completion`() {
        val doc = groupItemDocFromFields(
            "i-1", mapOf("kind" to "SCHEDULE", "title" to "Standup")
        )
        checkNotNull(doc)
        assertEquals(emptyList<String>(), doc.completedBy)
        assertEquals(0, doc.completedCount)
        assertEquals("SCHEDULE", doc.kind)
    }

    // ---------------------------------------------------------------- S5-5: assign + activity history

    @Test
    fun `groupItemDocFromFields reads the assignee and defaults an unassigned doc to nulls`() {
        val assigned = groupItemDocFromFields(
            "i-1", mapOf(
                "kind" to "TASK", "title" to "Homework",
                "assigneeUid" to "u-2", "assigneeName" to "Ayesha"
            )
        )
        checkNotNull(assigned)
        assertEquals("u-2", assigned.assigneeUid)
        assertEquals("Ayesha", assigned.assigneeName)
        val unassigned = groupItemDocFromFields(
            "i-2", mapOf("kind" to "TASK", "title" to "Homework")
        )
        checkNotNull(unassigned)
        assertNull(unassigned.assigneeUid)
        assertNull(unassigned.assigneeName)
    }

    private fun activityFields(
        type: String,
        actorUid: String = "u-1",
        actorName: String = "Ayesha",
        itemTitle: String? = "Homework",
        assigneeName: String? = "Bob"
    ): Map<String, Any?> = mutableMapOf<String, Any?>(
        "actorUid" to actorUid,
        "actorName" to actorName,
        "type" to type,
        "itemId" to "i-1",
        "itemTitle" to itemTitle,
        "assigneeUid" to "u-2",
        "assigneeName" to assigneeName,
        "createdAt" to 5_000L
    )

    private fun activityEntry(type: String): GroupActivityEntry {
        val entry = activityFromDoc("a-1", "g-1", activityFields(type))
        checkNotNull(entry)
        return entry
    }

    @Test
    fun `activityFromDoc maps every type and rejects an unknown type or a missing actor`() {
        for (type in GroupActivityType.ALL) {
            val entry = activityFromDoc("a-1", "g-1", activityFields(type))
            checkNotNull(entry)
            assertEquals(type, entry.type)
            assertEquals("u-1", entry.actorUid)
            assertEquals("Ayesha", entry.actorName)
            assertEquals("Homework", entry.itemTitle)
        }
        assertNull(activityFromDoc("a-1", "g-1", activityFields("not_a_type")))
        assertNull(
            activityFromDoc("a-1", "g-1", activityFields("task_created") - "actorUid")
        )
        assertNull(
            activityFromDoc("a-1", "g-1", activityFields("task_created") - "type")
        )
    }

    @Test
    fun `activityToFields round-trips through activityFromDoc`() {
        val fields = activityToFields(
            "u-1", "Ayesha", GroupActivityType.TASK_ASSIGNED,
            itemId = "i-1", itemTitle = "Homework",
            assigneeUid = "u-2", assigneeName = "Bob"
        )
        val entry = activityFromDoc("a-1", "g-1", fields + mapOf("createdAt" to 5_000L))
        checkNotNull(entry)
        assertEquals(GroupActivityType.TASK_ASSIGNED, entry.type)
        assertEquals("u-2", entry.assigneeUid)
        assertEquals("Bob", entry.assigneeName)
        assertEquals(5_000L, entry.createdAtEpochMillis)
    }

    @Test
    fun `activityDisplayText writes one sentence per type with a blank actor as Member`() {
        assertEquals("Ayesha created this group", activityDisplayText(activityEntry(GroupActivityType.GROUP_CREATED)))
        assertEquals("Ayesha joined the group", activityDisplayText(activityEntry(GroupActivityType.MEMBER_JOINED)))
        assertEquals("Ayesha left the group", activityDisplayText(activityEntry(GroupActivityType.MEMBER_LEFT)))
        assertEquals("Ayesha created task \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_CREATED)))
        assertEquals("Ayesha created schedule \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.SCHEDULE_CREATED)))
        assertEquals("Ayesha edited task \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_EDITED)))
        assertEquals("Ayesha deleted task \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_DELETED)))
        assertEquals("Ayesha deleted schedule \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.SCHEDULE_DELETED)))
        assertEquals("Ayesha completed \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_COMPLETED)))
        assertEquals("Ayesha reopened \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_REOPENED)))
        assertEquals("Ayesha assigned \u201CHomework\u201D to Bob", activityDisplayText(activityEntry(GroupActivityType.TASK_ASSIGNED)))
        assertEquals("Ayesha unassigned \u201CHomework\u201D", activityDisplayText(activityEntry(GroupActivityType.TASK_UNASSIGNED)))
        assertEquals("Ayesha sent a notice", activityDisplayText(activityEntry(GroupActivityType.NOTICE_SENT)))
        val blankActor = activityFromDoc(
            "a-1", "g-1", activityFields(GroupActivityType.TASK_COMPLETED, actorName = "")
        )
        checkNotNull(blankActor)
        assertEquals("Member completed \u201CHomework\u201D", activityDisplayText(blankActor))
        val blankAssignee = activityFromDoc(
            "a-1", "g-1", activityFields(GroupActivityType.TASK_ASSIGNED, assigneeName = "")
        )
        checkNotNull(blankAssignee)
        assertEquals("Ayesha assigned \u201CHomework\u201D to Member", activityDisplayText(blankAssignee))
    }

    @Test
    fun `creator-only activity types are exactly the creator-only actions`() {
        assertEquals(
            setOf(
                GroupActivityType.GROUP_CREATED, GroupActivityType.TASK_CREATED,
                GroupActivityType.SCHEDULE_CREATED, GroupActivityType.TASK_EDITED,
                GroupActivityType.TASK_DELETED, GroupActivityType.SCHEDULE_DELETED,
                GroupActivityType.TASK_ASSIGNED, GroupActivityType.TASK_UNASSIGNED,
                GroupActivityType.NOTICE_SENT
            ),
            GroupActivityType.CREATOR_ONLY
        )
        assertTrue(GroupActivityType.ALL.containsAll(GroupActivityType.CREATOR_ONLY))
    }
}
