package eu.kanade.tachiyomi.animeextension.all.chineseanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class ChineseAnime : AnimeStream(
    "all",
    "ChineseAnime",
    "https://chineseanime.top",
) {

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = "div.mrgn > a.r"

    // =========================== Anime Details ============================
    override val animeDescriptionSelector = ".entry-content"

    // ============================== Filters ===============================
    override val filtersSelector = "div.filter > ul"

    // ============================ Video Links =============================
    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("dailymotion") -> {
                DailymotionExtractor(client, headers).videosFromUrl(url, prefix)
            }
            url.contains("embedwish") -> {
                StreamWishExtractor(client, headers).videosFromUrl(url, prefix)
            }
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences
        val videoLangPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_VALUES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoLangPref)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val language = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(language, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_TITLE = "Preferred Video Language"
        private const val PREF_LANG_DEFAULT = "All Sub"
        private val PREF_LANG_VALUES = arrayOf(
            "All Sub", "Arabic", "English", "Indonesia", "Persian", "Malay",
            "Polish", "Portuguese", "Spanish", "Thai", "Vietnamese",
        )
    }
}
