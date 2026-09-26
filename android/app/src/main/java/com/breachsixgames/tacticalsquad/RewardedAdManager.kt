package com.breachsixgames.tacticalsquad

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Gère le cycle de vie d'une pub récompensée AdMob et applique EXACTEMENT le
 * contrat attendu côté web (voir cahier des charges "Pont natif AdMob TWA") :
 *
 *   Web -> Natif : { "type": "REQUEST_REWARDED_AD" }
 *   Natif -> Web : { "type": "REWARDED_AD_RESULT", "success": <boolean> }
 *
 * Règle non négociable : exactement UN REWARDED_AD_RESULT par
 * REQUEST_REWARDED_AD reçu — jamais zéro (le bouton "Regarder une pub" reste
 * bloqué indéfiniment côté jeu), jamais deux. `onResult` ne doit donc être
 * invoqué qu'une seule fois par appel à `requestAd()`.
 */
class RewardedAdManager(
    private val activity: Activity,
    private val onResult: (success: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "RewardedAdManager"
    }

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    /** À appeler dès le démarrage de l'activité pour que la pub soit prête au premier clic. */
    fun preload() {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        val request = AdRequest.Builder().build()
        RewardedAd.load(
            activity,
            AdConfig.rewardedAdUnitId,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                    Log.d(TAG, "Rewarded ad loaded (${AdConfig.rewardedAdUnitId})")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                }
            }
        )
    }

    /**
     * Appelé à la réception de REQUEST_REWARDED_AD depuis le jeu. Garantit un
     * unique appel à `onResult` : soit via le callback de récompense/fermeture
     * AdMob, soit immédiatement si aucune pub n'est disponible.
     */
    fun requestAd() {
        val ad = rewardedAd
        if (ad == null) {
            // Jamais laisser le jeu sans réponse : pas de pub prête => échec
            // immédiat, et on retente un préchargement pour la prochaine fois.
            Log.w(TAG, "REQUEST_REWARDED_AD reçu mais aucune pub chargée — success:false")
            onResult(false)
            preload()
            return
        }

        // Invalidé après le premier callback pour empêcher un double envoi si
        // AdMob devait déclencher plusieurs callbacks pour un même affichage.
        var responseSent = false
        var earnedReward = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                if (!responseSent) {
                    responseSent = true
                    onResult(earnedReward)
                }
                // Recharger immédiatement pour que la prochaine pub soit prête
                // sans délai perceptible pour le joueur.
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                Log.w(TAG, "Rewarded ad failed to show: ${error.message}")
                if (!responseSent) {
                    responseSent = true
                    onResult(false)
                }
                preload()
            }

            override fun onAdShowedFullScreenContent() {
                // Rien à envoyer ici : on attend la fermeture ou la récompense.
            }
        }

        ad.show(activity) { rewardItem ->
            // OnUserEarnedRewardListener : ne marque le succès que si le SDK a
            // effectivement validé la récompense (le joueur a regardé jusqu'au bout).
            earnedReward = true
            Log.d(TAG, "Reward earned: ${rewardItem.amount} ${rewardItem.type}")
        }
    }
}
