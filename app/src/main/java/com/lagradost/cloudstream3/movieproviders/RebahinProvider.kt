package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI
import java.util.ArrayList

class RebahinProvider : MainAPI() {
    override var mainUrl = "http://167.88.14.149"
    override var name = "Rebahin"
    override val hasMainPage = true
    override val lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(): HomePageResponse {
        val urls = listOf(
            Pair("Featured", "xtab1"),
            Pair("Film Terbaru", "xtab2"),
            Pair("Romance", "xtab3"),
            Pair("Drama", "xtab4"),
            Pair("Action", "xtab5"),
            Pair("Scifi", "xtab6"),
            Pair("Tv Series Terbaru", "stab1"),
            Pair("Anime Series", "stab2"),
            Pair("Drakor Series", "stab3"),
            Pair("West Series", "stab4"),
            Pair("China Series", "stab5"),
            Pair("Japan Series", "stab6"),
        )

        val items = ArrayList<HomePageList>()

        for ((header, tab) in urls) {
            try {
                val home =
                    app.get("$mainUrl/wp-content/themes/indoxxi/ajax-top-$tab.php").document.select(
                        "div.ml-item"
                    ).map {
                        it.toSearchResult()
                    }
                items.add(HomePageList(header, home))
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("span.mli-info > h2")!!.text().trim()
        val href = this.selectFirst("a")!!.attr("href")
        val type =
            if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = this.select("img").attr("src")
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl =
                this.select("img").attr("src").ifEmpty { this.select("img").attr("data-original") }
            val episode =
                this.select("div.mli-eps > span").text().replace(Regex("[^0-9]"), "").toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = false, subExist = true, subEpisodes = episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select("div.ml-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h3[itemprop=name]")!!.ownText().trim()
        val poster = document.select(".mvic-desc > div.thumb.mvic-thumb").attr("style")
            .substringAfter("url(").substringBeforeLast(")")
        val tags = document.select("span[itemprop=genre]").map { it.text() }

        val year = Regex("([0-9]{4}?)-").find(
            document.selectFirst(".mvici-right > p:nth-child(3)")!!.ownText().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toRatingInt()
        val duration = document.selectFirst(".mvici-right > p:nth-child(1)")!!
            .ownText().replace(Regex("[^0-9]"), "").toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map {
            ActorData(
                Actor(
                    it.select("span").text()
                )
            )
        }

        return if (tvType == TvType.TvSeries) {
            val baseLink = document.select("div#mv-info > a").attr("href")
            val episodes = app.get(baseLink).document.select("div#list-eps > a").map {
                val name = it.text().replace(Regex("Server\\s?\\d"), "").trim()
                name
            }.distinct().map {
                val name = it
//                val epNum = Regex("[^r|R]\\s(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                val epNum = it.replace(Regex("[^0-9]"), "").toIntOrNull()
                val link = "$baseLink?ep=$epNum"
                newEpisode(link) {
                    this.name = name
                    this.episode = epNum
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
            }
        } else {
            val episodes = document.select("div#mv-info > a").attr("href")
            newMovieLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
            }
        }
    }

    private data class ResponseLocal(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("type") val type: String?
    )

    private data class Tracks(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    private suspend fun invokeLokalSource(
        url: String,
        name: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(
            url,
            allowRedirects = false,
            referer = mainUrl,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        ).document

        document.select("script").map { script ->
            if (script.data().contains("sources: [")) {
                val source = tryParseJson<ResponseLocal>(
                    script.data().substringAfter("sources: [").substringBefore("],"))
                M3u8Helper.generateM3u8(
                    name,
                    source!!.file,
                    "http://172.96.161.72",
                ).forEach(sourceCallback)

                val trackJson = script.data().substringAfter("tracks: [").substringBefore("],")
                val track = tryParseJson<List<Tracks>>("[$trackJson]")
                track?.map {
                    subCallback(
                        SubtitleFile(
                            "Indonesian",
                            (if (it.file.contains(".srt")) it.file else null)!!
                        )
                    )
                }
            }
        }
    }

    private data class Captions(
        @JsonProperty("id") val id: String,
        @JsonProperty("hash") val hash: String,
        @JsonProperty("language") val language: String,
    )

    private data class Data(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
    )

    private data class Player(
        @JsonProperty("poster_file") val poster_file: String,
    )

    private data class ResponseKotakAjair(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("player") val player: Player,
        @JsonProperty("data") val data: List<Data>?,
        @JsonProperty("captions") val captions: List<Captions>?
    )

    private suspend fun invokeKotakAjairSource(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val domainUrl = "https://kotakajair.xyz"
        val id = url.trimEnd('/').split("/").last()
        val sources = app.post(
            url = "$domainUrl/api/source/$id",
            data = mapOf("r" to mainUrl, "d" to URI(url).host)
        ).parsed<ResponseKotakAjair>()

        sources.data?.map {
            sourceCallback.invoke(
                ExtractorLink(
                    name,
                    "KotakAjair",
                    fixUrl(it.file),
                    referer = url,
                    quality = getQualityFromName(it.label)
                )
            )
        }
        val userData = sources.player.poster_file.split("/")[2]
        sources.captions?.map {
            subCallback(
                SubtitleFile(
                    if (it.language.lowercase().contains("eng")) it.language else "Indonesian",
                    "$domainUrl/asset/userdata/$userData/caption/${it.hash}/${it.id}.srt"
                )
            )
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sources = if (data.contains("/play")) {
            app.get(data).document.select(".server-wrapper").mapNotNull {
                val iframeLink =
                    "${mainUrl}/iembed/?source=${it.selectFirst("div.server")?.attr("data-iframe")}"
                app.get(iframeLink).document.select("iframe").attr("src")
            }
        } else {
            val fixData = Regex("(.*?)\\?ep").find(data)?.groupValues?.get(1).toString()
            val document = app.get(fixData).document
            val ep = Regex("\\?ep=([0-9]+)").find(data)?.groupValues?.get(1).toString()
            val title = document.selectFirst("div#list-eps > a")?.text()?.replace(Regex("[\\d]"), "")
                    ?.trim()?.replace("Server", "")?.trim()
            document.select("div#list-eps > a:matches(${title}\\s?${ep}$)").mapNotNull {
                val iframeLink = "${mainUrl}/iembed/?source=${it.attr("data-iframe")}"
                app.get(iframeLink).document.select("iframe")
                    .attr("src")
            }
        }

        sources.apmap {
            safeApiCall {
                when {
                    it.startsWith("http://172.96.161.72") -> invokeLokalSource(
                        it,
                        this.name,
                        subtitleCallback,
                        callback
                    )
                    it.startsWith("https://kotakajair.xyz") -> invokeKotakAjairSource(
                        it,
                        subtitleCallback,
                        callback
                    )
                    else -> {
                        loadExtractor(it, data, callback)
                        if (it.startsWith("https://sbfull.com")) {
                            val response = app.get(
                                it, interceptor = WebViewResolver(
                                    Regex("""\.srt""")
                                )
                            )
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    "Indonesian",
                                    response.url
                                )
                            )
                        }
                    }
                }
            }
        }

        return true
    }

}

