package eu.kanade.tachiyomi.animeextension.pt.animestc

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animestc.ATCFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.AnonFilesExtractor
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.LinkBypasser
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.SendcmExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit.DAYS

class AnimesTC : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimesTC"

    override val baseUrl = "https://api2.animestc.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$HOST_URL/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series?order=id&direction=asc&page=1&top=true", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<List<AnimeDto>>()
        val animes = data.map(::searchAnimeFromObject)
        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(HOST_URL, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.use { it.asJsoup() }
        val animes = doc.select("div > article.episode").map {
            SAnime.create().apply {
                val ahref = it.selectFirst("h3 > a.episode-info-title-orange")!!
                title = ahref.text()
                val slug = ahref.attr("href").substringAfterLast("/")
                setUrlWithoutDomain("/series?slug=$slug")
                thumbnail_url = it.selectFirst("img.episode-image")?.attr("abs:data-src")
            }
        }
            .filter { it.thumbnail_url?.contains("/_nuxt/img/") == false }
            .distinctBy { it.url }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/series?slug=$slug"))
                .asObservableSuccess()
                .map(::searchAnimeBySlugParse)
        } else {
            return Observable.just(searchAnime(page, query, filters))
        }
    }

    private val allAnimesList by lazy {
        val cache = CacheControl.Builder().maxAge(1, DAYS).build()
        listOf("movie", "ova", "series").map { type ->
            val url = "$baseUrl/series?order=title&direction=asc&page=1&full=true&type=$type"
            val response = client.newCall(GET(url, cache = cache)).execute()
            response.parseAs<ResponseDto<AnimeDto>>().items
        }.flatten()
    }

    override fun getFilterList(): AnimeFilterList = ATCFilters.FILTER_LIST

    private fun searchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = ATCFilters.getSearchParameters(filters).apply {
            animeName = query
        }
        val filtered = allAnimesList.applyFilterParams(params)
        val results = filtered.chunked(30)
        val hasNextPage = results.size > page
        val currentPage = if (results.size == 0) {
            emptyList<SAnime>()
        } else {
            results.get(page - 1).map(::searchAnimeFromObject)
        }
        return AnimesPage(currentPage, hasNextPage)
    }

    private fun searchAnimeFromObject(anime: AnimeDto) = SAnime.create().apply {
        thumbnail_url = anime.cover.url
        title = anime.title
        setUrlWithoutDomain("/series/${anime.id}")
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val anime = response.getAnimeDto()
        setUrlWithoutDomain("/series/${anime.id}")
        title = anime.title
        status = anime.status
        thumbnail_url = anime.cover.url
        artist = anime.producer
        genre = anime.genres
        description = buildString {
            append(anime.synopsis + "\n")

            anime.classification?.also { append("\nClassificação: $it anos") }
            anime.year?.also { append("\nAno de lançamento: $it ") }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val id = response.getAnimeDto().id
        return getEpisodeList(id)
    }

    private fun episodeListRequest(animeId: Int, page: Int) =
        GET("$baseUrl/episodes?order=id&direction=desc&page=$page&seriesId=$animeId&specialOrder=true")

    private fun getEpisodeList(animeId: Int, page: Int = 1): List<SEpisode> {
        val response = client.newCall(episodeListRequest(animeId, page)).execute()
        val parsed = response.parseAs<ResponseDto<EpisodeDto>>()
        val episodes = parsed.items.map(::episodeFromObject)

        if (parsed.page < parsed.lastPage) {
            return episodes + getEpisodeList(animeId, page + 1)
        } else {
            return episodes
        }
    }

    private fun episodeFromObject(episode: EpisodeDto) = SEpisode.create().apply {
        name = episode.title
        setUrlWithoutDomain("/episodes?slug=${episode.slug}")
        episode_number = episode.number.toFloat()
        date_upload = episode.created_at.toDate()
    }

    // ============================ Video Links =============================
    private val anonFilesExtractor by lazy { AnonFilesExtractor(client) }
    private val sendcmExtractor by lazy { SendcmExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val videoDto = response.parseAs<ResponseDto<VideoDto>>().items.first()
        val links = videoDto.links
        val allLinks = listOf(links.low, links.medium, links.high).flatten()
        val supportedPlayers = listOf("anonfiles", "send")
        val online = links.online?.filterNot { "mega" in it }?.map {
            Video(it, "Player ATC", it, headers)
        } ?: emptyList<Video>()
        return online + allLinks.filter { it.name in supportedPlayers }.parallelMap {
            val playerUrl = LinkBypasser(client, json).bypass(it, videoDto.id)
            if (playerUrl == null) return@parallelMap null
            val quality = when (it.quality) {
                "low" -> "SD"
                "medium" -> "HD"
                "high" -> "FULLHD"
                else -> "SD"
            }
            when (it.name) {
                "anonfiles" -> anonFilesExtractor.videoFromUrl(playerUrl, quality)
                "send" -> sendcmExtractor.videoFromUrl(playerUrl, quality)
                else -> null
            }
        }.filterNotNull()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_VALUES
            entryValues = PREF_PLAYER_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun Response.getAnimeDto(): AnimeDto {
        val responseBody = body.string()
        return try {
            parseAs<AnimeDto>(responseBody)
        } catch (e: Exception) {
            // URL intent handler moment
            parseAs<ResponseDto<AnimeDto>>(responseBody).items.first()
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time
        }.getOrNull() ?: 0L
    }

    private inline fun <reified T> Response.parseAs(preloaded: String? = null): T {
        val responseBody = preloaded ?: use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(player) },
                { it.quality.contains("- $quality") },
            ),
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val HOST_URL = "https://www.animestc.net"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD", "FULLHD")

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_TITLE = "Player preferido"
        private const val PREF_PLAYER_DEFAULT = "AnonFiles"
        private val PREF_PLAYER_VALUES = arrayOf("AnonFiles", "Sendcm", "Player ATC")
    }
}
