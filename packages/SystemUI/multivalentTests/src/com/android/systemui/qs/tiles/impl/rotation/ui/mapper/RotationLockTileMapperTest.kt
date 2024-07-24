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

package com.android.systemui.qs.tiles.impl.rotation.ui.mapper

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.rotation.domain.model.RotationLockTileModel
import com.android.systemui.qs.tiles.impl.rotation.qsRotationLockTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.devicePostureController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class RotationLockTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val rotationLockTileConfig = kosmos.qsRotationLockTileConfig
    private val devicePostureController = kosmos.devicePostureController

    private lateinit var mapper: RotationLockTileMapper

    @Before
    fun setup() {
        whenever(devicePostureController.devicePosture)
            .thenReturn(DevicePostureController.DEVICE_POSTURE_CLOSED)

        mapper =
            RotationLockTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_auto_rotate_icon_off, TestStubDrawable())
                        addOverride(R.drawable.qs_auto_rotate_icon_on, TestStubDrawable())
                        addOverride(com.android.internal.R.bool.config_allowRotationResolver, true)
                        addOverride(
                            com.android.internal.R.array.config_foldedDeviceStates,
                            intArrayOf() // empty array <=> device is not foldable
                        )
                    }
                    .resources,
                context.theme,
                devicePostureController
            )
    }

    @Test
    fun rotationNotLocked_cameraRotationDisabled() {
        val inputModel = RotationLockTileModel(false, false)

        val outputState = mapper.map(rotationLockTileConfig, inputModel)

        val expectedState =
            createRotationLockTileState(
                QSTileState.ActivationState.ACTIVE,
                EMPTY_SECONDARY_STRING,
                R.drawable.qs_auto_rotate_icon_on
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun rotationNotLocked_cameraRotationEnabled() {
        val inputModel = RotationLockTileModel(false, true)

        val outputState = mapper.map(rotationLockTileConfig, inputModel)

        val expectedState =
            createRotationLockTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.rotation_lock_camera_rotation_on),
                R.drawable.qs_auto_rotate_icon_on
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun rotationLocked_cameraRotationNotEnabled() {
        val inputModel = RotationLockTileModel(true, false)

        val outputState = mapper.map(rotationLockTileConfig, inputModel)

        val expectedState =
            createRotationLockTileState(
                QSTileState.ActivationState.INACTIVE,
                EMPTY_SECONDARY_STRING,
                R.drawable.qs_auto_rotate_icon_off
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun deviceFoldableAndClosed_secondaryLabelIsFoldableSpecific() {
        setDeviceFoldable()
        val inputModel = RotationLockTileModel(false, false)

        val outputState = mapper.map(rotationLockTileConfig, inputModel)

        val expectedSecondaryLabelEnding =
            context.getString(R.string.quick_settings_rotation_posture_folded)
        assertThat(
                context.resources.getIntArray(
                    com.android.internal.R.array.config_foldedDeviceStates
                )
            )
            .isNotEmpty()
        val actualSecondaryLabel = outputState.secondaryLabel
        assertThat(actualSecondaryLabel).isNotNull()
        assertThat(actualSecondaryLabel!!.endsWith(expectedSecondaryLabelEnding)).isTrue()
    }

    @Test
    fun deviceFoldableAndNotClosed_secondaryLabelIsFoldableSpecific() {
        setDeviceFoldable()
        whenever(devicePostureController.devicePosture)
            .thenReturn(DevicePostureController.DEVICE_POSTURE_OPENED)
        val inputModel = RotationLockTileModel(false, false)

        val outputState = mapper.map(rotationLockTileConfig, inputModel)

        val expectedSecondaryLabelEnding =
            context.getString(R.string.quick_settings_rotation_posture_unfolded)
        assertThat(
                context.orCreateTestableResources.resources.getIntArray(
                    com.android.internal.R.array.config_foldedDeviceStates
                )
            )
            .isNotEmpty()
        val actualSecondaryLabel = outputState.secondaryLabel
        assertThat(actualSecondaryLabel).isNotNull()
        assertThat(actualSecondaryLabel!!.endsWith(expectedSecondaryLabelEnding)).isTrue()
    }

    private fun setDeviceFoldable() {
        mapper.apply {
            overrideResource(
                com.android.internal.R.array.config_foldedDeviceStates,
                intArrayOf(1, 2, 3)
            )
        }
    }

    private fun createRotationLockTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_rotation_unlocked_label)
        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            context.getString(R.string.accessibility_quick_settings_rotation),
            secondaryLabel,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }

    private companion object {
        private const val EMPTY_SECONDARY_STRING = ""
    }
}
