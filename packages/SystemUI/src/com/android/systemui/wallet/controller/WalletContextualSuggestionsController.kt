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

import android.content.Intent
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class WalletContextualSuggestionsController
@Inject
constructor(
    @Application private val applicationCoroutineScope: CoroutineScope,
    private val walletController: QuickAccessWalletController,
    broadcastDispatcher: BroadcastDispatcher,
    featureFlags: FeatureFlags
) {
    private val cardsReceivedCallbacks: MutableSet<(List<WalletCard>) -> Unit> = mutableSetOf()

    /** All potential cards. */
    val allWalletCards: StateFlow<List<WalletCard>> =
        if (featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            // TODO(b/237409756) determine if we should debounce this so we don't call the service
            // too frequently. Also check if the list actually changed before calling callbacks.
            broadcastDispatcher
                .broadcastFlow(IntentFilter(Intent.ACTION_SCREEN_ON))
                .flatMapLatest {
                    conflatedCallbackFlow {
                        val callback =
                            object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                                override fun onWalletCardsRetrieved(
                                    response: GetWalletCardsResponse
                                ) {
                                    trySendWithFailureLogging(response.walletCards, TAG)
                                }

                                override fun onWalletCardRetrievalError(
                                    error: GetWalletCardsError
                                ) {
                                    trySendWithFailureLogging(emptyList<WalletCard>(), TAG)
                                }
                            }

                        walletController.setupWalletChangeObservers(
                            callback,
                            QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                            QuickAccessWalletController.WalletChangeEvent
                                .DEFAULT_PAYMENT_APP_CHANGE,
                            QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE
                        )
                        walletController.updateWalletPreference()
                        walletController.queryWalletCards(callback, MAX_CARDS)

                        awaitClose {
                            walletController.unregisterWalletChangeObservers(
                                QuickAccessWalletController.WalletChangeEvent
                                    .WALLET_PREFERENCE_CHANGE,
                                QuickAccessWalletController.WalletChangeEvent
                                    .DEFAULT_PAYMENT_APP_CHANGE,
                                QuickAccessWalletController.WalletChangeEvent
                                    .DEFAULT_WALLET_APP_CHANGE
                            )
                        }
                    }
                }
                .onEach { notifyCallbacks(it) }
                .stateIn(
                    applicationCoroutineScope,
                    // Needs to be done eagerly since we need to notify callbacks even if there are
                    // no subscribers
                    SharingStarted.Eagerly,
                    emptyList()
                )
        } else {
            MutableStateFlow<List<WalletCard>>(emptyList()).asStateFlow()
        }

    private val _suggestionCardIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private val contextualSuggestionsCardIds: Flow<Set<String>> = _suggestionCardIds.asStateFlow()

    /** Contextually-relevant cards. */
    val contextualSuggestionCards: Flow<List<WalletCard>> =
        combine(allWalletCards, contextualSuggestionsCardIds) { cards, ids ->
                val ret =
                    cards.filter { card ->
                        card.cardType == WalletCard.CARD_TYPE_NON_PAYMENT &&
                            ids.contains(card.cardId)
                    }
                ret
            }
            .stateIn(applicationCoroutineScope, SharingStarted.WhileSubscribed(), emptyList())

    /** When called, {@link contextualSuggestionCards} will be updated to be for these IDs. */
    fun setSuggestionCardIds(cardIds: Set<String>) {
        _suggestionCardIds.update { _ -> cardIds }
    }

    /** Register callback to be called when a new list of cards is fetched. */
    fun registerWalletCardsReceivedCallback(callback: (List<WalletCard>) -> Unit) {
        cardsReceivedCallbacks.add(callback)
    }

    /** Unregister callback to be called when a new list of cards is fetched. */
    fun unregisterWalletCardsReceivedCallback(callback: (List<WalletCard>) -> Unit) {
        cardsReceivedCallbacks.remove(callback)
    }

    private fun notifyCallbacks(cards: List<WalletCard>) {
        applicationCoroutineScope.launch {
            cardsReceivedCallbacks.onEach { callback ->
                callback(cards.filter { card -> card.cardType == WalletCard.CARD_TYPE_NON_PAYMENT })
            }
        }
    }

    companion object {
        private const val TAG = "WalletSuggestions"
        private const val MAX_CARDS = 50
    }
}
