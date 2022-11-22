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
import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.TaskViewFactory
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
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
    @Mock lateinit var taskViewFactory: TaskViewFactory
    @Mock lateinit var activityContext: Context
    @Mock lateinit var dumpManager: DumpManager
    val sharedPreferences = FakeSharedPreferences()

    var uiExecutor = FakeExecutor(FakeSystemClock())
    var bgExecutor = FakeExecutor(FakeSystemClock())
    lateinit var underTest: ControlsUiControllerImpl
    lateinit var parent: FrameLayout

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        parent = FrameLayout(mContext)

        underTest =
            ControlsUiControllerImpl(
                Lazy { controlsController },
                context,
                uiExecutor,
                bgExecutor,
                Lazy { controlsListingController },
                controlActionCoordinator,
                activityStarter,
                iconCache,
                controlsMetricsLogger,
                keyguardStateController,
                userFileManager,
                userTracker,
                Optional.of(taskViewFactory),
                dumpManager
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
        underTest.getPreferredSelectedItem(listOf(structureInfo))
        verify(userFileManager)
            .getSharedPreferences(
                fileName = DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                mode = 0,
                userId = 0
            )
    }

    @Test
    fun testGetPreferredStructure_differentUserId() {
        val selectedItems =
            listOf(
                SelectedItem.StructureItem(
                    StructureInfo(ComponentName.unflattenFromString("pkg/.cls1"), "a", ArrayList())
                ),
                SelectedItem.StructureItem(
                    StructureInfo(ComponentName.unflattenFromString("pkg/.cls2"), "b", ArrayList())
                ),
            )
        val structures = selectedItems.map { it.structure }
        sharedPreferences
            .edit()
            .putString("controls_component", selectedItems[0].componentName.flattenToString())
            .putString("controls_structure", selectedItems[0].name.toString())
            .commit()

        val differentSharedPreferences = FakeSharedPreferences()
        differentSharedPreferences
            .edit()
            .putString("controls_component", selectedItems[1].componentName.flattenToString())
            .putString("controls_structure", selectedItems[1].name.toString())
            .commit()

        val previousPreferredStructure = underTest.getPreferredSelectedItem(structures)

        `when`(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    1
                )
            )
            .thenReturn(differentSharedPreferences)
        `when`(userTracker.userId).thenReturn(1)

        val currentPreferredStructure = underTest.getPreferredSelectedItem(structures)

        assertThat(previousPreferredStructure).isEqualTo(selectedItems[0])
        assertThat(currentPreferredStructure).isEqualTo(selectedItems[1])
        assertThat(currentPreferredStructure).isNotEqualTo(previousPreferredStructure)
    }

    @Test
    fun testGetPreferredPanel() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        sharedPreferences
            .edit()
            .putString("controls_component", panel.componentName.flattenToString())
            .putString("controls_structure", panel.appName.toString())
            .putBoolean("controls_is_panel", true)
            .commit()

        val selected = underTest.getPreferredSelectedItem(emptyList())

        assertThat(selected).isEqualTo(panel)
    }

    @Test
    fun testPanelDoesNotRefreshControls() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        sharedPreferences
            .edit()
            .putString("controls_component", panel.componentName.flattenToString())
            .putString("controls_structure", panel.appName.toString())
            .putBoolean("controls_is_panel", true)
            .commit()

        underTest.show(parent, {}, activityContext)
        verify(controlsController, never()).refreshStatus(any(), any())
    }
}
