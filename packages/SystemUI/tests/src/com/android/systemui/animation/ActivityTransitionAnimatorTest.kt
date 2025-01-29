package com.android.systemui.animation

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.view.IRemoteAnimationFinishedCallback
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.RemoteAnimationTarget.MODE_CLOSING
import android.view.RemoteAnimationTarget.MODE_OPENING
import android.view.SurfaceControl
import android.view.ViewGroup
import android.view.WindowManager.TRANSIT_NONE
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.RemoteTransition
import android.window.TransitionFilter
import android.window.WindowAnimationState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.Flags
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.wm.shell.shared.ShellTransitions
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import junit.framework.AssertionFailedError
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class ActivityTransitionAnimatorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val transitionContainer = LinearLayout(mContext)
    private val mainExecutor = context.mainExecutor
    private val testTransitionAnimator = fakeTransitionAnimator(mainExecutor)
    private val testShellTransitions = FakeShellTransitions()
    @Mock lateinit var callback: ActivityTransitionAnimator.Callback
    @Mock lateinit var listener: ActivityTransitionAnimator.Listener
    @Spy private val controller = TestTransitionAnimatorController(transitionContainer)
    @Mock lateinit var iCallback: IRemoteAnimationFinishedCallback

    private lateinit var underTest: ActivityTransitionAnimator
    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setup() {
        underTest =
            ActivityTransitionAnimator(
                mainExecutor,
                ActivityTransitionAnimator.TransitionRegister.fromShellTransitions(
                    testShellTransitions
                ),
                testTransitionAnimator,
                testTransitionAnimator,
                disableWmTimeout = true,
            )
        underTest.callback = callback
        underTest.addListener(listener)
    }

    @After
    fun tearDown() {
        underTest.removeListener(listener)
    }

    private fun startIntentWithAnimation(
        animator: ActivityTransitionAnimator = underTest,
        controller: ActivityTransitionAnimator.Controller? = this.controller,
        animate: Boolean = true,
        intentStarter: (RemoteAnimationAdapter?) -> Int,
    ) {
        // We start in a new thread so that we can ensure that the callbacks are called in the main
        // thread.
        thread {
                animator.startIntentWithAnimation(
                    controller = controller,
                    animate = animate,
                    intentStarter = intentStarter,
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

        startIntentWithAnimation(underTest) { adapter ->
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

    @EnableFlags(Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY)
    @Test
    fun registersReturnIffCookieIsPresent() {
        `when`(callback.isOnKeyguard()).thenReturn(false)

        startIntentWithAnimation(underTest, controller) { ActivityManager.START_DELIVERED_TO_TOP }

        waitForIdleSync()
        assertTrue(testShellTransitions.remotes.isEmpty())
        assertTrue(testShellTransitions.remotesForTakeover.isEmpty())

        val controller =
            object : DelegateTransitionAnimatorController(controller) {
                override val transitionCookie
                    get() = ActivityTransitionAnimator.TransitionCookie("testCookie")
            }

        startIntentWithAnimation(underTest, controller) { ActivityManager.START_DELIVERED_TO_TOP }

        waitForIdleSync()
        assertEquals(1, testShellTransitions.remotes.size)
        assertTrue(testShellTransitions.remotesForTakeover.isEmpty())
    }

    @EnableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun registersLongLivedTransition() {
        kosmos.runTest {
            var factory = controllerFactory()
            underTest.register(factory.cookie, factory, testScope)
            assertEquals(2, testShellTransitions.remotes.size)

            factory = controllerFactory()
            underTest.register(factory.cookie, factory, testScope)
            assertEquals(4, testShellTransitions.remotes.size)
        }
    }

    @EnableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun registersLongLivedTransitionOverridingPreviousRegistration() {
        kosmos.runTest {
            val cookie = ActivityTransitionAnimator.TransitionCookie("test_cookie")
            var factory = controllerFactory(cookie)
            underTest.register(cookie, factory, testScope)
            val transitions = testShellTransitions.remotes.values.toList()

            factory = controllerFactory(cookie)
            underTest.register(cookie, factory, testScope)
            assertEquals(2, testShellTransitions.remotes.size)
            for (transition in transitions) {
                assertThat(testShellTransitions.remotes.values).doesNotContain(transition)
            }
        }
    }

    @DisableFlags(Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED)
    @Test
    fun doesNotRegisterLongLivedTransitionIfFlagIsDisabled() {
        kosmos.runTest {
            val factory = controllerFactory(component = null)
            assertThrows(IllegalStateException::class.java) {
                underTest.register(factory.cookie, factory, testScope)
            }
        }
    }

    @EnableFlags(Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED)
    @Test
    fun doesNotRegisterLongLivedTransitionIfMissingRequiredProperties() {
        kosmos.runTest {
            // No ComponentName
            var factory = controllerFactory(component = null)
            assertThrows(IllegalStateException::class.java) {
                underTest.register(factory.cookie, factory, testScope)
            }

            // No TransitionRegister
            val activityTransitionAnimator =
                ActivityTransitionAnimator(
                    mainExecutor,
                    transitionRegister = null,
                    testTransitionAnimator,
                    testTransitionAnimator,
                    disableWmTimeout = true,
                )
            factory = controllerFactory()
            assertThrows(IllegalStateException::class.java) {
                activityTransitionAnimator.register(factory.cookie, factory, testScope)
            }
        }
    }

    @EnableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun unregistersLongLivedTransition() {
        kosmos.runTest {
            val cookies = arrayOfNulls<ActivityTransitionAnimator.TransitionCookie>(3)

            for (index in 0 until 3) {
                cookies[index] = mock(ActivityTransitionAnimator.TransitionCookie::class.java)
                val factory = controllerFactory(cookies[index]!!)
                underTest.register(factory.cookie, factory, testScope)
            }

            underTest.unregister(cookies[0]!!)
            assertEquals(4, testShellTransitions.remotes.size)

            underTest.unregister(cookies[2]!!)
            assertEquals(2, testShellTransitions.remotes.size)

            underTest.unregister(cookies[1]!!)
            assertThat(testShellTransitions.remotes).isEmpty()
        }
    }

    @Test
    fun doesNotStartIfAnimationIsCancelled() {
        val runner = underTest.createEphemeralRunner(controller)
        runner.onAnimationCancelled()
        runner.onAnimationStart(TRANSIT_NONE, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onTransitionAnimationCancelled()
        verify(controller, never()).onTransitionAnimationStart(anyBoolean())
        verify(listener).onTransitionAnimationCancelled()
        verify(listener, never()).onTransitionAnimationStart()
        assertNull(runner.delegate)
    }

    @Test
    fun cancelsIfNoOpeningWindowIsFound() {
        val runner = underTest.createEphemeralRunner(controller)
        runner.onAnimationStart(TRANSIT_NONE, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onTransitionAnimationCancelled()
        verify(controller, never()).onTransitionAnimationStart(anyBoolean())
        verify(listener).onTransitionAnimationCancelled()
        verify(listener, never()).onTransitionAnimationStart()
        assertNull(runner.delegate)
    }

    @Test
    fun startsAnimationIfWindowIsOpening() {
        val runner = underTest.createEphemeralRunner(controller)
        runner.onAnimationStart(
            TRANSIT_NONE,
            arrayOf(fakeWindow()),
            emptyArray(),
            emptyArray(),
            iCallback,
        )
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

    @DisableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun creatingRunnerWithLazyInitializationThrows_whenTheFlagsAreDisabled() {
        kosmos.runTest {
            assertThrows(IllegalStateException::class.java) {
                val factory = controllerFactory()
                underTest.createLongLivedRunner(factory, testScope, forLaunch = true)
            }
        }
    }

    @EnableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun runnerCreatesDelegateLazily_onAnimationStart() {
        kosmos.runTest {
            val factory = controllerFactory()
            val runner = underTest.createLongLivedRunner(factory, testScope, forLaunch = true)
            assertNull(runner.delegate)

            var delegateInitialized = false
            underTest.addListener(
                object : ActivityTransitionAnimator.Listener {
                    override fun onTransitionAnimationStart() {
                        // This is called iff the delegate was initialized, so it's a good proxy for
                        // checking the initialization.
                        delegateInitialized = true
                    }
                }
            )
            runner.onAnimationStart(
                TRANSIT_NONE,
                arrayOf(fakeWindow()),
                emptyArray(),
                emptyArray(),
                iCallback,
            )
            testScope.advanceUntilIdle()
            waitForIdleSync()

            assertTrue(delegateInitialized)
        }
    }

    @EnableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun runnerCreatesDelegateLazily_onAnimationTakeover() {
        kosmos.runTest {
            val factory = controllerFactory()
            val runner = underTest.createLongLivedRunner(factory, testScope, forLaunch = false)
            assertNull(runner.delegate)

            var delegateInitialized = false
            underTest.addListener(
                object : ActivityTransitionAnimator.Listener {
                    override fun onTransitionAnimationStart() {
                        // This is called iff the delegate was initialized, so it's a good proxy for
                        // checking the initialization.
                        delegateInitialized = true
                    }
                }
            )
            runner.takeOverAnimation(
                arrayOf(fakeWindow(MODE_CLOSING)),
                arrayOf(WindowAnimationState()),
                SurfaceControl.Transaction(),
                iCallback,
            )
            testScope.advanceUntilIdle()
            waitForIdleSync()

            assertTrue(delegateInitialized)
        }
    }

    @DisableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun animationTakeoverThrows_whenTheFlagsAreDisabled() {
        val runner = underTest.createEphemeralRunner(controller)
        assertThrows(IllegalStateException::class.java) {
            runner.takeOverAnimation(
                arrayOf(fakeWindow()),
                emptyArray(),
                SurfaceControl.Transaction(),
                iCallback,
            )
        }
    }

    @DisableFlags(
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        Flags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun disposeRunner_delegateDereferenced() {
        val runner = underTest.createEphemeralRunner(controller)
        assertNotNull(runner.delegate)
        runner.dispose()
        waitForIdleSync()
        assertNull(runner.delegate)
    }

    @Test
    fun concurrentListenerModification_doesNotThrow() {
        // Need a second listener to trigger the concurrent modification.
        underTest.addListener(object : ActivityTransitionAnimator.Listener {})
        `when`(listener.onTransitionAnimationStart()).thenAnswer {
            underTest.removeListener(listener)
            listener
        }

        val runner = underTest.createEphemeralRunner(controller)
        runner.onAnimationStart(
            TRANSIT_NONE,
            arrayOf(fakeWindow()),
            emptyArray(),
            emptyArray(),
            iCallback,
        )

        waitForIdleSync()
        verify(listener).onTransitionAnimationStart()
    }

    private fun controllerFactory(
        cookie: ActivityTransitionAnimator.TransitionCookie =
            mock(ActivityTransitionAnimator.TransitionCookie::class.java),
        component: ComponentName? = mock(ComponentName::class.java),
    ): ActivityTransitionAnimator.ControllerFactory {
        return object : ActivityTransitionAnimator.ControllerFactory(cookie, component) {
            override suspend fun createController(forLaunch: Boolean) =
                object : DelegateTransitionAnimatorController(controller) {
                    override val isLaunching: Boolean
                        get() = forLaunch
                }
        }
    }

    private fun fakeWindow(mode: Int = MODE_OPENING): RemoteAnimationTarget {
        val bounds = Rect(10 /* left */, 20 /* top */, 30 /* right */, 40 /* bottom */)
        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.topActivity = ComponentName("com.android.systemui", "FakeActivity")
        taskInfo.topActivityInfo = ActivityInfo().apply { applicationInfo = ApplicationInfo() }

        return RemoteAnimationTarget(
            0,
            mode,
            SurfaceControl(),
            false,
            Rect(),
            Rect(),
            1,
            Point(),
            Rect(),
            bounds,
            WindowConfiguration(),
            false,
            SurfaceControl(),
            Rect(),
            taskInfo,
            false,
        )
    }
}

/**
 * A fake implementation of [ShellTransitions] which saves filter-transition pairs locally and
 * allows inspection.
 */
private class FakeShellTransitions : ShellTransitions {
    val remotes = mutableMapOf<TransitionFilter, RemoteTransition>()
    val remotesForTakeover = mutableMapOf<TransitionFilter, RemoteTransition>()

    override fun registerRemote(filter: TransitionFilter, remoteTransition: RemoteTransition) {
        remotes[filter] = remoteTransition
    }

    override fun registerRemoteForTakeover(
        filter: TransitionFilter,
        remoteTransition: RemoteTransition,
    ) {
        remotesForTakeover[filter] = remoteTransition
    }

    override fun unregisterRemote(remoteTransition: RemoteTransition) {
        while (remotes.containsValue(remoteTransition)) {
            remotes.values.remove(remoteTransition)
        }
        while (remotesForTakeover.containsValue(remoteTransition)) {
            remotesForTakeover.values.remove(remoteTransition)
        }
    }
}

/**
 * A simple implementation of [ActivityTransitionAnimator.Controller] which throws if it is called
 * outside of the main thread.
 */
private class TestTransitionAnimatorController(override var transitionContainer: ViewGroup) :
    ActivityTransitionAnimator.Controller {
    override val isLaunching: Boolean = true

    override fun createAnimatorState() =
        TransitionAnimator.State(
            top = 100,
            bottom = 200,
            left = 300,
            right = 400,
            topCornerRadius = 10f,
            bottomCornerRadius = 20f,
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
        linearProgress: Float,
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
