package com.nextgentool.nudgy.data.model

enum class Priority {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Sprint 1-1 — task lifecycle status (Board column, §4).
 * Stored as TEXT in Room (see Converters). DONE ⇔ isCompleted = true;
 * the Repository's single write path keeps the two in sync.
 */
enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE
}

enum class TaskCategory {
    WORK,
    PERSONAL,
    HABIT,
    STUDY
}
