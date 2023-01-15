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
package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import android.service.notification.ZenModeConfig
import com.android.settingslib.notification.EnableZenModeDialog
import com.android.settingslib.notification.ZenModeDialogMetricsLogger
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

@SysUISingleton
class DoNotDisturbQuickAffordanceConfig
constructor(
    private val context: Context,
    private val controller: ZenModeController,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val testConditionId: Uri?,
    testDialog: EnableZenModeDialog?,
) : KeyguardQuickAffordanceConfig {

    @Inject
    constructor(
        context: Context,
        controller: ZenModeController,
        secureSettings: SecureSettings,
        userTracker: UserTracker,
        @Background backgroundDispatcher: CoroutineDispatcher,
    ) : this(context, controller, secureSettings, userTracker, backgroundDispatcher, null, null)

    private var dndMode: Int = 0
    private var isAvailable = false
    private var settingsValue: Int = 0

    private val conditionUri: Uri
        get() =
            testConditionId
                ?: ZenModeConfig.toTimeCondition(
                        context,
                        settingsValue,
                        userTracker.userId,
                        true, /* shortVersion */
                    )
                    .id

    private val dialog: EnableZenModeDialog by lazy {
        testDialog
            ?: EnableZenModeDialog(
                context,
                R.style.Theme_SystemUI_Dialog,
                true, /* cancelIsNeutral */
                ZenModeDialogMetricsLogger(context),
            )
    }

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.DO_NOT_DISTURB

    override val pickerName: String = context.getString(R.string.quick_settings_dnd_label)

    override val pickerIconResourceId: Int = R.drawable.ic_do_not_disturb

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        combine(
            conflatedCallbackFlow {
                val callback =
                    object : ZenModeController.Callback {
                        override fun onZenChanged(zen: Int) {
                            dndMode = zen
                            trySendWithFailureLogging(updateState(), TAG)
                        }

                        override fun onZenAvailableChanged(available: Boolean) {
                            isAvailable = available
                            trySendWithFailureLogging(updateState(), TAG)
                        }
                    }

                dndMode = controller.zen
                isAvailable = controller.isZenAvailable
                trySendWithFailureLogging(updateState(), TAG)

                controller.addCallback(callback)

                awaitClose { controller.removeCallback(callback) }
            },
            secureSettings
                .observerFlow(Settings.Secure.ZEN_DURATION)
                .onStart { emit(Unit) }
                .map { secureSettings.getInt(Settings.Secure.ZEN_DURATION, ZEN_MODE_OFF) }
                .flowOn(backgroundDispatcher)
                .distinctUntilChanged()
                .onEach { settingsValue = it }
        ) { callbackFlowValue, _ -> callbackFlowValue }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return if (controller.isZenAvailable) {
            KeyguardQuickAffordanceConfig.PickerScreenState.Default(
                configureIntent = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)
            )
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }
    }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        return when {
            !isAvailable -> KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
            dndMode != ZEN_MODE_OFF -> {
                controller.setZen(ZEN_MODE_OFF, null, TAG)
                KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
            }
            settingsValue == ZEN_DURATION_PROMPT ->
                KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog(
                    dialog.createDialog(),
                    expandable
                )
            settingsValue == ZEN_DURATION_FOREVER -> {
                controller.setZen(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG)
                KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
            }
            else -> {
                controller.setZen(ZEN_MODE_IMPORTANT_INTERRUPTIONS, conditionUri, TAG)
                KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
            }
        }
    }

    private fun updateState(): KeyguardQuickAffordanceConfig.LockScreenState {
        return if (!isAvailable) {
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        } else if (dndMode == ZEN_MODE_OFF) {
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                Icon.Resource(
                    R.drawable.qs_dnd_icon_off,
                    ContentDescription.Resource(R.string.dnd_is_off),
                ),
                ActivationState.Inactive,
            )
        } else {
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                Icon.Resource(
                    R.drawable.qs_dnd_icon_on,
                    ContentDescription.Resource(R.string.dnd_is_on),
                ),
                ActivationState.Active,
            )
        }
    }

    companion object {
        const val TAG = "DoNotDisturbQuickAffordanceConfig"
    }
}
