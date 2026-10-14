package eu.kanade.tachiyomi.extension.zh.mh160

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 漫画160 runs the "qingtiancms" (qTcms) reader: each chapter page embeds a base64 string of
 * image paths separated by `$qingtiandy$`, and the reader JS picks an image host based on the
 * chapter id.
 *
 * Image loading optimizations:
 *  1. Referer is always sent to the image CDN (anti-hotlinking).
 *  2. Short connect timeout so a dead node fails fast.
 *  3. Per-request failover: if a node fails, the same image path is retried on other nodes.
 *  4. Node health memory: a node that failed recently is skipped for a cooldown period,
 *     both when choosing the chapter's host and when failing over.
 */
@Source
abstract class Mh160 : KeiSource() {

    private val mobileUrl: String get() = baseUrl.replace("www.", "m.")

    private val refererValue: String get() = baseUrl.trimEnd('/') + "/"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        connectTimeout(6, TimeUnit.SECONDS)
        readTimeout(20, TimeUnit.SECONDS)
        addInterceptor(ImageFailoverInterceptor())
    }

    private inner class ImageFailoverInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val host = original.url.host

            val isImageHost = host in IMAGE_HOST_NAMES
            if (!isImageHost && host != LEGACY_HOST_NAME) return chain.proceed(original)

            val base = original.newBuilder().header("Referer", refererValue).build()

            // The legacy host has no known mirrors: only add the Referer.
            if (!isImageHost) return chain.proceed(base)

            // Current host first, then the others in random order; recently failed hosts go last.
            val candidates = (listOf(host) + IMAGE_HOST_NAMES.filter { it != host }.shuffled())
                .sortedBy { isCoolingDown(it) } // stable sort keeps the order above within a group
                .take(MAX_ATTEMPTS)

            var lastError: IOException? = null
            for (candidate in candidates) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                val request = base.newBuilder()
                    .url(base.url.newBuilder().host(candidate).build())
                    .build()
                try {
                    val response = chain.proceed(request)
                    if (response.isSuccessful) {
                        FAILED_AT.remove(candidate)
                        return response
                    }
                    val code = response.code
                    response.close()
                    // Only blame the node for errors that look node-related, not plain 404s.
                    if (code >= 500 || code == 403 || code == 408 || code == 429) markFailed(candidate)
                    lastError = IOException("HTTP $code @ $candidate")
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e
                    markFailed(candidate)
                    lastError = e
                }
            }
            throw lastError ?: IOException("所有图片节点均不可用")
        }
    }

    private fun markFailed(host: String) {
        FAILED_AT[host] = System.currentTimeMillis()
    }

    private fun isCoolingDown(host: String): Boolean {
        val failedAt = FAILED_AT[host] ?: return false
        return System.currentTimeMillis() - failedAt < COOLDOWN_MS
    }

    /** Pick a random healthy node; if every node is cooling down, pick any. */
    private fun pickImageHost(): String {
        val healthy = IMAGE_HOST_NAMES.filterNot { isCoolingDown(it) }
        return "https://" + (healthy.ifEmpty { IMAGE_HOST_NAMES }).random()
    }

    // ---------------------------------------------------------------- lists

    override suspend fun getPopularManga(page: Int): MangasPage = parseMobileList("$mobileUrl/kanmanhua/zaixian_hit.html")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMobileList("$mobileUrl/kanmanhua/zaixian_recent.html")

    private suspend fun parseMobileList(url: String): MangasPage {
        val mangas = client.get(url).asJsoup().select(".itemBox").mapNotNull(::mangaFromItemBox)
        return MangasPage(mangas, hasNextPage = false)
    }

    private fun mangaFromItemBox(element: Element): SManga? {
        val link = element.selectFirst(".itemTxt a.title") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.text()
            thumbnail_url = element.selectFirst(".itemImg img")?.absUrl("src")
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("statics/searchelxt1e1.aspx")
                .addQueryParameter("key", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            // Browse the selected category, or fall back to the hot ranking.
            val slug = filters.firstInstanceOrNull<CategoryFilter>()?.selectedSlug
            val path = slug ?: "zaixian_hit.html"
            "$mobileUrl/kanmanhua/$path".toHttpUrl()
        }
        val document = client.get(url).asJsoup()
        return if (query.isNotBlank()) parseMangaList(document) else parseCategoryPage(document)
    }

    // Keyword search results use the site's standard search list.
    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("ul.mh-search-list > li").mapNotNull(::mangaFromElement)
        return MangasPage(mangas, hasNextPage = false)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst(".mh-works-title h4 a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.text()
            thumbnail_url = element.selectFirst(".mh-nlook-w img")?.absUrl("src")
        }
    }

    // Category pages render the first page of results into #listbody.
    private fun parseCategoryPage(document: Document): MangasPage {
        val mangas = document.select("#listbody li").mapNotNull { element ->
            val link = element.selectFirst("a.ImgA") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst("a.txtA")?.text()
                    ?: link.attr("href").trim('/').substringAfterLast('/')
                thumbnail_url = link.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    // ---------------------------------------------------------------- details & chapters

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val match = MANGA_URL_REGEX.find(url.encodedPath) ?: return null
        val mangaUrl = "/kanmanhua/${match.groupValues[1]}/"
        val document = client.get(baseUrl + mangaUrl).asJsoup()
        return parseDetails(document).apply { this.url = mangaUrl }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(
            manga = parseDetails(document),
            chapters = parseChapters(document),
        )
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".mh-date-info-name h4 a")?.text()
            ?: document.selectFirst(".txtItme.h1")?.text()
            ?: document.title().substringBefore("漫画,")
        thumbnail_url = document.selectFirst(".mh-date-bgpic img")?.absUrl("src")
            ?: document.selectFirst("#Cover img")?.absUrl("src")
        author = document.selectFirst(".works-info-tc .one em")?.text()
            ?: document.selectFirst(".sub_r .txtItme:contains(作者)")?.text()?.substringAfter("作者：")?.trim()
        artist = author
        description = document.selectFirst("#workint")?.text()
            ?: document.selectFirst(".detailContent p")?.text()
        status = when (document.selectFirst("p.works-info-tc span:contains(状态) em")?.text()) {
            "连载中" -> SManga.ONGOING
            "已完结", "完结" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("ul[id^=mh-chapter-list-ol] li a, ul[id^=chapterList_ul] li a").map { link ->
        SChapter.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            name = link.selectFirst("p")?.text() ?: link.text()
        }
    }

    // ---------------------------------------------------------------- pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.get(baseUrl + chapter.url).use { it.body.string() }
        val encoded = MURL_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("找不到图片列表，页面结构可能已更改")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.contains("+http://") || decoded.contains("+https://")) {
            throw Exception("该章节已下架")
        }
        val chapterId = PID_REGEX.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        // Match the host selection behavior in the site's show.js, but skip nodes that failed recently.
        val host = if (chapterId > 542724) pickImageHost() else LEGACY_IMAGE_HOST

        return decoded.split(PATH_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapIndexed { index, path ->
                val imageUrl = when {
                    path.startsWith("http") -> path
                    path.startsWith("/") -> host + encodePath(path)
                    else -> "$baseUrl/statics/pic/?p=" + URLEncoder.encode(path, "UTF-8")
                }
                Page(index, imageUrl = imageUrl)
            }
    }

    private fun encodePath(path: String): String = path.split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(CategoryFilter())

    companion object {
        private const val PATH_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val LEGACY_HOST_NAME = "mhpic6.tgmhfc.uk"
        private val IMAGE_HOSTS = listOf(
            "https://mhpic5er.tgmhfc.uk",
            "https://mhpic789-5.tgmhfc.uk",
            "https://mhpic7fr.tgmhfc.uk",
            "https://mhpicwt.tgmhfc.uk",
            "https://mhpicwx.tgmhfc.uk",
        )
        private val IMAGE_HOST_NAMES = IMAGE_HOSTS.map { it.removePrefix("https://") }

        // Failover tuning
        private const val MAX_ATTEMPTS = 3
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private val FAILED_AT = ConcurrentHashMap<String, Long>()

        private val MANGA_URL_REGEX = Regex("""^/kanmanhua/([A-Za-z0-9]+)/?""")
        private val MURL_REGEX = Regex("""qTcms_S_m_murl_e\s*=\s*"([^"]+)"""")
        private val PID_REGEX = Regex("""qTcms_S_p_id\s*=\s*"(\d+)"""")
    }
}
