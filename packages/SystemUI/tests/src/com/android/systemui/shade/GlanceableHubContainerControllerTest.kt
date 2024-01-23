/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.MotionEvent
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class GlanceableHubContainerControllerTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @Mock private lateinit var communalViewModel: CommunalViewModel
    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock private lateinit var shadeInteractor: ShadeInteractor
    @Mock private lateinit var powerManager: PowerManager

    private lateinit var containerView: View
    private lateinit var testableLooper: TestableLooper

    private lateinit var communalInteractor: CommunalInteractor
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var underTest: GlanceableHubContainerController

    private val bouncerShowingFlow = MutableStateFlow(false)
    private val shadeShowingFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        communalInteractor = kosmos.communalInteractor
        communalRepository = kosmos.fakeCommunalRepository

        underTest =
            GlanceableHubContainerController(
                communalInteractor,
                communalViewModel,
                keyguardTransitionInteractor,
                shadeInteractor,
                powerManager
            )
        testableLooper = TestableLooper.get(this)

        communalRepository.setIsCommunalEnabled(true)

        whenever(keyguardTransitionInteractor.isFinishedInStateWhere(any()))
            .thenReturn(bouncerShowingFlow)
        whenever(shadeInteractor.isAnyFullyExpanded).thenReturn(shadeShowingFlow)

        overrideResource(R.dimen.communal_right_edge_swipe_region_width, RIGHT_SWIPE_REGION_WIDTH)
        overrideResource(R.dimen.communal_top_edge_swipe_region_height, TOP_SWIPE_REGION_WIDTH)
    }

    @Test
    fun isEnabled_interactorEnabled_interceptsTouches() {
        communalRepository.setIsCommunalEnabled(true)

        assertThat(underTest.isEnabled()).isTrue()
    }

    @Test
    fun isEnabled_interactorDisabled_doesNotIntercept() {
        communalRepository.setIsCommunalEnabled(false)

        assertThat(underTest.isEnabled()).isFalse()
    }

    @Test
    fun initView_notEnabled_throwsException() {
        communalRepository.setIsCommunalEnabled(false)

        assertThrows(RuntimeException::class.java) { underTest.initView(context) }
    }

    @Test
    fun initView_calledTwice_throwsException() {
        // First call succeeds.
        underTest.initView(context)

        // Second call throws.
        assertThrows(RuntimeException::class.java) { underTest.initView(context) }
    }

    @Test
    fun onTouchEvent_touchInsideGestureRegion_interceptsTouches() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()

        // Touch events are intercepted.
        assertThat(underTest.onTouchEvent(DOWN_IN_RIGHT_SWIPE_REGION_EVENT)).isTrue()
    }

    @Test
    fun onTouchEvent_subsequentTouchesAfterGestureStart_interceptsTouches() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()

        // Initial touch down is intercepted, and so are touches outside of the region, until an up
        // event is received.
        assertThat(underTest.onTouchEvent(DOWN_IN_RIGHT_SWIPE_REGION_EVENT)).isTrue()
        assertThat(underTest.onTouchEvent(MOVE_EVENT)).isTrue()
        assertThat(underTest.onTouchEvent(UP_EVENT)).isTrue()
        assertThat(underTest.onTouchEvent(MOVE_EVENT)).isFalse()
    }

    @Test
    fun onTouchEvent_communalOpen_interceptsTouches() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()
        testableLooper.processAllMessages()

        // Touch events are intercepted.
        assertThat(underTest.onTouchEvent(DOWN_EVENT)).isTrue()
        // User activity sent to PowerManager.
        verify(powerManager).userActivity(any(), any(), any())
    }

    @Test
    fun onTouchEvent_topSwipeWhenHubOpen_returnsFalse() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()

        // Touch event in the top swipe reqgion is not intercepted.
        assertThat(underTest.onTouchEvent(DOWN_IN_TOP_SWIPE_REGION_EVENT)).isFalse()
    }

    @Test
    fun onTouchEvent_communalAndBouncerShowing_doesNotIntercept() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()

        // Bouncer is visible.
        bouncerShowingFlow.value = true
        testableLooper.processAllMessages()

        // Touch events are not intercepted.
        assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
        // User activity is not sent to PowerManager.
        verify(powerManager, times(0)).userActivity(any(), any(), any())
    }

    @Test
    fun onTouchEvent_communalAndShadeShowing_doesNotIntercept() {
        // Communal is open.
        communalRepository.setDesiredScene(CommunalSceneKey.Communal)

        initAndAttachContainerView()

        shadeShowingFlow.value = true
        testableLooper.processAllMessages()

        // Touch events are not intercepted.
        assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
    }

    private fun initAndAttachContainerView() {
        containerView = View(context)
        // Make view clickable so that dispatchTouchEvent returns true.
        containerView.isClickable = true

        underTest.initView(containerView)
        // Attach the view so that flows start collecting.
        ViewUtils.attachView(containerView)
        // Give the view a size so that determining if a touch starts at the right edge works.
        containerView.layout(0, 0, CONTAINER_WIDTH, CONTAINER_HEIGHT)
    }

    companion object {
        private const val CONTAINER_WIDTH = 100
        private const val CONTAINER_HEIGHT = 100
        private const val RIGHT_SWIPE_REGION_WIDTH = 20
        private const val TOP_SWIPE_REGION_WIDTH = 20

        private val DOWN_EVENT =
            MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                CONTAINER_WIDTH.toFloat(),
                CONTAINER_HEIGHT.toFloat(),
                0
            )
        private val DOWN_IN_RIGHT_SWIPE_REGION_EVENT =
            MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, CONTAINER_WIDTH.toFloat(), 0f, 0)
        private val DOWN_IN_TOP_SWIPE_REGION_EVENT =
            MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                0f,
                TOP_SWIPE_REGION_WIDTH.toFloat(),
                0
            )
        private val MOVE_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        private val UP_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            // Glanceable hub requires Compose, no point running any of these tests if compose isn't
            // enabled.
            assumeTrue(ComposeFacade.isComposeAvailable())
        }
    }
}
