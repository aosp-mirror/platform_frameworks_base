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

package com.android.systemui.statusbar.phone

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class PhoneStatusBarViewTest : SysuiTestCase() {

    @Mock
    private lateinit var panelViewController: PanelViewController
    @Mock
    private lateinit var panelView: ViewGroup
    @Mock
    private lateinit var scrimController: ScrimController
    @Mock
    private lateinit var statusBar: StatusBar

    private lateinit var view: PhoneStatusBarView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        // TODO(b/197137564): Setting up a panel view and its controller feels unnecessary when
        //   testing just [PhoneStatusBarView].
        `when`(panelViewController.view).thenReturn(panelView)

        view = PhoneStatusBarView(mContext, null)
        view.setScrimController(scrimController)
        view.setBar(statusBar)
    }

    @Test
    fun panelStateChanged_toStateOpening_listenerNotified() {
        val listener = TestStateChangedListener()
        view.setPanelStateChangeListener(listener)

        view.panelExpansionChanged(0.5f, true)

        assertThat(listener.state).isEqualTo(PanelBar.STATE_OPENING)
    }

    @Test
    fun panelStateChanged_toStateOpen_listenerNotified() {
        val listener = TestStateChangedListener()
        view.setPanelStateChangeListener(listener)

        view.panelExpansionChanged(1f, true)

        assertThat(listener.state).isEqualTo(PanelBar.STATE_OPEN)
    }

    @Test
    fun panelStateChanged_toStateClosed_listenerNotified() {
        val listener = TestStateChangedListener()
        view.setPanelStateChangeListener(listener)

        // First, open the panel
        view.panelExpansionChanged(1f, true)

        // Then, close it again
        view.panelExpansionChanged(0f, false)

        assertThat(listener.state).isEqualTo(PanelBar.STATE_CLOSED)
    }

    @Test
    fun onTouchEvent_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onTouchEvent(event)

        assertThat(handler.lastEvent).isEqualTo(event)
    }

    @Test
    fun onTouchEvent_listenerReturnsTrue_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.returnValue = true

        assertThat(view.onTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsFalse_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.returnValue = false

        assertThat(view.onTouchEvent(event)).isFalse()
    }

    @Test
    fun onTouchEvent_noListener_noCrash() {
        view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        // No assert needed, just testing no crash
    }

    private class TestStateChangedListener : PanelBar.PanelStateChangeListener {
        var state: Int = 0
        override fun onStateChanged(state: Int) {
            this.state = state
        }
    }

    private class TestTouchEventHandler : PhoneStatusBarView.TouchEventHandler {
        var lastEvent: MotionEvent? = null
        var returnValue: Boolean = false
        override fun handleTouchEvent(event: MotionEvent?): Boolean {
            lastEvent = event
            return returnValue
        }
    }
}
