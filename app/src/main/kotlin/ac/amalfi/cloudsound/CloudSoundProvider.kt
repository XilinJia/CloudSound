package ac.amalfi.cloudsound

import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedIPC
import ac.mdiq.podcini.shared.VideoSpec
import ac.mdiq.podcini.sources.Provider
import ac.roma.npeconnector.FeedBuilder
import ac.roma.npeconnector.InfoCache
import ac.roma.npeconnector.getSortedVStreams
import ac.roma.npeconnector.toAudioSpec
import ac.roma.npeconnector.toEpisodeIPC
import android.service.autofill.UserData
import android.util.Log
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class CloudSoundProvider : Provider.Stub() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ongoingRequests = ConcurrentHashMap<String, Job>()

    private val serviceId = 1

    private val npService by lazy { NewPipe.getService(serviceId) }

    fun loadUserData(userId: String): UserData? {
        val requestKey = "loadUserData_$userId"
        val deferredResult = CompletableDeferred<UserData?>()
        ongoingRequests[requestKey] = deferredResult

        val job = serviceScope.launch { try { deferredResult.complete(fetchUserFromNetwork(userId)) } catch (e: Exception) { deferredResult.completeExceptionally(e) } }

        deferredResult.invokeOnCompletion {
            if (deferredResult.isCancelled) job.cancel()
            ongoingRequests.remove(requestKey)
        }

        return runBlocking { try { deferredResult.await() } catch (e: CancellationException) { null } }
    }

    suspend fun fetchUserFromNetwork(userId: String): UserData? {
        return null
    }

    fun cancelOperation(operationPrefix: String, id: String) {
        val requestKey = "${operationPrefix}_$id"
        ongoingRequests[requestKey]?.cancel()
    }

    private val CACHE: InfoCache = InfoCache.instance

    override fun canHandleUrl(url_: String): Int {
        return if (url_.contains("soundcloud.com")) 1 else -1
    }

    override fun buildEpisode(url: String): EpisodeIPC? {
        return StreamInfo.getInfo(npService, url)?.toEpisodeIPC(isAudio = true)
    }

    override fun getEpisodeDescription(url: String): String? {
        return getStreamInfo(url)?.description?.content
    }

    override fun getAudioSpecs(media: EpisodeIPC): List<AudioSpec> {
        var sSpecs = listOf<AudioSpec>()
        val audioStreams = getStreamInfo(media.downloadUrl)?.audioStreams
        if (audioStreams != null) {
            val collectedStreams = mutableSetOf<AudioSpec>()
            for (stream in audioStreams) {
                if (stream == null || stream.deliveryMethod == DeliveryMethod.TORRENT || (stream.deliveryMethod == DeliveryMethod.HLS && stream.format == MediaFormat.OPUS)) continue
                collectedStreams.add(stream.toAudioSpec())
            }
            sSpecs = collectedStreams.toList().sortedWith(compareBy { it.bitrate })
        }
        return sSpecs
    }

    override fun getVideoOnlySpecs(media: EpisodeIPC): List<VideoSpec> {
        return getSortedVStreams(listOf(), getStreamInfo(media.downloadUrl)?.videoOnlyStreams, ascendingOrder = true, preferVideoOnlyStreams = true)
    }

    override fun getVideoSpecs(media: EpisodeIPC): List<VideoSpec> {
        return getSortedVStreams(getStreamInfo(media.downloadUrl)?.videoStreams, listOf(), ascendingOrder = true, preferVideoOnlyStreams = false)
    }

    internal fun getStreamInfo(url: String?, forceLoad: Boolean = false): StreamInfo? {
        if (url.isNullOrBlank()) return null
        val cacheType = InfoCache.Type.STREAM
        val streamInfo = StreamInfo.getInfo(npService, url).also { info -> CACHE.putInfo(serviceId, url, info, cacheType) }
        return if (forceLoad) {
            CACHE.removeInfo(serviceId, url, cacheType)
            streamInfo
        } else {
            val cachedData = CACHE.getFromKey(serviceId, url, cacheType) as? StreamInfo
            cachedData ?: streamInfo
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
