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

import android.content.Context
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.sceneDataSourceDelegator
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class GlanceableHubContainerControllerTest : SysuiTestCase() {
    private val kosmos: Kosmos =
        testKosmos().apply {
            // UnconfinedTestDispatcher makes testing simpler due to CommunalInteractor flows using
            // SharedFlow
            testDispatcher = UnconfinedTestDispatcher()
        }

    @Mock private lateinit var communalViewModel: CommunalViewModel
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var dialogFactory: SystemUIDialogFactory
    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    private lateinit var shadeInteractor: ShadeInteractor
    private lateinit var keyguardInteractor: KeyguardInteractor

    private lateinit var parentView: FrameLayout
    private lateinit var containerView: View
    private lateinit var testableLooper: TestableLooper

    private lateinit var communalInteractor: CommunalInteractor
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var underTest: GlanceableHubContainerController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        communalInteractor = kosmos.communalInteractor
        communalRepository = kosmos.fakeCommunalRepository
        keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
        keyguardInteractor = kosmos.keyguardInteractor
        shadeInteractor = kosmos.shadeInteractor

        underTest =
            GlanceableHubContainerController(
                communalInteractor,
                communalViewModel,
                dialogFactory,
                keyguardTransitionInteractor,
                keyguardInteractor,
                shadeInteractor,
                powerManager,
                kosmos.sceneDataSourceDelegator,
            )
        testableLooper = TestableLooper.get(this)

        overrideResource(R.dimen.communal_right_edge_swipe_region_width, RIGHT_SWIPE_REGION_WIDTH)
        overrideResource(R.dimen.communal_top_edge_swipe_region_height, TOP_SWIPE_REGION_WIDTH)
        overrideResource(
            R.dimen.communal_bottom_edge_swipe_region_height,
            BOTTOM_SWIPE_REGION_WIDTH
        )

        // Make communal available so that communalInteractor.desiredScene accurately reflects
        // scene changes instead of just returning Blank.
        mSetFlagsRule.enableFlags(Flags.FLAG_COMMUNAL_HUB)
        with(kosmos.testScope) {
            launch { kosmos.setCommunalAvailable(true) }
            testScheduler.runCurrent()
        }

        initAndAttachContainerView()
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(parentView)
    }

    @Test
    fun initView_calledTwice_throwsException() =
        with(kosmos) {
            testScope.runTest {
                underTest =
                    GlanceableHubContainerController(
                        communalInteractor,
                        communalViewModel,
                        dialogFactory,
                        keyguardTransitionInteractor,
                        keyguardInteractor,
                        shadeInteractor,
                        powerManager,
                        kosmos.sceneDataSourceDelegator,
                    )

                // First call succeeds.
                underTest.initView(context)

                // Second call throws.
                assertThrows(RuntimeException::class.java) { underTest.initView(context) }
            }
        }

    @Test
    fun onTouchEvent_communalClosed_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is closed.
                goToScene(CommunalScenes.Blank)

                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_openGesture_interceptsTouches() =
        with(kosmos) {
            testScope.runTest {
                // Communal is closed.
                goToScene(CommunalScenes.Blank)

                // Initial touch down is intercepted, and so are touches outside of the region,
                // until an
                // up event is received.
                assertThat(underTest.onTouchEvent(DOWN_IN_RIGHT_SWIPE_REGION_EVENT)).isTrue()
                assertThat(underTest.onTouchEvent(MOVE_EVENT)).isTrue()
                assertThat(underTest.onTouchEvent(UP_EVENT)).isTrue()
                assertThat(underTest.onTouchEvent(MOVE_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_communalOpen_interceptsTouches() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Touch events are intercepted outside of any gesture areas.
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isTrue()
                // User activity sent to PowerManager.
                verify(powerManager).userActivity(any(), any(), any())
            }
        }

    @Test
    fun onTouchEvent_topSwipeWhenCommunalOpen_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Touch event in the top swipe region is not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_IN_TOP_SWIPE_REGION_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_bottomSwipeWhenCommunalOpen_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Touch event in the bottom swipe region is not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_IN_BOTTOM_SWIPE_REGION_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_topSwipeWhenDreaming_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Device is dreaming.
                fakeKeyguardRepository.setDreaming(true)
                runCurrent()

                // Touch event in the top swipe region is not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_IN_TOP_SWIPE_REGION_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_bottomSwipeWhenDreaming_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Device is dreaming.
                fakeKeyguardRepository.setDreaming(true)
                runCurrent()

                // Touch event in the bottom swipe region is not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_IN_BOTTOM_SWIPE_REGION_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_communalAndBouncerShowing_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    KeyguardState.GLANCEABLE_HUB,
                    KeyguardState.PRIMARY_BOUNCER,
                    testScope
                )
                testableLooper.processAllMessages()

                // Touch events are not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
                // User activity is not sent to PowerManager.
                verify(powerManager, times(0)).userActivity(any(), any(), any())
            }
        }

    @Test
    fun onTouchEvent_communalAndShadeShowing_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Shade shows up.
                fakeShadeRepository.setQsExpansion(1.0f)
                testableLooper.processAllMessages()

                // Touch events are not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
            }
        }

    @Test
    fun onTouchEvent_containerViewDisposed_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Touch events are intercepted.
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isTrue()

                // Container view disposed.
                underTest.disposeView()

                // Touch events are not intercepted.
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
            }
        }

    private fun initAndAttachContainerView() {
        containerView = View(context)

        parentView = FrameLayout(context)
        parentView.addView(containerView)

        underTest.initView(containerView)

        // Attach the view so that flows start collecting.
        ViewUtils.attachView(parentView)
        // Attaching is async so processAllMessages is required for view.repeatWhenAttached to run.
        testableLooper.processAllMessages()

        // Give the view a fixed size to simplify testing for edge swipes.
        val lp =
            parentView.layoutParams.apply {
                width = CONTAINER_WIDTH
                height = CONTAINER_HEIGHT
            }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.updateViewLayout(parentView, lp)
    }

    private fun goToScene(scene: SceneKey) {
        communalRepository.changeScene(scene)
        testableLooper.processAllMessages()
    }

    companion object {
        private const val CONTAINER_WIDTH = 100
        private const val CONTAINER_HEIGHT = 100
        private const val RIGHT_SWIPE_REGION_WIDTH = 20
        private const val TOP_SWIPE_REGION_WIDTH = 20
        private const val BOTTOM_SWIPE_REGION_WIDTH = 20

        /**
         * A touch down event right in the middle of the screen, to avoid being in any of the swipe
         * regions.
         */
        private val DOWN_EVENT =
            MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                CONTAINER_WIDTH.toFloat() / 2,
                CONTAINER_HEIGHT.toFloat() / 2,
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
        private val DOWN_IN_BOTTOM_SWIPE_REGION_EVENT =
            MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, CONTAINER_HEIGHT.toFloat(), 0)
        private val MOVE_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        private val UP_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
    }
}
