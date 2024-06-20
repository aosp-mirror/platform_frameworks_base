/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.WalletCard
import android.util.Log
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.wallet.controller.QuickAccessWalletController
import com.android.systemui.wallet.util.getPaymentCards
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Quick access wallet quick affordance data source. */
@SysUISingleton
class QuickAccessWalletKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val walletController: QuickAccessWalletController,
    private val activityStarter: ActivityStarter,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET

    override fun pickerName(): String = context.getString(R.string.accessibility_wallet_button)

    override val pickerIconResourceId = R.drawable.ic_wallet_lockscreen

    @OptIn(ExperimentalCoroutinesApi::class)
    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
                val callback =
                    object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                        override fun onWalletCardsRetrieved(response: GetWalletCardsResponse) {
                            val hasCards =
                                getPaymentCards(response.walletCards)?.isNotEmpty() == true
                            trySendWithFailureLogging(
                                hasCards,
                                TAG,
                            )
                        }

                        override fun onWalletCardRetrievalError(error: GetWalletCardsError) {
                            Log.e(
                                TAG,
                                "Wallet card retrieval error, message: \"${error?.message}\""
                            )
                            trySendWithFailureLogging(
                                null,
                                TAG,
                            )
                        }
                    }

                walletController.setupWalletChangeObservers(
                    callback,
                    QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                    QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE,
                    QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE
                )

                withContext(backgroundDispatcher) {
                    // Both must be called on background thread
                    walletController.updateWalletPreference()
                    walletController.queryWalletCards(callback)
                }

                awaitClose {
                    walletController.unregisterWalletChangeObservers(
                        QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE,
                        QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE,
                        QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE
                    )
                }
            }
            .flatMapLatest { hasCards ->
                // If hasCards is null, this indicates an error occurred upon card retrieval
                val state =
                    if (hasCards == null) {
                        KeyguardQuickAffordanceConfig.LockScreenState.Hidden
                    } else {
                        state(
                            isWalletAvailable(),
                            hasCards,
                            walletController.walletClient.tileIcon,
                        )
                    }
                flowOf(state)
            }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return when {
            !walletController.walletClient.isWalletServiceAvailable ->
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
            !isWalletAvailable() ->
                KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                    explanation =
                        context.getString(
                            R.string.wallet_quick_affordance_unavailable_install_the_app
                        ),
                )
            queryCards().isEmpty() ->
                KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                    explanation =
                        context.getString(
                            R.string.wallet_quick_affordance_unavailable_configure_the_app
                        ),
                )
            else -> KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        }
    }

    override fun onTriggered(
        expandable: Expandable?,
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        walletController.startQuickAccessUiIntent(
            activityStarter,
            expandable?.activityTransitionController(),
            /* hasCard= */ true,
        )
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }

    private suspend fun queryCards(): List<WalletCard> {
        return withContext(backgroundDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val callback =
                    object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                        override fun onWalletCardsRetrieved(response: GetWalletCardsResponse) {
                            continuation.resumeWith(
                                Result.success(getPaymentCards(response.walletCards))
                            )
                        }

                        override fun onWalletCardRetrievalError(error: GetWalletCardsError) {
                            continuation.resumeWith(Result.success(emptyList()))
                        }
                    }
                // Must be called on background thread
                walletController.queryWalletCards(callback)
            }
        }
    }

    private suspend fun isWalletAvailable() =
        withContext(backgroundDispatcher) {
            with(walletController.walletClient) {
                // Must be called on background thread
                isWalletServiceAvailable && isWalletFeatureAvailable
            }
        }

    private fun state(
        isFeatureEnabled: Boolean,
        hasCard: Boolean,
        tileIcon: Drawable?,
    ): KeyguardQuickAffordanceConfig.LockScreenState {
        return if (isFeatureEnabled && hasCard && tileIcon != null) {
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon =
                    Icon.Loaded(
                        drawable = tileIcon,
                        contentDescription =
                            ContentDescription.Resource(
                                res = R.string.accessibility_wallet_button,
                            ),
                    ),
            )
        } else {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    companion object {
        private const val TAG = "QuickAccessWalletKeyguardQuickAffordanceConfig"
    }
}
