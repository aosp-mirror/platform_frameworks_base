/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wallet.controller

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.WalletCard
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.shareIn

@SysUISingleton
class WalletContextualSuggestionsController
@Inject
constructor(
    @Application private val applicationCoroutineScope: CoroutineScope,
    private val walletController: QuickAccessWalletController,
    broadcastDispatcher: BroadcastDispatcher,
    featureFlags: FeatureFlags
) {
    private val allWalletCards: Flow<List<WalletCard>> =
        if (featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            conflatedCallbackFlow {
                val callback =
                    object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                        override fun onWalletCardsRetrieved(response: GetWalletCardsResponse) {
                            trySendWithFailureLogging(response.walletCards, TAG)
                        }

                        override fun onWalletCardRetrievalError(error: GetWalletCardsError) {
                            trySendWithFailureLogging(emptyList<WalletCard>(), TAG)
                        }
                    }

                walletController.setupWalletChangeObservers(
                    callback,
                    QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                    QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE
                )
                walletController.updateWalletPreference()
                walletController.queryWalletCards(callback)

                awaitClose {
                    walletController.unregisterWalletChangeObservers(
                        QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                        QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE
                    )
                }
            }
        } else {
            emptyFlow()
        }

    private val contextualSuggestionsCardIds: Flow<Set<String>> =
        if (featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            broadcastDispatcher.broadcastFlow(
                filter = IntentFilter(ACTION_UPDATE_WALLET_CONTEXTUAL_SUGGESTIONS),
                permission = Manifest.permission.BIND_QUICK_ACCESS_WALLET_SERVICE,
                flags = Context.RECEIVER_EXPORTED
            ) { intent, _ ->
                if (intent.hasExtra(UPDATE_CARD_IDS_EXTRA)) {
                    intent.getStringArrayListExtra(UPDATE_CARD_IDS_EXTRA).toSet()
                } else {
                    emptySet()
                }
            }
        } else {
            emptyFlow()
        }

    val contextualSuggestionCards: Flow<List<WalletCard>> =
        combine(allWalletCards, contextualSuggestionsCardIds) { cards, ids ->
                cards.filter { card -> ids.contains(card.cardId) }
            }
            .shareIn(applicationCoroutineScope, replay = 1, started = SharingStarted.Eagerly)

    companion object {
        private const val ACTION_UPDATE_WALLET_CONTEXTUAL_SUGGESTIONS =
            "com.android.systemui.wallet.UPDATE_CONTEXTUAL_SUGGESTIONS"

        private const val UPDATE_CARD_IDS_EXTRA = "cardIds"

        private const val TAG = "WalletSuggestions"
    }
}
