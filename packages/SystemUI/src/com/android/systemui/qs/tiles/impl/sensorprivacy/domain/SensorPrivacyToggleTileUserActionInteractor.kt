/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.sensorprivacy.domain

import android.content.Intent
import android.hardware.SensorPrivacyManager.Sensors.Sensor
import android.hardware.SensorPrivacyManager.Sources.QS_TILE
import android.provider.Settings
import android.safetycenter.SafetyCenterManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Handles sensor privacy toggle tile clicks and long clicks. */
class SensorPrivacyToggleTileUserActionInteractor
@AssistedInject
constructor(
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val keyguardInteractor: KeyguardInteractor,
    private val activityStarter: ActivityStarter,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val safetyCenterManager: SafetyCenterManager,
    @Assisted @Sensor private val sensorId: Int,
) : QSTileUserActionInteractor<SensorPrivacyToggleTileModel> {
    @AssistedFactory
    interface Factory {
        fun create(@Sensor id: Int): SensorPrivacyToggleTileUserActionInteractor
    }

    // should only be initialized in code known to run in background thread
    private lateinit var longClickIntent: Intent

    override suspend fun handleInput(input: QSTileInput<SensorPrivacyToggleTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val blocked = input.data.isBlocked
                    if (
                        sensorPrivacyController.requiresAuthentication() &&
                            keyguardInteractor.isKeyguardDismissible.value &&
                            keyguardInteractor.isKeyguardShowing()
                    ) {
                        activityStarter.postQSRunnableDismissingKeyguard {
                            sensorPrivacyController.setSensorBlocked(QS_TILE, sensorId, !blocked)
                        }
                        return
                    }
                    sensorPrivacyController.setSensorBlocked(QS_TILE, sensorId, !blocked)
                }
                is QSTileUserAction.LongClick -> {
                    if (!::longClickIntent.isInitialized) {
                        longClickIntent =
                            Intent(
                                if (safetyCenterManager.isSafetyCenterEnabled) {
                                    Settings.ACTION_PRIVACY_CONTROLS
                                } else {
                                    Settings.ACTION_PRIVACY_SETTINGS
                                }
                            )
                    }
                    qsTileIntentUserActionHandler.handle(action.expandable, longClickIntent)
                }
            }
        }
}
