package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/** Container for data fetched during MALSync requests */
data class MalSyncData(
    val title: String?,
    val zorotitle: String?,
    val hianimeurl: String?,
    val animepaheUrl: String?,
    val aniId: Int?,
    val episode: Int?,
    val year: Int?,
    val origin: String
)

data class ProviderDef(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false,
    val executeStandard: (suspend CineStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeAnime: (suspend CineStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeMalSync: (suspend CineStreamExtractors.(data: MalSyncData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null
)

object ProviderRegistry {

    // MAP UNTUK TUKAR TAJUK KHAS UNTUK KISSKH
    // Masukkan tajuk Simkl (huruf kecil) di sebelah kiri, dan tajuk KissKH di sebelah kanan.
    private val kisskhTitleAliases = mapOf(
        "men on a mission" to "knowing bros",
        "how do you play?" to "hangout with yoo",
        "how do you play" to "hangout with yoo"
        // Anda boleh tambah lagi tajuk-tajuk lain di sini pada masa hadapan (pisahkan dengan koma)
    )

    val builtInProviders = listOf(
        
        // 1. KAIDO (DIUTAMAKAN UNTUK ANIME)
        ProviderDef(
            key = "p_kaido", displayName = "Kaido",
            executeAnime = { res, subCb, cb -> 
                invokeKaido(res.title, res.episode, res.season, subCb, cb) 
            },
            executeMalSync = { data, subCb, cb -> 
                invokeKaido(data.hianimeurl, data.episode, null, subCb, cb) 
            }
        ),

        // 2. VIDLINK (DIUTAMAKAN UNTUK MOVIES & TV SERIES SAHAJA)
        ProviderDef(
            key = "p_vidlink", displayName = "Vidlink",
            executeStandard = { res, subCb, cb -> 
                // Syarat: Bukan Anime DAN bukan rancangan Variety
                if (!res.isAnime && !res.isVariety) {
                    invokeVidlink(
                        title = res.title,
                        tmdbId = res.tmdbId,
                        imdbId = res.imdbId,
                        year = res.year,
                        season = res.season,
                        episode = res.episode,
                        subtitleCallback = subCb,
                        callback = cb
                    ) 
                }
            }
        ),

        // 3. KISSKH (KHUSUS UNTUK VARIETY/REALITY SHOW KOREA)
        ProviderDef(
            key = "p_kisskh", displayName = "KissKH",
            executeStandard = { res, subCb, cb -> 
                // Syarat: Mesti Variety DAN dari Korea
                if (res.isVariety && res.isKorean) { 
                    
                    // LOGIK PENUKARAN TAJUK:
                    // Tukar tajuk asal kepada huruf kecil, check dalam map, kalau ada tukar, kalau tak ada guna tajuk asal.
                    val titleLower = res.title?.lowercase() ?: ""
                    val searchTitle = kisskhTitleAliases[titleLower] ?: res.title
                    
                    invokeKisskh(searchTitle, res.year, res.season, res.episode, subCb, cb) 
                } 
            }
        ),

        // 4. ANIMES (BACKUP UNTUK ANIME)
        ProviderDef(
            key = "p_animes", displayName = "Animes",
            executeAnime = { res, subCb, cb -> invokeAnimes(res.malId, res.anilistId, res.episode, res.year, "kitsu", subCb, cb) }
        ),
        
        // 5. VIDEASY (BACKUP AM UNTUK SEMUA SELAIN ANIME)
        ProviderDef(
            key = "p_videasy", displayName = "Videasy",
            executeStandard = { res, subCb, cb -> 
                if (!res.isAnime) {
                    invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb) 
                }
            }
        ),
        
        // 6. STREMIOSUBS (Enjin sub)
        ProviderDef(
            key = "p_stremiosubs", displayName = "StremioSubs",
            executeStandard = { res, subCb, _ -> invokeStremioSubtitles(res.imdbId, res.season, res.episode, subCb) },
            executeAnime = { res, subCb, _ -> invokeStremioSubtitles(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb) }
        )
    )

    val keys get() = builtInProviders.map { it.key }
    val namesMap get() = builtInProviders.associate { it.key to it.displayName }
    val torrentKeys get() = builtInProviders.filter { it.isTorrent }.map { it.key }.toSet()
}
