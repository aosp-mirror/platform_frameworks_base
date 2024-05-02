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

import android.graphics.Rect
import android.os.PowerManager
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.ambient.touch.TouchHandler
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.sceneDataSourceDelegator
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@ExperimentalCoroutinesApi
@RunWith(ParameterizedAndroidJunit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class GlanceableHubContainerControllerTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos: Kosmos =
        testKosmos().apply {
            // UnconfinedTestDispatcher makes testing simpler due to CommunalInteractor flows using
            // SharedFlow
            testDispatcher = UnconfinedTestDispatcher()
        }

    @Mock private lateinit var communalViewModel: CommunalViewModel
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var dialogFactory: SystemUIDialogFactory
    @Mock private lateinit var touchMonitor: TouchMonitor
    @Mock private lateinit var communalColors: CommunalColors
    private lateinit var ambientTouchComponentFactory: AmbientTouchComponent.Factory

    private lateinit var parentView: FrameLayout
    private lateinit var containerView: View
    private lateinit var testableLooper: TestableLooper

    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var underTest: GlanceableHubContainerController

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        communalRepository = kosmos.fakeCommunalRepository

        ambientTouchComponentFactory =
            object : AmbientTouchComponent.Factory {
                override fun create(
                    lifecycleOwner: LifecycleOwner,
                    touchHandlers: Set<TouchHandler>
                ): AmbientTouchComponent =
                    object : AmbientTouchComponent {
                        override fun getTouchMonitor(): TouchMonitor = touchMonitor
                    }
            }

        with(kosmos) {
            underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    dialogFactory,
                    keyguardTransitionInteractor,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    kosmos.sceneDataSourceDelegator,
                )
        }
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
                        communalColors,
                        ambientTouchComponentFactory,
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
    fun onTouchEvent_communalAndBouncerShowing_doesNotIntercept() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setPrimaryShow(true)
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
                shadeTestUtil.setQsExpansion(1.0f)
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

    @Test
    fun lifecycle_initializedAfterConstruction() =
        with(kosmos) {
            val underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    dialogFactory,
                    keyguardTransitionInteractor,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    kosmos.sceneDataSourceDelegator,
                )

            assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
        }

    @Test
    fun lifecycle_createdAfterViewCreated() =
        with(kosmos) {
            val underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    dialogFactory,
                    keyguardTransitionInteractor,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    kosmos.sceneDataSourceDelegator,
                )

            // Only initView without attaching a view as we don't want the flows to start collecting
            // yet.
            underTest.initView(View(context))

            assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun lifecycle_startedAfterFlowsUpdate() {
        // Flows start collecting due to test setup, causing the state to advance to STARTED.
        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun lifecycle_resumedAfterCommunalShows() {
        // Communal is open.
        goToScene(CommunalScenes.Communal)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun lifecycle_startedAfterCommunalCloses() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

                // Communal closes.
                goToScene(CommunalScenes.Blank)

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_startedAfterPrimaryBouncerShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setPrimaryShow(true)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_startedAfterAlternateBouncerShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setAlternateVisible(true)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_createdAfterDisposeView() {
        // Container view disposed.
        underTest.disposeView()

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun lifecycle_startedAfterShadeShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Shade shows up.
                shadeTestUtil.setQsExpansion(1.0f)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun editMode_communalAvailable() =
        with(kosmos) {
            testScope.runTest {
                val available by collectLastValue(underTest.communalAvailable())
                setCommunalAvailable(false)

                assertThat(available).isFalse()
                communalInteractor.setEditModeOpen(true)
                assertThat(available).isTrue()
            }
        }

    @Test
    fun gestureExclusionZone_setAfterInit() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                assertThat(containerView.systemGestureExclusionRects)
                    .containsExactly(
                        Rect(
                            /* left */ 0,
                            /* top */ TOP_SWIPE_REGION_WIDTH,
                            /* right */ CONTAINER_WIDTH,
                            /* bottom */ CONTAINER_HEIGHT - BOTTOM_SWIPE_REGION_WIDTH
                        )
                    )
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenShadeOpen() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Shade shows up.
                shadeTestUtil.setQsExpansion(1.0f)
                testableLooper.processAllMessages()

                // Exclusion rects are unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenBouncerOpen() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setPrimaryShow(true)
                testableLooper.processAllMessages()

                // Exclusion rects are unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenHubClosed() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Exclusion rect is set.
                assertThat(containerView.systemGestureExclusionRects).hasSize(1)

                // Leave the hub.
                goToScene(CommunalScenes.Blank)

                // Exclusion rect is unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    private fun initAndAttachContainerView() {
        containerView = View(context)

        parentView = FrameLayout(context)
        parentView.addView(containerView)

        underTest.initView(containerView)

        // Attach the view so that flows start collecting.
        ViewUtils.attachView(parentView, CONTAINER_WIDTH, CONTAINER_HEIGHT)
        // Attaching is async so processAllMessages is required for view.repeatWhenAttached to run.
        testableLooper.processAllMessages()
    }

    private fun goToScene(scene: SceneKey) {
        if (SceneContainerFlag.isEnabled) {
            if (scene == CommunalScenes.Communal) {
                kosmos.sceneInteractor.changeScene(Scenes.Communal, "test")
            } else {
                kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "test")
            }
        }
        communalRepository.changeScene(scene)
        testableLooper.processAllMessages()
    }

    companion object {
        private const val CONTAINER_WIDTH = 100
        private const val CONTAINER_HEIGHT = 100
        private const val RIGHT_SWIPE_REGION_WIDTH = 20
        private const val TOP_SWIPE_REGION_WIDTH = 12
        private const val BOTTOM_SWIPE_REGION_WIDTH = 14

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
        private val MOVE_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        private val UP_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }
}
