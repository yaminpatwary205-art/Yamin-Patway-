package com.example.translation

import android.util.Log
import com.example.data.local.AppDao
import com.example.data.local.TranslationCacheEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

private const val TAG = "TranslationService"

sealed interface TranslationOutcome {
    data class Success(val translatedText: String, val fromCache: Boolean) : TranslationOutcome
    data class Error(val message: String) : TranslationOutcome
}

/**
 * Separate single-item translation service using Firebase AI Logic (Gemini).
 * Strictly translates ONLY one line or one word lazily at the moment it is shown,
 * and caches every result locally (and syncs to Firestore when signed in) so no item
 * is ever translated twice.
 */
class TranslationService(
    private val dao: AppDao,
    private val onTranslationCached: suspend (TranslationCacheEntity) -> Unit = {}
) {
    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    suspend fun translate(
        text: String,
        from: String = "en",
        to: String = "bn"
    ): TranslationOutcome {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return TranslationOutcome.Error("অনুবাদ করার জন্য কোনো লেখা পাওয়া যায়নি।")
        }

        val cacheId = buildCacheKey(normalized, from, to)
        val cached = dao.getTranslationById(cacheId)
        if (cached != null && cached.translatedText.isNotBlank()) {
            return TranslationOutcome.Success(cached.translatedText, fromCache = true)
        }

        val prompt = buildString {
            append("Translate the following ")
            append(if (from == "en") "English" else from)
            append(" text into natural, simple, everyday ")
            append(if (to == "bn") "Bengali (বাংলা)" else to)
            append(" only. ")
            append("Return ONLY the translated Bengali text—no quotes, no explanation, no transliteration, no extra words.\n\n")
            append(normalized)
        }

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val result = response.text?.trim().orEmpty()
            if (result.isBlank()) {
                TranslationOutcome.Error("অনুবাদ খালি এসেছে। আবার চেষ্টা করুন।")
            } else {
                val entity = TranslationCacheEntity(
                    id = cacheId,
                    sourceText = normalized,
                    sourceLang = from,
                    targetLang = to,
                    translatedText = result,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.upsertTranslation(entity)
                onTranslationCached(entity)
                TranslationOutcome.Success(result, fromCache = false)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Translation timed out", e)
            TranslationOutcome.Error("অনুবাদ করতে সময় বেশি লাগছে। আবার চেষ্টা করুন অথবা নিজে অর্থ লিখুন।")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini translation failed", e)
            TranslationOutcome.Error(
                e.localizedMessage ?: "অফলাইন বা নেটওয়ার্ক সমস্যা। পুনরায় চেষ্টা করুন অথবা হাতে অর্থ লিখুন।"
            )
        }
    }

    suspend fun saveManualTranslation(
        text: String,
        meaningBn: String,
        from: String = "en",
        to: String = "bn"
    ) {
        val normalized = text.trim()
        val meaning = meaningBn.trim()
        if (normalized.isBlank() || meaning.isBlank()) return
        val entity = TranslationCacheEntity(
            id = buildCacheKey(normalized, from, to),
            sourceText = normalized,
            sourceLang = from,
            targetLang = to,
            translatedText = meaning,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertTranslation(entity)
        onTranslationCached(entity)
    }

    companion object {
        fun buildCacheKey(text: String, from: String, to: String): String {
            val input = "${from.lowercase()}_${to.lowercase()}_${text.trim().lowercase()}"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return "tr_" + bytes.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
