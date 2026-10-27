package org.koitharu.kotatsu.reader.translation

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Translates a single manga page image. Implementations must be stateless.
 */
interface TranslationProvider {

	suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray
}

private fun errorByCode(code: Int, fallback: String): IOException = when (code) {
	401 -> IOException("Invalid API key (check it in reader settings)")
	402 -> IOException("Not enough credits on the translation service account")
	403 -> IOException("Your plan does not include API access")
	429 -> IOException("Rate limit exceeded, try again later")
	else -> IOException("Translation service error: HTTP $code${
		fallback.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
	}")
}

class ImageTranslateAiProvider(
	private val okHttpClient: OkHttpClient,
) : TranslationProvider {

	@Serializable
	private data class Response(
		val success: Boolean = false,
		val resultImage: String? = null,
		val detail: String? = null,
		val error: String? = null,
	)

	private val json = Json {
		ignoreUnknownKeys = true
	}

	override suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray =
		withContext(Dispatchers.IO) {
			val payload = json.encodeToString(
				mapOf(
					"imageBase64" to Base64.encodeToString(sourceImage, Base64.NO_WRAP),
					"sourceLanguage" to settings.sourceLanguage,
					"targetLanguage" to settings.targetLanguage,
					"mode" to "manga",
					"translator" to settings.model,
				),
			)
			val request = Request.Builder()
				.url("https://api.imagetranslate.ai/translate/image")
				.header("Authorization", "Bearer ${settings.apiKey}")
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.post(payload.toRequestBody("application/json".toMediaType()))
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				val bodyText = response.body?.string().orEmpty()
				val parsed = runCatching { json.decodeFromString<Response>(bodyText) }.getOrNull()
				if (!response.isSuccessful) {
					throw errorByCode(response.code, parsed?.detail ?: parsed?.error.orEmpty())
				}
				val dataUrl = parsed?.resultImage
					?: throw IOException(if (parsed?.detail != null) parsed.detail else "Translation service returned no image")
				val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
					.ifEmpty { dataUrl }
				Base64.decode(base64, Base64.DEFAULT)
			}
		}
}

class GeminiProvider(
	private val okHttpClient: OkHttpClient,
) : TranslationProvider {

	@Serializable
	private data class Response(
		val candidates: List<Candidate> = emptyList(),
		val error: ErrorBody? = null,
	)

	@Serializable
	private data class Candidate(val content: Content? = null)

	@Serializable
	private data class Content(val parts: List<Part> = emptyList())

	@Serializable
	private data class Part(val text: String? = null, val inlineData: InlineData? = null)

	@Serializable
	private data class InlineData(val mimeType: String? = null, val data: String? = null)

	@Serializable
	private data class ErrorBody(val code: Int = 0, val message: String? = null, val status: String? = null)

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = false
		explicitNulls = false
	}

	override suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray =
		withContext(Dispatchers.IO) {
			val target = displayLanguageName(settings.targetLanguage)
			val prompt = "You are a professional manga and comic translator. Translate ALL text on this " +
				"comic/manga page into $target. Erase the original text inside speech bubbles, captions, " +
				"signs and sound effects and redraw the translated text in place, preserving the original " +
				"layout, bubble size, font style and artwork. Do not modify the artwork outside the text " +
				"areas. Keep sound effects short and readable. Return only the edited image."
			val payload = json.encodeToString(
				RequestPayload(
					contents = listOf(
						ContentPayload(
							parts = listOf(
								PartPayload(text = prompt),
								PartPayload(
									inlineData = InlinePayload(
										mimeType = detectMimeType(sourceImage),
										data = Base64.encodeToString(sourceImage, Base64.NO_WRAP),
									),
								),
							),
						),
					),
				),
			)
			val model = settings.model
			val request = Request.Builder()
				.url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
				.header("x-goog-api-key", settings.apiKey)
				.post(payload.toRequestBody("application/json".toMediaType()))
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				val bodyText = response.body?.string().orEmpty()
				val parsed = runCatching { json.decodeFromString<Response>(bodyText) }.getOrNull()
				if (!response.isSuccessful) {
					throw errorByCode(response.code, parsed?.error?.message.orEmpty())
				}
				val inline = parsed?.candidates?.firstOrNull()?.content?.parts
					?.firstOrNull { it.inlineData?.data != null }?.inlineData
					?: throw IOException(
						parsed?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.takeIf { it.isNotBlank() }
							?: "Model returned no image, check the model name (an image editing model is required)",
					)
				Base64.decode(inline.data, Base64.DEFAULT)
			}
		}

	@Serializable
	private data class RequestPayload(val contents: List<ContentPayload>)

	@Serializable
	private data class ContentPayload(val parts: List<PartPayload>)

	@Serializable
	private data class PartPayload(
		val text: String? = null,
		val inlineData: InlinePayload? = null,
	)

	@Serializable
	private data class InlinePayload(val mimeType: String, val data: String)

	private fun detectMimeType(bytes: ByteArray): String = when {
		bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
		bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
		bytes.size > 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
		else -> "image/jpeg"
	}

	private fun displayLanguageName(code: String): String = when (code) {
		"uk" -> "Ukrainian"
		"en" -> "English"
		"ru" -> "Russian"
		"ja" -> "Japanese"
		"ko" -> "Korean"
		"zh" -> "Chinese"
		"pl" -> "Polish"
		"de" -> "German"
		"fr" -> "French"
		"es" -> "Spanish"
		"it" -> "Italian"
		"pt" -> "Portuguese"
		else -> code
	}
}
