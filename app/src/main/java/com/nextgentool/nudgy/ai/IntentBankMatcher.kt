package com.nextgentool.nudgy.ai

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Local TF-IDF matcher over the chat intent bank
 * (assets/chat_intent_bank.json, v2-clean, 6275 entries).
 *
 * Decides INTENT ONLY — never time, never title. Fully offline, no
 * network, no Gemini, no cap consumption.
 *
 * Normalization mirrors the validated Python prototype EXACTLY
 * (matcher_prototype.py — do not "improve" one side without the other):
 * lowercase (Locale.ROOT), Bengali digits ০-৯ -> 0-9, keep only Unicode
 * letters (L*), marks (M* — keeps Bengali vowel signs), decimal numbers
 * (Nd) and whitespace; every other character -> space; split on
 * whitespace. No stemming, no stopword removal.
 *
 * IDF(term) = ln(N / df). Doc and query vectors are TF*IDF,
 * L2-normalized; score = cosine similarity via an inverted index.
 * Prediction = intent of the best-scoring document.
 *
 * Validated by leave-one-out over the bank (prototype): overall accuracy
 * 85.29% (SCHEDULE_CREATE 99.3%, QUERY_SCHEDULE 93.1%, QUERY_TASKS
 * 90.1%, TASK_CREATE 78.4%, REMINDER 71.6%). Threshold 0.30 is the
 * highest floor that costs zero measured LOO accuracy (lowest correct
 * LOO score is 0.327); greetings score 0.000 and abstain. The threshold
 * is a floor for nonsense input only — it cannot reject near-miss
 * commands ("I am busy" scores 0.794); those are handled by the
 * pre-filter's precise branches and question/small-talk guards, which
 * run before the matcher.
 *
 * NOTE: bank entries carry their markdown numbering as a token
 * ("1. আজ বাজার করতে হবে" -> token "1"). Deliberately NOT stripped:
 * the numbers are low-IDF noise and the prototype numbers were measured
 * with them present — keep parity, do not diverge.
 */
/**
 * Folds precomposed Bengali nukta letters to the decomposed form the
 * bank uses: য় (U+09DF) -> য + ়, ড় (U+09DC) -> ড + ়,
 * ঢ় (U+09DD) -> ঢ + ়. The bank contains ZERO precomposed forms
 * (3160 decomposed য + ়, 0 precomposed য়), but phone keyboards
 * commonly emit the precomposed form — without this fold a user-typed
 * "মনে করিয়ে" would match neither the reminder shortcut nor any bank
 * token. Identity on bank text, so the validated LOO numbers hold.
 */
internal fun foldBengaliNukta(text: String): String {
    return text
        .replace("\u09DF", "\u09AF\u09BC")
        .replace("\u09DC", "\u09A1\u09BC")
        .replace("\u09DD", "\u09A2\u09BC")
}

enum class BankIntent {
    TASK_CREATE,
    SCHEDULE_CREATE,
    REMINDER,
    QUERY_SCHEDULE,
    QUERY_TASKS;

