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

/**
 * Point d'entrée natif du jeu. Établit le canal postMessage TWA AVANT de
 * lancer la Trusted Web Activity, pour que le pont AdMob soit actif dès
 * l'ouverture du jeu.
 *
 * Contrat exact avec le code web (déjà en place dans index.html, voir le
 * cahier des charges "Pont natif AdMob TWA") :
 *   Web -> Natif : { "type": "REQUEST_REWARDED_AD" }
 *   Natif -> Web : { "type": "REWARDED_AD_RESULT", "success": <boolean> }
 * Règle non négociable : exactement UN REWARDED_AD_RESULT par
 * REQUEST_REWARDED_AD — jamais zéro, jamais deux.
 *
 * Référence officielle du protocole :
 * https://developer.chrome.com/docs/android/trusted-web-activity/postmessage-api
 *
 * À ADAPTER : le projet généré par PWABuilder fournit normalement une
 * LauncherActivity (com.google.androidbrowserhelper.trusted.LauncherActivity)
 * avec son propre écran de démarrage (splash screen). Si ce splash screen
 * doit être conservé, reporter la logique ci-dessous (établissement du canal
 * AVANT le lancement de la TWA) dans cette classe existante plutôt que de la
 * remplacer, et adapter le nom de l'activité déclarée comme LAUNCHER dans
 * AndroidManifest.xml en conséquence.
 */
class TwaLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TwaLauncherActivity"

        // Doit correspondre au domaine déjà vérifié par Digital Asset Links
        // (assetlinks.json) pour que la TWA s'affiche sans barre d'adresse.
        private const val LAUNCH_URL = "https://breachsixgames.com/"
        private const val POST_MESSAGE_ORIGIN = "https://breachsixgames.com"

        /** Délai maximal d'attente du canal avant de lancer le jeu sans pont pub. */
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
            // Rien à piloter ici : on ne fait qu'observer le cycle de vie de la session.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MobileAds.initialize(this)
        adManager = RewardedAdManager(this) { success -> sendAdResult(success) }
        adManager.preload()

        bindCustomTabsService()

        // Filet de sécurité : même si le canal postMessage ne s'établit
        // jamais (navigateur incompatible, Digital Asset Links pas encore
        // propagés, etc.), le jeu doit démarrer — le code web retombe alors
        // sur la pub simulée, comm
