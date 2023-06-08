package com.android.systemui.statusbar.gesture

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.InputEvent
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeDisplayTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class GenericGestureDetectorTest : SysuiTestCase() {

    private lateinit var gestureDetector: TestGestureDetector
    private val displayTracker = FakeDisplayTracker(mContext)

    @Before
    fun setUp() {
        gestureDetector = TestGestureDetector()
    }

    @Test
    fun noCallbacksRegistered_notGestureListening() {
        assertThat(gestureDetector.isGestureListening).isFalse()
    }

    @Test
    fun callbackRegistered_isGestureListening() {
        gestureDetector.addOnGestureDetectedCallback("tag"){}

        assertThat(gestureDetector.isGestureListening).isTrue()
    }

    @Test
    fun multipleCallbacksRegistered_isGestureListening() {
        gestureDetector.addOnGestureDetectedCallback("tag"){}
        gestureDetector.addOnGestureDetectedCallback("tag2"){}

        assertThat(gestureDetector.isGestureListening).isTrue()
    }

    @Test
    fun allCallbacksUnregistered_notGestureListening() {
        gestureDetector.addOnGestureDetectedCallback("tag"){}
        gestureDetector.addOnGestureDetectedCallback("tag2"){}

        gestureDetector.removeOnGestureDetectedCallback("tag")
        gestureDetector.removeOnGestureDetectedCallback("tag2")

        assertThat(gestureDetector.isGestureListening).isFalse()
    }

    @Test
    fun someButNotAllCallbacksUnregistered_isGestureListening() {
        gestureDetector.addOnGestureDetectedCallback("tag"){}
        gestureDetector.addOnGestureDetectedCallback("tag2"){}

        gestureDetector.removeOnGestureDetectedCallback("tag2")

        assertThat(gestureDetector.isGestureListening).isTrue()
    }

    @Test
    fun onInputEvent_meetsGestureCriteria_allCallbacksNotified() {
        var callbackNotified = false
        gestureDetector.addOnGestureDetectedCallback("tag"){
            callbackNotified = true
        }

        gestureDetector.onInputEvent(
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, CORRECT_X, 0f, 0)
        )

        assertThat(callbackNotified).isTrue()
    }

    @Test
    fun onInputEvent_doesNotMeetGestureCriteria_callbackNotNotified() {
        var callbackNotified = false
        gestureDetector.addOnGestureDetectedCallback("tag"){
            callbackNotified = true
        }

        gestureDetector.onInputEvent(
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, CORRECT_X - 5, 0f, 0)
        )

        assertThat(callbackNotified).isFalse()
    }

    @Test
    fun callbackUnregisteredThenGestureDetected_oldCallbackNotNotified() {
        var oldCallbackNotified = false
        gestureDetector.addOnGestureDetectedCallback("tag"){
            oldCallbackNotified = true
        }
        gestureDetector.addOnGestureDetectedCallback("tag2"){}

        gestureDetector.removeOnGestureDetectedCallback("tag")
        gestureDetector.onInputEvent(
            MotionEvent.obtain(
                0,
                0,
                MotionEvent.ACTION_DOWN,
                CORRECT_X,
                0f,
                0
            )
        )

        assertThat(oldCallbackNotified).isFalse()
    }

    inner class TestGestureDetector : GenericGestureDetector("fakeTag", displayTracker) {
        var isGestureListening = false

        override fun onInputEvent(ev: InputEvent) {
            if (ev is MotionEvent && ev.x == CORRECT_X) {
                onGestureDetected(ev)
            }
        }

        override fun startGestureListening() {
            super.startGestureListening()
            isGestureListening = true
        }

        override fun stopGestureListening() {
            super.stopGestureListening()
            isGestureListening = false
        }
    }
}

private const val CORRECT_X = 1234f
