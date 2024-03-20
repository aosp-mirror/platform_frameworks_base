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

package com.android.systemui.haptics.slider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SliderStateTrackerTest : SysuiTestCase() {

    @Mock private lateinit var sliderStateListener: SliderStateListener
    private val sliderEventProducer = FakeSliderEventProducer()
    private lateinit var mSliderStateTracker: SliderStateTracker

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun initializeSliderTracker_startsTracking() = runTest {
        // GIVEN Initialized tracker
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // THEN the tracker job is active
        assertThat(mSliderStateTracker.isTracking).isTrue()
    }

    @Test
    fun stopTracking_onAnyState_resetsToIdle() = runTest {
        enumValues<SliderState>().forEach {
            // GIVEN Initialized tracker
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            // GIVEN a state in the state machine
            mSliderStateTracker.setState(it)

            // WHEN the tracker stops tracking the state and listening to events
            mSliderStateTracker.stopTracking()

            // THEN The state is idle and the tracker is not active
            assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
            assertThat(mSliderStateTracker.isTracking).isFalse()
        }
    }

    // Tests on the IDLE state
    @Test
    fun initializeSliderTracker_isIdle() = runTest {
        // GIVEN Initialized tracker
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // THEN The state is idle and the listener is not called to play haptics
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyZeroInteractions(sliderStateListener)
    }

    @Test
    fun startsTrackingTouch_onIdle_entersWaitState() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a start of tracking touch event
        val progress = 0f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // THEN the tracker moves to the wait state and the timer job begins
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.WAIT)
        verifyZeroInteractions(sliderStateListener)
        assertThat(mSliderStateTracker.isWaiting).isTrue()
    }

    // Tests on the WAIT state

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun waitCompletes_onWait_movesToHandleAcquired() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT
        val progress = 0f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // WHEN the wait time completes plus a small buffer time
        advanceTimeBy(config.waitTimeMillis + 10L)

        // THEN the tracker moves to the DRAG_HANDLE_ACQUIRED_BY_TOUCH state
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        verify(sliderStateListener).onHandleAcquiredByTouch()
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun impreciseTouch_onWait_movesToHandleAcquired() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT at the middle of the
        // slider
        var progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // GIVEN a progress event due to an imprecise touch with a progress below threshold
        progress += (config.jumpThreshold - 0.01f)
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker moves to the DRAG_HANDLE_ACQUIRED_BY_TOUCH state without the timer job
        // being complete
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        verify(sliderStateListener).onHandleAcquiredByTouch()
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun trackJump_onWait_movesToJumpTrackLocationSelected() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT at the middle of the
        // slider
        var progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // GIVEN a progress event due to a touch on the slider track beyond threshold
        progress += (config.jumpThreshold + 0.01f)
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker moves to the jump-track location selected state
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.JUMP_TRACK_LOCATION_SELECTED)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        verify(sliderStateListener).onProgressJump(anyFloat())
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun upperBookendSelection_onWait_movesToBookendSelected() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT at the middle of the
        // slider
        var progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // GIVEN a progress event due to a touch on the slider upper bookend zone.
        progress = (config.upperBookendThreshold + 0.01f)
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker moves to the jump-track location selected state
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.JUMP_BOOKEND_SELECTED)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun lowerBookendSelection_onWait_movesToBookendSelected() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT at the middle of the
        // slider
        var progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // GIVEN a progress event due to a touch on the slider lower bookend zone
        progress = (config.lowerBookendThreshold - 0.01f)
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker moves to the JUMP_TRACK_LOCATION_SELECTED state
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.JUMP_BOOKEND_SELECTED)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun stopTracking_onWait_whenWaitingJobIsActive_resetsToIdle() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a start of tracking touch event that moves the tracker to WAIT at the middle of the
        // slider
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, 0.5f))
        assertThat(mSliderStateTracker.isWaiting).isTrue()

        // GIVEN that the tracker stops tracking the state and listening to events
        mSliderStateTracker.stopTracking()

        // THEN the tracker moves to the IDLE state without the timer job being complete
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        assertThat(mSliderStateTracker.isWaiting).isFalse()
        assertThat(mSliderStateTracker.isTracking).isFalse()
        verifyNoMoreInteractions(sliderStateListener)
    }

    // Tests on the JUMP_TRACK_LOCATION_SELECTED state

    @Test
    fun progressChangeByUser_onJumpTrackLocationSelected_movesToDragHandleDragging() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a JUMP_TRACK_LOCATION_SELECTED state
        mSliderStateTracker.setState(SliderState.JUMP_TRACK_LOCATION_SELECTED)

        // GIVEN a progress event due to dragging the handle
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, 0.5f))

        // THEN the tracker moves to the DRAG_HANDLE_DRAGGING state
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
        verify(sliderStateListener).onProgress(anyFloat())
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun touchRelease_onJumpTrackLocationSelected_movesToIdle() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a JUMP_TRACK_LOCATION_SELECTED state
        mSliderStateTracker.setState(SliderState.JUMP_TRACK_LOCATION_SELECTED)

        // GIVEN that the slider stopped tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5f))

        // THEN the tracker executes on onHandleReleasedFromTouch before moving to the IDLE state
        verify(sliderStateListener).onHandleReleasedFromTouch()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun progressChangeByUser_onJumpBookendSelected_movesToDragHandleDragging() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a JUMP_BOOKEND_SELECTED state
        mSliderStateTracker.setState(SliderState.JUMP_BOOKEND_SELECTED)

        // GIVEN that the slider stopped tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, 0.5f))

        // THEN the tracker moves to the DRAG_HANDLE_DRAGGING state
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
        verify(sliderStateListener).onProgress(anyFloat())
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun touchRelease_onJumpBookendSelected_movesToIdle() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a JUMP_BOOKEND_SELECTED state
        mSliderStateTracker.setState(SliderState.JUMP_BOOKEND_SELECTED)

        // GIVEN that the slider stopped tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5f))

        // THEN the tracker executes on onHandleReleasedFromTouch before moving to the IDLE state
        verify(sliderStateListener).onHandleReleasedFromTouch()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyNoMoreInteractions(sliderStateListener)
    }

    // Tests on the DRAG_HANDLE_ACQUIRED state

    @Test
    fun progressChangeByUser_onHandleAcquired_movesToDragHandleDragging() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a DRAG_HANDLE_ACQUIRED_BY_TOUCH state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)

        // GIVEN a progress change by the user
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker moves to the DRAG_HANDLE_DRAGGING state
        verify(sliderStateListener).onProgress(progress)
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun touchRelease_onHandleAcquired_movesToIdle() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a DRAG_HANDLE_ACQUIRED_BY_TOUCH state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)

        // GIVEN that the handle stops tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5f))

        // THEN the tracker executes on onHandleReleasedFromTouch before moving to the IDLE state
        verify(sliderStateListener).onHandleReleasedFromTouch()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyNoMoreInteractions(sliderStateListener)
    }

    // Tests on DRAG_HANDLE_DRAGGING

    @Test
    fun progressChangeByUser_onHandleDragging_progressOutsideOfBookends_doesNotChangeState() =
        runTest {
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            // GIVEN a DRAG_HANDLE_DRAGGING state
            mSliderStateTracker.setState(SliderState.DRAG_HANDLE_DRAGGING)

            // GIVEN a progress change by the user outside of bookend bounds
            val progress = 0.5f
            sliderEventProducer.sendEvent(
                SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
            )

            // THEN the tracker does not change state and executes the onProgress call
            assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
            verify(sliderStateListener).onProgress(progress)
            verifyNoMoreInteractions(sliderStateListener)
        }

    @Test
    fun progressChangeByUser_onHandleDragging_reachesLowerBookend_movesToHandleReachedBookend() =
        runTest {
            val config = SeekableSliderTrackerConfig()
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

            // GIVEN a DRAG_HANDLE_DRAGGING state
            mSliderStateTracker.setState(SliderState.DRAG_HANDLE_DRAGGING)

            // GIVEN a progress change by the user reaching the lower bookend
            val progress = config.lowerBookendThreshold - 0.01f
            sliderEventProducer.sendEvent(
                SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
            )

            // THEN the tracker moves to the DRAG_HANDLE_REACHED_BOOKEND state and executes the
            // corresponding callback
            assertThat(mSliderStateTracker.currentState)
                .isEqualTo(SliderState.DRAG_HANDLE_REACHED_BOOKEND)
            verify(sliderStateListener).onLowerBookend()
            verifyNoMoreInteractions(sliderStateListener)
        }

    @Test
    fun progressChangeByUser_onHandleDragging_reachesUpperBookend_movesToHandleReachedBookend() =
        runTest {
            val config = SeekableSliderTrackerConfig()
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

            // GIVEN a DRAG_HANDLE_DRAGGING state
            mSliderStateTracker.setState(SliderState.DRAG_HANDLE_DRAGGING)

            // GIVEN a progress change by the user reaching the upper bookend
            val progress = config.upperBookendThreshold + 0.01f
            sliderEventProducer.sendEvent(
                SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
            )

            // THEN the tracker moves to the DRAG_HANDLE_REACHED_BOOKEND state and executes the
            // corresponding callback
            assertThat(mSliderStateTracker.currentState)
                .isEqualTo(SliderState.DRAG_HANDLE_REACHED_BOOKEND)
            verify(sliderStateListener).onUpperBookend()
            verifyNoMoreInteractions(sliderStateListener)
        }

    @Test
    fun touchRelease_onHandleDragging_movesToIdle() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a DRAG_HANDLE_DRAGGING state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_DRAGGING)

        // GIVEN that the slider stops tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5f))

        // THEN the tracker executes on onHandleReleasedFromTouch before moving to the IDLE state
        verify(sliderStateListener).onHandleReleasedFromTouch()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyNoMoreInteractions(sliderStateListener)
    }

    // Tests on the DRAG_HANDLE_REACHED_BOOKEND state

    @Test
    fun progressChangeByUser_outsideOfBookendRange_onLowerBookend_movesToDragHandleDragging() =
        runTest {
            val config = SeekableSliderTrackerConfig()
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

            // GIVEN a DRAG_HANDLE_REACHED_BOOKEND state
            mSliderStateTracker.setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)

            // GIVEN a progress event that falls outside of the lower bookend range
            val progress = config.lowerBookendThreshold + 0.01f
            sliderEventProducer.sendEvent(
                SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
            )

            // THEN the tracker moves to the DRAG_HANDLE_DRAGGING state and executes accordingly
            verify(sliderStateListener).onProgress(progress)
            assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
            verifyNoMoreInteractions(sliderStateListener)
        }

    @Test
    fun progressChangeByUser_insideOfBookendRange_onLowerBookend_doesNotChangeState() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a DRAG_HANDLE_REACHED_BOOKEND state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)

        // GIVEN a progress event that falls inside of the lower bookend range
        val progress = config.lowerBookendThreshold - 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker stays in the current state and executes accordingly
        verify(sliderStateListener).onLowerBookend()
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.DRAG_HANDLE_REACHED_BOOKEND)
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun progressChangeByUser_outsideOfBookendRange_onUpperBookend_movesToDragHandleDragging() =
        runTest {
            val config = SeekableSliderTrackerConfig()
            initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

            // GIVEN a DRAG_HANDLE_REACHED_BOOKEND state
            mSliderStateTracker.setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)

            // GIVEN a progress event that falls outside of the upper bookend range
            val progress = config.upperBookendThreshold - 0.01f
            sliderEventProducer.sendEvent(
                SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
            )

            // THEN the tracker moves to the DRAG_HANDLE_DRAGGING state and executes accordingly
            verify(sliderStateListener).onProgress(progress)
            assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.DRAG_HANDLE_DRAGGING)
            verifyNoMoreInteractions(sliderStateListener)
        }

    @Test
    fun progressChangeByUser_insideOfBookendRange_onUpperBookend_doesNotChangeState() = runTest {
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a DRAG_HANDLE_REACHED_BOOKEND state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)

        // GIVEN a progress event that falls inside of the upper bookend range
        val progress = config.upperBookendThreshold + 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress)
        )

        // THEN the tracker stays in the current state and executes accordingly
        verify(sliderStateListener).onUpperBookend()
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.DRAG_HANDLE_REACHED_BOOKEND)
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun touchRelease_onHandleReachedBookend_movesToIdle() = runTest {
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a DRAG_HANDLE_REACHED_BOOKEND state
        mSliderStateTracker.setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)

        // GIVEN that the handle stops tracking touch
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5f))

        // THEN the tracker executes on onHandleReleasedFromTouch before moving to the IDLE state
        verify(sliderStateListener).onHandleReleasedFromTouch()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyNoMoreInteractions(sliderStateListener)
    }

    @Test
    fun onStartedTrackingProgram_atTheMiddle_onIdle_movesToArrowHandleMovedOnce() = runTest {
        // GIVEN an initialized tracker in the IDLE state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // GIVEN a progress due to an external source that lands at the middle of the slider
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.STARTED_TRACKING_PROGRAM, progress)
        )

        // THEN the state moves to ARROW_HANDLE_MOVED_ONCE and the listener is called to play
        // haptics
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.ARROW_HANDLE_MOVED_ONCE)
        verify(sliderStateListener).onSelectAndArrow(progress)
    }

    @Test
    fun onStartedTrackingProgram_atUpperBookend_onIdle_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the IDLE state
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // GIVEN a progress due to an external source that lands at the upper bookend
        val progress = config.upperBookendThreshold + 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.STARTED_TRACKING_PROGRAM, progress)
        )

        // THEN the tracker executes upper bookend haptics before moving back to IDLE
        verify(sliderStateListener).onUpperBookend()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
    }

    @Test
    fun onStartedTrackingProgram_atLowerBookend_onIdle_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the IDLE state
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)

        // WHEN a progress is recorded due to an external source that lands at the lower bookend
        val progress = config.lowerBookendThreshold - 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.STARTED_TRACKING_PROGRAM, progress)
        )

        // THEN the tracker executes lower bookend haptics before moving to IDLE
        verify(sliderStateListener).onLowerBookend()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
    }

    @Test
    fun onArrowUp_onArrowMovedOnce_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVED_ONCE state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVED_ONCE)

        // WHEN the external stimulus is released
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.STOPPED_TRACKING_PROGRAM, progress)
        )

        // THEN the tracker moves back to IDLE and there are no haptics
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyZeroInteractions(sliderStateListener)
    }

    @Test
    fun onStartTrackingTouch_onArrowMovedOnce_movesToWait() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVED_ONCE state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVED_ONCE)

        // WHEN the slider starts tracking touch
        val progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // THEN the tracker moves back to WAIT and starts the waiting job. Also, there are no
        // haptics
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.WAIT)
        assertThat(mSliderStateTracker.isWaiting).isTrue()
        verifyZeroInteractions(sliderStateListener)
    }

    @Test
    fun onProgressChangeByProgram_onArrowMovedOnce_movesToArrowMovesContinuously() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVED_ONCE state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVED_ONCE)

        // WHEN the slider gets an external progress change
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, progress)
        )

        // THEN the tracker moves to ARROW_HANDLE_MOVES_CONTINUOUSLY and calls the appropriate
        // haptics
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)
        verify(sliderStateListener).onProgress(progress)
    }

    @Test
    fun onArrowUp_onArrowMovesContinuously_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVES_CONTINUOUSLY state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)

        // WHEN the external stimulus is released
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.STOPPED_TRACKING_PROGRAM, progress)
        )

        // THEN the tracker moves to IDLE and no haptics are played
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
        verifyZeroInteractions(sliderStateListener)
    }

    @Test
    fun onStartTrackingTouch_onArrowMovesContinuously_movesToWait() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVES_CONTINUOUSLY state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)

        // WHEN the slider starts tracking touch
        val progress = 0.5f
        sliderEventProducer.sendEvent(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress))

        // THEN the tracker moves to WAIT and the wait job starts.
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.WAIT)
        assertThat(mSliderStateTracker.isWaiting).isTrue()
        verifyZeroInteractions(sliderStateListener)
    }

    @Test
    fun onProgressChangeByProgram_onArrowMovesContinuously_preservesState() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVES_CONTINUOUSLY state
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)

        // WHEN the slider changes progress programmatically at the middle
        val progress = 0.5f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, progress)
        )

        // THEN the tracker stays in the same state and haptics are delivered appropriately
        assertThat(mSliderStateTracker.currentState)
            .isEqualTo(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)
        verify(sliderStateListener).onProgress(progress)
    }

    @Test
    fun onProgramProgress_atLowerBookend_onArrowMovesContinuously_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVES_CONTINUOUSLY state
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)

        // WHEN the slider reaches the lower bookend programmatically
        val progress = config.lowerBookendThreshold - 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, progress)
        )

        // THEN the tracker executes lower bookend haptics before moving to IDLE
        verify(sliderStateListener).onLowerBookend()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
    }

    @Test
    fun onProgramProgress_atUpperBookend_onArrowMovesContinuously_movesToIdle() = runTest {
        // GIVEN an initialized tracker in the ARROW_HANDLE_MOVES_CONTINUOUSLY state
        val config = SeekableSliderTrackerConfig()
        initTracker(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), config)
        mSliderStateTracker.setState(SliderState.ARROW_HANDLE_MOVES_CONTINUOUSLY)

        // WHEN the slider reaches the lower bookend programmatically
        val progress = config.upperBookendThreshold + 0.01f
        sliderEventProducer.sendEvent(
            SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, progress)
        )

        // THEN the tracker executes upper bookend haptics before moving to IDLE
        verify(sliderStateListener).onUpperBookend()
        assertThat(mSliderStateTracker.currentState).isEqualTo(SliderState.IDLE)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun initTracker(
        scope: CoroutineScope,
        config: SeekableSliderTrackerConfig = SeekableSliderTrackerConfig(),
    ) {
        mSliderStateTracker =
            SliderStateTracker(sliderStateListener, sliderEventProducer, scope, config)
        mSliderStateTracker.startTracking()
    }
}
