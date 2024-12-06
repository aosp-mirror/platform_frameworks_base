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

package com.android.systemui.haptics.msdl.qs

import android.service.quicksettings.Tile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.haptics.msdl.tileHapticsViewModelFactory
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.panels.ui.viewmodel.fakeQsTile
import com.android.systemui.qs.panels.ui.viewmodel.tileViewModel
import com.android.systemui.testKosmos
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TileHapticsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val qsTile = kosmos.fakeQsTile
    private val msdlPlayer = kosmos.fakeMSDLPlayer
    private val tileViewModel = kosmos.tileViewModel

    private val underTest = kosmos.tileHapticsViewModelFactory.create(tileViewModel)

    @Before
    fun setUp() {
        underTest.activateIn(testScope)
    }

    @Test
    fun whenTileTogglesOnFromClick_playsSwitchOnHaptics() =
        testScope.runTest {
            // WHEN the tile toggles on after being clicked
            underTest.setTileInteractionState(TileHapticsViewModel.TileInteractionState.CLICKED)
            toggleOn()

            // THEN the switch on token plays
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.SWITCH_ON)
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun whenTileTogglesOffFromClick_playsSwitchOffHaptics() =
        testScope.runTest {
            // WHEN the tile toggles off after being clicked
            underTest.setTileInteractionState(TileHapticsViewModel.TileInteractionState.CLICKED)
            toggleOff()

            // THEN the switch off token plays
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.SWITCH_OFF)
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun whenTileTogglesOnWhileIdle_doesNotPlaySwitchOnHaptics() =
        testScope.runTest {
            // WHEN the tile toggles on without being clicked
            toggleOn()

            // THEN no token plays
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun whenTileTogglesOffWhileIdle_doesNotPlaySwitchOffHaptics() =
        testScope.runTest {
            // WHEN the tile toggles off without being clicked
            toggleOff()

            // THEN no token plays
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun whenLaunchingFromLongClick_playsLongPressHaptics() =
        testScope.runTest {
            // WHEN the tile is long-clicked and its action state changes accordingly
            underTest.setTileInteractionState(
                TileHapticsViewModel.TileInteractionState.LONG_CLICKED
            )
            // WHEN the activity transition (from the long-click) starts
            underTest.onActivityLaunchTransitionStart()
            runCurrent()

            // THEN the long-press token plays
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.LONG_PRESS)
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun whenLaunchingFromClick_doesNotPlayHaptics() =
        testScope.runTest {
            // WHEN the tile is clicked and its action state changes accordingly
            underTest.setTileInteractionState(TileHapticsViewModel.TileInteractionState.CLICKED)
            // WHEN an activity transition starts (from clicking)
            underTest.onActivityLaunchTransitionStart()
            runCurrent()

            // THEN no haptics play
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    private fun TestScope.toggleOn() {
        qsTile.changeState(QSTile.State().apply { state = Tile.STATE_INACTIVE })
        runCurrent()

        qsTile.changeState(QSTile.State().apply { state = Tile.STATE_ACTIVE })
        runCurrent()
    }

    private fun TestScope.toggleOff() {
        qsTile.changeState(QSTile.State().apply { state = Tile.STATE_ACTIVE })
        runCurrent()

        qsTile.changeState(QSTile.State().apply { state = Tile.STATE_INACTIVE })
        runCurrent()
    }
}
