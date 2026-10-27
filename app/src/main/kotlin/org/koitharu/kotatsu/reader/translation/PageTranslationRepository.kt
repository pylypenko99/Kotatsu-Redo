package org.koitharu.kotatsu.reader.translation

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageTranslationRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
	private val settings: PageTranslationSettings,
) {

	private val cacheDir = File(context.cacheDir, "page_translations")

	private val providers by lazy {
		mapOf(
			PageTranslationProvider.IMAGETRANSLATE to ImageTranslateAiProvider(okHttpClient),
			PageTranslationProvider.GEMINI to GeminiProvider(okHttpClient),
		)
	}

	val isConfigured: Boolean
		get() = settings.isConfigured

	/** Returns a file URI of the previously translated version of [page], or null. */
	fun getCachedTranslation(page: MangaPage): Uri? {
		val file = cacheFileFor(page)
		return if (file.isFile) Uri.fromFile(file) else null
	}

	/**
	 * Translates the image behind [source] (a local cached page) and stores the result
	 * in the cache dir. Throws [IOException] with a user-readable message on failure.
	 */
	suspend fun translate(page: MangaPage, source: Uri): Uri = withContext(Dispatchers.IO) {
		val sourceBytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
			?: throw IOException("Page image is not available yet")
		val result = providers.getValue(settings.provider).translate(sourceBytes, settings)
		val dest = cacheFileFor(page)
		dest.parentFile?.mkdirs()
		val tmp = File(dest.parentFile, dest.name + ".tmp")
		tmp.writeBytes(result)
		if (!tmp.renameTo(dest)) {
			tmp.delete()
			throw IOException("Cannot save the translated page")
		}
		Uri.fromFile(dest)
	}

	private fun cacheFileFor(page: MangaPage): File {
		val digest = MessageDigest.getInstance("SHA-1")
			.digest("${page.url}|${settings.provider.name}|${settings.targetLanguage}|${settings.model}".toByteArray())
		val key = digest.joinToString("") { "%02x".format(it) }
		return File(cacheDir, "$key.png")
	}
}
