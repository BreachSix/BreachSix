package com.breachsixgames.tacticalsquad

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import com.google.android.gms.ads.MobileAds
import org.json.JSONObject

class TwaLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TwaLauncherActivity"
        private const val LAUNCH_URL = "https://breachsixgames.com/"
        private const val POST_MESSAGE_ORIGIN = "https://breachsixgames.com"
        private const val CHANNEL_TIMEOUT_MS = 2000L
    }

    private var customTabsSession: CustomTabsSession? = null
    private var serviceConnection: CustomTabsServiceConnection? = null
    private var twaLaunched = false

    private lateinit var adManager: RewardedAdManager

    private val customTabsCallback = object : CustomTabsCallback() {
        override fun onMessageChannelReady(extras: Bundle?) {
            Log.d(TAG, "postMessage channel ready")
            launchTwa()
        }

        override fun onPostMessage(message: String, extras: Bundle?) {
            handleIncomingMessage(message)
        }

        override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MobileAds.initialize(this)
        adManager = RewardedAdManager(this) { success -> sendAdResult(success) }
        adManager.preload()

        bindCustomTabsService()

        window.decorView.postDelayed({ launchTwa() }, CHANNEL_TIMEOUT_MS)
    }

    private fun bindCustomTabsService() {
        val packageName = CustomTabsClient.getPackageName(this, null)
        if (packageName == null) {
            Log.w(TAG, "Aucun provider Custom Tabs disponible sur l'appareil")
            return
        }

        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0)
                val session = client.newSession(customTabsCallback)
                if (session == null) {
                    Log.w(TAG, "Impossible de créer une session Custom Tabs")
                    return
                }
                customTabsSession = session
                session.validateRelationship(
                    CustomTabsService.RELATION_HANDLE_ALL_URLS,
                    Uri.parse(LAUNCH_URL),
                    null
                )
                val requested = session.requestPostMessageChannel(Uri.parse(POST_MESSAGE_ORIGIN))
                if (!requested) {
                    Log.w(TAG, "requestPostMessageChannel a échoué")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                customTabsSession = null
            }
        }
        serviceConnection = connection
        CustomTabsClient.bindCustomTabsService(this, packageName, connection)
    }

    private fun launchTwa() {
        if (twaLaunched) return
        twaLaunched = true
        val twaIntent = TrustedWebActivityIntentBuilder(Uri.parse(LAUNCH_URL))
            .build(customTabsSession)
        twaIntent.launchTrustedWebActivity(this)
    }

    override fun onDestroy() {
        serviceConnection?.let { unbindService(it) }
        super.onDestroy()
    }

    private fun handleIncomingMessage(message: String) {
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            Log.w(TAG, "Message web illisible: $message")
            return
        }
        when (json.optString("type")) {
            "REQUEST_REWARDED_AD" -> adManager.requestAd()
            else -> Log.d(TAG, "Message web ignoré (type inconnu): $message")
        }
    }

    private fun sendAdResult(success: Boolean) {
        val session = customTabsSession
        if (session == null) {
            Log.w(TAG, "Pas de session postMessage active, impossible d'envoyer REWARDED_AD_RESULT")
            return
        }
        val payload = JSONObject()
            .put("type", "REWARDED_AD_RESULT")
            .put("success", success)
            .toString()
        session.postMessage(payload, null)
    }
}
