package org.koitharu.kotatsu.reader.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class PageTranslationProvider {
	IMAGETRANSLATE,
	GEMINI,
}

@Singleton
class PageTranslationSettings @Inject constructor(
	@ApplicationContext context: Context,
) {

	// Lives in the default prefs file so the preference screen binds to it directly.
	private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

	var provider: PageTranslationProvider
		get() = PageTranslationProvider.entries.firstOrNull {
			it.name == prefs.getString(KEY_PROVIDER, null)
		} ?: PageTranslationProvider.IMAGETRANSLATE
		set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

	var apiKey: String
		get() = prefs.getString(KEY_API_KEY, null).orEmpty()
		set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

	/** ISO 639-1 code of the language pages are translated into, e.g. "uk". */
	var targetLanguage: String
		get() = prefs.getString(KEY_TARGET_LANGUAGE, null).takeUnless { it.isNullOrBlank() } ?: "uk"
		set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value.trim()).apply()

	/** "auto" or an ISO 639-1 code of the source language. */
	var sourceLanguage: String
		get() = prefs.getString(KEY_SOURCE_LANGUAGE, null).takeUnless { it.isNullOrBlank() } ?: "auto"
		set(value) = prefs.edit().putString(KEY_SOURCE_LANGUAGE, value.trim()).apply()

	/**
	 * Engine id: for IMAGETRANSLATE one of grok|gemini|deepseek|kimi|chatgpt|claude,
	 * for GEMINI a Gemini image model name, e.g. gemini-2.5-flash-image.
	 */
	var model: String
		get() = prefs.getString(KEY_MODEL, null).takeUnless { it.isNullOrBlank() }
			?: defaultModel(provider)
		set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

	val isConfigured: Boolean
		get() = apiKey.isNotEmpty()

	companion object {

		const val KEY_PROVIDER = "page_translation_provider"
		const val KEY_API_KEY = "page_translation_api_key"
		const val KEY_TARGET_LANGUAGE = "page_translation_target_language"
		const val KEY_SOURCE_LANGUAGE = "page_translation_source_language"
		const val KEY_MODEL = "page_translation_model"

		fun defaultModel(provider: PageTranslationProvider) = when (provider) {
			PageTranslationProvider.IMAGETRANSLATE -> "grok"
			PageTranslationProvider.GEMINI -> "gemini-2.5-flash-image"
		}
	}
}
