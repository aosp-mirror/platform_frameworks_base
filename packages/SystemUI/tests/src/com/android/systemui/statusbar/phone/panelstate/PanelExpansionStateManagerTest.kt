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

package com.android.systemui.statusbar.phone.panelstate

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class PanelExpansionStateManagerTest : SysuiTestCase() {

    private lateinit var panelExpansionStateManager: PanelExpansionStateManager

    @Before
    fun setUp() {
        panelExpansionStateManager = PanelExpansionStateManager()
    }

    @Test
    fun onPanelExpansionChanged_listenerNotified() {
        val listener = TestPanelExpansionListener()
        panelExpansionStateManager.addExpansionListener(listener)
        val fraction = 0.6f
        val expanded = true
        val tracking = true
        val dragDownAmount = 1234f

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction, expanded, tracking, dragDownAmount)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.expanded).isEqualTo(expanded)
        assertThat(listener.tracking).isEqualTo(tracking)
        assertThat(listener.dragDownAmountPx).isEqualTo(dragDownAmount)
    }

    @Test
    fun addExpansionListener_listenerNotifiedOfCurrentValues() {
        val fraction = 0.6f
        val expanded = true
        val tracking = true
        val dragDownAmount = 1234f
        panelExpansionStateManager.onPanelExpansionChanged(
            fraction, expanded, tracking, dragDownAmount)
        val listener = TestPanelExpansionListener()

        panelExpansionStateManager.addExpansionListener(listener)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.expanded).isEqualTo(expanded)
        assertThat(listener.tracking).isEqualTo(tracking)
        assertThat(listener.dragDownAmountPx).isEqualTo(dragDownAmount)
    }

    @Test
    fun updateState_listenerNotified() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)

        panelExpansionStateManager.updateState(STATE_OPEN)

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* ***** [PanelExpansionStateManager.onPanelExpansionChanged] test cases *******/

    /* Fraction < 1 test cases */

    @Test
    fun onPEC_fractionLessThanOne_expandedTrue_trackingFalse_becomesStateOpening() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f, expanded = true, tracking = false, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedTrue_trackingTrue_becomesStateOpening() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f, expanded = true, tracking = true, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedFalse_trackingFalse_becomesStateClosed() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        panelExpansionStateManager.updateState(STATE_OPEN)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f, expanded = false, tracking = false, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_CLOSED)
    }

    @Test
    fun onPEC_fractionLessThanOne_expandedFalse_trackingTrue_doesNotBecomeStateClosed() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        panelExpansionStateManager.updateState(STATE_OPEN)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 0.5f, expanded = false, tracking = true, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* Fraction = 1 test cases */

    @Test
    fun onPEC_fractionOne_expandedTrue_trackingFalse_becomesStateOpeningThenStateOpen() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f)

        assertThat(listener.previousState).isEqualTo(STATE_OPENING)
        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    @Test
    fun onPEC_fractionOne_expandedTrue_trackingTrue_becomesStateOpening() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f, expanded = true, tracking = true, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_OPENING)
    }

    @Test
    fun onPEC_fractionOne_expandedFalse_trackingFalse_becomesStateClosed() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        panelExpansionStateManager.updateState(STATE_OPEN)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f, expanded = false, tracking = false, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_CLOSED)
    }

    @Test
    fun onPEC_fractionOne_expandedFalse_trackingTrue_doesNotBecomeStateClosed() {
        val listener = TestPanelStateListener()
        panelExpansionStateManager.addStateListener(listener)
        // Start out on a different state
        panelExpansionStateManager.updateState(STATE_OPEN)

        panelExpansionStateManager.onPanelExpansionChanged(
            fraction = 1f, expanded = false, tracking = true, dragDownPxAmount = 0f)

        assertThat(listener.state).isEqualTo(STATE_OPEN)
    }

    /* ***** end [PanelExpansionStateManager.onPanelExpansionChanged] test cases ******/

    class TestPanelExpansionListener : PanelExpansionListener {
        var fraction: Float = 0f
        var expanded: Boolean = false
        var tracking: Boolean = false
        var dragDownAmountPx: Float = 0f

        override fun onPanelExpansionChanged(event: PanelExpansionChangeEvent) {
            this.fraction = event.fraction
            this.expanded = event.expanded
            this.tracking = event.tracking
            this.dragDownAmountPx = event.dragDownPxAmount
        }
    }

    class TestPanelStateListener : PanelStateListener {
        @PanelState var previousState: Int = STATE_CLOSED
        @PanelState var state: Int = STATE_CLOSED

        override fun onPanelStateChanged(state: Int) {
            this.previousState = this.state
            this.state = state
        }
    }
}
