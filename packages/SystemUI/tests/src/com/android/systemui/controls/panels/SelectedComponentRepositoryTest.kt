/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.controls.panels

import android.content.ComponentName
import android.content.SharedPreferences
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class SelectedComponentRepositoryTest : SysuiTestCase() {

    private companion object {
        val COMPONENT_A =
            SelectedComponentRepository.SelectedComponent(
                name = "a",
                componentName = ComponentName.unflattenFromString("pkg/.cls_a"),
                isPanel = false,
            )
        val COMPONENT_B =
            SelectedComponentRepository.SelectedComponent(
                name = "b",
                componentName = ComponentName.unflattenFromString("pkg/.cls_b"),
                isPanel = false,
            )
    }

    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var userFileManager: UserFileManager

    private val featureFlags = FakeFeatureFlags()
    private val sharedPreferences: SharedPreferences = FakeSharedPreferences()

    // under test
    private lateinit var repository: SelectedComponentRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(userFileManager.getSharedPreferences(any(), any(), any()))
            .thenReturn(sharedPreferences)

        repository = SelectedComponentRepositoryImpl(userFileManager, userTracker, featureFlags)
    }

    @Test
    fun testUnsetIsNull() {
        assertThat(repository.getSelectedComponent()).isNull()
    }

    @Test
    fun testGetReturnsSet() {
        repository.setSelectedComponent(COMPONENT_A)

        assertThat(repository.getSelectedComponent()).isEqualTo(COMPONENT_A)
    }

    @Test
    fun testSetOverrides() {
        repository.setSelectedComponent(COMPONENT_A)
        repository.setSelectedComponent(COMPONENT_B)

        assertThat(repository.getSelectedComponent()).isEqualTo(COMPONENT_B)
    }

    @Test
    fun testRemove() {
        repository.setSelectedComponent(COMPONENT_A)

        repository.removeSelectedComponent()

        assertThat(repository.getSelectedComponent()).isNull()
    }

    @Test
    fun testFeatureEnabled_shouldAddDefaultPanelDefaultsToTrue() {
        featureFlags.set(Flags.APP_PANELS_REMOVE_APPS_ALLOWED, true)

        assertThat(repository.shouldAddDefaultComponent()).isTrue()
    }

    @Test
    fun testFeatureDisabled_shouldAddDefaultPanelDefaultsToTrue() {
        featureFlags.set(Flags.APP_PANELS_REMOVE_APPS_ALLOWED, false)

        assertThat(repository.shouldAddDefaultComponent()).isTrue()
    }

    @Test
    fun testFeatureEnabled_shouldAddDefaultPanelChecked() {
        featureFlags.set(Flags.APP_PANELS_REMOVE_APPS_ALLOWED, true)
        repository.setShouldAddDefaultComponent(false)

        assertThat(repository.shouldAddDefaultComponent()).isFalse()
    }

    @Test
    fun testFeatureDisabled_shouldAlwaysAddDefaultPanelAlwaysTrue() {
        featureFlags.set(Flags.APP_PANELS_REMOVE_APPS_ALLOWED, false)
        repository.setShouldAddDefaultComponent(false)

        assertThat(repository.shouldAddDefaultComponent()).isTrue()
    }

    @Test
    fun testGetPreferredStructure_differentUserId() {
        sharedPreferences.savePanel(COMPONENT_A)
        whenever(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    1,
                )
            )
            .thenReturn(FakeSharedPreferences().also { it.savePanel(COMPONENT_B) })

        val previousPreferredStructure = repository.getSelectedComponent()
        whenever(userTracker.userId).thenReturn(1)
        val currentPreferredStructure = repository.getSelectedComponent()

        assertThat(previousPreferredStructure).isEqualTo(COMPONENT_A)
        assertThat(currentPreferredStructure).isNotEqualTo(previousPreferredStructure)
        assertThat(currentPreferredStructure).isEqualTo(COMPONENT_B)
    }

    private fun SharedPreferences.savePanel(panel: SelectedComponentRepository.SelectedComponent) {
        edit()
            .putString("controls_component", panel.componentName?.flattenToString())
            .putString("controls_structure", panel.name)
            .putBoolean("controls_is_panel", panel.isPanel)
            .commit()
    }
}
