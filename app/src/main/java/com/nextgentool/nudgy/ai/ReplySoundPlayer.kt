package com.nextgentool.nudgy.ai

/**
 * Sprint 3-0 — the short reply sound for chat text replies (§4.4).
 *
 * The production implementation is [VoiceAssistantManager.playReplySound]:
 * the system default notification sound, played once on the notification
 * stream (silent / Do Not Disturb respected, never throws).
 *
 * A one-method seam, not a second voice abstraction: the only reason it
 * exists is observability — a test cannot watch RingtoneManager, but it
 * can count calls on a fake [ReplySoundPlayer]. The voice side of the
 * chat (listening, [VoiceAssistantManager]) stays a concrete dependency.
 */
fun interface ReplySoundPlayer {
    fun playReplySound()
}
