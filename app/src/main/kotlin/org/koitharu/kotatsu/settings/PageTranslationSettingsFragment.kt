package org.koitharu.kotatsu.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.reader.translation.PageTranslationProvider
import org.koitharu.kotatsu.reader.translation.PageTranslationSettings

@AndroidEntryPoint
class PageTranslationSettingsFragment :
	BasePreferenceFragment(R.string.page_translation) {

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_page_translation)
		val settings = PageTranslationSettings(requireContext().applicationContext)
		findPreference<ListPreference>(PageTranslationSettings.KEY_PROVIDER)?.run {
			entries = arrayOf("ImageTranslate.AI", "Gemini")
			entryValues = PageTranslationProvider.entries.names()
			setDefaultValueCompat(PageTranslationProvider.IMAGETRANSLATE.name)
		}
		findPreference<EditTextPreference>(PageTranslationSettings.KEY_MODEL)?.run {
			if (text.isNullOrBlank()) {
				text = PageTranslationSettings.defaultModel(settings.provider)
			}
		}
		// The default model differs per provider: clear the override when the provider changes.
		findPreference<Preference>(PageTranslationSettings.KEY_PROVIDER)?.setOnPreferenceChangeListener { _, newValue ->
			preferenceManager.sharedPreferences?.edit()
				?.remove(PageTranslationSettings.KEY_MODEL)?.apply()
			findPreference<EditTextPreference>(PageTranslationSettings.KEY_MODEL)?.text =
				PageTranslationSettings.defaultModel(providerFromValue(newValue))
			true
		}
	}

	private fun providerFromValue(value: Any?): PageTranslationProvider =
		PageTranslationProvider.entries.firstOrNull { it.name == value }
			?: PageTranslationProvider.IMAGETRANSLATE
}
