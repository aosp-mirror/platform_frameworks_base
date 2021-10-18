/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.unfold.updates

import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider.ScreenListener
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DeviceFoldStateProviderTest : SysuiTestCase() {

    @Mock
    private lateinit var hingeAngleProvider: HingeAngleProvider

    @Mock
    private lateinit var screenStatusProvider: ScreenStatusProvider

    @Mock
    private lateinit var deviceStateManager: DeviceStateManager

    private lateinit var foldStateProvider: FoldStateProvider

    private val foldUpdates: MutableList<Int> = arrayListOf()
    private val hingeAngleUpdates: MutableList<Float> = arrayListOf()

    private val foldStateListenerCaptor = ArgumentCaptor.forClass(FoldStateListener::class.java)
    private var foldedDeviceState: Int = 0
    private var unfoldedDeviceState: Int = 0

    private val screenOnListenerCaptor = ArgumentCaptor.forClass(ScreenListener::class.java)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val foldedDeviceStates: IntArray = context.resources.getIntArray(
            com.android.internal.R.array.config_foldedDeviceStates)
        assumeTrue("Test should be launched on a foldable device",
            foldedDeviceStates.isNotEmpty())

        foldedDeviceState = foldedDeviceStates.maxOrNull()!!
        unfoldedDeviceState = foldedDeviceState + 1

        foldStateProvider = DeviceFoldStateProvider(
            context,
            hingeAngleProvider,
            screenStatusProvider,
            deviceStateManager,
            context.mainExecutor
        )

        foldStateProvider.addCallback(object : FoldStateProvider.FoldUpdatesListener {
            override fun onHingeAngleUpdate(angle: Float) {
                hingeAngleUpdates.add(angle)
            }

            override fun onFoldUpdate(update: Int) {
                foldUpdates.add(update)
            }
        })
        foldStateProvider.start()

        verify(deviceStateManager).registerCallback(any(), foldStateListenerCaptor.capture())
        verify(screenStatusProvider).addCallback(screenOnListenerCaptor.capture())
    }

    @Test
    fun testOnFolded_emitsFinishClosedEvent() {
        setFoldState(folded = true)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_FINISH_CLOSED)
    }

    @Test
    fun testOnUnfolded_emitsStartOpeningEvent() {
        setFoldState(folded = false)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_OPENING)
    }

    @Test
    fun testOnFolded_stopsHingeAngleProvider() {
        setFoldState(folded = true)

        verify(hingeAngleProvider).stop()
    }

    @Test
    fun testOnUnfolded_startsHingeAngleProvider() {
        setFoldState(folded = false)

        verify(hingeAngleProvider).start()
    }

    @Test
    fun testFirstScreenOnEventWhenFolded_doesNotEmitEvents() {
        setFoldState(folded = true)
        foldUpdates.clear()

        fireScreenOnEvent()

        // Power button turn on
        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun testFirstScreenOnEventWhenUnfolded_doesNotEmitEvents() {
        setFoldState(folded = false)
        foldUpdates.clear()

        fireScreenOnEvent()

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun testFirstScreenOnEventAfterFoldAndUnfold_emitsUnfoldedScreenAvailableEvent() {
        setFoldState(folded = false)
        setFoldState(folded = true)
        fireScreenOnEvent()
        setFoldState(folded = false)
        foldUpdates.clear()

        fireScreenOnEvent()

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE)
    }

    @Test
    fun testSecondScreenOnEventWhenUnfolded_doesNotEmitEvents() {
        setFoldState(folded = false)
        fireScreenOnEvent()
        foldUpdates.clear()

        fireScreenOnEvent()

        // No events as this is power button turn on
        assertThat(foldUpdates).isEmpty()
    }

    private fun setFoldState(folded: Boolean) {
        val state = if (folded) foldedDeviceState else unfoldedDeviceState
        foldStateListenerCaptor.value.onStateChanged(state)
    }

    private fun fireScreenOnEvent() {
        screenOnListenerCaptor.value.onScreenTurnedOn()
    }
}
