package com.nextgentool.nudgy.ai

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Server-side daily LLM usage counter for the central Gemini key.
 *
 * Where it sits in the call chain (MainActivity.handleUserChat):
 * local preFilter first -> Unhandled -> local ChatTurnCap.tryConsumeTurn
 * (5 free/day + rewarded +1, max 3/day) -> [this counter, signed-in
 * users only] -> only then the Gemini call (BYOK REST or Firebase AI
 * Logic). Guest users SKIP this counter entirely — their cap stays
 * local-only (owner decision, no anonymous auth).
 *
 * Storage: `users/{uid}/llmUsage/{yyyy-MM-dd}` with int fields
 * `freeUsed`, `bonusUsed` (plus `updatedAt`). The local day
 * (yyyy-MM-dd, device-local) matches ChatTurnCap's day boundary, so
 * both counters roll over together.
 *
 * The increment happens inside a Firestore transaction that reads
 * today's doc, checks the SAME bucket the local cap just consumed
 * (free or bonus) against its cap, and aborts the call when that
 * bucket is already full. The Firestore rules (firestore.rules,
 * `isValidLlmUsage`) enforce the same shape server-side: both buckets
 * are ints >= 0, hard caps freeUsed <= 5 and bonusUsed <= 3, and each
 * write increments exactly one bucket by exactly 1.
 *
 * Fail-closed: ANY transaction failure (offline, permission denied,
 * contention) returns ERROR and the caller must NOT call Gemini — the
 * user gets a local reply instead. A modified client could skip this
 * check, but it cannot steal the billing key (the key never ships in
 * the APK) and App Check bounds calls to genuine app installs; billing
 * alerts + the Gemini API quota are the hard stop on spend.
 */
object LlmUsageCounter {

    enum class Bucket { FREE, BONUS }

    enum class CounterResult { ALLOWED, CAPPED, ERROR }

    private const val TAG = "LlmUsageCounter"

    // Fallbacks match the Remote Config defaults (chat_free_turns_per_day
    // = 5, ad_rewarded_max_turns_per_day = 3); the caller passes the
    // live RC values, so these only apply to a non-positive RC value.
    private const val FREE_CAP_FALLBACK = 5L
    private const val BONUS_CAP_FALLBACK = 3L

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Atomically consumes one turn from [bucket] in today's server
     * counter. Returns ALLOWED only when the increment was committed.
     * CAPPED when the bucket is already at its cap (the local and server
     * counters have desynced — e.g. the user spent turns on another
     * device — so the local turn stays spent and the call is refused).
     * ERROR on any transaction failure: the caller must fail closed.
     */
    suspend fun tryConsumeServerTurn(
        uid: String,
        bucket: Bucket,
        freeCapPerDay: Long,
        bonusCapPerDay: Long
    ): CounterResult {
        val freeCap = freeCapPerDay.takeIf { it > 0 } ?: FREE_CAP_FALLBACK
        val bonusCap = bonusCapPerDay.takeIf { it > 0 } ?: BONUS_CAP_FALLBACK
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("users").document(uid)
            .collection("llmUsage").document(today())
        return try {
            db.runTransaction { tx ->
                val snapshot = tx.get(docRef)
                val freeUsed = snapshot.getLong("freeUsed") ?: 0L
                val bonusUsed = snapshot.getLong("bonusUsed") ?: 0L
                when (bucket) {
                    Bucket.FREE -> {
                        if (freeUsed >= freeCap) return@runTransaction CounterResult.CAPPED
                        tx.set(
                            docRef,
                            mapOf(
                                "freeUsed" to freeUsed + 1,
                                "bonusUsed" to bonusUsed,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    }
                    Bucket.BONUS -> {
                        if (bonusUsed >= bonusCap) return@runTransaction CounterResult.CAPPED
                        tx.set(
                            docRef,
                            mapOf(
                                "freeUsed" to freeUsed,
                                "bonusUsed" to bonusUsed + 1,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    }
                }
                CounterResult.ALLOWED
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "LLM usage counter transaction failed (fail closed)", e)
            CounterResult.ERROR
        }
    }
}
