package com.nextgentool.nudgy.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sprint 2-1 — the Firestore side of calendar sync, behind a seam.
 *
 * The repository's sync paths (Google import, export retry, deletes)
 * only ever need two awaited Firestore calls. The real implementation
 * below does exactly what the repository used to do inline. Tests
 * inject a fake so no Firestore client, network, or FirebaseApp is
 * ever touched on the JVM — and so the "same occurrence on two
 * devices → one Firestore doc" requirement is testable as a real
 * assertion (both devices' uploads must target the same doc id).
 *
 * startCloudSync's listener is NOT behind this seam: it is a
 * long-lived snapshot listener, not a per-call write, and no S2-1
 * test drives it.
 */
interface CalendarEventSync {
    /** Awaited upload of one event doc; true only when the write completed. */
    suspend fun uploadEventAwaited(userId: String, event: CalendarEventEntity): Boolean

    /** Awaited delete of one event doc; true only when the delete completed. */
    suspend fun deleteEventAwaited(userId: String, eventId: String): Boolean
}

class RealCalendarEventSync(
    private val context: Context,
    private val scope: CoroutineScope
) : CalendarEventSync {

    /** Fire-and-forget wrapper for the ordinary call sites. */
    fun uploadEvent(userId: String, event: CalendarEventEntity) {
        scope.launch { uploadEventAwaited(userId, event) }
    }

    /** Fire-and-forget wrapper for the ordinary call sites. */
    fun deleteEvent(userId: String, eventId: String) {
        scope.launch { deleteEventAwaited(userId, eventId) }
    }

    override suspend fun uploadEventAwaited(userId: String, event: CalendarEventEntity): Boolean {
        if (userId == com.nextgentool.nudgy.auth.AuthManager.GUEST_USER_ID) return true
        return try {
            val dbId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            val payload = hashMapOf<String, Any>(
                "id" to event.id,
                "userId" to event.userId,
                "title" to event.title,
                "startEpochMillis" to event.startEpochMillis,
                "endEpochMillis" to event.endEpochMillis,
                "eventType" to event.eventType,
                "isAllDay" to event.isAllDay,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (event.locationOrLink != null) payload["locationOrLink"] = event.locationOrLink
            event.googleCalendarEventId?.let { payload["googleCalendarEventId"] = it }
            payload["isFromGoogleCalendar"] = event.isFromGoogleCalendar
            firestore.collection("users")
                .document(userId)
                .collection("events")
                .document(event.id)
                .set(payload, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sync event to Firestore failed: ${e.message}")
            false
        }
    }

    override suspend fun deleteEventAwaited(userId: String, eventId: String): Boolean {
        if (userId == com.nextgentool.nudgy.auth.AuthManager.GUEST_USER_ID) return true
        return try {
            val dbId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            firestore.collection("users")
                .document(userId)
                .collection("events")
                .document(eventId)
                .delete()
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Delete event from Firestore failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "CalendarEventSync"
    }
}
