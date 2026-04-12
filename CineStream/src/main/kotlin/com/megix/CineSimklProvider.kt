package com.megix

// Cloudstream Core & Utils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Cloudstream Static Helpers
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Cloudstream Sync Providers
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.SyncRepo

// Coroutines
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// JSON Parsing
import com.google.gson.Gson
import org.json.JSONObject

import com.megix.CineStreamExtractors.invokeAllSources
import com.megix.CineStreamExtractors.invokeAllAnimeSources

class CineSimklProvider: MainAPI() {
    override var name = "CloudStream"
    override var mainUrl = "https://simkl.com"
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedSyncNames = setOf(SyncIdName.Simkl)
    
    private val apiUrl = "https://api.simkl.com"
    private val simklDataAPI = "https://data.simkl.in"
    private final val mediaLimit = 10
    
    private val auth = BuildConfig.SIMKL_CLIENT_ID
    private val auth2 = BuildConfig.SIMKL_CLIENT_ID
    
    private val headers = mapOf("Content-Type" to "application/json")
    private val repo = SyncRepo(AccountManager.simklApi)
    private val cinemetaAPI = "https://v3-cinemeta.strem.io"
    private val image_proxy = "https://wsrv.nl/?url="

    override val mainPage = mainPageOf(
        "/discover/trending/movies/today_500.json" to "Trending Movies Today",
        "/discover/trending/tv/today_500.json" to "Trending Shows Today",
        
        "anilist_TRENDING_DESC" to "Trending Anime Today",
        "anilist_POPULARITY_DESC_RELEASING" to "Airing Anime Today",
        
        "/tv/genres/all/all-types/kr/all-networks/this-year/popular-today?limit=$mediaLimit" to "Trending Korean Shows",
        "/tv/genres/all/all-types/kr/all-networks/all-years/rank?limit=$mediaLimit" to "Top Rated Korean Shows",
    )

    private fun getSimklIdAndType(url: String): Pair<String, String> {
        val id =  url.split('/').filter { part -> part.toIntOrNull() != null }.firstOrNull() ?: "" 
        val type = when {
            url.contains("/movies/") -> "movies"
            url.contains("/anime/") -> "anime"
            else -> "tv"
        }
        return Pair(id, type)
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when (status) {
            "airing", "RELEASING" -> ShowStatus.Ongoing
            "ended", "FINISHED" -> ShowStatus.Completed
            else -> null
        }
    }

    private suspend fun extractNameAndYear(imdbId: String? = null): Pair<String?, Int?>? {
        return try {
            if (imdbId.isNullOrBlank()) return null
            val response = app.get("$cinemetaAPI/meta/series/$imdbId.json")
            if (!response.isSuccessful) return null
            val jsonString = response.text
            val metaObject = JSONObject(jsonString).optJSONObject("meta")
            val name = metaObject?.optString("name")?.takeIf { it.isNotBlank() }
            val year = metaObject?.optString("year")?.substringBefore("-")?.toIntOrNull()
                    ?: metaObject?.optString("year")?.substringBefore("–")?.toIntOrNull()
                    ?: metaObject?.optString("year")?.toIntOrNull()
            Pair(name, year)
        } catch (e: Exception) { null }
    }

    private fun getPosterUrl(id: String? = null, type: String): String? {
        val baseUrl = "${image_proxy}https://simkl.in"
        if(id == null) return null
        return when (type) {
            "imdb:lg" -> "${image_proxy}https://live.metahub.space/logo/medium/$id/img"
            "episode" -> "$baseUrl/episodes/${id}_w.webp"
            "poster" -> "$baseUrl/posters/${id}_m.webp"
            "imdb:bg" -> "${image_proxy}https://images.metahub.space/background/large/$id/img"
            "youtube" -> "https://img.youtube.com/vi/${id}/maxresdefault.jpg"
            else -> "$baseUrl/fanart/${id}_medium.webp"
        }
    }

