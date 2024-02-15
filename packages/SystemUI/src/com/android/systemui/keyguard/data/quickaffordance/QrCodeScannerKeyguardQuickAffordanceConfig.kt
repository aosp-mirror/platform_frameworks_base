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
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** QR code scanner quick affordance data source. */
@SysUISingleton
class QrCodeScannerKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val controller: QRCodeScannerController,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER

    override fun pickerName(): String = context.getString(R.string.qr_code_scanner_title)

    override val pickerIconResourceId = R.drawable.ic_qr_code_scanner

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
            val callback =
                object : QRCodeScannerController.Callback {
                    override fun onQRCodeScannerActivityChanged() {
                        trySendWithFailureLogging(state(), TAG)
                    }

                    override fun onQRCodeScannerPreferenceChanged() {
                        trySendWithFailureLogging(state(), TAG)
                    }
                }

            controller.addCallback(callback)
            controller.registerQRCodeScannerChangeObservers(
                QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE,
                QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE
            )
            // Registering does not push an initial update.
            trySendWithFailureLogging(state(), "initial state", TAG)

            awaitClose {
                controller.unregisterQRCodeScannerChangeObservers(
                    QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE,
                    QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE
                )
                controller.removeCallback(callback)
            }
        }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return when {
            !isEnabledForPickerStateOption() ->
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
            else -> KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        }
    }

    override fun onTriggered(
        expandable: Expandable?,
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            intent = controller.intent,
            canShowWhileLocked = true,
        )
    }

    private fun state(): KeyguardQuickAffordanceConfig.LockScreenState {
        return if (controller.isEnabledForLockScreenButton) {
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon =
                    Icon.Resource(
                        res = R.drawable.ic_qr_code_scanner,
                        contentDescription =
                            ContentDescription.Resource(
                                res = R.string.accessibility_qr_code_scanner_button,
                            ),
                    ),
            )
        } else {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    /** Returns whether QR scanner be shown as one of available lockscreen shortcut option. */
    private fun isEnabledForPickerStateOption(): Boolean {
        return controller.isAbleToLaunchScannerActivity && controller.isAllowedOnLockScreen
    }

    companion object {
        private const val TAG = "QrCodeScannerKeyguardQuickAffordanceConfig"
    }
}
