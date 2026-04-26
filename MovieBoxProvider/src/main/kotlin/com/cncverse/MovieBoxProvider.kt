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
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

    // ==========================================
    // SISTEM PENAPIS ANIME & PORN
    // ==========================================
    private val blockedKeywords = listOf(
        "anime", "porn", "pornography", "hentai", "jav", "xxx", "sex tape", "kamasutra",
        "ullu", "kooku", "primeshots", "hotshots", "charmsukh", "palang tod", "rabbit movies",
        "papa katsu", "uncensored", "r18", "18+", "erotic", "adult animation", "nympho",
        "stepmom", "stepbrother", "stepsister", "incest", "lust", "seduction", "desire"
    )

    private fun isBlocked(title: String, genre: String? = null): Boolean {
        val textToCheck = (title + " " + (genre ?: "")).lowercase()
        return blockedKeywords.any { textToCheck.contains(it) }
    }
    // ==========================================

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
        "4516404531735022304" to "Trending",
        "1|1;country=United States" to "Movies",
        "1|1;country=Malaysia" to "Trending Malay Movies",
        "1|2;country=United States" to "TV Series",
        "1|2;country=Malaysia" to "Trending Malay Series",
        "1|2;country=Korea" to "K-Drama & TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 15
        val url = if (request.data.contains("|")) "$mainUrl/wefeed-mobile-bff/subject-api/list" else "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=${request.data}&page=$page&perPage=$perPage"

        val data1 = request.data
        val mainParts = data1.substringBefore(";").split("|")
        val pg = mainParts.getOrNull(0)?.toIntOrNull() ?: 1
        val channelId = mainParts.getOrNull(1)

        val options = mutableMapOf<String, String>()
        data1.substringAfter(";", "")
            .split(";")
            .forEach {
                val (k, v) = it.split("=").let { p ->
                    p.getOrNull(0) to p.getOrNull(1)
                }
                if (!k.isNullOrBlank() && !v.isNullOrBlank()) {
                    options[k] = v
                }
            }

        val classify = options["classify"] ?: "All"
        val country  = options["country"] ?: "All"
        val year     = options["year"] ?: "All"
        val genre    = options["genre"] ?: "All"
        val sort     = options["sort"] ?: "ForYou"

        val jsonBody = """{"page":$pg,"perPage":$perPage,"channelId":"$channelId","classify":"$classify","country":"$country","year":"$year","genre":"$genre","sort":"$sort"}"""

        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url , jsonBody)
        val getxTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)

        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_MY; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2" 
        )

        val getheaders = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_MY; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to getxTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":""}""",
            "x-client-status" to "0",
        )

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val response = if (request.data.contains("|")) app.post(url, headers = headers, requestBody = requestBody) else app.get(url, headers = getheaders)

        val responseBody = response.body.string()
        val data = try {
            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(responseBody)
            val items = root["data"]?.get("items") ?: root["data"]?.get("subjects") ?: return newHomePageResponse(emptyList())
            items.mapNotNull { item ->
                val title = item["title"]?.asText()?.substringBefore("[") ?: return@mapNotNull null
                val itemGenre = item["genre"]?.asText() ?: ""
                
                if (isBlocked(title, itemGenre)) return@mapNotNull null

                val id = item["subjectId"]?.asText() ?: return@mapNotNull null
                val coverImg = item["cover"]?.get("url")?.asText()
                val subjectType = item["subjectType"]?.asInt() ?: 1
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                newMovieSearchResponse(
                    name = title,
                    url = id,
                    type = type
                ) {
                    this.posterUrl = coverImg
                    this.score = Score.from10(item["imdbRatingValue"]?.asText())
                }
            }
        } catch (_: Exception) { null } ?: emptyList()

        return newHomePageResponse(listOf(HomePageList(request.name, data)))
    }

    override suspend fun search(query: String,page: Int): SearchResponseList {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": $page, "perPage": 20, "keyword": "$query"}"""
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url, jsonBody)
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_MY; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":""}""",
            "x-client-status" to "0"
        )
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val response = app.post(url, headers = headers, requestBody = requestBody)

        val responseBody = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val results = root.get("data")?.get("results") ?: return newSearchResponseList(emptyList())
        val searchList = mutableListOf<SearchResponse>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
                val title = subject["title"]?.asText() ?: continue
                val itemGenre = subject["genre"]?.asText() ?: ""

                if (isBlocked(title, itemGenre)) continue

                val id = subject["subjectId"]?.asText() ?: continue
                val coverImg = subject["cover"]?.get("url")?.asText()
                val subjectType = subject["subjectType"]?.asInt() ?: 1
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                searchList.add(
                    newMovieSearchResponse(name = title, url = id, type = type) {
                        this.posterUrl = coverImg
                        this.score = Score.from10(subject["imdbRatingValue"]?.asText())
                    }
                )
            }
        }
        return searchList.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""subjectId=([^&]+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')

        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", finalUrl)

        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_MY; ${randomBrandModel()}; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )

        val response = app.get(finalUrl, headers = headers)
        if (response.code != 200) throw ErrorLoadingException("Failed to load data: ${response.body.string()}")

        val body = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(body)
        val data = root["data"] ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title found")
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val genre = data["genre"]?.asText()
        
        if (isBlocked(title, genre)) {
            throw ErrorLoadingException("Kandungan ini disekat.")
        }

        val duration = data["duration"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.substring(0, 4)?.toIntOrNull()

        val coverUrl = data["cover"]?.get("url")?.asText()
        val subjectType = data["subjectType"]?.asInt() ?: 1

        val actors = data["staffList"]?.mapNotNull { staff ->
            val staffType = staff["staffType"]?.asInt()
            if (staffType == 1) {
                val name = staff["name"]?.asText() ?: return@mapNotNull null
                val character = staff["character"]?.asText()
                val avatarUrl = staff["avatarUrl"]?.asText()
                ActorData(Actor(name, avatarUrl), roleString = character)
            } else null
        }?.distinctBy { it.actor.name } ?: emptyList()

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
            2, 7 -> TvType.TvSeries
            else -> TvType.Movie
        }

        // OPTIMIZATION KILAT: Kita buang terus fungsi cari background/logo dekat TMDB dan Stremio.
        // Guna data sedia ada dari API MovieBox supaya kelajuan loading maksimum.
        
        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Tarik season-info HANYA untuk ID utama, tak perlu pusing semua dub
            val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$id"
            val seasonSig = generateXTrSignature("GET", "application/json", "application/json", seasonUrl)
            val seasonHeaders = headers.toMutableMap().apply { put("x-tr-signature", seasonSig) }
            
            val seasonResponse = app.get(seasonUrl, headers = seasonHeaders)
            if (seasonResponse.code == 200) {
                val seasons = mapper.readTree(seasonResponse.body.string())["data"]?.get("seasons")
                if (seasons != null && seasons.isArray) {
                    seasons.forEach { season ->
                        val seasonNumber = season["se"]?.asInt() ?: 1
                        val maxEp = season["maxEp"]?.asInt() ?: 1
                        
                        for (ep in 1..maxEp) {
                            episodes.add(
                                newEpisode("$id|$seasonNumber|$ep") {
                                    this.name = "Episode $ep"
                                    this.season = seasonNumber
                                    this.episode = ep
                                    this.posterUrl = coverUrl
                                }
                            )
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode("$id|1|1") {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = coverUrl
                })
            }

            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl
                this.backgroundPosterUrl = coverUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = imdbRating?.let { Score.from10(it) }
                this.duration = durationMinutes
            }
        }

        return newMovieLoadResponse(title, finalUrl, type, id) {
            this.posterUrl = coverUrl
            this.backgroundPosterUrl = coverUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = imdbRating?.let { Score.from10(it) }
            this.duration = durationMinutes
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
                parts[0].contains("get?subjectId") -> Regex("""subjectId=([^&]+)""").find(parts[0])?.groupValues?.get(1) ?: parts[0].substringAfterLast('/')
                parts[0].contains("/") -> parts[0].substringAfterLast('/')
                else -> parts[0]
            }

            val season = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val episode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
            val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            val subjectXClientToken = generateXClientToken()
            val subjectXTrSignature = generateXTrSignature("GET", "application/json", "application/json", subjectUrl)
            
            val subjectHeaders = mapOf(
                "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_MY; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to subjectXClientToken,
                "x-tr-signature" to subjectXTrSignature,
                "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"$model","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                "x-client-status" to "0"
            )

            val subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
            val mapper = jacksonObjectMapper()
            val subjectIds = mutableListOf<Pair<String, String>>()
            var originalLanguageName = "Original"
            
            if (subjectResponse.code == 200) {
                val subjectResponseBody = subjectResponse.body.string()
                val subjectRoot = mapper.readTree(subjectResponseBody)
                val dubs = subjectRoot["data"]?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubSubjectId = dub["subjectId"]?.asText()
                        val lanName = dub["lanName"]?.asText()
                        if (dubSubjectId != null && lanName != null) {
                            if (dubSubjectId == originalSubjectId) originalLanguageName = lanName
                            else subjectIds.add(Pair(dubSubjectId, lanName))
                        }
                    }
                }
            }

            val token = subjectResponse.headers["x-user"]?.let { mapper.readTree(it)["token"]?.asText() }
            subjectIds.add(0, Pair(originalSubjectId, originalLanguageName))

            coroutineScope {
                subjectIds.map { (subjectId, language) ->
                    async {
                        try {
                            val url = "$mainUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
                            val xClientToken = generateXClientToken()
                            val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)
                            val headers = mapOf(
                                "Authorization" to "Bearer $token",
                                "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_MY; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                                "accept" to "application/json",
                                "content-type" to "application/json",
                                "connection" to "keep-alive",
                                "x-client-token" to xClientToken,
                                "x-tr-signature" to xTrSignature,
                                "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"$model","model":"$brand","system_language":"en","net":"NETWORK_WIFI","region":"MY","timezone":"Asia/Kuala Lumpur","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                                "x-client-status" to "0"
                            )

                            val response = app.get(url, headers = headers)
                            if (response.code == 200) {
                                val root = mapper.readTree(response.body.string())
                                val streams = root["data"]?.get("streams")
                                
                                if (streams != null && streams.isArray && streams.size() > 0) {
                                    coroutineScope {
                                        streams.map { stream ->
                                            async {
                                                val streamUrl = stream["url"]?.asText() ?: return@async
                                                val format = stream["format"]?.asText() ?: ""
                                                val resolutions = stream["resolutions"]?.asText() ?: ""
                                                val signCookie = stream["signCookie"]?.asText()?.takeIf { it.isNotEmpty() }
                                                val id = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                                                val quality = getHighestQuality(resolutions)
                                                
                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = "$name ${language.replace("dub","Audio")}",
                                                        name = "$name (${language.replace("dub","Audio")})",
                                                        url = streamUrl,
                                                        type = when {
                                                            streamUrl.startsWith("magnet:", true) -> ExtractorLinkType.MAGNET
                                                            streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                                                            streamUrl.substringAfterLast('.', "").equals("torrent", true) -> ExtractorLinkType.TORRENT
                                                            format.equals("HLS", true) || streamUrl.substringAfterLast('.', "").equals("m3u8", true) -> ExtractorLinkType.M3U8
                                                            streamUrl.contains(".mp4", true) || streamUrl.contains(".mkv", true) -> ExtractorLinkType.VIDEO
                                                            else -> INFER_TYPE
                                                        }
                                                    ) {
                                                        this.headers = mapOf("Referer" to mainUrl)
                                                        if (quality != null) this.quality = quality
                                                        if (signCookie != null) this.headers += mapOf("Cookie" to signCookie)
                                                    }
                                                )

                                                val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$id"
                                                val subLink1 = "$mainUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$id&episode=0"
                                                
                                                val subHeaders = mapOf("Authorization" to "Bearer $token", "X-Client-Status" to "0", "X-Client-Token" to generateXClientToken(), "x-tr-signature" to generateXTrSignature("GET", "", "", subLink))
                                                val subHeaders1 = mapOf("Authorization" to "Bearer $token", "X-Client-Status" to "0", "X-Client-Token" to generateXClientToken(), "x-tr-signature" to generateXTrSignature("GET", "", "", subLink1))

                                                coroutineScope {
                                                    val s1 = async { runCatching { app.get(subLink, headers = subHeaders) }.getOrNull() }
                                                    val s2 = async { runCatching { app.get(subLink1, headers = subHeaders1) }.getOrNull() }
                                                    
                                                    s1.await()?.let { subResponse ->
                                                        mapper.readTree(subResponse.toString())["data"]?.get("extCaptions")?.forEach { caption ->
                                                            val captionUrl = caption["url"]?.asText() ?: return@forEach
                                                            val lang = caption["language"]?.asText() ?: caption["lanName"]?.asText() ?: caption["lan"]?.asText() ?: "Unknown"
                                                            subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = "$lang (${language.replace("dub","Audio")})"))
                                                        }
                                                    }
                                                    
                                                    s2.await()?.let { subResponse1 ->
                                                        mapper.readTree(subResponse1.toString())["data"]?.get("extCaptions")?.forEach { caption ->
                                                            val captionUrl = caption["url"]?.asText() ?: return@forEach
                                                            val lang = caption["lan"]?.asText() ?: caption["lanName"]?.asText() ?: caption["language"]?.asText() ?: "Unknown"
                                                            subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = "$lang (${language.replace("dub","Audio")})"))
                                                        }
                                                    }
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                } else {
                                    val fallbackUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                                    val fallbackHeaders = headers.toMutableMap().apply { put("x-tr-signature", generateXTrSignature("GET", "application/json", "application/json", fallbackUrl)) }
                                    val fallbackResponse = app.get(fallbackUrl, headers = fallbackHeaders)

                                    if (fallbackResponse.code == 200) {
                                        mapper.readTree(fallbackResponse.body.string())["data"]?.get("resourceDetectors")?.forEach { detector ->
                                            detector["resolutionList"]?.forEach { video ->
                                                val link = video["resourceLink"]?.asText() ?: return@forEach
                                                val quality = video["resolution"]?.asInt() ?: 0
                                                val se = video["se"]?.asInt()
                                                val ep = video["ep"]?.asInt()
                                                callback.invoke(
                                                    newExtractorLink(source = "$name ${language.replace("dub","Audio")}", name = "$name S${se}E${ep} ${quality}p (${language.replace("dub","Audio")})", url = link, type = ExtractorLinkType.VIDEO) {
                                                        this.headers = mapOf("Referer" to mainUrl)
                                                        this.quality = quality
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
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
