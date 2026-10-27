package org.koitharu.kotatsu.reader.translation

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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

internal fun translationPrompt(targetLanguageCode: String): String {
	val target = displayLanguageName(targetLanguageCode)
	return "You are a professional manga and comic translator. Translate ALL text on this " +
		"comic/manga page into $target. Erase the original text inside speech bubbles, captions, " +
		"signs and sound effects and redraw the translated text in place, preserving the original " +
		"layout, bubble size, font style and artwork. Do not modify the artwork outside the text " +
		"areas. Keep sound effects short and readable. Return only the edited image."
}

private fun detectImageMimeType(bytes: ByteArray): String = when {
	bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
	bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
	bytes.size > 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
	else -> "image/jpeg"
}

private fun dataUrl(bytes: ByteArray): String =
	"data:${detectImageMimeType(bytes)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

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
										mimeType = detectImageMimeType(sourceImage),
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
}

/**
 * OpenRouter dedicated Image API (https://openrouter.ai/api/v1/images).
 * Uses the user's existing OpenRouter key; image-to-image via input_references.
 */
class OpenRouterImageProvider(
	private val okHttpClient: OkHttpClient,
) : TranslationProvider {

	@Serializable
	private data class Response(val data: List<OutputImage> = emptyList(), val error: ErrorBody? = null)

	@Serializable
	private data class OutputImage(@SerialName("b64_json") val b64Json: String? = null)

	@Serializable
	private data class ErrorBody(val message: String? = null)

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
	}

	override suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray =
		withContext(Dispatchers.IO) {
			val payload = json.encodeToString(
				RequestPayload(
					model = settings.model,
					prompt = translationPrompt(settings.targetLanguage),
					inputReferences = listOf(ReferencePayload(imageUrl = ImageRefPayload(url = dataUrl(sourceImage)))),
				),
			)
			val request = Request.Builder()
				.url("https://openrouter.ai/api/v1/images")
				.header("Authorization", "Bearer ${settings.apiKey}")
				.post(payload.toRequestBody("application/json".toMediaType()))
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				val bodyText = response.body?.string().orEmpty()
				val parsed = runCatching { json.decodeFromString<Response>(bodyText) }.getOrNull()
				if (!response.isSuccessful) {
					throw errorByCode(response.code, parsed?.error?.message.orEmpty())
				}
				parsed?.data?.firstOrNull()?.b64Json?.let { Base64.decode(it, Base64.DEFAULT) }
					?: throw IOException("OpenRouter returned no image, check the model name (an image model is required)")
			}
		}

	@Serializable
	private data class RequestPayload(
		val model: String,
		val prompt: String,
		val inputReferences: List<ReferencePayload>,
	)

	@Serializable
	private data class ReferencePayload(val type: String = "image_url", val imageUrl: ImageRefPayload)

	@Serializable
	private data class ImageRefPayload(val url: String)
}

/**
 * OpenAI images/edits endpoint (gpt-image-1 and similar).
 */
class OpenAiImageEditProvider(
	private val okHttpClient: OkHttpClient,
) : TranslationProvider {

	@Serializable
	private data class Response(val data: List<OutputImage> = emptyList(), val error: ErrorBody? = null)

	@Serializable
	private data class OutputImage(@SerialName("b64_json") val b64Json: String? = null)

	@Serializable
	private data class ErrorBody(val message: String? = null)

	private val json = Json {
		ignoreUnknownKeys = true
	}

	override suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray =
		withContext(Dispatchers.IO) {
			val body = MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("model", settings.model)
				.addFormDataPart(
					"image",
					"page.${detectImageMimeType(sourceImage).substringAfter('/')}",
					sourceImage.toRequestBody(detectImageMimeType(sourceImage).toMediaType()),
				)
				.addFormDataPart("prompt", translationPrompt(settings.targetLanguage))
				.build()
			val request = Request.Builder()
				.url("https://api.openai.com/v1/images/edits")
				.header("Authorization", "Bearer ${settings.apiKey}")
				.post(body)
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				val bodyText = response.body?.string().orEmpty()
				val parsed = runCatching { json.decodeFromString<Response>(bodyText) }.getOrNull()
				if (!response.isSuccessful) {
					throw errorByCode(response.code, parsed?.error?.message.orEmpty())
				}
				parsed?.data?.firstOrNull()?.b64Json?.let { Base64.decode(it, Base64.DEFAULT) }
					?: throw IOException("OpenAI returned no image")
			}
		}
}

/**
 * Any manga-image-translator compatible server (self-hosted or third-party hosting).
 * Endpoint: POST {base}/translate/with-form/image (multipart image + config JSON).
 */
class MitServerProvider(
	private val okHttpClient: OkHttpClient,
) : TranslationProvider {

	override suspend fun translate(sourceImage: ByteArray, settings: PageTranslationSettings): ByteArray =
		withContext(Dispatchers.IO) {
			val base = settings.serverUrl
			if (base.isBlank()) {
				throw IOException("Translation server URL is not set")
			}
			val config = """{"translator":{"target_lang":"${settings.targetLanguage.uppercase()}"}}"""
			val body = MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart(
					"image",
					"page.${detectImageMimeType(sourceImage).substringAfter('/')}",
					sourceImage.toRequestBody(detectImageMimeType(sourceImage).toMediaType()),
				)
				.addFormDataPart("config", config)
				.build()
			val request = Request.Builder()
				.url("$base/translate/with-form/image")
				.apply {
					if (settings.apiKey.isNotEmpty()) {
						header("Authorization", "Bearer ${settings.apiKey}")
					}
				}
				.post(body)
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					throw errorByCode(response.code, response.body?.string()?.take(200).orEmpty())
				}
				val bytes = response.body?.bytes() ?: throw IOException("Empty response from the translation server")
				// The endpoint streams the translated image directly; validate it is an image.
				if (bytes.size > 8 && bytes[0] == '<'.code.toByte()) {
					throw IOException("Unexpected response from the translation server (check the URL)")
				}
				bytes
			}
		}
}

