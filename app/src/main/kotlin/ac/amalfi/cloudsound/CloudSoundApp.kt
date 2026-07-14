package ac.amalfi.cloudsound

import android.app.Application
import android.content.Context
import android.content.Intent

class CloudSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        cloudSoundApp = this
    }

    companion object {
        private lateinit var cloudSoundApp: CloudSoundApp

        fun getApp(): CloudSoundApp = cloudSoundApp

        fun getAppContext(): Context = cloudSoundApp.applicationContext

        fun forceRestart() {
            val intent = Intent(cloudSoundApp, MainActivity::class.java)
            val mainIntent = Intent.makeRestartActivityTask(intent.component)
            cloudSoundApp.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
