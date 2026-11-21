package com.nextgentool.nudgy.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.ads.ChatTurnConsumeResult
import com.nextgentool.nudgy.analytics.NudgyRemoteConfig
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.model.TaskCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sprint 3-3 — the LLM fallback orchestrator (spec §8, step 4).
 *
 * The cap chain, in this exact order — enforced by tests, not just by
 * reading the code:
 * 1. Offline check — no network → local fallback, NO cap consumed.
 * 2. Availability check — no BYOK key AND the central gate closed →
 *    local fallback, NO cap consumed. A user who cannot reach any LLM
 *    must never spend a turn on a local answer.
 * 3. [ChatTurnCap.tryConsumeTurn] (local: free first, then a banked
 *    bonus turn) → CAPPED → [LlmFallbackResult.Capped].
 * 4. [LlmUsageCounter.tryConsumeServerTurn] — signed-in users ONLY.
 *    Guests skip it entirely (local-only cap, no anonymous auth).
 *    CAPPED → the local turn stays spent (the documented trade-off:
 *    the counters desynced, e.g. another device spent the server
 *    turns) → Capped. ERROR → refund the local turn, fail closed.
 * 5. [GeminiClient.generateStructuredResponse] (BYOK → central, and
 *    ONLY if the gate is open) → null (the call itself failed) →
 *    refund the LOCAL turn; the server turn is NOT refunded (its
 *    transaction already committed — the documented limit in §8.5).
 * 6. [LlmStructuredOutput.parse] + validate → a single validated
 *    task/event becomes a [ChatDraft] for the preview card; anything
 *    else is a plain-text reply. ANY validation failure → plain text,
 *    NEVER a save. Sprint 3-5: when [handleSplit] passes a
 *    [SplitParent], a validated `split_task` becomes a SUBTASK_SPLIT
 *    draft instead (the parent is known); a task/event answer in that
 *    context is the wrong shape and degrades to its plain-text reply.
 *
 * This handler NEVER writes to the DB: the only save path is the
 * user confirming the draft on the preview card
 * ([com.nextgentool.nudgy.ui.viewmodel.ChatViewModel.confirmDraft] →
 * [ClientIntentPreFilter.confirmDraft]). The LLM's text never calls
 * createTask/addEvent — only validated JSON → draft → preview →
 * confirm.
 *
 * Seams (fun interfaces, so tests pass lambdas/fakes without Hilt):
 * [LlmConfigProvider] (live Remote Config values in prod),
 * [LlmServerCounter] (the Firestore counter in prod),
 * [LlmConnectivity] (ConnectivityManager in prod).
 */
data class LlmConfig(
    val centralEnabled: Boolean,
    val freeTurnsPerDay: Long,
    val maxRewardedTurnsPerDay: Long,
    val modelVersion: String
)

fun interface LlmConfigProvider {
    fun current(): LlmConfig
}

fun interface LlmServerCounter {
    suspend fun tryConsumeServerTurn(
        uid: String,
        bucket: LlmUsageCounter.Bucket,
        freeCapPerDay: Long,
        bonusCapPerDay: Long
    ): LlmUsageCounter.CounterResult
}

fun interface LlmConnectivity {
    fun isOnline(): Boolean
}

/** Production [LlmConfigProvider]: reads the live Remote Config values. */
class RemoteConfigLlmConfigProvider(
    private val remoteConfig: NudgyRemoteConfig
) : LlmConfigProvider {
    override fun current(): LlmConfig {
        val v = remoteConfig.configState.value
        return LlmConfig(
            centralEnabled = v.llmCentralEnabled,
            freeTurnsPerDay = v.chatFreeTurnsPerDay,
            maxRewardedTurnsPerDay = v.adRewardedMaxTurnsPerDay,
            modelVersion = v.geminiModelVersion
        )
    }
}

/** Production [LlmConnectivity]: the device's active network must
 *  report the INTERNET capability. A missing/unusable network means
 *  offline — the Gemini call would fail anyway, so the turn is never
 *  spent on the attempt. */
