package ac.amalfi.cloudsound

import ac.amalfi.cloudsound.CloudSoundProvider.Companion.FEEDTYPE
import ac.mdiq.podcini.shared.PROVIDER_API_VERSION
import ac.mdiq.podcini.shared.ProviderAttrs
import ac.mdiq.podcini.sources.IFeedSearchProvider
import ac.mdiq.podcini.sources.IMediaSearchProvider
import ac.mdiq.podcini.sources.IPodciniGateway
import ac.mdiq.podcini.sources.Provider
import ac.roma.npeconnector.DownloaderImpl
import ac.roma.npeconnector.DownloaderImpl.Companion.RECAPTCHA_COOKIES_KEY
import ac.roma.npeconnector.DownloaderImpl.Companion.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY
import ac.roma.npeconnector.FeedSearcher
import ac.roma.npeconnector.InfoCache
import ac.roma.npeconnector.Localization.Companion.getPreferredContentCountry
import ac.roma.npeconnector.Localization.Companion.getPreferredLocalization
import ac.roma.npeconnector.MediaSearcher
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import kotlin.collections.set

class GatewayService : Service() {
    private val providerBinder = CloudSoundProvider()
    private val searchProviderBinder = FeedSearcher("CloudSound", 1)
    private val mediaSearcherBinder = MediaSearcher("CloudSound", 1)
    private val gatewayBinder = object : IPodciniGateway.Stub() {
        override fun getAttributes(): ProviderAttrs {
            return ProviderAttrs(
                name = "CloudSound",
                apiVersion = PROVIDER_API_VERSION,
                feedType = FEEDTYPE,
                hasVideo = false,
                hasMultiQualities = false,
                hasSeparateAVs = false,
                supportDownload = false,
                hasViewCount = true,
                hasLikeCount = true
            )
        }

        override fun getSearchProvider(): IFeedSearchProvider {
            return searchProviderBinder
        }

        override fun getMediaSearcher(): IMediaSearchProvider {
            return mediaSearcherBinder
        }

        override fun getProvider(): Provider {
            return providerBinder
        }
    }

    override fun onCreate() {
        init()
    }

    private fun init() {
        val downloader = DownloaderImpl.init()
        downloader.mCookies[RECAPTCHA_COOKIES_KEY] = ""
        downloader.mCookies.remove(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
        InfoCache.instance.clearCache()
        NewPipe.init(downloader, getPreferredLocalization(), getPreferredContentCountry())
        for (s in ServiceList.all()) {
            if (s.serviceId == ServiceList.SoundCloud.serviceId) {
                //                not doing anything now
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return gatewayBinder
    }
}
