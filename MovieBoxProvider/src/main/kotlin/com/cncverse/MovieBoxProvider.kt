package com.cncverse

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import java.security.SecureRandom

class MovieBoxProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    val deviceId = generateDeviceId()

    data class BrandModel(val brand: String, val model: String)

    private val brandModels = mapOf(
        "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
        "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
        "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
        "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
        "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
    )

    fun randomBrandModel(): BrandModel {
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return BrandModel(brand, model)
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value" 
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

    override val mainPage = mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending Series",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular Series",
        "movie/top_rated" to "Top Rated Movies",
        "discover/movie?with_genres=28" to "Action Movies",
        "discover/movie?with_genres=35" to "Comedy Movies",
        "discover/movie?with_genres=27" to "Horror Movies",
        "discover/movie?with_genres=878" to "Sci-Fi Movies",
        "discover/movie?with_genres=16" to "Animation Movies",
        "discover/tv?with_genres=10759" to "Action & Adventure Series",
        "discover/tv?with_genres=10765" to "Sci-Fi & Fantasy Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"
        val baseUrl = "https://api.themoviedb.org/3/${request.data}"
        
        val url = if (baseUrl.contains("?")) {
            "$baseUrl&api_key=$apiKey&page=$page&with_original_language=en"
        } else {
            "$baseUrl?api_key=$apiKey&page=$page&with_original_language=en"
        }

        val response = app.get(url).text
        val root = try {
            mapper.readTree(response)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList())
        }

        val results = root["results"] ?: return newHomePageResponse(emptyList())

        val items = results.mapNotNull { item ->
            val origLang = item["original_language"]?.asText()
            if (origLang != null && origLang != "en") return@mapNotNull null

            val isMovie = item.has("title")
            val title = if (isMovie) item["title"]?.asText() else item["name"]?.asText()
            title ?: return@mapNotNull null

            val posterPath = item["poster_path"]?.asText()
            val posterUrl = if (!posterPath.isNullOrEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else null

            val releaseDate = if (isMovie) item["release_date"]?.asText() else item["first_air_date"]?.asText()
            val year = releaseDate?.take(4) ?: ""

            val type = if (isMovie) TvType.Movie else TvType.TvSeries

            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val packedUrl = "$mainUrl/tmdbsearch?title=$encodedTitle&year=$year&type=${if(isMovie) "movie" else "tv"}"

            newMovieSearchResponse(
                name = title,
                url = packedUrl,
                type = type
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(item["vote_average"]?.asText())
            }
        }

        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    private suspend fun internalSearch(query: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": $page, "perPage": 20, "keyword": "$query"}"""
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url, jsonBody)
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val response = app.post(
            url,
            headers = headers,
            requestBody = requestBody
        )

        val responseBody = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val results = root.get("data")?.get("results") ?: return emptyList()
        val searchList = mutableListOf<SearchResponse>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
                val title = subject["title"]?.asText() ?: continue
                val id = subject["subjectId"]?.asText() ?: continue
                val coverImg = subject["cover"]?.get("url")?.asText()
                val subjectType = subject["subjectType"]?.asInt() ?: 1
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                searchList.add(
                    newMovieSearchResponse(
                    name = title,
                    url = id,
                    type = type
                    ) {
                        this.posterUrl = coverImg
                        this.score = Score.from10(subject["imdbRatingValue"]?.asText())
                    }
                )
            }
        }
        return searchList
    }

    override suspend fun search(query: String,page: Int): SearchResponseList {
        return internalSearch(query, page).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        var id = ""

        if (url.contains("tmdbsearch")) {
            val uri = Uri.parse(url)
            val rawTitle = uri.getQueryParameter("title") ?: ""
            val targetTitle = URLDecoder.decode(rawTitle, "UTF-8")
            
            val searchResults = internalSearch(targetTitle, 1)
            val normTarget = cleanTitle(targetTitle)
            
            var bestMatchUrl: String? = null
            var highestScore = 0
            
            for (res in searchResults) {
                val resTitleClean = cleanTitle(res.name)
                var score = 0
                
                if (resTitleClean == normTarget) {
                    score = 100
                } else if (resTitleClean.startsWith(normTarget)) {
                    score = 80
                } else if (resTitleClean.contains(normTarget)) {
                    score = 50
                } else if (tokenEquals(resTitleClean, normTarget)) {
                    score = 40
                }
                
                if (score > highestScore) {
                    highestScore = score
                    bestMatchUrl = res.url
                }
            }
            
            if (bestMatchUrl == null) {
                throw ErrorLoadingException("Cerita '$targetTitle' belum wujud di server MovieBox.")
            }
            id = bestMatchUrl
        } else {
            id = Regex("""subjectId=([^&]+)""")
                .find(url)
                ?.groupValues?.get(1)
                ?: url.substringAfterLast('/')
        }

        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", finalUrl)

        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; ${randomBrandModel()}; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )

        val response = app.get(finalUrl, headers = headers)
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.body.string()}")
        }

        val body = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(body)
        val data = root["data"] ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title found")
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val duration = data["duration"]?.asText()
        val genre = data["genre"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.substring(0, 4)?.toIntOrNull()

        val coverUrl = data["cover"]?.get("url")?.asText()
        val backgroundUrl = data["cover"]?.get("url")?.asText()

        val subjectType = data["subjectType"]?.asInt() ?: 1

        val actors = data["staffList"]
            ?.mapNotNull { staff ->
                val staffType = staff["staffType"]?.asInt()
                if (staffType == 1) {
                    val name = staff["name"]?.asText() ?: return@mapNotNull null
                    val characterName = staff["character"]?.asText()
                    val avatarUrl = staff["avatarUrl"]?.asText()
                    ActorData(
                        Actor(name, avatarUrl),
                        roleString = characterName
                    )
                } else null
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()

        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        val durationMinutes = duration?.let { dur ->
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val m = regex.find(dur)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                h * 60 + min
            } else dur.replace("m", "").toIntOrNull()
        }

        val type = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            7 -> TvType.TvSeries
            else -> TvType.Movie
        }

        val (tmdbId, imdbId) = identifyID(
            title = title.substringBefore("(").substringBefore("["),
            year = releaseDate?.take(4)?.toIntOrNull(),
            imdbRatingValue = imdbRating?.toDouble(),
        )

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val meta = if (!imdbId.isNullOrBlank()) fetchMetaData(imdbId, type) else null
        val metaVideos = meta?.get("videos")?.toList() ?: emptyList()

        val Poster = meta?.get("poster")?.asText() ?: coverUrl
        val Background = meta?.get("background")?.asText() ?: backgroundUrl
        val Description = meta?.get("overview")?.asText() ?: description
        val IMDBRating = meta?.get("imdbRating")?.asText()

        if (type == TvType.TvSeries) {
            val allSubjectIds = mutableListOf<String>()
            allSubjectIds.add(id)
            data["dubs"]?.forEach {
                val sid = it["subjectId"]?.asText()
                if (!sid.isNullOrBlank() && sid !in allSubjectIds) {
                    allSubjectIds.add(sid)
                }
            }

            val episodeMap = mutableMapOf<Int, MutableSet<Int>>()

            for (subjectId in allSubjectIds) {
                val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                val seasonSig = generateXTrSignature("GET", "application/json", "application/json", seasonUrl)

                val seasonHeaders = headers.toMutableMap().apply {
                    put("x-tr-signature", seasonSig)
                }

                val seasonResponse = app.get(seasonUrl, headers = seasonHeaders)
                if (seasonResponse.code != 200) continue

                val seasonRoot = mapper.readTree(seasonResponse.body.string())
                val seasons = seasonRoot["data"]?.get("seasons")

                if (seasons == null || !seasons.isArray || seasons.size() == 0) {
                    continue
                }

                seasons.forEach { season ->
                    val seasonNumber = season["se"]?.asInt() ?: 1
                    val maxEp = season["maxEp"]?.asInt() ?: 1

                    val epSet = episodeMap.getOrPut(seasonNumber) { mutableSetOf() }

                    for (ep in 1..maxEp) {
                        epSet.add(ep)
                    }
                }
            }

            val episodes = mutableListOf<Episode>()

            episodeMap.forEach { (seasonNumber, epSet) ->
                epSet.sorted().forEach { episodeNumber ->

                    val epMeta = metaVideos.firstOrNull {
                        it["season"]?.asInt() == seasonNumber &&
                                it["episode"]?.asInt() == episodeNumber
                    }

                    val epName = epMeta?.get("name")?.asText()
                        ?: epMeta?.get("title")?.asText()?.takeIf { it.isNotBlank() }
                        ?: "S${seasonNumber}E${episodeNumber}"

                    val epDesc = epMeta?.get("overview")?.asText()
                        ?: epMeta?.get("description")?.asText()
                        ?: "Season $seasonNumber Episode $episodeNumber"

                    val epThumb = epMeta?.get("thumbnail")?.asText()?.takeIf { it.isNotBlank() }
                        ?: coverUrl

                    val runtime = epMeta?.get("runtime")?.asText()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()

                    val aired = epMeta?.get("released")?.asText()
                        ?.takeIf { it.isNotBlank() } ?: ""

                    episodes.add(
                        newEpisode("$id|$seasonNumber|$episodeNumber") {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = runtime
                            addDate(aired)
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$id|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl ?: Poster
                this.backgroundPosterUrl = Background ?: backgroundUrl ?: Poster
                try { this.logoUrl = logoUrl } catch(_: Throwable) {}
                this.plot = Description ?: description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.from10(IMDBRating) ?: imdbRating?.let { Score.from10(it) }
                this.duration = durationMinutes
                addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        }

        return newMovieLoadResponse(title, finalUrl, type, id) {
            this.posterUrl = coverUrl ?: Poster
            this.backgroundPosterUrl = Background ?: backgroundUrl
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.plot = Description ?: description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = Score.from10(IMDBRating) ?:imdbRating?.let { Score.from10(it) }
            this.duration = durationMinutes
            addImdbId(imdbId)
            addTMDbId(tmdbId.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (brand, model) = randomBrandModel()

        try {
            val parts = data.split("|")
            val originalSubjectId = when {
                parts[0].contains("get?subjectId") -> {
                    Regex("""subjectId=([^&]+)""")
                        .find(parts[0])
                        ?.groupValues?.get(1)
                        ?: parts[0].substringAfterLast('/')
                }
                parts[0].contains("/") -> {
                    parts[0].substringAfterLast('/')
                }
                else -> parts[0]
            }

            val season = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val episode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
            val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            val subjectXClientToken = generateXClientToken()
            val subjectXTrSignature = generateXTrSignature("GET", "application/json", "application/json", subjectUrl)
            val subjectHeaders = mapOf(
                "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to subjectXClientToken,
                "x-tr-signature" to subjectXTrSignature,
                "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"$model","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                "x-client-status" to "0"
            )

            val subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
            val mapper = jacksonObjectMapper()
            val subjectIds = mutableListOf<Pair<String, String>>()
            var originalLanguageName = "Original"
            
            if (subjectResponse.code == 200) {
                val subjectResponseBody = subjectResponse.body.string()
                val subjectRoot = mapper.readTree(subjectResponseBody)
                val subjectData = subjectRoot["data"]
                val dubs = subjectData?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubSubjectId = dub["subjectId"]?.asText()
                        val lanName = dub["lanName"]?.asText()
                        if (dubSubjectId != null && lanName != null) {
                            if (dubSubjectId == originalSubjectId) {
                                originalLanguageName = lanName
                            } else {
                                if (lanName.contains("English", ignoreCase = true) || lanName.equals("en", ignoreCase = true)) {
                                    subjectIds.add(Pair(dubSubjectId, lanName))
                                }
                            }
                        }
                    }
                }
            }

            val xUserHeader = subjectResponse.headers["x-user"]
            var token: String? = null

            if (!xUserHeader.isNullOrBlank()) {
                val xUserJson = mapper.readTree(xUserHeader)
                token = xUserJson["token"]?.asText()
            }

            subjectIds.add(0, Pair(originalSubjectId, originalLanguageName))

            for ((subjectId, language) in subjectIds) {
                try {
                    val url = "$mainUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"

                    val xClientToken = generateXClientToken()
                    val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)
                    val headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                        "accept" to "application/json",
                        "content-type" to "application/json",
                        "connection" to "keep-alive",
                        "x-client-token" to xClientToken,
                        "x-tr-signature" to xTrSignature,
                        "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"$model","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                        "x-client-status" to "0"
                    )

                    val response = app.get(url, headers = headers)
                    if (response.code == 200) {
                        val responseBody = response.body.string()
                        val root = mapper.readTree(responseBody)
                        val playData = root["data"]
                        val streams = playData?.get("streams")
                        if (streams != null && streams.isArray) {
                            for (stream in streams) {
                                val streamUrl = stream["url"]?.asText() ?: continue
                                val format = stream["format"]?.asText() ?: ""
                                val resolutions = stream["resolutions"]?.asText() ?: ""
                                val signCookieRaw = stream["signCookie"]?.asText()
                                val signCookie = if (signCookieRaw.isNullOrEmpty()) null else signCookieRaw
                                val streamId = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                                val quality = getHighestQuality(resolutions)
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name ${language.replace("dub","Audio")}",
                                        name = "$name (${language.replace("dub","Audio")})",
                                        url = streamUrl,
                                        type = when {
                                            streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                                            streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                                            streamUrl.substringAfterLast('.', "").equals("torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                                            format.equals("HLS", ignoreCase = true) || streamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                                            streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                                            else -> INFER_TYPE
                                        }
                                    ) {
                                        this.headers = mapOf("Referer" to mainUrl)
                                        if (quality != null) {
                                            this.quality = quality
                                        }
                                        if (signCookie != null) {
                                            this.headers += mapOf("Cookie" to signCookie)
                                        }
                                    }
                                )
                                
                                val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
                                val xClientTokenS = generateXClientToken()
                                val xTrSignatureS = generateXTrSignature("GET", "", "", subLink)
                                val headersS = mapOf(
                                    "Authorization" to "Bearer $token",
                                    "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                                    "Accept" to "",
                                    "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"$model","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                                    "X-Client-Status" to "0",
                                    "Content-Type" to "",
                                    "X-Client-Token" to xClientTokenS,
                                    "x-tr-signature" to xTrSignatureS,
                                )
                                val subResponse = app.get(subLink, headers = headersS)
                                val subRoot = mapper.readTree(subResponse.toString())
                                val extCaptions = subRoot["data"]?.get("extCaptions")
                                if (extCaptions != null && extCaptions.isArray) {
                                    for (caption in extCaptions) {
                                        val captionUrl = caption["url"]?.asText() ?: continue
                                        val lang = caption["language"]?.asText()
                                            ?: caption["lanName"]?.asText()
                                            ?: caption["lan"]?.asText()
                                            ?: "Unknown"
                                        
                                        if (lang.contains("English", ignoreCase = true) || lang.equals("en", ignoreCase = true)) {
                                            subtitleCallback.invoke(
                                                newSubtitleFile(
                                                    url = captionUrl,
                                                    lang = "$lang (${language.replace("dub","Audio")})"
                                                )
                                            )
                                        }
                                    }
                                }

                                val subLink1 = "$mainUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=0"
                                val xClientToken1 = generateXClientToken()
                                val xTrSignature1 = generateXTrSignature("GET", "", "", subLink1)
                                val headers1 = mapOf(
                                    "Authorization" to "Bearer $token",
                                    "User-Agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $brand; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                                    "Accept" to "",
                                    "X-Client-Info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                                    "X-Client-Status" to "0",
                                    "Content-Type" to "",
                                    "X-Client-Token" to xClientToken1,
                                    "x-tr-signature" to xTrSignature1,
                                )
                                val subResponse1 = app.get(subLink1, headers = headers1)

                                val subRoot1 = mapper.readTree(subResponse1.toString())
                                val extCaptions1 = subRoot1["data"]?.get("extCaptions")
                                if (extCaptions1 != null && extCaptions1.isArray) {
                                    for (caption in extCaptions1) {
                                        val captionUrl = caption["url"]?.asText() ?: continue
                                        val lang = caption["lan"]?.asText()
                                            ?: caption["lanName"]?.asText()
                                            ?: caption["language"]?.asText()
                                            ?: "Unknown"
                                        
                                        if (lang.contains("English", ignoreCase = true) || lang.equals("en", ignoreCase = true)) {
                                            subtitleCallback.invoke(
                                                newSubtitleFile(
                                                    url = captionUrl,
                                                    lang = "$lang (${language.replace("dub","Audio")})"
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (streams == null || !streams.isArray || streams.size() == 0) {
                            val fallbackUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                            val fallbackHeaders = headers.toMutableMap().apply {
                                put("x-tr-signature", generateXTrSignature(
                                    "GET",
                                    "application/json",
                                    "application/json",
                                    fallbackUrl
                                ))
                            }

                            val fallbackResponse = app.get(fallbackUrl, headers = fallbackHeaders)

                            if (fallbackResponse.code == 200) {
                                val fallbackRoot = mapper.readTree(fallbackResponse.body.string())
                                val detectors = fallbackRoot["data"]?.get("resourceDetectors")

                                detectors?.forEach { detector ->
                                    detector["resolutionList"]?.forEach { video ->
                                        val link = video["resourceLink"]?.asText() ?: return@forEach
                                        val quality = video["resolution"]?.asInt() ?: 0
                                        val se = video["se"]?.asInt()
                                        val ep = video["ep"]?.asInt()

                                        callback.invoke(
                                            newExtractorLink(
                                                source = "$name ${language.replace("dub","Audio")}",
                                                name = "$name S${se}E${ep} ${quality}p (${language.replace("dub","Audio")})",
                                                url = link,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf("Referer" to mainUrl)
                                                this.quality = quality
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}

fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}

private fun cleanTitle(s: String): String {
    return s.lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}

private suspend fun identifyID(
    title: String,
    year: Int?,
    imdbRatingValue: Double?
): Pair<Int?, String?> {
    val normTitle = normalize(title)
    val res = searchAndPick(normTitle, year, imdbRatingValue)
    if (res.first != null) return res

    return Pair(null, null)
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
): Pair<Int?, String?> {

    suspend fun doSearch(endpoint: String, extraParams: String = ""): org.json.JSONArray? {
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(endpoint)
            append("?api_key=").append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
        }
        val text = app.get(url).text
        return JSONObject(text).optJSONArray("results")
    }

    val multiResults = doSearch("search/multi", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    val searchQueues: List<Pair<String, org.json.JSONArray?>> = listOf(
        "multi" to multiResults,
        "tv" to doSearch("search/tv", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&first_air_date_year=$year" else "")),
        "movie" to doSearch("search/movie", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    )

    var bestId: Int? = null
    var bestScore = -1.0
    var bestIsTv = false

    for ((sourceType, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)

            val mediaType = when (sourceType) {
                "multi" -> o.optString("media_type", "")
                "tv" -> "tv"
                else -> "movie"
            }

            val candidateId = o.optInt("id", -1)
            if (candidateId == -1) continue

            val titles = listOf(
                o.optString("title"),
                o.optString("name"),
                o.optString("original_title"),
                o.optString("original_name")
            ).filter { it.isNotBlank() }

            val candDate = when (mediaType) {
                "tv" -> o.optString("first_air_date", "")
                else -> o.optString("release_date", "")
            }
            val candYear = candDate.take(4).toIntOrNull()
            val candRating = o.optDouble("vote_average", Double.NaN)

            var score = 0.0
            val normClean = cleanTitle(normTitle)

            var titleScore = 0.0
            for (t in titles) {
                val candClean = cleanTitle(t)

                if (tokenEquals(candClean, normClean)) {
                    titleScore = 50.0
                    break
                }

                if (candClean.contains(normClean) || normClean.contains(candClean)) {
                    titleScore = maxOf(titleScore, 20.0)
                }
            }
            score += titleScore

            if (candYear != null && year != null && candYear == year) score += 35.0

            if (imdbRatingValue != null && !candRating.isNaN()) {
                val diff = kotlin.math.abs(candRating - imdbRatingValue)
                if (diff <= 0.5) score += 10.0 else if (diff <= 1.0) score += 5.0
            }

            if (o.has("popularity")) score += (o.optDouble("popularity", 0.0) / 100.0).coerceAtMost(5.0)

            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
                bestIsTv = (mediaType == "tv")
            }
        }
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids"
    val detailText = app.get(detailUrl).text
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
