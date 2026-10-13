package ac.amalfi.cloudsound

import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.CaptionSpec
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedIPC
import ac.mdiq.podcini.shared.HTTPAttribs
import ac.mdiq.podcini.shared.MediaSpecs
import ac.mdiq.podcini.shared.USER_AGENT
import ac.mdiq.podcini.sources.Provider
import ac.roma.npeconnector.FeedBuilder
import ac.roma.npeconnector.InfoCache
import ac.roma.npeconnector.getSortedVStreams
import ac.roma.npeconnector.toAudioSpec
import ac.roma.npeconnector.toEpisodeIPC
import android.util.Log
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

class CloudSoundProvider : Provider.Stub() {
    private val serviceId = 1

    private val npService by lazy { NewPipe.getService(serviceId) }

    private val CACHE: InfoCache = InfoCache.instance

    override fun httpAtribsFor(url: String): HTTPAttribs {
        return HTTPAttribs(USER_AGENT, "", "")
    }

    override fun canHandleUrl(url_: String): Int {
//        Log.d("canHandleUrl", "url: $url_")
        return if (url_.contains("soundcloud.com")) 1 else -1
    }

    override fun buildEpisode(url: String): EpisodeIPC? {
        return getStreamInfo(url)?.toEpisodeIPC(isAudio = true)
    }

    override fun getEpisodeDescription(url: String): String? {
        return getStreamInfo(url)?.description?.content
    }

    override fun getCaptionSpecs(media: EpisodeIPC): List<CaptionSpec> {
        return listOf()
    }

    override fun getMediaSpecs(media: EpisodeIPC, force: Boolean): MediaSpecs {
        val mediaSpecs = MediaSpecs()
        val streamInfo = getStreamInfo(media.downloadUrl, force) ?: return mediaSpecs
        var aSpecs = listOf<AudioSpec>()
        val audioStreams = streamInfo.audioStreams
        if (audioStreams != null) {
            val collectedStreams = mutableSetOf<AudioSpec>()
            for (stream in audioStreams) {
                if (stream == null || stream.deliveryMethod == DeliveryMethod.TORRENT || (stream.deliveryMethod == DeliveryMethod.HLS && stream.format == MediaFormat.OPUS)) continue
                collectedStreams.add(stream.toAudioSpec())
            }
            aSpecs = collectedStreams.toList().sortedWith(compareBy { it.bitrate })
        }
        val vSpecs = getSortedVStreams(listOf(), streamInfo.videoOnlyStreams, ascendingOrder = true, preferVideoOnlyStreams = true)
        val mSpecs = getSortedVStreams(streamInfo.videoStreams, listOf(), ascendingOrder = true, preferVideoOnlyStreams = false)
        mediaSpecs.audioSpecs = aSpecs
        mediaSpecs.videoSpecs = vSpecs
        mediaSpecs.muxedSpecs = mSpecs
        return mediaSpecs
    }

    internal fun getStreamInfo(url: String?, forceLoad: Boolean = false): StreamInfo? {
        if (url.isNullOrBlank()) return null
        val cacheType = InfoCache.Type.STREAM
        return if (forceLoad) {
            CACHE.removeInfo(serviceId, url, cacheType)
            StreamInfo.getInfo(npService, url).also { info -> CACHE.putInfo(serviceId, url, info, cacheType) }
        } else {
            val cachedData = CACHE.getFromKey(serviceId, url, cacheType) as? StreamInfo
            cachedData ?: StreamInfo.getInfo(npService, url).also { info -> CACHE.putInfo(serviceId, url, info, cacheType) }
        }
    }

    private fun isChannel(url: String): Boolean {
        try {
//            Log.d(TAG, "isChannel url: $url ${Url(url).encodedPath}")
            return !Url(url).encodedPath.contains("/sets/")
        } catch (e: Exception) {
            Log.e(TAG, "isChannel urlInit is not valid $url")
            return false
        }
    }

    private fun isPlaylist(url: String): Boolean {
        try {
            return Url(url).encodedPath.contains("/sets/")
        } catch (e: Exception) {
            Log.e(TAG, "isPTPlaylist urlInit is not valid $url")
            return false
        }
    }

    private var fb: FeedBuilder? = null

    override fun buildFeed(url: String, index: Int): FeedIPC? {
        fb = FeedBuilder(FEEDTYPE, url, npService)
        return runBlocking(Dispatchers.IO) {
            when {
                isChannel(url) -> {
                    fb?.channelInfo = ChannelInfo.getInfo(npService, url)
                    fb?.feedFromChannel(index, "", hasVideo = false)
                }
                isPlaylist(url) -> if (index == 0) fb?.feedFromPlaylist(hasVideo = false) else null
                else -> null
            }
        }
    }

    override fun getEpisodes(total: Int, since: Long): List<EpisodeIPC> {
        if (fb == null) return listOf()
        return runBlocking(Dispatchers.IO) {
            when {
                isChannel(fb!!.urlInit) -> fb!!.episodesFromChannel(total, since, isAudio = true)
                isPlaylist(fb!!.urlInit) -> fb!!.episodesFromList(total, since, isAudio = true)
                else -> listOf()
            }
        }
    }

    override fun feedToUpdate(url: String): FeedIPC? {
        var feed_: FeedIPC?
        when {
            isChannel(url) -> {
                fb = FeedBuilder(FEEDTYPE, url, npService)
                fb?.channelInfo = ChannelInfo.getInfo(npService, url)
                runBlocking(Dispatchers.IO) { feed_ = fb?.feedFromChannel(0, "", hasVideo = false) }
            }
            isPlaylist(url) -> runBlocking(Dispatchers.IO) {
                fb = FeedBuilder(FEEDTYPE, url, npService)
                feed_ = fb?.feedFromPlaylist(hasVideo = false)
            }
            else -> {
                // channel tabs other than videos
                // TODO: is this needed
                feed_ = null
            }
        }
        feed_?.id = 0L
        return feed_
    }

    override fun feedsTitlesAtUrl(url_: String): List<String> {
        if (!isChannel(url_)) return listOf()
        val channelInfo = ChannelInfo.getInfo(npService, url_)
        val tabs = channelInfo.tabs
        val titles = mutableListOf<String>()
        for (i in tabs.indices) {
            val t = channelInfo.tabs[i]
            var url = t.url
            if (!url.startsWith("http")) url = url_ + url
            try {
                val urlEnd = Url(url).encodedPath.split("/").last()
                titles.add(urlEnd)
            } catch (e: Exception) {
                Log.e(TAG, "feedsTitlesAtUrl tab url not valid: $url")
            }
        }
        return titles.toList()
    }

    companion object {
        private const val TAG = "CloudSoundProvider"
        const val FEEDTYPE = "SoundCloud"
    }
}
