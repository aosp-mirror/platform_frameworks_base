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
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@RunWith(AndroidTestingRunner::class)
@SmallTest
class SelectedComponentRepositoryTest : SysuiTestCase() {

    private companion object {
        const val PREF_COMPONENT = "controls_component"
        const val PREF_STRUCTURE_OR_APP_NAME = "controls_structure"
        const val PREF_IS_PANEL = "controls_is_panel"
        val PRIMARY_USER: UserHandle = UserHandle.of(0)
        val SECONDARY_USER: UserHandle = UserHandle.of(12)
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
    private lateinit var primaryUserSharedPref: FakeSharedPreferences
    private lateinit var secondaryUserSharedPref: FakeSharedPreferences

    @Mock private lateinit var userTracker: UserTracker
    private lateinit var userFileManager: UserFileManager

    // under test
    private lateinit var repository: SelectedComponentRepository

    private val kosmos = testKosmos()

    @Before
    fun setUp() =
        with(kosmos) {
            primaryUserSharedPref = FakeSharedPreferences()
            secondaryUserSharedPref = FakeSharedPreferences()
            MockitoAnnotations.initMocks(this@SelectedComponentRepositoryTest)
            userFileManager =
                FakeUserFileManager(
                    mapOf(
                        PRIMARY_USER.identifier to primaryUserSharedPref,
                        SECONDARY_USER.identifier to secondaryUserSharedPref
                    )
                )
            repository =
                SelectedComponentRepositoryImpl(
                    userFileManager = userFileManager,
                    userTracker = userTracker,
                    bgDispatcher = testDispatcher,
                )
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
    fun testShouldAddDefaultPanelDefaultsToTrue() {
        assertThat(repository.shouldAddDefaultComponent()).isTrue()
    }

    @Test
    fun testShouldAddDefaultPanelChecked() {
        repository.setShouldAddDefaultComponent(false)

        assertThat(repository.shouldAddDefaultComponent()).isFalse()
    }

    @Test
    fun testGetPreferredStructure_differentUserId() {
        primaryUserSharedPref.savePanel(COMPONENT_A)
        secondaryUserSharedPref.savePanel(COMPONENT_B)
        val previousPreferredStructure = repository.getSelectedComponent()
        whenever(userTracker.userId).thenReturn(SECONDARY_USER.identifier)
        val currentPreferredStructure = repository.getSelectedComponent()

        assertThat(previousPreferredStructure).isEqualTo(COMPONENT_A)
        assertThat(currentPreferredStructure).isNotEqualTo(previousPreferredStructure)
        assertThat(currentPreferredStructure).isEqualTo(COMPONENT_B)
    }

    @Test
    fun testEmitValueFromGetSelectedComponent() =
        with(kosmos) {
            testScope.runTest {
                primaryUserSharedPref.savePanel(COMPONENT_A)
                val emittedValue by collectLastValue(repository.selectedComponentFlow(PRIMARY_USER))
                assertThat(emittedValue).isEqualTo(COMPONENT_A)
            }
        }

    @Test
    fun testEmitNullWhenRemoveSelectedComponentIsCalled() =
        with(kosmos) {
            testScope.runTest {
                primaryUserSharedPref.savePanel(COMPONENT_A)
                primaryUserSharedPref.removePanel()
                val emittedValue by collectLastValue(repository.selectedComponentFlow(PRIMARY_USER))
                assertThat(emittedValue).isEqualTo(null)
            }
        }

    @Test
    fun testChangeEmitValueChangeWhenANewComponentIsSelected() =
        with(kosmos) {
            testScope.runTest {
                primaryUserSharedPref.savePanel(COMPONENT_A)
                val emittedValue by collectLastValue(repository.selectedComponentFlow(PRIMARY_USER))
                advanceUntilIdle()
                assertThat(emittedValue).isEqualTo(COMPONENT_A)
                primaryUserSharedPref.savePanel(COMPONENT_B)
                advanceUntilIdle()
                assertThat(emittedValue).isEqualTo(COMPONENT_B)
            }
        }

    @Test
    fun testDifferentUsersWithDifferentComponentSelected() =
        with(kosmos) {
            testScope.runTest {
                primaryUserSharedPref.savePanel(COMPONENT_A)
                secondaryUserSharedPref.savePanel(COMPONENT_B)
                val primaryUserValue by
                    collectLastValue(repository.selectedComponentFlow(PRIMARY_USER))
                val secondaryUserValue by
                    collectLastValue(repository.selectedComponentFlow(SECONDARY_USER))
                assertThat(primaryUserValue).isEqualTo(COMPONENT_A)
                assertThat(secondaryUserValue).isEqualTo(COMPONENT_B)
            }
        }

    private fun SharedPreferences.savePanel(panel: SelectedComponentRepository.SelectedComponent) {
        edit()
            .putString(PREF_COMPONENT, panel.componentName?.flattenToString())
            .putString(PREF_STRUCTURE_OR_APP_NAME, panel.name)
            .putBoolean(PREF_IS_PANEL, panel.isPanel)
            .commit()
    }

    private fun SharedPreferences.removePanel() {
        edit()
            .remove(PREF_COMPONENT)
            .remove(PREF_STRUCTURE_OR_APP_NAME)
            .remove(PREF_IS_PANEL)
            .commit()
    }

    private class FakeUserFileManager(private val sharedPrefs: Map<Int, SharedPreferences>) :
        UserFileManager {
        override fun getFile(fileName: String, userId: Int): File {
            throw UnsupportedOperationException()
        }

        override fun getSharedPreferences(
            fileName: String,
            mode: Int,
            userId: Int
        ): SharedPreferences {
            if (fileName != DeviceControlsControllerImpl.PREFS_CONTROLS_FILE) {
                throw IllegalArgumentException(
                    "Preference files must be " +
                        "$DeviceControlsControllerImpl.PREFS_CONTROLS_FILE"
                )
            }
            return sharedPrefs.getValue(userId)
        }
    }
}
