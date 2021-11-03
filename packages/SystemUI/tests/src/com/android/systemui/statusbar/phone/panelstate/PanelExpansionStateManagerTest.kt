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
    fun onPanelExpansionChanged_listenersNotified() {
        val listener = TestPanelExpansionListener()
        panelExpansionStateManager.addListener(listener)
        val fraction = 0.6f
        val tracking = true

        panelExpansionStateManager.onPanelExpansionChanged(fraction, tracking)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.tracking).isEqualTo(tracking)
    }

    @Test
    fun addPanelExpansionListener_listenerNotifiedOfCurrentValues() {
        val fraction = 0.6f
        val tracking = true
        panelExpansionStateManager.onPanelExpansionChanged(fraction, tracking)
        val listener = TestPanelExpansionListener()

        panelExpansionStateManager.addListener(listener)

        assertThat(listener.fraction).isEqualTo(fraction)
        assertThat(listener.tracking).isEqualTo(tracking)
    }

    class TestPanelExpansionListener : PanelExpansionListener {
        var fraction: Float = 0f
        var tracking: Boolean = false

        override fun onPanelExpansionChanged(
            fraction: Float,
            tracking: Boolean
        ) {
            this.fraction = fraction
            this.tracking = tracking
        }
    }
}
