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

    val builtInProviders = listOf(
        // 1. Animes
        ProviderDef(
            key = "p_animes", displayName = "Animes",
            executeAnime = { res, subCb, cb -> invokeAnimes(res.malId, res.anilistId, res.episode, res.year, "kitsu", subCb, cb) }
        ),
        
        // 2. Kaido
        ProviderDef(
            key = "p_kaido", displayName = "Kaido",
            executeAnime = { res, subCb, cb -> 
                invokeKaido(res.title, res.episode, res.season, subCb, cb) 
            },
            executeMalSync = { data, subCb, cb -> 
                invokeKaido(data.hianimeurl, data.episode, null, subCb, cb) 
            }
        ),
        
        // 3. KissKH
        ProviderDef(
            key = "p_kisskh", displayName = "KissKH",
            executeStandard = { res, subCb, cb -> if (res.isVariety) invokeKisskh(res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
        
        // 4. Vidlink
        ProviderDef(
            key = "p_vidlink", displayName = "Vidlink",
            executeStandard = { res, subCb, cb -> 
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
            },
            executeAnime = { res, subCb, cb -> 
                invokeVidlink(
                    title = res.title,
                    tmdbId = res.tmdbId,
                    imdbId = res.imdbId,
                    year = res.year,
                    season = res.imdbSeason,
                    episode = res.imdbEpisode,
                    subtitleCallback = subCb,
                    callback = cb
                ) 
            }
        ),
        
        // 5. Videasy
        ProviderDef(
            key = "p_videasy", displayName = "Videasy",
            executeStandard = { res, subCb, cb -> invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb) }
        ),
        
        // 6. StremioSubs (Ini enjin yang tarik subtitle Stremio Addon kau tu!)
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
