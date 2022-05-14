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

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnPreDrawListener
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
class PhoneStatusBarViewControllerTest : SysuiTestCase() {

    private val touchEventHandler = TestTouchEventHandler()

    @Mock
    private lateinit var panelViewController: PanelViewController
    @Mock
    private lateinit var panelView: ViewGroup
    @Mock
    private lateinit var moveFromCenterAnimation: StatusBarMoveFromCenterAnimationController
    @Mock
    private lateinit var sysuiUnfoldComponent: SysUIUnfoldComponent
    @Mock
    private lateinit var progressProvider: ScopedUnfoldTransitionProgressProvider
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var userSwitcherController: StatusBarUserSwitcherController
    @Mock
    private lateinit var viewUtil: ViewUtil

    private lateinit var view: PhoneStatusBarView
    private lateinit var controller: PhoneStatusBarViewController

    private val unfoldConfig = UnfoldConfig()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(panelViewController.view).thenReturn(panelView)
        `when`(sysuiUnfoldComponent.getStatusBarMoveFromCenterAnimationController())
            .thenReturn(moveFromCenterAnimation)
        // create the view on main thread as it requires main looper
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(mContext) // add parent to keep layout params
            view = LayoutInflater.from(mContext)
                .inflate(R.layout.status_bar, parent, false) as PhoneStatusBarView
        }

        controller = createAndInitController(view)
    }

    @Test
    fun constructor_setsTouchHandlerOnView() {
        val interceptEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 10f, 10f, 0)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        view.onInterceptTouchEvent(interceptEvent)
        view.onTouchEvent(event)

        assertThat(touchEventHandler.lastInterceptEvent).isEqualTo(interceptEvent)
        assertThat(touchEventHandler.lastEvent).isEqualTo(event)
    }

    @Test
    fun onViewAttachedAndDrawn_moveFromCenterAnimationEnabled_moveFromCenterAnimationInitialized() {
        val view = createViewMock()
        val argumentCaptor = ArgumentCaptor.forClass(OnPreDrawListener::class.java)
        unfoldConfig.isEnabled = true
        controller = createAndInitController(view)

        verify(view.viewTreeObserver).addOnPreDrawListener(argumentCaptor.capture())
        argumentCaptor.value.onPreDraw()

        verify(moveFromCenterAnimation).onViewsReady(any())
    }

    private fun createViewMock(): PhoneStatusBarView {
        val view = spy(view)
        val viewTreeObserver = mock(ViewTreeObserver::class.java)
        `when`(view.viewTreeObserver).thenReturn(viewTreeObserver)
        `when`(view.isAttachedToWindow).thenReturn(true)
        return view
    }

    private fun createAndInitController(view: PhoneStatusBarView): PhoneStatusBarViewController {
        return PhoneStatusBarViewController.Factory(
            Optional.of(sysuiUnfoldComponent),
            Optional.of(progressProvider),
            userSwitcherController,
            viewUtil,
            configurationController
        ).create(view, touchEventHandler).also {
            it.init()
        }
    }

    private class UnfoldConfig : UnfoldTransitionConfig {
        override var isEnabled: Boolean = false
        override var isHingeAngleEnabled: Boolean = false
        override val halfFoldedTimeoutMillis: Int = 0
    }

    private class TestTouchEventHandler : PhoneStatusBarView.TouchEventHandler {
        var lastEvent: MotionEvent? = null
        var lastInterceptEvent: MotionEvent? = null

        override fun onInterceptTouchEvent(event: MotionEvent?) {
            lastInterceptEvent = event
        }
        override fun handleTouchEvent(event: MotionEvent?): Boolean {
            lastEvent = event
            return false
        }
    }
}
