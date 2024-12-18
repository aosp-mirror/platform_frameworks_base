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

package com.android.systemui.qs.tiles.impl.sensorprivacy.ui

import android.graphics.drawable.TestStubDrawable
import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.hardware.SensorPrivacyManager.Sensors.MICROPHONE
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.qs.tiles.impl.sensorprivacy.qsCameraSensorPrivacyToggleTileConfig
import com.android.systemui.qs.tiles.impl.sensorprivacy.qsMicrophoneSensorPrivacyToggleTileConfig
import com.android.systemui.qs.tiles.impl.sensorprivacy.ui.SensorPrivacyTileResources.CameraPrivacyTileResources
import com.android.systemui.qs.tiles.impl.sensorprivacy.ui.SensorPrivacyTileResources.MicrophonePrivacyTileResources
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SensorPrivacyToggleTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val cameraConfig = kosmos.qsCameraSensorPrivacyToggleTileConfig
    private val micConfig = kosmos.qsMicrophoneSensorPrivacyToggleTileConfig

    @Test
    fun mapCamera_notBlocked() {
        val mapper = createMapper(CameraPrivacyTileResources)
        val inputModel = SensorPrivacyToggleTileModel(false)

        val outputState = mapper.map(cameraConfig, inputModel)

        val expectedState =
            createSensorPrivacyToggleTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_camera_mic_available),
                R.drawable.qs_camera_access_icon_on,
                null,
                CAMERA
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun mapCamera_blocked() {
        val mapper = createMapper(CameraPrivacyTileResources)
        val inputModel = SensorPrivacyToggleTileModel(true)

        val outputState = mapper.map(cameraConfig, inputModel)

        val expectedState =
            createSensorPrivacyToggleTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.quick_settings_camera_mic_blocked),
                R.drawable.qs_camera_access_icon_off,
                null,
                CAMERA
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun mapMic_notBlocked() {
        val mapper = createMapper(MicrophonePrivacyTileResources)
        val inputModel = SensorPrivacyToggleTileModel(false)

        val outputState = mapper.map(micConfig, inputModel)

        val expectedState =
            createSensorPrivacyToggleTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_camera_mic_available),
                R.drawable.qs_mic_access_on,
                null,
                MICROPHONE
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun mapMic_blocked() {
        val mapper = createMapper(MicrophonePrivacyTileResources)
        val inputModel = SensorPrivacyToggleTileModel(true)

        val outputState = mapper.map(micConfig, inputModel)

        val expectedState =
            createSensorPrivacyToggleTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.quick_settings_camera_mic_blocked),
                R.drawable.qs_mic_access_off,
                null,
                MICROPHONE
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createMapper(
        sensorResources: SensorPrivacyTileResources
    ): SensorPrivacyToggleTileMapper {
        val mapper =
            SensorPrivacyToggleTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_camera_access_icon_off, TestStubDrawable())
                        addOverride(R.drawable.qs_camera_access_icon_on, TestStubDrawable())
                        addOverride(R.drawable.qs_mic_access_off, TestStubDrawable())
                        addOverride(R.drawable.qs_mic_access_on, TestStubDrawable())
                    }
                    .resources,
                context.theme,
                sensorResources,
            )
        return mapper
    }

    private fun createSensorPrivacyToggleTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int,
        stateDescription: CharSequence?,
        sensorId: Int,
    ): QSTileState {
        val label =
            if (sensorId == CAMERA) context.getString(R.string.quick_settings_camera_label)
            else context.getString(R.string.quick_settings_mic_label)

        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            iconRes,
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            label,
            stateDescription,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
