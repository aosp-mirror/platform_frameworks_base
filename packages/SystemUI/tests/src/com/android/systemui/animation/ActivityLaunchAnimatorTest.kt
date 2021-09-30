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
import android.util.Log
import android.view.IRemoteAnimationFinishedCallback
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import junit.framework.AssertionFailedError
import kotlin.concurrent.thread
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ActivityLaunchAnimatorTest : SysuiTestCase() {
    private val launchContainer = LinearLayout(mContext)
    private val launchAnimator = LaunchAnimator(mContext, isForTesting = true)
    @Mock lateinit var callback: ActivityLaunchAnimator.Callback
    @Spy private val controller = TestLaunchAnimatorController(launchContainer)
    @Mock lateinit var iCallback: IRemoteAnimationFinishedCallback
    @Mock lateinit var failHandler: Log.TerribleFailureHandler

    private lateinit var activityLaunchAnimator: ActivityLaunchAnimator
    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setup() {
        activityLaunchAnimator = ActivityLaunchAnimator(launchAnimator)
        activityLaunchAnimator.callback = callback
    }

    private fun startIntentWithAnimation(
        animator: ActivityLaunchAnimator = this.activityLaunchAnimator,
        controller: ActivityLaunchAnimator.Controller? = this.controller,
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
        }.join()
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
        val animator = ActivityLaunchAnimator(launchAnimator)
        animator.callback = callback

        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        var animationAdapter: RemoteAnimationAdapter? = null

        startIntentWithAnimation(animator) { adapter ->
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
        val runner = activityLaunchAnimator.createRunner(controller)
        runner.onAnimationCancelled()
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onLaunchAnimationCancelled()
        verify(controller, never()).onLaunchAnimationStart(anyBoolean())
    }

    @Test
    fun cancelsIfNoOpeningWindowIsFound() {
        val runner = activityLaunchAnimator.createRunner(controller)
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onLaunchAnimationCancelled()
        verify(controller, never()).onLaunchAnimationStart(anyBoolean())
    }

    @Test
    fun startsAnimationIfWindowIsOpening() {
        val runner = activityLaunchAnimator.createRunner(controller)
        runner.onAnimationStart(0, arrayOf(fakeWindow()), emptyArray(), emptyArray(), iCallback)
        waitForIdleSync()
        verify(callback).setBlursDisabledForAppLaunch(eq(true))
        verify(controller).onLaunchAnimationStart(anyBoolean())
    }

    @Test
    fun controllerFromOrphanViewReturnsNullAndIsATerribleFailure() {
        Log.setWtfHandler(failHandler)
        assertNull(ActivityLaunchAnimator.Controller.fromView(View(mContext)))
        verify(failHandler).onTerribleFailure(any(), any(), anyBoolean())
    }

    private fun fakeWindow(): RemoteAnimationTarget {
        val bounds = Rect(10 /* left */, 20 /* top */, 30 /* right */, 40 /* bottom */)
        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.topActivity = ComponentName("com.android.systemui", "FakeActivity")
        taskInfo.topActivityInfo = ActivityInfo().apply {
            applicationInfo = ApplicationInfo()
        }

        return RemoteAnimationTarget(
                0, RemoteAnimationTarget.MODE_OPENING, SurfaceControl(), false, Rect(), Rect(), 0,
                Point(), Rect(), bounds, WindowConfiguration(), false, SurfaceControl(), Rect(),
                taskInfo, false
        )
    }
}

/**
 * A simple implementation of [ActivityLaunchAnimator.Controller] which throws if it is called
 * outside of the main thread.
 */
private class TestLaunchAnimatorController(
    override var launchContainer: ViewGroup
) : ActivityLaunchAnimator.Controller {
    override fun createAnimatorState() = LaunchAnimator.State(
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

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationProgress(
        state: LaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationCancelled() {
        assertOnMainThread()
    }
}
