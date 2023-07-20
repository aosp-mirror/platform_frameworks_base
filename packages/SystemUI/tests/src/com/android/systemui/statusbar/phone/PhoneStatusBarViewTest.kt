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
import com.android.systemui.shade.NotificationPanelViewController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class PhoneStatusBarViewTest : SysuiTestCase() {

    @Mock
    private lateinit var notificationPanelViewController: NotificationPanelViewController
    @Mock
    private lateinit var panelView: ViewGroup

    private lateinit var view: PhoneStatusBarView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        view = PhoneStatusBarView(mContext, null)
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
    fun onInterceptTouchEvent_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onInterceptTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
    }

    @Test
    fun onTouchEvent_listenerReturnsTrue_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsFalse_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onTouchEvent(event)).isFalse()
    }

    @Test
    fun onTouchEvent_noListener_noCrash() {
        view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        // No assert needed, just testing no crash
    }

    private class TestTouchEventHandler : PhoneStatusBarView.TouchEventHandler {
        var lastInterceptEvent: MotionEvent? = null
        var lastEvent: MotionEvent? = null
        var handleTouchReturnValue: Boolean = false

        override fun onInterceptTouchEvent(event: MotionEvent?) {
            lastInterceptEvent = event
        }

        override fun handleTouchEvent(event: MotionEvent?): Boolean {
            lastEvent = event
            return handleTouchReturnValue
        }
    }
}
