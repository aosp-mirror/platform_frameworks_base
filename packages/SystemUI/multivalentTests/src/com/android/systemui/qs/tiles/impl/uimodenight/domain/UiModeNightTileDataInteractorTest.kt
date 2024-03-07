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

package com.android.systemui.qs.tiles.impl.uimodenight.domain

import android.app.UiModeManager
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.UserHandle
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileDataInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.FakeLocationController
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class UiModeNightTileDataInteractorTest : SysuiTestCase() {
    private val configurationController: ConfigurationController =
        ConfigurationControllerImpl(context)
    private val batteryController = FakeBatteryController(LeakCheck())
    private val locationController = FakeLocationController(LeakCheck())

    private lateinit var underTest: UiModeNightTileDataInteractor

    @Mock private lateinit var uiModeManager: UiModeManager
    @Mock private lateinit var dateFormatUtil: DateFormatUtil

    @Before
    fun setup() {
        uiModeManager = mock<UiModeManager>()
        dateFormatUtil = mock<DateFormatUtil>()

        whenever(uiModeManager.customNightModeStart).thenReturn(LocalTime.MIN)
        whenever(uiModeManager.customNightModeEnd).thenReturn(LocalTime.MAX)

        underTest =
            UiModeNightTileDataInteractor(
                context,
                configurationController,
                uiModeManager,
                batteryController,
                locationController,
                dateFormatUtil
            )
    }

    @Test
    fun collectTileDataReadsUiModeManagerNightMode() = runTest {
        val expectedNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
        whenever(uiModeManager.nightMode).thenReturn(expectedNightMode)

        val model by
            collectLastValue(
                underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
            )
        runCurrent()

        assertThat(model).isNotNull()
        val actualNightMode = model?.uiMode
        assertThat(actualNightMode).isEqualTo(expectedNightMode)
    }

    @Test
    fun collectTileDataReadsUiModeManagerNightModeCustomTypeAndTimes() = runTest {
        collectLastValue(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()

        verify(uiModeManager).nightMode
        verify(uiModeManager).nightModeCustomType
        verify(uiModeManager).customNightModeStart
        verify(uiModeManager).customNightModeEnd
    }

    /** Here, available refers to the tile showing up, not the tile being clickable. */
    @Test
    fun isAvailableRegardlessOfPowerSaveModeOn() = runTest {
        batteryController.setPowerSaveMode(true)

        runCurrent()
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isTrue()
    }

    @Test
    fun dataMatchesConfigurationController() = runTest {
        setUiMode(UI_MODE_NIGHT_NO)
        val flowValues: List<UiModeNightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        setUiMode(UI_MODE_NIGHT_YES)
        runCurrent()
        setUiMode(UI_MODE_NIGHT_NO)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(3)
        assertThat(flowValues.map { it.isNightMode }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun dataMatchesBatteryController() = runTest {
        batteryController.setPowerSaveMode(false)
        val flowValues: List<UiModeNightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        batteryController.setPowerSaveMode(true)
        runCurrent()
        batteryController.setPowerSaveMode(false)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(3)
        assertThat(flowValues.map { it.isPowerSave }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun dataMatchesLocationController() = runTest {
        locationController.setLocationEnabled(false)
        val flowValues: List<UiModeNightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        locationController.setLocationEnabled(true)
        runCurrent()
        locationController.setLocationEnabled(false)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(3)
        assertThat(flowValues.map { it.isLocationEnabled })
            .containsExactly(false, true, false)
            .inOrder()
    }

    @Test
    fun collectTileDataReads24HourFormatFromDateTimeUtil() = runTest {
        collectLastValue(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))
        runCurrent()

        verify(dateFormatUtil).is24HourFormat
    }

    /**
     * Use this method to trigger [ConfigurationController.ConfigurationListener.onUiModeChanged]
     */
    private fun setUiMode(uiMode: Int) {
        val config = context.resources.configuration
        val newConfig = Configuration(config)
        newConfig.uiMode = uiMode

        /** [underTest] will see this config the next time it creates a model */
        context.orCreateTestableResources.overrideConfiguration(newConfig)

        /** Trigger updateUiMode callbacks */
        configurationController.onConfigurationChanged(newConfig)
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