class SystemLlmConnectivity(
    private val context: Context
) : LlmConnectivity {
    override fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

sealed class LlmFallbackResult {
    /** Plain-text reply — no draft, no save. */
    data class Reply(val text: String) : LlmFallbackResult()

    /** The daily cap is spent — the reply text points at the rewarded
     *  offer (+1 turn) and at tomorrow. No Gemini call was made. */
    data class Capped(val text: String) : LlmFallbackResult()

    /** One validated task/event, ready for the preview card. The draft
     *  is NOT saved — only the user's Confirm saves it. */
    data class Draft(val draft: ChatDraft, val previewText: String) : LlmFallbackResult()
}

/**
 * Sprint 3-5 — the parent a split is attached to. The handler needs
 * the id (the draft's save target), the title (the Gemini prompt and
 * the preview card), and the due (the draft's start time — the card
 * renders the subtask list, never the time).
 */
data class SplitParent(
    val taskId: String,
    val title: String,
    val dueEpochMillis: Long
)

class LlmFallbackHandler @Inject constructor(
    private val geminiClient: GeminiClient,
    @ApplicationContext private val context: Context,
    private val configProvider: LlmConfigProvider,
    private val serverCounter: LlmServerCounter,
    private val connectivity: LlmConnectivity
) {

    /**
     * Sprint 3-5 — the split entry point (feature 20, spec §6). The
     * same cap chain as [handleUnhandled], but the Gemini call asks
     * for a `split_task` breakdown of the known parent, and a valid
     * split becomes a SUBTASK_SPLIT draft. The parent is resolved
     * BEFORE this runs (by title in the chat, or by id from the detail
     * sheet) — this handler never guesses which task to split.
     *
     * LLM unavailable (offline, or no BYOK + central gate closed) is
     * NOT the generic local fallback: the reply says plainly that
     * splitting needs AI and points at the manual path (add subtasks
     * from the task detail sheet — Sprint 1), and spends no turn.
     */
    suspend fun handleSplit(
        userId: String,
        parent: SplitParent,
        prompt: String,
        recentHistory: List<Pair<String, Boolean>> = emptyList(),
        userMemories: List<String> = emptyList()
    ): LlmFallbackResult = handleUnhandled(userId, prompt, recentHistory, userMemories, splitParent = parent)

    suspend fun handleUnhandled(
        userId: String,
        prompt: String,
        recentHistory: List<Pair<String, Boolean>> = emptyList(),
        userMemories: List<String> = emptyList(),
        splitParent: SplitParent? = null
    ): LlmFallbackResult {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        // A split has no local fallback to offer: without an LLM there
        // is no split at all, so the answer names the manual path.
        // Two different failures need two different wordings: no AI
        // available at all (offline / no BYOK + gate closed) vs the AI
        // was reached but gave nothing usable.
        val unavailableReply =
            if (splitParent != null) splitUnavailableReply(isBengali)
            else localFallbackReply(prompt)
        val failedReply =
            if (splitParent != null) splitFailedReply(isBengali)
            else localFallbackReply(prompt)

        // 1. Offline → local fallback, zero cap consumed (§4.7).
        if (!connectivity.isOnline()) {
            return LlmFallbackResult.Reply(unavailableReply)
        }

        val config = configProvider.current()

        // 2. No LLM available (no BYOK, central gate closed) → local
        //    fallback, zero cap consumed (§10.14). Checked BEFORE any
        //    consume, so a turn is never spent on a local answer.
        if (!geminiClient.hasCustomKey() && !config.centralEnabled) {
            return LlmFallbackResult.Reply(unavailableReply)
        }

        // 3. Local cap first.
        val consume = ChatTurnCap.tryConsumeTurn(
            context, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
        )
        if (consume == ChatTurnConsumeResult.CAPPED) {
            return LlmFallbackResult.Capped(cappedReply(prompt, config.freeTurnsPerDay, splitParent, isBengali))
        }
        val bucket = if (consume == ChatTurnConsumeResult.FREE) {
            LlmUsageCounter.Bucket.FREE
        } else {
            LlmUsageCounter.Bucket.BONUS
        }

        // 4. Server counter for signed-in users only; guests stay
        //    local-only (no anonymous auth).
        if (userId != AuthManager.GUEST_USER_ID) {
            when (serverCounter.tryConsumeServerTurn(
                userId, bucket, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
            )) {
                LlmUsageCounter.CounterResult.ALLOWED -> Unit
                LlmUsageCounter.CounterResult.CAPPED -> {
                    // The local turn stays spent (ChatTurnCap C2): the
                    // counters desynced — another device spent the
                    // server turns. No Gemini call.
                    return LlmFallbackResult.Capped(cappedReply(prompt, config.freeTurnsPerDay, splitParent, isBengali))
                }
                LlmUsageCounter.CounterResult.ERROR -> {
                    // Fail closed: refund the local turn, answer locally.
                    ChatTurnCap.refundTurn(
                        context, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
                    )
                    return LlmFallbackResult.Reply(unavailableReply)
                }
            }
        }

        // 5. Gemini. A null means the call itself failed (exception,
        //    empty response, or the gate closed under us) — refund the
        //    LOCAL turn. The server turn is NOT refunded: its
        //    transaction already committed (spec §8.5, documented).
        //    A split asks for the breakdown of the known parent, not
        //    for the user's raw command (the command carries no
        //    content the model needs beyond the parent title).
        val raw = geminiClient.generateStructuredResponse(
            userMessage = if (splitParent != null) {
                splitInstruction(splitParent.title, isBengali)
            } else {
                prompt
            },
            recentHistory = recentHistory,
            userMemories = userMemories,
            modelVersion = config.modelVersion,
            centralEnabled = config.centralEnabled
        ) ?: run {
            ChatTurnCap.refundTurn(
                context, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
            )
            return LlmFallbackResult.Reply(failedReply)
        }

        // 6. Validate. ANY failure → plain text, NEVER a save.
        return when (val parsed = LlmStructuredOutput.parse(raw)) {
            is LlmStructuredResult.SplitTask -> {
                if (splitParent == null) {
                    // S3-3 behaviour: no parent to attach the split
                    // to — the reply is plain text, nothing is saved.
                    LlmFallbackResult.Reply(
                        parsed.reply.ifBlank { localFallbackReply(prompt) }
                    )
                } else {
                    LlmFallbackResult.Draft(
                        draft = ChatDraft(
                            kind = DraftKind.SUBTASK_SPLIT,
                            title = splitParent.title,
                            startEpochMillis = splitParent.dueEpochMillis,
                            parentTaskId = splitParent.taskId,
                            subtaskTitles = parsed.subtasks.map { it.title },
                            isBengali = isBengali
                        ),
                        previewText = parsed.reply.ifBlank {
                            splitPreviewQuestion(isBengali, splitParent.title)
                        }
                    )
                }
            }
            is LlmStructuredResult.SingleTask -> {
                if (splitParent != null) {
                    // The model answered the wrong shape for a split:
                    // its reply is plain text, never a creation.
                    return LlmFallbackResult.Reply(
                        parsed.reply.ifBlank { localFallbackReply(prompt) }
                    )
                }
                // The parser maps a null due to ReplyOnly, so due is
                // non-null here by construction; the check is the
                // backstop, not the guarantee.
                val due = parsed.task.dueEpochMillis
                    ?: return LlmFallbackResult.Reply(localFallbackReply(prompt))
                LlmFallbackResult.Draft(
                    draft = ChatDraft(
                        kind = DraftKind.TASK,
                        title = parsed.task.title,
                        startEpochMillis = due,
                        priority = parsed.task.priority,
                        category = TaskCategory.PERSONAL,
                        isBengali = isBengali
                    ),
                    previewText = parsed.reply.ifBlank { previewQuestion(isBengali, isEvent = false) }
                )
            }
            is LlmStructuredResult.SingleEvent -> {
                if (splitParent != null) {
                    // Wrong shape for a split — plain text, never a save.
                    return LlmFallbackResult.Reply(
                        parsed.reply.ifBlank { localFallbackReply(prompt) }
                    )
                }
                LlmFallbackResult.Draft(
                    draft = ChatDraft(
                        kind = DraftKind.SCHEDULE,
                        title = parsed.event.title,
                        startEpochMillis = parsed.event.startEpochMillis,
                        endEpochMillis = parsed.event.endEpochMillis,
                        isBengali = isBengali
                    ),
                    previewText = parsed.reply.ifBlank { previewQuestion(isBengali, isEvent = true) }
                )
            }
            is LlmStructuredResult.ReplyOnly -> LlmFallbackResult.Reply(
                parsed.reply.ifBlank { failedReply }
            )
            is LlmStructuredResult.Invalid -> LlmFallbackResult.Reply(
                parsed.reply.ifBlank { failedReply }
            )
        }
    }

    /**
     * Sprint 3-5 — the split instruction sent to Gemini (the user's
     * raw command carries no content the model needs beyond the parent
     * title). The system instruction already defines the split_task
     * JSON shape; this only names the task and the count. The reply
     * language follows the user's prompt, not this English instruction.
     */
    private fun splitInstruction(parentTitle: String, isBengali: Boolean): String =
        "Split the task \"$parentTitle\" into 3-6 small, concrete subtasks. " +
            "Answer with ONLY the split_task JSON." +
            if (isBengali) " Write the subtask titles and reply in Bengali." else ""

    /** No AI exists to split with (offline, or no BYOK + gate closed):
     *  say so plainly and point at the manual path (spec §6). */
    private fun splitUnavailableReply(isBengali: Boolean): String =
        if (isBengali) {
            "টাস্ক ভাগ করতে AI লাগে, আর এখন কোনো AI নেই — টাস্কের detail sheet থেকে নিজে subtask যোগ করো (টাস্কটা খুলে \"Add subtask\" দাও)।"
        } else {
            "Breaking a task into subtasks needs AI, and there's no AI available right now — add subtasks manually from the task detail sheet (open the task, then \"Add subtask\")."
        }

    /** The AI was reached but the split produced nothing usable. */
    private fun splitFailedReply(isBengali: Boolean): String =
        if (isBengali) {
            "এখন ভাগ করতে পারলাম না — আবার চেষ্টা করো, অথবা টাস্কের detail sheet থেকে নিজে subtask যোগ করো।"
        } else {
            "I couldn't break that down right now — try again, or add subtasks manually from the task detail sheet."
        }

    /** The manual path, appended to the capped reply for a split. */
    private fun splitManualPath(isBengali: Boolean): String =
        if (isBengali) "অথবা টাস্কের detail sheet থেকে নিজে subtask যোগ করো।"
        else "Or add subtasks manually from the task detail sheet."

    /** The preview question when the model's `reply` is blank: the
     *  card itself lists the subtasks, so the text is only the ask. */
    private fun splitPreviewQuestion(isBengali: Boolean, parentTitle: String): String =
        if (isBengali) {
            "\"${parentTitle}\" এভাবে ভাগ করব — Confirm করলে এই subtask-গুলো তৈরি হবে:"
        } else {
            "Here's how I'd break down \"$parentTitle\" — confirm to create these subtasks:"
        }

    /**
     * Local fallback for Unhandled input — in the input's language,
     * pointing at what the chat can actually do. Never a guess, never
     * a creation, never an LLM call. The single source of truth: the
     * ChatViewModel uses this for the prefilter-failure branch too.
     */
    fun localFallbackReply(prompt: String): String =
        if (prompt.any { it in '\u0980'..'\u09FF' }) {
            "এটা ঠিক বুঝতে পারিনি — আমাকে একটা কাজ বানাতে বলো, কিছু শিডিউল করতে বলো, বা দিনের প্ল্যান চাও।"
        } else {
            "I didn't quite catch that — try asking me to create a task, schedule something, or plan your day."
        }

    /** The capped reply: the daily free turns are spent. The rewarded
     *  offer (+1 turn) lives in the ChatScreen rewarded entry; the
     *  text points at it. Local (prefilter) commands still work. A
     *  split appends the manual path — the user can still break the
     *  task down by hand while the turns are spent. */
    private fun cappedReply(
        prompt: String,
        freeTurnsPerDay: Long,
        splitParent: SplitParent? = null,
        isBengali: Boolean = false
    ): String {
        val base = if (prompt.any { it in '\u0980'..'\u09FF' }) {
            "আজকের $freeTurnsPerDay টা ফ্রি AI উত্তর শেষ। +১ উত্তরের জন্য একটা ছোট বিজ্ঞাপন দেখো, অথবা কাল আবার চেষ্টা করো — লোকাল কমান্ড এখনো চলবে।"
        } else {
            "You've used your $freeTurnsPerDay free AI answers for today. Watch a short ad for +1 answer, or come back tomorrow — local commands still work."
        }
        return if (splitParent != null) "$base ${splitManualPath(isBengali)}" else base
    }

    /** The preview question when the model's `reply` is blank: the
     *  card itself shows the title/time, so the text is only the ask
     *  (same shape as the prefilter's preview questions). */
    private fun previewQuestion(isBengali: Boolean, isEvent: Boolean): String =
        if (isBengali) {
            if (isEvent) "এই ইভেন্টটা শিডিউল করব?" else "এই টাস্কটা তৈরি করব?"
        } else {
            if (isEvent) "Schedule this event?" else "Create this task?"
        }
}
