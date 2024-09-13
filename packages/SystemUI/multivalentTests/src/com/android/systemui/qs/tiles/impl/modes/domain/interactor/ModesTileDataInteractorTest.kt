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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.app.AutomaticZenRule
import android.app.Flags
import android.graphics.drawable.TestStubDrawable
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val dispatcher = kosmos.testDispatcher
    private val zenModeRepository = kosmos.fakeZenModeRepository

    private val underTest = ModesTileDataInteractor(context, kosmos.zenModeInteractor, dispatcher)

    @Before
    fun setUp() {
        context.orCreateTestableResources.apply {
            addOverride(MODES_DRAWABLE_ID, MODES_DRAWABLE)
            addOverride(R.drawable.ic_zen_mode_type_bedtime, BEDTIME_DRAWABLE)
        }

        val customPackageContext = SysuiTestableContext(context)
        context.prepareCreatePackageContext(CUSTOM_PACKAGE, customPackageContext)
        customPackageContext.orCreateTestableResources.apply {
            addOverride(CUSTOM_DRAWABLE_ID, CUSTOM_DRAWABLE)
        }
    }

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun availableWhenFlagIsOn() =
        testScope.runTest {
            val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

            assertThat(availability).containsExactly(true)
        }

    @DisableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun unavailableWhenFlagIsOff() =
        testScope.runTest {
            val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

            assertThat(availability).containsExactly(false)
        }

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun isActivatedWhenModesChange() =
        testScope.runTest {
            val dataList: List<ModesTileModel> by
                collectValues(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false).inOrder()

            // Add active mode
            zenModeRepository.addMode(id = "One", active = true)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last()).containsExactly("Mode One")

            // Add an inactive mode: state hasn't changed, so this shouldn't cause another emission
            zenModeRepository.addMode(id = "Two", active = false)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last()).containsExactly("Mode One")

            // Add another active mode
            zenModeRepository.addMode(id = "Three", active = true)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last())
                .containsExactly("Mode One", "Mode Three")
                .inOrder()

            // Remove a mode and deactivate the other
            zenModeRepository.removeMode("One")
            runCurrent()
            zenModeRepository.deactivateMode("Three")
            runCurrent()
            assertThat(dataList.map { it.isActivated })
                .containsExactly(false, true, true, true, false)
                .inOrder()
            assertThat(dataList.map { it.activeModes }.last()).isEmpty()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun tileData_iconsFlagEnabled_changesIconWhenActiveModesChange() =
        testScope.runTest {
            val tileData by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )

            // Tile starts with the generic Modes icon.
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)

            // Add an inactive mode -> Still modes icon
            zenModeRepository.addMode(id = "Mode", active = false)
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)

            // Add an active mode with a default icon: icon should be the mode icon, and the
            // iconResId is also populated, because we know it's a system icon.
            zenModeRepository.addMode(
                id = "Bedtime with default icon",
                type = AutomaticZenRule.TYPE_BEDTIME,
                active = true
            )
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(BEDTIME_ICON)
            assertThat(tileData?.iconResId).isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            // Add another, less-prioritized mode that has a *custom* icon: for now, icon should
            // remain the first mode icon
            zenModeRepository.addMode(
                TestModeBuilder()
                    .setId("Driving with custom icon")
                    .setType(AutomaticZenRule.TYPE_DRIVING)
                    .setPackage(CUSTOM_PACKAGE)
                    .setIconResId(CUSTOM_DRAWABLE_ID)
                    .setActive(true)
                    .build()
            )
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(BEDTIME_ICON)
            assertThat(tileData?.iconResId).isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            // Deactivate more important mode: icon should be the less important, still active mode
            // And because it's a package-provided icon, iconResId is not populated.
            zenModeRepository.deactivateMode("Bedtime with default icon")
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(CUSTOM_ICON)
            assertThat(tileData?.iconResId).isNull()

            // Deactivate remaining mode: back to the default modes icon
            zenModeRepository.deactivateMode("Driving with custom icon")
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    @DisableFlags(Flags.FLAG_MODES_UI_ICONS)
    fun tileData_iconsFlagDisabled_hasPriorityModesIcon() =
        testScope.runTest {
            val tileData by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )

            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)

            // Activate a Mode -> Icon doesn't change.
            zenModeRepository.addMode(id = "Mode", active = true)
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)

            zenModeRepository.deactivateMode(id = "Mode")
            runCurrent()
            assertThat(tileData?.icon).isEqualTo(MODES_ICON)
            assertThat(tileData?.iconResId).isEqualTo(MODES_DRAWABLE_ID)
        }

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun getCurrentTileModel_returnsActiveModes() = runTest {
        var tileData = underTest.getCurrentTileModel()
        assertThat(tileData.isActivated).isFalse()
        assertThat(tileData.activeModes).isEmpty()

        // Add active mode
        zenModeRepository.addMode(id = "One", active = true)
        tileData = underTest.getCurrentTileModel()
        assertThat(tileData.isActivated).isTrue()
        assertThat(tileData.activeModes).containsExactly("Mode One")

        // Add an inactive mode: state hasn't changed
        zenModeRepository.addMode(id = "Two", active = false)
        tileData = underTest.getCurrentTileModel()
        assertThat(tileData.isActivated).isTrue()
        assertThat(tileData.activeModes).containsExactly("Mode One")

        // Add another active mode
        zenModeRepository.addMode(id = "Three", active = true)
        tileData = underTest.getCurrentTileModel()
        assertThat(tileData.isActivated).isTrue()
        assertThat(tileData.activeModes).containsExactly("Mode One", "Mode Three").inOrder()

        // Remove a mode and deactivate the other
        zenModeRepository.removeMode("One")
        zenModeRepository.deactivateMode("Three")
        tileData = underTest.getCurrentTileModel()
        assertThat(tileData.isActivated).isFalse()
        assertThat(tileData.activeModes).isEmpty()
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
        const val CUSTOM_PACKAGE = "com.some.mode.owner.package"

        val MODES_DRAWABLE_ID = R.drawable.ic_zen_priority_modes
        const val CUSTOM_DRAWABLE_ID = 12345

        val MODES_DRAWABLE = TestStubDrawable("modes_icon")
        val BEDTIME_DRAWABLE = TestStubDrawable("bedtime")
        val CUSTOM_DRAWABLE = TestStubDrawable("custom")

        val MODES_ICON = MODES_DRAWABLE.asIcon()
        val BEDTIME_ICON = BEDTIME_DRAWABLE.asIcon()
        val CUSTOM_ICON = CUSTOM_DRAWABLE.asIcon()
    }
}
