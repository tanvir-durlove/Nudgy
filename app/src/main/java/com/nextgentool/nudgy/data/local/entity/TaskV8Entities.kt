package com.nextgentool.nudgy.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sprint 1-1 — the six new tables of MIGRATION_7_8 (spec §4).
 *
 * Column order in each entity MUST match the CREATE TABLE statements in
 * MIGRATION_7_8 exactly — Room validates the migrated schema against
 * these entities on open, and a mismatch fails the app start.
 * No @Index declarations: the foreign-key cascades work without them at
 * this scale, and every index would need a matching CREATE INDEX in the
 * migration. (Revisit if a list ever grows past a few hundred rows.)
 */

/** Label (Feature 2). A task can carry several tags. */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    /** Hex colour, e.g. "#0F766E" — one of the tag palette colours. */
    val color: String
)

/**
 * Task ↔ tag many-to-many (Feature 2). Composite PK; deleting a task or
 * a tag removes its links (CASCADE) — the task itself always survives a
 * tag delete.
 */
@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskTagCrossRef(
    val taskId: String,
    val tagId: String
)

/** Project / list (Feature 2). A task belongs to at most one project. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    /** Hex colour, e.g. "#0F766E" — one of the tag palette colours. */
    val color: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Saved filter + search setup (Feature 2). filterJson is a
 * SavedViewFilter document (see domain/model/SavedViewFilter.kt):
 * projectId / tagIds / priority / status / groupOnly / text — every key
 * optional, a missing key means "no constraint".
 */
@Entity(tableName = "saved_views")
data class SavedViewEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val filterJson: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * File attachment (Feature 10 — table only in S1-1; UI lands in S1-4).
 * The file lives in app-private storage; Room holds only its path.
 * Deleting the task removes the row (CASCADE); the file itself is
 * removed by the repository's delete path (S1-4).
 */
@Entity(
    tableName = "task_attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskAttachmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val userId: String,
    val fileName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Task template (Feature 13 — table only in S1-1; seeding + UI land in
 * S1-4). templateJson = the list of task drafts (title, estimate,
 * priority, subtask titles). Built-in templates are seeded on first run.
 */
@Entity(tableName = "task_templates")
data class TaskTemplateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val templateJson: String,
    val isBuiltIn: Boolean = false
)
