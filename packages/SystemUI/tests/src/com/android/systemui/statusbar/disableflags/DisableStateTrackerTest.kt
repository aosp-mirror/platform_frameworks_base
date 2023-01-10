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

package com.android.systemui.statusbar.disableflags

import android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_EXPAND
import android.app.StatusBarManager.DISABLE_NAVIGATION
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_NOTIFICATION_TICKER
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class DisableStateTrackerTest : SysuiTestCase() {

    private lateinit var underTest: DisableStateTracker

    @Mock private lateinit var commandQueue: CommandQueue

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun startTracking_commandQueueGetsCallback() {
        underTest = DisableStateTracker(0, 0) { }

        underTest.startTracking(commandQueue, displayId = 0)

        verify(commandQueue).addCallback(underTest)
    }

    @Test
    fun stopTracking_commandQueueLosesCallback() {
        underTest = DisableStateTracker(0, 0) { }

        underTest.stopTracking(commandQueue)

        verify(commandQueue).removeCallback(underTest)
    }

    @Test
    fun disable_hadNotStartedTracking_isDisabledFalse() {
        underTest = DisableStateTracker(DISABLE_CLOCK, 0) { }

        underTest.disable(displayId = 0, state1 = DISABLE_CLOCK, state2 = 0, animate = false)

        assertThat(underTest.isDisabled).isFalse()
    }

    @Test
    fun disable_wrongDisplayId_isDisabledFalse() {
        underTest = DisableStateTracker(DISABLE_CLOCK, 0) { }
        underTest.startTracking(commandQueue, displayId = 15)

        underTest.disable(displayId = 20, state1 = DISABLE_CLOCK, state2 = 0, animate = false)

        assertThat(underTest.isDisabled).isFalse()
    }

    @Test
    fun disable_irrelevantFlagsUpdated_isDisabledFalse() {
        underTest = DisableStateTracker(DISABLE_CLOCK, DISABLE2_GLOBAL_ACTIONS) { }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        underTest.disable(
            DISPLAY_ID, state1 = DISABLE_EXPAND, state2 = DISABLE2_QUICK_SETTINGS, animate = false
        )

        assertThat(underTest.isDisabled).isFalse()
    }

    @Test
    fun disable_partOfMask1True_isDisabledTrue() {
        underTest = DisableStateTracker(
            mask1 = DISABLE_CLOCK or DISABLE_EXPAND or DISABLE_NAVIGATION,
            mask2 = DISABLE2_GLOBAL_ACTIONS
        ) { }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        underTest.disable(DISPLAY_ID, state1 = DISABLE_EXPAND, state2 = 0, animate = false)

        assertThat(underTest.isDisabled).isTrue()
    }

    @Test
    fun disable_partOfMask2True_isDisabledTrue() {
        underTest = DisableStateTracker(
            mask1 = DISABLE_CLOCK,
            mask2 = DISABLE2_GLOBAL_ACTIONS or DISABLE2_SYSTEM_ICONS
        ) { }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        underTest.disable(DISPLAY_ID, state1 = 0, state2 = DISABLE2_SYSTEM_ICONS, animate = false)

        assertThat(underTest.isDisabled).isTrue()
    }

    @Test
    fun disable_isDisabledChangesFromFalseToTrue_callbackNotified() {
        var callbackCalled = false

        underTest = DisableStateTracker(
            mask1 = DISABLE_CLOCK,
            mask2 = DISABLE2_GLOBAL_ACTIONS
        ) { callbackCalled = true }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        underTest.disable(DISPLAY_ID, state1 = DISABLE_CLOCK, state2 = 0, animate = false)

        assertThat(callbackCalled).isTrue()
    }

    @Test
    fun disable_isDisabledChangesFromTrueToFalse_callbackNotified() {
        var callbackCalled: Boolean

        underTest = DisableStateTracker(
            mask1 = DISABLE_CLOCK,
            mask2 = DISABLE2_GLOBAL_ACTIONS
        ) { callbackCalled = true }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        // First, update isDisabled to true
        underTest.disable(DISPLAY_ID, state1 = DISABLE_CLOCK, state2 = 0, animate = false)
        assertThat(underTest.isDisabled).isTrue()

        // WHEN isDisabled updates back to false
        callbackCalled = false
        underTest.disable(DISPLAY_ID, state1 = 0, state2 = 0, animate = false)

        // THEN the callback is called again
        assertThat(callbackCalled).isTrue()
    }

    @Test
    fun disable_manyUpdates_isDisabledUpdatesAppropriately() {
        underTest = DisableStateTracker(
            mask1 = DISABLE_CLOCK or DISABLE_EXPAND or DISABLE_NAVIGATION,
            mask2 = DISABLE2_GLOBAL_ACTIONS or DISABLE2_SYSTEM_ICONS
        ) { }
        underTest.startTracking(commandQueue, DISPLAY_ID)

        // All flags from mask1 -> isDisabled = true
        underTest.disable(
            DISPLAY_ID,
            state1 = DISABLE_CLOCK or DISABLE_EXPAND or DISABLE_NAVIGATION,
            state2 = 0,
            animate = false
        )
        assertThat(underTest.isDisabled).isTrue()

        // Irrelevant flags from mask1 -> isDisabled = false
        underTest.disable(
            DISPLAY_ID,
            state1 = DISABLE_NOTIFICATION_ICONS or DISABLE_NOTIFICATION_TICKER,
            state2 = 0,
            animate = false
        )
        assertThat(underTest.isDisabled).isFalse()

        // All flags from mask1 & all flags from mask2 -> isDisabled = true
        underTest.disable(
            DISPLAY_ID,
            state1 = DISABLE_CLOCK or DISABLE_EXPAND or DISABLE_NAVIGATION,
            state2 = DISABLE2_GLOBAL_ACTIONS or DISABLE2_SYSTEM_ICONS,
            animate = false
        )
        assertThat(underTest.isDisabled).isTrue()

        // No flags -> isDisabled = false
        underTest.disable(DISPLAY_ID, state1 = 0, state2 = 0, animate = false)
        assertThat(underTest.isDisabled).isFalse()

        // 1 flag from mask1 & 1 flag from mask2 -> isDisabled = true
        underTest.disable(
            DISPLAY_ID,
            state1 = DISABLE_NAVIGATION,
            state2 = DISABLE2_SYSTEM_ICONS,
            animate = false
        )
        assertThat(underTest.isDisabled).isTrue()
    }

    companion object {
        private const val DISPLAY_ID = 3
    }
}