    companion object {
        fun fromString(raw: String): BankIntent? = try {
            valueOf(raw.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

data class BankMatch(
    val intent: BankIntent,
    val score: Double,
    /** Best score from a document of a DIFFERENT intent (diagnostics). */
    val secondBestOtherIntentScore: Double
)

class IntentBankMatcher(private val assets: AssetManager) {

    companion object {
        /** Validated floor: lowest correct LOO score is 0.327. */
        const val MATCH_THRESHOLD = 0.30
        private const val ASSET_PATH = "chat_intent_bank.json"
    }

    private data class DocVector(
        val intent: BankIntent,
        val weights: Map<String, Double> // term -> tf*idf (L2-normalized)
    )

    // term -> list of (docIndex, normalized weight)
    private data class Index(
        val docs: List<DocVector>,
        val idf: Map<String, Double>,
        val inverted: Map<String, List<Pair<Int, Double>>>
    )

    @Volatile
    private var cachedIndex: Index? = null

    /** Normalizes and tokenizes exactly like the Python prototype. */
    fun tokenize(text: String): List<String> {
        val sb = StringBuilder(text.length)
        for (ch in foldBengaliNukta(text).lowercase(java.util.Locale.ROOT)) {
            val mapped: Char = when {
                ch in '০'..'৯' -> '0' + (ch - '০')
                ch.isLetterOrDigit() -> ch
                ch.category == CharCategory.NON_SPACING_MARK ||
                    ch.category == CharCategory.COMBINING_SPACING_MARK ||
                    ch.category == CharCategory.ENCLOSING_MARK -> ch
                ch.isWhitespace() -> ' '
                else -> ' '
            }
            sb.append(mapped)
        }
        return sb.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    /** Builds the index once, lazily, on first use. Null when the asset
     *  cannot be loaded or parsed — the caller then falls back to the
     *  old keyword routing; chat never crashes on a missing bank. */
    private fun index(): Index? {
        cachedIndex?.let { return it }
        val built = try {
            buildIndex()
        } catch (_: Exception) {
            null
        }
        if (built != null) cachedIndex = built
        return built
    }

    private fun buildIndex(): Index {
        val jsonText = assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entries: JSONArray = JSONObject(jsonText).getJSONArray("entries")
        val docs = ArrayList<DocVector>(entries.length())
        val df = HashMap<String, Int>()
        val rawDocs = ArrayList<List<String>>(entries.length())
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val intent = BankIntent.fromString(obj.optString("intent", "")) ?: continue
            val tokens = tokenize(obj.optString("text", ""))
            if (tokens.isEmpty()) continue
            rawDocs.add(tokens)
            docs.add(DocVector(intent, emptyMap()))
            for (term in tokens.toSet()) {
                df[term] = (df[term] ?: 0) + 1
            }
        }
        val n = docs.size.toDouble()
        val idf = HashMap<String, Double>(df.size)
        for ((term, count) in df) {
            idf[term] = ln(n / count.toDouble())
        }
        val inverted = HashMap<String, MutableList<Pair<Int, Double>>>()
        val vectors = ArrayList<DocVector>(docs.size)
        for ((docIdx, tokens) in rawDocs.withIndex()) {
            val tf = HashMap<String, Int>()
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            val weights = HashMap<String, Double>()
            var normSq = 0.0
            for ((term, count) in tf) {
                val w = count * (idf[term] ?: 0.0)
                weights[term] = w
                normSq += w * w
            }
            val norm = sqrt(normSq)
            val normalized = if (norm > 0.0) weights.mapValues { it.value / norm } else weights
            vectors.add(DocVector(docs[docIdx].intent, normalized))
            for ((term, w) in normalized) {
                inverted.getOrPut(term) { ArrayList() }.add(docIdx to w)
            }
        }
        return Index(vectors, idf, inverted)
    }

    /**
     * Best bank match for [text], or null when the bank is unavailable
     * or the best score is below [MATCH_THRESHOLD].
     */
    fun match(text: String): BankMatch? {
        val idx = index() ?: return null
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val tf = HashMap<String, Int>()
        for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
        val queryWeights = HashMap<String, Double>()
        var normSq = 0.0
        for ((term, count) in tf) {
            val w = count * (idx.idf[term] ?: continue)
            queryWeights[term] = w
            normSq += w * w
        }
        if (queryWeights.isEmpty()) return null
        val norm = sqrt(normSq)
        if (norm == 0.0) return null
        val scores = HashMap<Int, Double>()
        for ((term, w) in queryWeights) {
            val qw = w / norm
            val postings = idx.inverted[term] ?: continue
            for ((docIdx, dw) in postings) {
                scores[docIdx] = (scores[docIdx] ?: 0.0) + qw * dw
            }
        }
        if (scores.isEmpty()) return null
        var bestIdx = -1
        var bestScore = 0.0
        for ((docIdx, score) in scores) {
            if (score > bestScore) {
                bestScore = score
                bestIdx = docIdx
            }
        }
        if (bestIdx < 0 || bestScore < MATCH_THRESHOLD) return null
        val bestIntent = idx.docs[bestIdx].intent
        var secondBest = 0.0
        for ((docIdx, score) in scores) {
            if (idx.docs[docIdx].intent != bestIntent && score > secondBest) {
                secondBest = score
            }
        }
        return BankMatch(bestIntent, bestScore, secondBest)
    }
}