    private suspend fun fetchAniListGraphQL(query: String, variables: Map<String, Any>): JSONObject? {
        return try {
            val response = app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                json = mapOf("query" to query, "variables" to variables)
            ).text
            JSONObject(response).optJSONObject("data")
        } catch (e: Exception) { null }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
        suspend fun fetchSimkl(type: String): List<SearchResponse> {
            return runCatching {
                val json = app.get("$apiUrl/search/$type?q=$query&page=$page&limit=$mediaLimit&extended=full&client_id=$auth", headers = headers).text
                parseJson<Array<SimklResponse>>(json).mapNotNull {
                    val allratings = it.ratings
                    val score = allratings?.mal?.rating ?: allratings?.imdb?.rating
                    val title = it.title_en ?: it.title ?: return@mapNotNull null
                    newMovieSearchResponse(title, "${mainUrl}${it.url}") {
                        posterUrl = getPosterUrl(it.poster, "poster")
                        this.score = Score.from10(score)
                    }
                }
            }.getOrDefault(emptyList())
        }

        suspend fun fetchAniListAnime(): List<SearchResponse> {
            val gqlQuery = """
                query(${'$'}search: String, ${'$'}page: Int) {
                    Page(page: ${'$'}page, perPage: $mediaLimit) {
                        media(type: ANIME, search: ${'$'}search) {
                            id
                            title { romaji english userPreferred }
                            coverImage { extraLarge large }
                            averageRating
                        }
                    }
                }
            """.trimIndent()
            
            val data = fetchAniListGraphQL(gqlQuery, mapOf("search" to query, "page" to page)) ?: return emptyList()
            val mediaList = data.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
            
            val list = mutableListOf<SearchResponse>()
            for (i in 0 until mediaList.length()) {
                val item = mediaList.getJSONObject(i)
                val id = item.getInt("id")
                val titles = item.getJSONObject("title")
                val title = titles.optString("english").takeIf { it.isNotBlank() } ?: titles.optString("romaji").takeIf { it.isNotBlank() } ?: titles.optString("userPreferred")
                val poster = item.optJSONObject("coverImage")?.optString("extraLarge") ?: item.optJSONObject("coverImage")?.optString("large")
                val rating = item.optInt("averageRating", 0).toDouble() / 10.0
                
                list.add(newAnimeSearchResponse(title, "https://anilist.co/anime/$id") {
                    this.posterUrl = poster
                    if (rating > 0) this.score = Score.from10(rating)
                })
            }
            return list
        }

        val types = listOf("movie", "tv", "anime") 
        val resultsLists = types.map { async { fetchSimkl(it) } }.toMutableList()
        resultsLists.add(async { fetchAniListAnime() }) 
        
        val awaitedLists = resultsLists.awaitAll()
        val maxSize = awaitedLists.maxOfOrNull { it.size } ?: 0

