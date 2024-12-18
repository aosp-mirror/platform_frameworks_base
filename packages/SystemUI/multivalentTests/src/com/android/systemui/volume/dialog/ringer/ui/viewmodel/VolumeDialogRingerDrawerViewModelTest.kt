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

package com.android.systemui.volume.dialog.ringer.ui.viewmodel

import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import android.media.AudioManager.STREAM_RING
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.fakeVibratorHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioSystemRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogRingerDrawerViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val controller = kosmos.fakeVolumeDialogController
    private val vibratorHelper = kosmos.fakeVibratorHelper

    private lateinit var underTest: VolumeDialogRingerDrawerViewModel

    @Before
    fun setUp() {
        underTest = kosmos.volumeDialogRingerDrawerViewModel
    }

    @Test
    fun onSelectedRingerNormalModeButtonClicked_openDrawer() =
        testScope.runTest {
            val ringerViewModel by collectLastValue(underTest.ringerViewModel)
            val normalRingerMode = RingerMode(RINGER_MODE_NORMAL)

            setUpRingerModeAndOpenDrawer(normalRingerMode)

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Available::class.java)
            assertThat((ringerViewModel as RingerViewModelState.Available).uiModel.drawerState)
                .isEqualTo(RingerDrawerState.Open(normalRingerMode))
        }

    @Test
    fun onSelectedRingerButtonClicked_drawerOpened_closeDrawer() =
        testScope.runTest {
            val ringerViewModel by collectLastValue(underTest.ringerViewModel)
            val normalRingerMode = RingerMode(RINGER_MODE_NORMAL)

            setUpRingerModeAndOpenDrawer(normalRingerMode)
            underTest.onRingerButtonClicked(normalRingerMode)
            controller.getState()

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Available::class.java)
            assertThat((ringerViewModel as RingerViewModelState.Available).uiModel.drawerState)
                .isEqualTo(RingerDrawerState.Closed(normalRingerMode, normalRingerMode))
        }

    @Test
    fun onNewRingerButtonClicked_drawerOpened_updateRingerMode_closeDrawer() =
        testScope.runTest {
            val ringerViewModel by collectLastValue(underTest.ringerViewModel)
            val vibrateRingerMode = RingerMode(RINGER_MODE_VIBRATE)
            val normalRingerMode = RingerMode(RINGER_MODE_NORMAL)

            setUpRingerModeAndOpenDrawer(normalRingerMode)
            // Select vibrate ringer mode.
            underTest.onRingerButtonClicked(vibrateRingerMode)
            controller.getState()
            runCurrent()

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Available::class.java)

            var uiModel = (ringerViewModel as RingerViewModelState.Available).uiModel
            assertThat(uiModel.availableButtons[uiModel.currentButtonIndex]?.ringerMode)
                .isEqualTo(vibrateRingerMode)
            assertThat(uiModel.drawerState)
                .isEqualTo(RingerDrawerState.Closed(vibrateRingerMode, normalRingerMode))

            val silentRingerMode = RingerMode(RINGER_MODE_SILENT)
            // Open drawer
            underTest.onRingerButtonClicked(vibrateRingerMode)
            controller.getState()

            // Select silent ringer mode.
            underTest.onRingerButtonClicked(silentRingerMode)
            controller.getState()
            runCurrent()

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Available::class.java)

            uiModel = (ringerViewModel as RingerViewModelState.Available).uiModel
            assertThat(uiModel.availableButtons[uiModel.currentButtonIndex]?.ringerMode)
                .isEqualTo(silentRingerMode)
            assertThat(uiModel.drawerState)
                .isEqualTo(RingerDrawerState.Closed(silentRingerMode, vibrateRingerMode))
            assertThat(controller.hasScheduledTouchFeedback).isFalse()
            assertThat(vibratorHelper.totalVibrations).isEqualTo(2)
        }

    @Test
    fun onVolumeSingleMode_ringerIsUnavailable() =
        testScope.runTest {
            val ringerViewModel by collectLastValue(underTest.ringerViewModel)

            kosmos.audioSystemRepository.setIsSingleVolume(true)
            setUpRingerMode(RingerMode(RINGER_MODE_NORMAL))

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Unavailable::class.java)
        }

    @Test
    fun setUnsupportedRingerMode_ringerIsUnavailable() =
        testScope.runTest {
            val ringerViewModel by collectLastValue(underTest.ringerViewModel)

            controller.setHasVibrator(false)
            setUpRingerMode(RingerMode(RINGER_MODE_VIBRATE))

            assertThat(ringerViewModel).isInstanceOf(RingerViewModelState.Unavailable::class.java)
        }

    private fun TestScope.setUpRingerModeAndOpenDrawer(selectedRingerMode: RingerMode) {
        setUpRingerMode(selectedRingerMode)
        underTest.onRingerButtonClicked(RingerMode(selectedRingerMode.value))
        controller.getState()
        runCurrent()
    }

    private fun TestScope.setUpRingerMode(selectedRingerMode: RingerMode) {
        controller.setStreamVolume(STREAM_RING, 50)
        controller.setRingerMode(selectedRingerMode.value, false)
        runCurrent()
    }
}
