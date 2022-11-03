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
import android.util.Log
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
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
    @Application context: Context,
    private val walletController: QuickAccessWalletController,
    private val activityStarter: ActivityStarter,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET

    override val pickerName = context.getString(R.string.accessibility_wallet_button)

    override val pickerIconResourceId = R.drawable.ic_wallet_lockscreen

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
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
                            KeyguardQuickAffordanceConfig.LockScreenState.Hidden,
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

    override fun onTriggered(
        expandable: Expandable?,
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        walletController.startQuickAccessUiIntent(
            activityStarter,
            expandable?.activityLaunchController(),
            /* hasCard= */ true,
        )
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
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
