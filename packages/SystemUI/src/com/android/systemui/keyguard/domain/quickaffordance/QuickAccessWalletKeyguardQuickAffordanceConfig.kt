/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.util.Log
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.wallet.controller.QuickAccessWalletController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Quick access wallet quick affordance data source. */
@SysUISingleton
class QuickAccessWalletKeyguardQuickAffordanceConfig
@Inject
constructor(
    private val walletController: QuickAccessWalletController,
    private val activityStarter: ActivityStarter,
) : KeyguardQuickAffordanceConfig {

    override val state: Flow<KeyguardQuickAffordanceConfig.State> = conflatedCallbackFlow {
        val callback =
            object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                override fun onWalletCardsRetrieved(response: GetWalletCardsResponse?) {
                    trySendWithFailureLogging(
                        state(
                            isFeatureEnabled = walletController.isWalletEnabled,
                            hasCard = response?.walletCards?.isNotEmpty() == true,
                            tileIcon = walletController.walletClient.tileIcon,
                        ),
                        TAG,
                    )
                }

                override fun onWalletCardRetrievalError(error: GetWalletCardsError?) {
                    Log.e(TAG, "Wallet card retrieval error, message: \"${error?.message}\"")
                    trySendWithFailureLogging(
                        KeyguardQuickAffordanceConfig.State.Hidden,
                        TAG,
                    )
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

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): KeyguardQuickAffordanceConfig.OnClickedResult {
        walletController.startQuickAccessUiIntent(
            activityStarter,
            animationController,
            /* hasCard= */ true,
        )
        return KeyguardQuickAffordanceConfig.OnClickedResult.Handled
    }

    private fun state(
        isFeatureEnabled: Boolean,
        hasCard: Boolean,
        tileIcon: Drawable?,
    ): KeyguardQuickAffordanceConfig.State {
        return if (isFeatureEnabled && hasCard && tileIcon != null) {
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ContainedDrawable.WithDrawable(tileIcon),
                contentDescriptionResourceId = R.string.accessibility_wallet_button,
            )
        } else {
            KeyguardQuickAffordanceConfig.State.Hidden
        }
    }

    companion object {
        private const val TAG = "QuickAccessWalletKeyguardQuickAffordanceConfig"
    }
}
