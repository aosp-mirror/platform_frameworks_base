package com.android.systemui.animation

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.IRemoteAnimationFinishedCallback
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import junit.framework.AssertionFailedError
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ActivityTransitionAnimatorTest : SysuiTestCase() {
    private val transitionContainer = LinearLayout(mContext)
    private val testTransitionAnimator = fakeTransitionAnimator()
    @Mock lateinit var callback: ActivityTransitionAnimator.Callback
    @Mock lateinit var listener: ActivityTransitionAnimator.Listener
    @Spy private val controller = TestTransitionAnimatorController(transitionContainer)
    @Mock lateinit var iCallback: IRemoteAnimationFinishedCallback

    private lateinit var activityTransitionAnimator: ActivityTransitionAnimator
    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setup() {
        activityTransitionAnimator =
            ActivityTransitionAnimator(
                testTransitionAnimator,
                testTransitionAnimator,
                disableWmTimeout = true
            )
        activityTransitionAnimator.callback = callback
        activityTransitionAnimator.addListener(listener)
    }

    @After
    fun tearDown() {
        activityTransitionAnimator.removeListener(listener)
    }

    private fun startIntentWithAnimation(
        animator: ActivityTransitionAnimator = this.activityTransitionAnimator,
        controller: ActivityTransitionAnimator.Controller? = this.controller,
        animate: Boolean = true,
        intentStarter: (RemoteAnimationAdapter?) -> Int
    ) {
        // We start in a new thread so that we can ensure that the callbacks are called in the main
        // thread.
        thread {
                animator.startIntentWithAnimation(
                    controller = controller,
                    animate = animate,
                    intentStarter = intentStarter
                )
            }
            .join()
    }

    @Test
    fun animationAdapterIsNullIfControllerIsNull() {
        var startedIntent = false
        var animationAdapter: RemoteAnimationAdapter? = null

        startIntentWithAnimation(controller = null) { adapter ->
            startedIntent = true
            animationAdapter = adapter

            ActivityManager.START_SUCCESS
        }

        assertTrue(startedIntent)
        assertNull(animationAdapter)
    }

    @Test
    fun animatesIfActivityOpens() {
        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        var animationAdapter: RemoteAnimationAdapter? = null
        startIntentWithAnimation { adapter ->
            animationAdapter = adapter
            ActivityManager.START_SUCCESS
        }

        assertNotNull(animationAdapter)
        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        assertTrue(willAnimateCaptor.value)
    }

    @Test
    fun doesNotAnimateIfActivityIsAlreadyOpen() {
        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        startIntentWithAnimation { ActivityManager.START_DELIVERED_TO_TOP }

        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        assertFalse(willAnimateCaptor.value)
    }

    @Test
    fun animatesIfActivityIsAlreadyOpenAndIsOnKeyguard() {
        `when`(callback.isOnKeyguard()).thenReturn(true)

        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        var animationAdapter: RemoteAnimationAdapter? = null

        startIntentWithAnimation(activityTransitionAnimator) { adapter ->
            animationAdapter = adapter
            ActivityManager.START_DELIVERED_TO_TOP
        }

        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        verify(callback).hideKeyguardWithAnimation(any())

        assertTrue(willAnimateCaptor.value)
        assertNull(animationAdapter)
    }

    @Test
    fun doesNotAnimateIfAnimateIsFalse() {
        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        startIntentWithAnimation(animate = false) { ActivityManager.START_SUCCESS }

        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        assertFalse(willAnimateCaptor.value)
    }

    @Test
    fun doesNotStartIfAnimationIsCancelled() {
        val runner = activityTransitionAnimator.createRunner(controller)
        runner.onAnimationCancelled()
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onTransitionAnimationCancelled()
        verify(controller, never()).onTransitionAnimationStart(anyBoolean())
        verify(listener).onTransitionAnimationCancelled()
        verify(listener, never()).onTransitionAnimationStart()
        assertNull(runner.delegate)
    }

    @Test
    fun cancelsIfNoOpeningWindowIsFound() {
        val runner = activityTransitionAnimator.createRunner(controller)
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onTransitionAnimationCancelled()
        verify(controller, never()).onTransitionAnimationStart(anyBoolean())
        verify(listener).onTransitionAnimationCancelled()
        verify(listener, never()).onTransitionAnimationStart()
        assertNull(runner.delegate)
    }

    @Test
    fun startsAnimationIfWindowIsOpening() {
        val runner = activityTransitionAnimator.createRunner(controller)
        runner.onAnimationStart(0, arrayOf(fakeWindow()), emptyArray(), emptyArray(), iCallback)
        waitForIdleSync()
        verify(listener).onTransitionAnimationStart()
        verify(controller).onTransitionAnimationStart(anyBoolean())
    }

    @Test
    fun creatingControllerFromNormalViewThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTransitionAnimator.Controller.fromView(FrameLayout(mContext))
        }
    }

    @Test
    fun disposeRunner_delegateDereferenced() {
        val runner = activityTransitionAnimator.createRunner(controller)
        assertNotNull(runner.delegate)
        runner.dispose()
        waitForIdleSync()
        assertNull(runner.delegate)
    }

    private fun fakeWindow(): RemoteAnimationTarget {
        val bounds = Rect(10 /* left */, 20 /* top */, 30 /* right */, 40 /* bottom */)
        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.topActivity = ComponentName("com.android.systemui", "FakeActivity")
        taskInfo.topActivityInfo = ActivityInfo().apply { applicationInfo = ApplicationInfo() }

        return RemoteAnimationTarget(
            0,
            RemoteAnimationTarget.MODE_OPENING,
            SurfaceControl(),
            false,
            Rect(),
            Rect(),
            0,
            Point(),
            Rect(),
            bounds,
            WindowConfiguration(),
            false,
            SurfaceControl(),
            Rect(),
            taskInfo,
            false
        )
    }
}

/**
 * A simple implementation of [ActivityTransitionAnimator.Controller] which throws if it is called
 * outside of the main thread.
 */
private class TestTransitionAnimatorController(override var transitionContainer: ViewGroup) :
    ActivityTransitionAnimator.Controller {
    override fun createAnimatorState() =
        TransitionAnimator.State(
            top = 100,
            bottom = 200,
            left = 300,
            right = 400,
            topCornerRadius = 10f,
            bottomCornerRadius = 20f
        )

    private fun assertOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw AssertionFailedError("Called outside of main thread.")
        }
    }

    override fun onIntentStarted(willAnimate: Boolean) {
        assertOnMainThread()
    }

    override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onTransitionAnimationProgress(
        state: TransitionAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        assertOnMainThread()
    }

    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
        assertOnMainThread()
    }
}