        val combinedList: List<SearchResponse> = buildList {
            for (i in 0 until maxSize) {
                for (list in awaitedLists) {
                    if (i < list.size) add(list[i])
                }
            }
        }
        newSearchResponseList(combinedList, true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
        if (request.name.contains("Personal")) {
            repo.authUser() ?: return@coroutineScope newHomePageResponse("Login required for personal content.", emptyList<SearchResponse>(), false)
            val homePageList = repo.library().getOrThrow()?.allLibraryLists?.mapNotNull {
                if (it.items.isEmpty()) return@mapNotNull null
                val libraryName = it.name.asString(activity ?: return@mapNotNull null)
                HomePageList("${request.name}: $libraryName", it.items)
            } ?: return@coroutineScope null
            return@coroutineScope newHomePageResponse(homePageList, false)
        } 
        else if (request.data.startsWith("anilist_")) {
            val parts = request.data.split("_")
            val sortParam = parts[1] + "_" + parts[2]
            val statusParam = parts.getOrNull(3)
            val statusFilter = if (statusParam != null) ", status: $statusParam" else ""
            
            val gqlQuery = """
                query(${'$'}page: Int) {
                    Page(page: ${'$'}page, perPage: 20) {
                        pageInfo { hasNextPage }
                        media(type: ANIME, sort: [$sortParam]$statusFilter) {
                            id
                            title { romaji english userPreferred }
                            coverImage { extraLarge large }
                        }
                    }
                }
            """.trimIndent()
            
            val data = fetchAniListGraphQL(gqlQuery, mapOf("page" to page)) ?: return@coroutineScope null
            val pageInfo = data.optJSONObject("Page")?.optJSONObject("pageInfo")
            val mediaList = data.optJSONObject("Page")?.optJSONArray("media") ?: return@coroutineScope null
            
            val list = mutableListOf<SearchResponse>()
            for (i in 0 until mediaList.length()) {
                val item = mediaList.getJSONObject(i)
                val id = item.getInt("id")
                val titles = item.getJSONObject("title")
                val title = titles.optString("english").takeIf { it.isNotBlank() } ?: titles.optString("romaji").takeIf { it.isNotBlank() } ?: titles.optString("userPreferred")
                val poster = item.optJSONObject("coverImage")?.optString("extraLarge") ?: item.optJSONObject("coverImage")?.optString("large")
                
                list.add(newAnimeSearchResponse(title, "https://anilist.co/anime/$id") {
                    this.posterUrl = poster
                })
            }

            return@coroutineScope newHomePageResponse(
                list = HomePageList(name = request.name, list = list),
                hasNext = pageInfo?.optBoolean("hasNextPage") ?: false
            )
        } 
        else {
            val url = if(request.data.contains(".json")) simklDataAPI + request.data else apiUrl + request.data + "&client_id=$auth&page=$page"
             val data = app.get(url, headers = headers).parsedSafe<Array<SimklResponse>>()?.mapNotNull {
                    val allratings = it.ratings
                    val score = allratings?.mal?.rating ?: allratings?.imdb?.rating
                    val title = it.title ?: return@mapNotNull null
                    newMovieSearchResponse(title, "${mainUrl}${it.url?.replace("movie", "movies")}") {
                        this.posterUrl = getPosterUrl(it.poster, "poster")
                        this.score = Score.from10(score)
                    }
                } ?: return@coroutineScope null
            return@coroutineScope newHomePageResponse(list = HomePageList(name = request.name, list = data), hasNext = if(request.data.contains("limit=")) true else false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        
        if (url.startsWith("https://anilist.co")) {
            val id = url.substringAfterLast("/").toIntOrNull() ?: return newAnimeLoadResponse("Unknown", url, TvType.Anime)
            
            val gqlQuery = """
                query(${'$'}id: Int) {
                    Media(id: ${'$'}id) {
                        idMal
                        title { romaji english userPreferred }
                        description(asHtml: true)
                        coverImage { extraLarge }
                        bannerImage
                        seasonYear
                        status
                        episodes
                        nextAiringEpisode { episode }
                    }
                }
            """.trimIndent()
            
            val data = fetchAniListGraphQL(gqlQuery, mapOf("id" to id))?.optJSONObject("Media") ?: return newAnimeLoadResponse("Unknown", url, TvType.Anime)
            
            val titles = data.getJSONObject("title")
            val title = titles.optString("english").takeIf { it.isNotBlank() } ?: titles.optString("romaji").takeIf { it.isNotBlank() } ?: titles.optString("userPreferred")
            val originalTitle = titles.optString("romaji")
            val plot = data.optString("description")
            val poster = data.optJSONObject("coverImage")?.optString("extraLarge")
            val bgPoster = data.optString("bannerImage")
            val year = data.optInt("seasonYear", 0).takeIf { it > 0 }
            val status = data.optString("status")
            val malId = data.optInt("idMal", 0).takeIf { it > 0 }
            
            val showStatus = when(status) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            
            val episodes = mutableListOf<Episode>()
            var simklEpsFetched = false

            try {
                val lookupRes = app.get("$apiUrl/search/id?anilist=$id&client_id=$auth", headers = headers).text
                val lookupData = parseJson<Array<SimklResponse>>(lookupRes)
                val simklIdFound = lookupData.firstOrNull()?.ids?.simkl

                if (simklIdFound != null) {
                    val epsJson = app.get("$apiUrl/tv/episodes/$simklIdFound?client_id=$auth2&extended=full", headers = headers).text
                    val epsData = parseJson<Array<Episodes>>(epsJson)
                    
                    epsData.filter { it.type != "special" }.forEach { ep ->
                        val epNum = ep.episode ?: return@forEach
                        episodes.add(
                            newEpisode(
                                LoadLinksData(title = title, original_title = originalTitle, tvtype = "anime", anilistId = id, malId = malId, episode = epNum, season = 1, year = year, isAnime = true).toJson()
                            ) {
                                this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                                this.episode = epNum 
                                this.season = 1
                                this.description = ep.description
                                this.posterUrl = getPosterUrl(ep.img, "episode")
                                addDate(ep.date, "yyyy-MM-dd'T'HH:mm:ss")
                            }
                        )
                    }
                    if (episodes.isNotEmpty()) simklEpsFetched = true
                }
            } catch(e: Exception) {}

            if (!simklEpsFetched) {
                val nextEp = data.optJSONObject("nextAiringEpisode")?.optInt("episode", 0) ?: 0
                val totalEps = data.optInt("episodes", 0)
                
                val safeCount = when {
                    totalEps > 0 -> totalEps
                    nextEp > 0 -> nextEp - 1 
                    else -> 24
                }
                
                for (i in 1..safeCount) {
                    episodes.add(
                        newEpisode(
                            LoadLinksData(title = title, original_title = originalTitle, tvtype = "anime", anilistId = id, malId = malId, episode = i, season = 1, year = year, isAnime = true).toJson()
                        ) {
                            this.name = "Episode $i"
                            this.episode = i
                            this.season = 1
                        }
                    )
                }
            } else {
                episodes.sortBy { it.episode }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster 
                this.backgroundPosterUrl = bgPoster
                this.plot = plot
                this.year = year
                this.showStatus = showStatus
                this.comingSoon = status == "NOT_YET_RELEASED"
                addEpisodes(DubStatus.Subbed, episodes)
                addAniListId(id)
                malId?.let { addMalId(it) }
            }
        }

        val (simklId, simklType) = getSimklIdAndType(url)
        var res = app.get("$apiUrl/$simklType/$simklId?client_id=$auth2&extended=full", headers = headers, allowRedirects = false)
        if(res.code in 300..399) {
            var location = res.headers["Location"] ?: res.headers["location"]
            if(location != null) {
                if(!location.contains("extended=full")) location += "&extended=full"
                res = app.get(fixUrl(location, apiUrl), headers = headers)
            }
        }

        val jsonString = res.text
        val json = tryParseJson<SimklResponse>(jsonString) ?: return newMovieLoadResponse("Not Found", url, TvType.Movie, "") {}
        
        val genres = json.genres?.map { it }
        val tvType = json.type.orEmpty()
        val country = json.country.orEmpty()
        val isAnime = tvType == "anime"
        val isBollywood = country == "IN"
        val isCartoon = genres?.contains("Animation") == true
        val isAsian = !isAnime && country in listOf("JP", "KR", "CN")
        
        // ===============================================
        // RADAR VARIETY: Tapis Genre Running Man / Game Show
        // ===============================================
        val isVariety = genres?.any { it.contains("Reality", true) || it.contains("Game Show", true) || it.contains("Variety", true) || it.contains("Talk", true) } == true
        
        val ids = json.ids
        val allRatings = json.ratings
        val rating = allRatings?.mal?.rating ?: allRatings?.imdb?.rating
        val anilistId = ids?.anilist?.toIntOrNull()
        val malId = ids?.mal?.toIntOrNull()
        val tmdbId = ids?.tmdb?.toIntOrNull()
        val imdbId = ids?.imdb

        return coroutineScope {
            val imdbType = if (tvType == "show" || json.anime_type?.equals("tv") == true) "series" else tvType
            
            val anilistMetaDeferred = async { anilistId?.let { getAniListInfo(it) } }
            val tvdbDataDeferred = async { if(!isAnime) getTvdbData(imdbType, imdbId) else null }
            val epsDeferred = async {
                if (tvType != "movie" && !(tvType == "anime" && json.anime_type?.equals("movie") == true)) {
                    val epsResponse = app.get("$apiUrl/tv/episodes/$simklId?client_id=$auth2&extended=full", headers = headers)
                    tryParseJson<Array<Episodes>>(epsResponse.text) ?: emptyArray()
                } else {
                    emptyArray()
                }
            }

            val anilist_meta = anilistMetaDeferred.await()
            val tvdbData = tvdbDataDeferred.await()
            val eps = epsDeferred.await()

            val fallbackFromUrl = url.trimEnd('/').substringAfterLast("/").replace("-", " ").split(" ").joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
            val finalFallback = fallbackFromUrl.takeIf { it.isNotBlank() && it.toIntOrNull() == null } ?: "Unknown Title"
            val originalTitle = anilist_meta?.romajiTitle ?: json.title ?: json.title_en ?: finalFallback
            val enTitle = anilist_meta?.title ?: json.title_en ?: json.en_title ?: json.title ?: finalFallback

            val plot = if (tvType == "anime") {
                val altTitles = listOfNotNull(anilist_meta?.title, json.en_title, json.title).filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "[${"Alt Titles".toSansSerifBold()}: ", postfix = "]")
                val description = anilist_meta?.description?.takeIf { it.isNotBlank() } ?: json.overview
                when {
                    altTitles != null && !description.isNullOrBlank() -> "$altTitles<br><br>$description"
                    altTitles != null -> altTitles
                    else -> description ?: ""
                }
            } else { json.overview }

            val logo = imdbId?.let { getPosterUrl(it, "imdb:lg") }
            val firstTrailerId = json.trailers?.firstOrNull()?.youtube
            val trailerLink = firstTrailerId?.let { "https://www.youtube.com/watch?v=$it" }

            val backgroundPosterUrl = tvdbData?.background ?: checkPosterAvailable(getPosterUrl(imdbId, "imdb:bg")) ?: anilist_meta?.banner ?: getPosterUrl(json.fanart, "fanart") ?: getPosterUrl(firstTrailerId, "youtube")
            val poster = tvdbData?.poster ?: getPosterUrl(json.poster, "poster")

            val recommendations = buildList {
                json.relations?.forEach {
                    val prefix = it.relation_type?.replaceFirstChar { c -> c.uppercase() }?.let { "($it) " } ?: ""
                    add(newMovieSearchResponse("${prefix} ${it.en_title ?: it.title}", "$mainUrl/$tvType/${it.ids.simkl}/${it.ids.slug}") { this.posterUrl = getPosterUrl(it.poster, "poster") })
                }
                json.users_recommendations?.forEach {
                    add(newMovieSearchResponse(it.en_title ?: it.title ?: "", "$mainUrl/$tvType/${it.ids.simkl}/${it.ids.slug}") { this.posterUrl = getPosterUrl(it.poster, "poster") })
                }
            }

            val duration = json.runtimeInMinutes?.let { rt -> json.total_episodes?.let { epsCount -> rt * epsCount } ?: rt }

            if (tvType == "movie" || (tvType == "anime" && json.anime_type?.equals("movie") == true)) {
                val data = LoadLinksData(enTitle, originalTitle, tvType, simklId.toIntOrNull(), imdbId, tmdbId, json.year, anilistId, malId, null, null, null, null, isAnime, isBollywood, isAsian, isCartoon, null, null, isVariety).toJson()
                newMovieLoadResponse(enTitle, url, if(isAnime) TvType.AnimeMovie  else TvType.Movie, data) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgroundPosterUrl
                    this.plot = plot
                    this.tags = genres
                    this.comingSoon = isUpcoming(json.released)
                    this.duration = duration
                    this.score = Score.from10(rating)
                    this.year = json.year
                    this.actors = tvdbData?.cast
                    try { this.logoUrl = logo} catch(_:Throwable){}
                    this.recommendations = recommendations
                    this.contentRating = json.certification
                    this.addSimklId(simklId.toInt())
                    this.addAniListId(anilistId)
                    this.addMalId(malId)
                    this.addTrailer(trailerLink)
                }
            } else {
                val episodes = eps.filter { it.type != "special" }.map {
                    newEpisode(LoadLinksData(enTitle, originalTitle, tvType, simklId.toIntOrNull(), imdbId, tmdbId, json.year, anilistId, malId, null, it.season, it.episode, it.date.toString().substringBefore("-").toIntOrNull(), isAnime, isBollywood, isAsian, isCartoon, it.tvdb?.season ?: json.season?.toIntOrNull(), it.tvdb?.episode, isVariety).toJson()) {
                        this.name = it.title + if(it.aired == false) " • [UPCOMING]" else ""
                        this.season = it.season
                        this.episode = it.episode
                        this.description = it.description
                        this.posterUrl = getPosterUrl(it.img, "episode") ?: "https://github.com/SaurabhKaperwan/Utils/raw/refs/heads/main/missing_thumbnail.png"
                        addDate(it.date, "yyyy-MM-dd'T'HH:mm:ss")
                    }
                }
                newAnimeLoadResponse(enTitle, url, if(tvType == "anime") TvType.Anime else TvType.TvSeries) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgroundPosterUrl
                    this.plot = plot
                    this.tags = genres
                    this.duration = duration
                    this.score = Score.from10(rating)
                    this.year = json.year
                    try { this.logoUrl = logo} catch(_:Throwable){}
                    this.actors = tvdbData?.cast
                    this.showStatus = getStatus(json.status)
                    this.recommendations = recommendations
                    this.contentRating = json.certification
                    this.addSimklId(simklId.toInt())
                    this.addAniListId(anilistId)
                    this.addMalId(malId)
                    this.addTrailer(trailerLink)
                }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = parseJson<LoadLinksData>(data)
        if(res.isAnime) {
            val (imdbTitle, imdbYear) = try { extractNameAndYear(res.imdbId) ?: Pair(res.title, res.year) } catch (e: Exception) { Pair(res.title, res.year) }
            invokeAllAnimeSources(AllLoadLinksData(res.title, res.imdbId, res.tmdbId, res.anilistId, res.malId, null, res.year, res.airedYear, res.season, res.episode, res.isAnime, res.isBollywood, res.isAsian, res.isCartoon, res.original_title, imdbTitle, res.imdbSeason, res.imdbEpisode, imdbYear, isVariety = res.isVariety), subtitleCallback, callback)
        } else {
            invokeAllSources(AllLoadLinksData(res.title, res.imdbId, res.tmdbId, res.anilistId, res.malId, null, res.year, res.airedYear, res.season, res.episode, res.isAnime, res.isBollywood, res.isAsian, res.isCartoon, res.original_title, null, null, null, null, isVariety = res.isVariety), subtitleCallback, callback)
        }
        return true
    }

    data class SimklResponse(var title: String? = null, var en_title: String? = null, var title_en: String? = null, var year: Int? = null, var released: String? = null, var type: String? = null, var url: String? = null, var poster: String? = null, var fanart: String? = null, var ids: Ids? = Ids(), var release_date: String? = null, var ratings: Ratings? = Ratings(), var country: String? = null, var certification: String? = null, var runtime: Any? = null, var status: String? = null, var total_episodes: Int? = null, var network: String? = null, var overview: String? = null, var anime_type: String? = null, var season: String? = null, var endpoint_type: String? = null, var genres: ArrayList<String>? = null, var users_recommendations: ArrayList<UsersRecommendations>? = null, var relations: ArrayList<Relations>? = null, var trailers: ArrayList<Trailers>? = null) { val runtimeInMinutes: Int? get() = runtime?.toString()?.filter { it.isDigit() }?.toIntOrNull() }
    data class Trailers(var name: String? = null, var youtube: String? = null)
    data class Ids(var simkl_id: Int? = null, var tmdb: String? = null, var imdb: String? = null, var slug: String? = null, var mal: String? = null, var anilist: String? = null, var kitsu: String? = null, var anidb: String? = null, var simkl: Int? = null, var tvdb: String? = null)
    data class Ratings(var simkl: Simkl? = Simkl(), var imdb: Imdb? = Imdb(), var mal: Mal? = Mal())
    data class Simkl(var rating: Double? = null, var votes: Int? = null)
    data class Imdb(var rating: Double? = null, var votes: Int? = null)
    data class Mal(var rating: Double? = null, var votes: Int? = null)
    data class UsersRecommendations(var title: String? = null, var en_title: String? = null, var year: Int? = null, var poster: String? = null, var type: String? = null, var ids: Ids = Ids())
    data class Relations(var title: String? = null, var en_title: String? = null, var poster: String? = null, var anime_type: String? = null, var relation_type: String? = null, var ids: Ids = Ids())
    data class Episodes(var title: String? = null, var season: Int? = null, var episode: Int? = null, var type: String? = null, var description: String? = null, var aired: Boolean = false, var img: String? = null, var date: String? = null, var tvdb: Tvdb? = Tvdb())
    data class Tvdb(var season: Int? = null, var episode: Int? = null)
    
    // TAMBAH: val isVariety kat belakang sekali
    data class LoadLinksData(val title: String? = null, val original_title: String? = null, val tvtype: String? = null, val simklId: Int? = null, val imdbId: String? = null, val tmdbId: Int? = null, val year: Int? = null, val anilistId: Int? = null, val malId: Int? = null, val kitsuId: String? = null, val season: Int? = null, val episode: Int? = null, val airedYear: Int? = null, val isAnime: Boolean = false, val isBollywood: Boolean = false, val isAsian: Boolean = false, val isCartoon: Boolean = false, val imdbSeason: Int? = null, val imdbEpisode: Int? = null, val isVariety: Boolean = false)
}
