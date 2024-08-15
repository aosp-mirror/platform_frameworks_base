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

package com.android.systemui.shade

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeExpansionStateManagerTest : SysuiTestCase() {

    private lateinit var shadeExpansionStateManager: ShadeExpansionStateManager

    @Before
    fun setUp() {
        shadeExpansionStateManager = ShadeExpansionStateManager()
    }

    @Test
    fun onPanelExpansionChanged_listenerNotified() {
        val listener = TestShadeExpansionListener()
        val currentState = shadeExpansionStateManager.addExpansionListener(listener)
        listener.onPanelExpansionChanged(currentState)
        val fraction = 0.6f
        val expanded = true
        val tracking = true
        val dragDownAmount = 1234f

        shadeExpansionStateManager.onPanelExpansionChanged(fraction, expanded, tracking)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.expanded).isEqualTo(expanded)
        assertThat(listener.tracking).isEqualTo(tracking)
    }

    @Test
    fun addExpansionListener_listenerNotifiedOfCurrentValues() {
        val fraction = 0.6f
        val expanded = true
        val tracking = true
        val dragDownAmount = 1234f
        shadeExpansionStateManager.onPanelExpansionChanged(fraction, expanded, tracking)
        val listener = TestShadeExpansionListener()

        val currentState = shadeExpansionStateManager.addExpansionListener(listener)
        listener.onPanelExpansionChanged(currentState)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.expanded).isEqualTo(expanded)
        assertThat(listener.tracking).isEqualTo(tracking)
    }

    @Test
    fun updateState_listenerNotified() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)

        shadeExpansionStateManager.updateState(STATE_OPEN)

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* ***** [PanelExpansionStateManager.onPanelExpansionChanged] test cases *******/

    /* Fraction < 1 test cases */

    @Test
    fun onPEC_fractionLessThanOne_expandedTrue_trackingFalse_becomesStateOpening() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f,
            expanded = true,
            tracking = false
        )

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedTrue_trackingTrue_becomesStateOpening() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f,
            expanded = true,
            tracking = true
        )

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedFalse_trackingFalse_becomesStateClosed() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        shadeExpansionStateManager.updateState(STATE_OPEN)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f,
            expanded = false,
            tracking = false
        )

        assertThat(listener.state).isEqualTo(STATE_CLOSED)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedFalse_trackingTrue_doesNotBecomeStateClosed() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        shadeExpansionStateManager.updateState(STATE_OPEN)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f,
            expanded = false,
            tracking = true
        )

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* Fraction = 1 test cases */

    @Test
    fun onPEC_fractionOne_expandedTrue_trackingFalse_becomesStateOpeningThenStateOpen() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f,
            expanded = true,
            tracking = false
        )

        assertThat(listener.previousState).isEqualTo(STATE_OPENING)
        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    @Test
    fun onPEC_fractionOne_expandedTrue_trackingTrue_becomesStateOpening() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f,
            expanded = true,
            tracking = true
        )

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionOne_expandedFalse_trackingFalse_becomesStateClosed() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        shadeExpansionStateManager.updateState(STATE_OPEN)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f,
            expanded = false,
            tracking = false
        )

        assertThat(listener.state).isEqualTo(STATE_CLOSED)
    }

    @Test
    fun onPEC_fractionOne_expandedFalse_trackingTrue_doesNotBecomeStateClosed() {
        val listener = TestShadeStateListener()
        shadeExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        shadeExpansionStateManager.updateState(STATE_OPEN)

        shadeExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f,
            expanded = false,
            tracking = true
        )

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* ***** end [PanelExpansionStateManager.onPanelExpansionChanged] test cases ******/

    class TestShadeExpansionListener : ShadeExpansionListener {
        var fraction: Float = 0f
        var expanded: Boolean = false
        var tracking: Boolean = false

        override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
            this.fraction = event.fraction
            this.expanded = event.expanded
            this.tracking = event.tracking
        }
    }

    class TestShadeStateListener : ShadeStateListener {
        @PanelState var previousState: Int = STATE_CLOSED
        @PanelState var state: Int = STATE_CLOSED

        override fun onPanelStateChanged(state: Int) {
            this.previousState = this.state
            this.state = state
        }
    }
}
