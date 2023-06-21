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
import android.content.Intent
import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.WalletCard
import android.util.Log
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.Companion.componentName
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.wallet.controller.QuickAccessWalletController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Quick access wallet quick affordance data source. */
@SysUISingleton
class QuickAccessWalletKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val walletController: QuickAccessWalletController,
    private val activityStarter: ActivityStarter,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET

    override val pickerName: String = context.getString(R.string.accessibility_wallet_button)

    override val pickerIconResourceId = R.drawable.ic_wallet_lockscreen

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
            val callback =
                object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                    override fun onWalletCardsRetrieved(response: GetWalletCardsResponse?) {
                        val hasCards = response?.walletCards?.isNotEmpty() == true
                        trySendWithFailureLogging(
                            state(
                                isFeatureEnabled = walletController.isWalletEnabled,
                                hasCard = hasCards,
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

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return when {
            !walletController.walletClient.isWalletServiceAvailable ->
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
            !walletController.isWalletEnabled || queryCards().isEmpty() -> {
                val componentName =
                    walletController.walletClient.createWalletSettingsIntent().toComponentName()
                val actionText =
                    if (componentName != null) {
                        context.getString(
                            R.string.keyguard_affordance_enablement_dialog_action_template,
                            pickerName,
                        )
                    } else {
                        null
                    }
                KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                    instructions =
                        listOf(
                            context.getString(
                                R.string.keyguard_affordance_enablement_dialog_wallet_instruction_1
                            ),
                            context.getString(
                                R.string.keyguard_affordance_enablement_dialog_wallet_instruction_2
                            ),
                        ),
                    actionText = actionText,
                    actionComponentName = componentName,
                )
            }
            else -> KeyguardQuickAffordanceConfig.PickerScreenState.Default()
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

    private suspend fun queryCards(): List<WalletCard> {
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : QuickAccessWalletClient.OnWalletCardsRetrievedCallback {
                    override fun onWalletCardsRetrieved(response: GetWalletCardsResponse?) {
                        continuation.resumeWith(
                            Result.success(response?.walletCards ?: emptyList())
                        )
                    }

                    override fun onWalletCardRetrievalError(error: GetWalletCardsError?) {
                        continuation.resumeWith(Result.success(emptyList()))
                    }
                }
            walletController.queryWalletCards(callback)
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

    private fun Intent?.toComponentName(): String? {
        if (this == null) {
            return null
        }

        return componentName(packageName = `package`, action = action)
    }

    companion object {
        private const val TAG = "QuickAccessWalletKeyguardQuickAffordanceConfig"
    }
}
