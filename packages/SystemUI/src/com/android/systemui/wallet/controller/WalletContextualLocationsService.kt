package com.android.systemui.wallet.controller

import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.systemui.Flags.registerNewWalletCardInBackground
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Serves as an intermediary between QuickAccessWalletService and ContextualCardManager (in PCC).
 * When QuickAccessWalletService has a list of store locations, WalletContextualLocationsService
 * will send them to ContextualCardManager. When the user enters a store location, this Service
 * class will be notified, and WalletContextualSuggestionsController will be updated.
 */
class WalletContextualLocationsService
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val controller: WalletContextualSuggestionsController,
    private val featureFlags: FeatureFlags,
) : LifecycleService() {
    private var listener: IWalletCardsUpdatedListener? = null
    private var scope: CoroutineScope = this.lifecycleScope

    @VisibleForTesting
    constructor(
        dispatcher: CoroutineDispatcher,
        controller: WalletContextualSuggestionsController,
        featureFlags: FeatureFlags,
        scope: CoroutineScope,
    ) : this(dispatcher, controller, featureFlags) {
        this.scope = scope
    }
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        if (registerNewWalletCardInBackground()) {
            scope.launch(backgroundDispatcher) {
                controller.allWalletCards.collect { cards ->
                    val cardsSize = cards.size
                    Log.i(TAG, "Number of cards registered $cardsSize")
                    listener?.registerNewWalletCards(cards)
                }
            }
        } else {
            scope.launch {
                controller.allWalletCards.collect { cards ->
                    val cardsSize = cards.size
                    Log.i(TAG, "Number of cards registered $cardsSize")
                    listener?.registerNewWalletCards(cards)
                }
            }
        }
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        listener = null
    }

    @VisibleForTesting
    fun addWalletCardsUpdatedListenerInternal(listener: IWalletCardsUpdatedListener) {
        if (!featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            return
        }
        this.listener = listener // Currently, only one listener at a time is supported
        // Sends WalletCard objects from QuickAccessWalletService to the listener
        val cards = controller.allWalletCards.value
        if (!cards.isEmpty()) {
            val cardsSize = cards.size
            Log.i(TAG, "Number of cards registered $cardsSize")
            listener.registerNewWalletCards(cards)
        }
    }

    @VisibleForTesting
    fun onWalletContextualLocationsStateUpdatedInternal(storeLocations: List<String>) {
        if (!featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            return
        }
        Log.i(TAG, "Entered store $storeLocations")
        controller.setSuggestionCardIds(storeLocations.toSet())
    }

    private val binder: IWalletContextualLocationsService.Stub =
        object : IWalletContextualLocationsService.Stub() {
            override fun addWalletCardsUpdatedListener(listener: IWalletCardsUpdatedListener) {
                addWalletCardsUpdatedListenerInternal(listener)
            }
            override fun onWalletContextualLocationsStateUpdated(storeLocations: List<String>) {
                onWalletContextualLocationsStateUpdatedInternal(storeLocations)
            }
        }

    companion object {
        private const val TAG = "WalletContextualLocationsService"
    }
}
