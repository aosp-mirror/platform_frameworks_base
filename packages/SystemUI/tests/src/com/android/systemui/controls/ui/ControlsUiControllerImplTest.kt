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
 */

package com.android.systemui.controls.ui

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsUiControllerImplTest : SysuiTestCase() {
    @Mock lateinit var controlsController: ControlsController
    @Mock lateinit var controlsListingController: ControlsListingController
    @Mock lateinit var controlActionCoordinator: ControlActionCoordinator
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock lateinit var shadeController: ShadeController
    @Mock lateinit var iconCache: CustomIconCache
    @Mock lateinit var controlsMetricsLogger: ControlsMetricsLogger
    @Mock lateinit var keyguardStateController: KeyguardStateController
    @Mock lateinit var userFileManager: UserFileManager
    @Mock lateinit var userTracker: UserTracker
    val sharedPreferences = FakeSharedPreferences()

    var uiExecutor = FakeExecutor(FakeSystemClock())
    var bgExecutor = FakeExecutor(FakeSystemClock())
    lateinit var underTest: ControlsUiControllerImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            ControlsUiControllerImpl(
                Lazy { controlsController },
                context,
                uiExecutor,
                bgExecutor,
                Lazy { controlsListingController },
                controlActionCoordinator,
                activityStarter,
                shadeController,
                iconCache,
                controlsMetricsLogger,
                keyguardStateController,
                userFileManager,
                userTracker
            )
        `when`(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    0
                )
            )
            .thenReturn(sharedPreferences)
        `when`(userFileManager.getSharedPreferences(anyString(), anyInt(), anyInt()))
            .thenReturn(sharedPreferences)
        `when`(userTracker.userId).thenReturn(0)
    }

    @Test
    fun testGetPreferredStructure() {
        val structureInfo = mock(StructureInfo::class.java)
        underTest.getPreferredStructure(listOf(structureInfo))
        verify(userFileManager, times(2))
            .getSharedPreferences(
                fileName = DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                mode = 0,
                userId = 0
            )
    }

    @Test
    fun testGetPreferredStructure_differentUserId() {
        val structureInfo =
            listOf(
                StructureInfo(ComponentName.unflattenFromString("pkg/.cls1"), "a", ArrayList()),
                StructureInfo(ComponentName.unflattenFromString("pkg/.cls2"), "b", ArrayList()),
            )
        sharedPreferences
            .edit()
            .putString("controls_component", structureInfo[0].componentName.flattenToString())
            .putString("controls_structure", structureInfo[0].structure.toString())
            .commit()

        val differentSharedPreferences = FakeSharedPreferences()
        differentSharedPreferences
            .edit()
            .putString("controls_component", structureInfo[1].componentName.flattenToString())
            .putString("controls_structure", structureInfo[1].structure.toString())
            .commit()

        val previousPreferredStructure = underTest.getPreferredStructure(structureInfo)

        `when`(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    1
                )
            )
            .thenReturn(differentSharedPreferences)
        `when`(userTracker.userId).thenReturn(1)

        val currentPreferredStructure = underTest.getPreferredStructure(structureInfo)

        assertThat(previousPreferredStructure).isEqualTo(structureInfo[0])
        assertThat(currentPreferredStructure).isEqualTo(structureInfo[1])
        assertThat(currentPreferredStructure).isNotEqualTo(previousPreferredStructure)
    }
}
