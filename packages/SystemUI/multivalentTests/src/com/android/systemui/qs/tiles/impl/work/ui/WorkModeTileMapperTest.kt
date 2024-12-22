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

package com.android.systemui.qs.tiles.impl.work.ui

import android.app.admin.DevicePolicyResources
import android.app.admin.DevicePolicyResourcesManager
import android.app.admin.devicePolicyManager
import android.graphics.drawable.TestStubDrawable
import android.service.quicksettings.Tile
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.work.domain.model.WorkModeTileModel
import com.android.systemui.qs.tiles.impl.work.qsWorkModeTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class WorkModeTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val qsTileConfig = kosmos.qsWorkModeTileConfig
    private val devicePolicyManager = kosmos.devicePolicyManager
    private val testLabel = context.getString(R.string.quick_settings_work_mode_label)
    private val devicePolicyResourceManager = mock<DevicePolicyResourcesManager>()
    private lateinit var mapper: WorkModeTileMapper

    @Before
    fun setup() {
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyResourceManager)
        whenever(
                devicePolicyResourceManager.getString(
                    eq(DevicePolicyResources.Strings.SystemUi.QS_WORK_PROFILE_LABEL),
                    any()
                )
            )
            .thenReturn(testLabel)
        mapper =
            WorkModeTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(
                            com.android.internal.R.drawable.stat_sys_managed_profile_status,
                            TestStubDrawable()
                        )
                    }
                    .resources,
                context.theme,
                devicePolicyManager
            )
    }

    @Test
    fun mapsDisabledDataToInactiveState() {
        val isEnabled = false

        val actualState: QSTileState =
            mapper.map(qsTileConfig, WorkModeTileModel.HasActiveProfile(isEnabled))

        val expectedState = createWorkModeTileState(QSTileState.ActivationState.INACTIVE)
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsEnabledDataToActiveState() {
        val isEnabled = true

        val actualState: QSTileState =
            mapper.map(qsTileConfig, WorkModeTileModel.HasActiveProfile(isEnabled))

        val expectedState = createWorkModeTileState(QSTileState.ActivationState.ACTIVE)
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsNoActiveProfileDataToUnavailableState() {
        val actualState: QSTileState = mapper.map(qsTileConfig, WorkModeTileModel.NoActiveProfile)

        val expectedState = createWorkModeTileState(QSTileState.ActivationState.UNAVAILABLE)
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    private fun createWorkModeTileState(
        activationState: QSTileState.ActivationState,
    ): QSTileState {
        val label = testLabel
        val iconRes = com.android.internal.R.drawable.stat_sys_managed_profile_status
        return QSTileState(
            icon = { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            iconRes = iconRes,
            label = label,
            activationState = activationState,
            secondaryLabel =
                if (activationState == QSTileState.ActivationState.INACTIVE) {
                    context.getString(R.string.quick_settings_work_mode_paused_state)
                } else if (activationState == QSTileState.ActivationState.UNAVAILABLE) {
                    context.resources
                        .getStringArray(R.array.tile_states_work)[Tile.STATE_UNAVAILABLE]
                } else {
                    ""
                },
            supportedActions =
                if (activationState == QSTileState.ActivationState.UNAVAILABLE) {
                    setOf()
                } else {
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                },
            contentDescription = label,
            stateDescription = null,
            sideViewIcon = QSTileState.SideViewIcon.None,
            enabledState = QSTileState.EnabledState.ENABLED,
            expandedAccessibilityClassName = Switch::class.qualifiedName
        )
    }
}
