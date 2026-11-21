package com.nextgentool.nudgy.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 3-7 — the local bank matcher on the REAL intent bank asset
 * (context.assets, same harness as ClientIntentPreFilterTest).
 *
 * Pinned here: the three new intents (feature 23) match their Bengali
 * and English phrasings, the three bank-only phrasings (which the
 * parsers cannot read) still land on the right intent, the old
 * intents still match, a greeting abstains, and the documented
 * negative "split the bill" matches SPLIT_TASK at 0.420 — the
 * behaviour change spec §8 records (ask-when-unsure, never a
 * wrong creation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntentBankMatcherTest {

    private lateinit var matcher: IntentBankMatcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        matcher = IntentBankMatcher(context.assets)
    }

    private fun intentOf(text: String): BankIntent? = matcher.match(text)?.intent

    @Test
    fun `bengali split plan and priority commands match the new intents`() {
        assertEquals(BankIntent.SPLIT_TASK, intentOf("এই টাস্কটা ভেঙে দাও"))
        assertEquals(BankIntent.PLAN_DAY, intentOf("আমার দিনটা সাজাও"))
        assertEquals(BankIntent.PRIORITY_SORT, intentOf("কোনটা আগে করবো"))
    }

    @Test
    fun `english split plan and priority commands match the new intents`() {
        assertEquals(BankIntent.SPLIT_TASK, intentOf("break this task down"))
        assertEquals(BankIntent.PLAN_DAY, intentOf("plan my day"))
        assertEquals(BankIntent.PRIORITY_SORT, intentOf("what should I do first"))
    }

    @Test
    fun `bank-only phrasings the parsers cannot read still match`() {
        // The parsers miss all three (no verb phrase they know):
        // "ভেঙে ছোট ছোট subtask বানিয়ে দাও" has no "ভেঙে দাও",
        // "দিনের সব কাজ সাজাও" has no "দিনটা সাজাও", and
        // "কোন কাজটা করবো" has no "কোনটা আগে করবো". The bank is the
        // second net — verified against the Python LOO prototype
        // (all three score 1.000 there).
        assertEquals(BankIntent.SPLIT_TASK, intentOf("টাস্কটা ভেঙে ছোট ছোট subtask বানিয়ে দাও"))
        assertEquals(BankIntent.PLAN_DAY, intentOf("আজকের দিনের সব কাজ সাজাও"))
        assertEquals(BankIntent.PRIORITY_SORT, intentOf("আজ সবার আগে কোন কাজটা করবো"))
    }

    @Test
    fun `old intents still match in bengali`() {
        assertEquals(BankIntent.TASK_CREATE, intentOf("বাজার করতে হবে"))
        assertEquals(BankIntent.QUERY_TASKS, intentOf("আজকের কাজগুলো দেখাও"))
    }

    @Test
    fun `a greeting abstains below the threshold`() {
        assertNull(intentOf("hi"))
    }

    @Test
    fun `split the bill matches SPLIT_TASK above the threshold`() {
        // Documented negative (spec §8): the bank cannot reject this
        // at threshold 0.30 — it scores 0.420. The prefilter routes
        // it to a Command, which asks "which task?" and creates
        // nothing — ask-when-unsure, never a wrong split.
        val result = matcher.match("split the bill")
        assertEquals(BankIntent.SPLIT_TASK, result?.intent)
    }
}
