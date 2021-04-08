package com.android.systemui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.PendingIntent
import android.graphics.Matrix
import android.graphics.Rect
import android.os.RemoteException
import android.util.MathUtils
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SyncRtSurfaceTransactionApplier
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.policy.ScreenDecorationsUtils
import kotlin.math.roundToInt

/**
 * A class that allows activities to be started in a seamless way from a view that is transforming
 * nicely into the starting window.
 */
class ActivityLaunchAnimator {
    companion object {
        const val ANIMATION_DURATION = 400L
        const val ANIMATION_DURATION_FADE_OUT_CONTENT = 67L
        const val ANIMATION_DURATION_FADE_IN_WINDOW = 200L
        private const val ANIMATION_DURATION_NAV_FADE_IN = 266L
        private const val ANIMATION_DURATION_NAV_FADE_OUT = 133L
        private const val ANIMATION_DELAY_NAV_FADE_IN =
                ANIMATION_DURATION - ANIMATION_DURATION_NAV_FADE_IN
        private const val LAUNCH_TIMEOUT = 1000L

        private val NAV_FADE_IN_INTERPOLATOR = PathInterpolator(0f, 0f, 0f, 1f)
        private val NAV_FADE_OUT_INTERPOLATOR = PathInterpolator(0.2f, 0f, 1f, 1f)

        /**
         * Given the [linearProgress] of a launch animation, return the linear progress of the
         * sub-animation starting [delay] ms after the launch animation and that lasts [duration].
         */
        @JvmStatic
        fun getProgress(linearProgress: Float, delay: Long, duration: Long): Float {
            return MathUtils.constrain(
                    (linearProgress * ANIMATION_DURATION - delay) / duration,
                    0.0f,
                    1.0f
            )
        }
    }

    /**
     * Start an intent and animate the opening window. The intent will be started by running
     * [intentStarter], which should use the provided [RemoteAnimationAdapter] and return the launch
     * result. [controller] is responsible from animating the view from which the intent was started
     * in [Controller.onLaunchAnimationProgress]. No animation will start if there is no window
     * opening.
     *
     * If [controller] is null, then the intent will be started and no animation will run.
     *
     * This method will throw any exception thrown by [intentStarter].
     */
    inline fun startIntentWithAnimation(
        controller: Controller?,
        intentStarter: (RemoteAnimationAdapter?) -> Int
    ) {
        if (controller == null) {
            intentStarter(null)
            return
        }

        val runner = Runner(controller)
        val animationAdapter = RemoteAnimationAdapter(
            runner,
            ANIMATION_DURATION,
            ANIMATION_DURATION - 150 /* statusBarTransitionDelay */
        )
        val launchResult = intentStarter(animationAdapter)
        val willAnimate = launchResult == ActivityManager.START_TASK_TO_FRONT ||
            launchResult == ActivityManager.START_SUCCESS
        runner.context.mainExecutor.execute { controller.onIntentStarted(willAnimate) }

        // If we expect an animation, post a timeout to cancel it in case the remote animation is
        // never started.
        if (willAnimate) {
            runner.postTimeout()
        }
    }

    /**
     * Same as [startIntentWithAnimation] but allows [intentStarter] to throw a
     * [PendingIntent.CanceledException] which must then be handled by the caller. This is useful
     * for Java caller starting a [PendingIntent].
     */
    @Throws(PendingIntent.CanceledException::class)
    fun startPendingIntentWithAnimation(
        controller: Controller?,
        intentStarter: PendingIntentStarter
    ) {
        startIntentWithAnimation(controller) { intentStarter.startPendingIntent(it) }
    }

    interface PendingIntentStarter {
        /**
         * Start a pending intent using the provided [animationAdapter] and return the launch
         * result.
         */
        @Throws(PendingIntent.CanceledException::class)
        fun startPendingIntent(animationAdapter: RemoteAnimationAdapter?): Int
    }

    /**
     * A controller that takes care of applying the animation to an expanding view.
     *
     * Note that all callbacks (onXXX methods) are all called on the main thread.
     */
    interface Controller {
        companion object {
            /**
             * Return a [Controller] that will animate and expand [view] into the opening window.
             *
             * Important: The view must be attached to the window when calling this function and
             * during the animation.
             */
            @JvmStatic
            fun fromView(view: View): Controller = GhostedViewLaunchAnimatorController(view)
        }

        /**
         * Return the root [View] that contains the view that started the intent and will be
         * animating together with the window.
         *
         * This view will be used to:
         *  - Get the associated [Context].
         *  - Compute whether we are expanding fully above the current window.
         *  - Apply surface transactions in sync with RenderThread.
         */
        fun getRootView(): View

        /**
         * Return the [State] of the view that will be animated. We will animate from this state to
         * the final window state.
         *
         * Note: This state will be mutated and passed to [onLaunchAnimationProgress] during the
         * animation.
         */
        fun createAnimatorState(): State

        /**
         * The intent was started. If [willAnimate] is false, nothing else will happen and the
         * animation will not be started.
         */
        fun onIntentStarted(willAnimate: Boolean) {}

        /**
         * The animation started. This is typically used to initialize any additional resource
         * needed for the animation. [isExpandingFullyAbove] will be true if the window is expanding
         * fully above the [root view][getRootView].
         */
        fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {}

        /** The animation made progress and the expandable view [state] should be updated. */
        fun onLaunchAnimationProgress(state: State, progress: Float, linearProgress: Float) {}

        /**
         * The animation ended. This will be called *if and only if* [onLaunchAnimationStart] was
         * called previously. This is typically used to clean up the resources initialized when the
         * animation was started.
         */
        fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {}

        /**
         * The animation was cancelled remotely. Note that [onLaunchAnimationEnd] will still be
         * called after this if the animation was already started, i.e. if [onLaunchAnimationStart]
         * was called before the cancellation.
         */
        fun onLaunchAnimationCancelled() {}

        /**
         * The remote animation was not started within the expected time. It timed out and will
         * never [start][onLaunchAnimationStart].
         */
        fun onLaunchAnimationTimedOut() {}

        /**
         * The animation was aborted because the opening window was not found. It will never
         * [start][onLaunchAnimationStart].
         */
        fun onLaunchAnimationAborted() {}
    }

    /** The state of an expandable view during an [ActivityLaunchAnimator] animation. */
    open class State(
        /** The position of the view in screen space coordinates. */
        var top: Int,
        var bottom: Int,
        var left: Int,
        var right: Int,

        var topCornerRadius: Float = 0f,
        var bottomCornerRadius: Float = 0f,

        var contentAlpha: Float = 1f,
        var backgroundAlpha: Float = 1f
    ) {
        private val startTop = top
        private val startLeft = left
        private val startRight = right

        val width: Int
            get() = right - left

        val height: Int
            get() = bottom - top

        open val topChange: Int
            get() = top - startTop

        val leftChange: Int
            get() = left - startLeft

        val rightChange: Int
            get() = right - startRight
    }

    @VisibleForTesting
    class Runner(private val controller: Controller) : IRemoteAnimationRunner.Stub() {
        private val rootView = controller.getRootView()
        @PublishedApi internal val context = rootView.context
        private val transactionApplier = SyncRtSurfaceTransactionApplier(rootView)
        private var animator: ValueAnimator? = null

        private var windowCrop = Rect()
        private var timedOut = false
        private var cancelled = false

        // A timeout to cancel the remote animation if it is not started within X milliseconds after
        // the intent was started.
        //
        // Note that this is important to keep this a Runnable (and not a Kotlin lambda), otherwise
        // it will be automatically converted when posted and we wouldn't be able to remove it after
        // posting it.
        private var onTimeout = Runnable { onAnimationTimedOut() }

        @PublishedApi
        internal fun postTimeout() {
            rootView.postDelayed(onTimeout, LAUNCH_TIMEOUT)
        }

        private fun removeTimeout() {
            rootView.removeCallbacks(onTimeout)
        }

        override fun onAnimationStart(
            @WindowManager.TransitionOldType transit: Int,
            remoteAnimationTargets: Array<out RemoteAnimationTarget>,
            remoteAnimationWallpaperTargets: Array<out RemoteAnimationTarget>,
            remoteAnimationNonAppTargets: Array<out RemoteAnimationTarget>,
            iRemoteAnimationFinishedCallback: IRemoteAnimationFinishedCallback
        ) {
            removeTimeout()

            // The animation was started too late and we already notified the controller that it
            // timed out.
            if (timedOut) {
                invokeCallback(iRemoteAnimationFinishedCallback)
                return
            }

            // This should not happen, but let's make sure we don't start the animation if it was
            // cancelled before and we already notified the controller.
            if (cancelled) {
                return
            }

            context.mainExecutor.execute {
                startAnimation(remoteAnimationTargets, iRemoteAnimationFinishedCallback)
            }
        }

        private fun startAnimation(
            remoteAnimationTargets: Array<out RemoteAnimationTarget>,
            iCallback: IRemoteAnimationFinishedCallback
        ) {
            val window = remoteAnimationTargets.firstOrNull {
                it.mode == RemoteAnimationTarget.MODE_OPENING
            }

            if (window == null) {
                removeTimeout()
                invokeCallback(iCallback)
                controller.onLaunchAnimationAborted()
                return
            }

            val navigationBar = remoteAnimationTargets.firstOrNull {
                it.windowType == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
            }

            // Start state.
            val state = controller.createAnimatorState()

            val startTop = state.top
            val startBottom = state.bottom
            val startLeft = state.left
            val startRight = state.right

            val startTopCornerRadius = state.topCornerRadius
            val startBottomCornerRadius = state.bottomCornerRadius

            // End state.
            val windowBounds = window.screenSpaceBounds
            val endTop = windowBounds.top
            val endBottom = windowBounds.bottom
            val endLeft = windowBounds.left
            val endRight = windowBounds.right

            // TODO(b/184121838): Ensure that we are launching on the same screen.
            val rootViewLocation = rootView.locationOnScreen
            val isExpandingFullyAbove = endTop <= rootViewLocation[1] &&
                endBottom >= rootViewLocation[1] + rootView.height &&
                endLeft <= rootViewLocation[0] &&
                endRight >= rootViewLocation[0] + rootView.width

            // TODO(b/184121838): We should somehow get the top and bottom radius of the window.
            val endRadius = if (isExpandingFullyAbove) {
                // Most of the time, expanding fully above the root view means expanding in full
                // screen.
                ScreenDecorationsUtils.getWindowCornerRadius(context.resources)
            } else {
                // This usually means we are in split screen mode, so 2 out of 4 corners will have
                // a radius of 0.
                0f
            }

            // Update state.
            val animator = ValueAnimator.ofFloat(0f, 1f)
            this.animator = animator
            animator.duration = ANIMATION_DURATION
            animator.interpolator = Interpolators.LINEAR

            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?, isReverse: Boolean) {
                    controller.onLaunchAnimationStart(isExpandingFullyAbove)
                }

                override fun onAnimationEnd(animation: Animator?) {
                    invokeCallback(iCallback)
                    controller.onLaunchAnimationEnd(isExpandingFullyAbove)
                }
            })

            animator.addUpdateListener { animation ->
                if (cancelled) {
                    return@addUpdateListener
                }

                // TODO(b/184121838): Use android.R.interpolator.fast_out_extra_slow_in instead.
                val linearProgress = animation.animatedFraction
                val progress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(linearProgress)

                state.top = lerp(startTop, endTop, progress).roundToInt()
                state.bottom = lerp(startBottom, endBottom, progress).roundToInt()
                state.left = lerp(startLeft, endLeft, progress).roundToInt()
                state.right = lerp(startRight, endRight, progress).roundToInt()

                state.topCornerRadius = MathUtils.lerp(startTopCornerRadius, endRadius, progress)
                state.bottomCornerRadius =
                    MathUtils.lerp(startBottomCornerRadius, endRadius, progress)

                val contentAlphaProgress = getProgress(linearProgress, 0,
                        ANIMATION_DURATION_FADE_OUT_CONTENT)
                state.contentAlpha =
                        1 - Interpolators.ALPHA_OUT.getInterpolation(contentAlphaProgress)

                val backgroundAlphaProgress = getProgress(linearProgress,
                        ANIMATION_DURATION_FADE_OUT_CONTENT, ANIMATION_DURATION_FADE_IN_WINDOW)
                state.backgroundAlpha =
                        1 - Interpolators.ALPHA_IN.getInterpolation(backgroundAlphaProgress)

                applyStateToWindow(window, state)
                navigationBar?.let { applyStateToNavigationBar(it, state, linearProgress) }
                controller.onLaunchAnimationProgress(state, progress, linearProgress)
            }

            animator.start()
        }

        private fun applyStateToWindow(window: RemoteAnimationTarget, state: State) {
            val m = Matrix()
            m.postTranslate(0f, (state.top - window.sourceContainerBounds.top).toFloat())
            windowCrop.set(state.left, 0, state.right, state.height)

            val cornerRadius = minOf(state.topCornerRadius, state.bottomCornerRadius)
            val params = SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(window.leash)
                    .withAlpha(1f)
                    .withMatrix(m)
                    .withWindowCrop(windowCrop)
                    .withLayer(window.prefixOrderIndex)
                    .withCornerRadius(cornerRadius)
                    .withVisibility(true)
                    .build()

            transactionApplier.scheduleApply(params)
        }

        private fun applyStateToNavigationBar(
            navigationBar: RemoteAnimationTarget,
            state: State,
            linearProgress: Float
        ) {
            val fadeInProgress = getProgress(linearProgress, ANIMATION_DELAY_NAV_FADE_IN,
                    ANIMATION_DURATION_NAV_FADE_OUT)

            val params = SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(navigationBar.leash)
            if (fadeInProgress > 0) {
                val m = Matrix()
                m.postTranslate(0f, (state.top - navigationBar.sourceContainerBounds.top).toFloat())
                windowCrop.set(state.left, 0, state.right, state.height)
                params
                        .withAlpha(NAV_FADE_IN_INTERPOLATOR.getInterpolation(fadeInProgress))
                        .withMatrix(m)
                        .withWindowCrop(windowCrop)
                        .withVisibility(true)
            } else {
                val fadeOutProgress = getProgress(linearProgress, 0,
                        ANIMATION_DURATION_NAV_FADE_OUT)
                params.withAlpha(1f - NAV_FADE_OUT_INTERPOLATOR.getInterpolation(fadeOutProgress))
            }

            transactionApplier.scheduleApply(params.build())
        }

        private fun onAnimationTimedOut() {
            if (cancelled) {
                return
            }

            timedOut = true
            controller.onLaunchAnimationTimedOut()
        }

        override fun onAnimationCancelled() {
            if (timedOut) {
                return
            }

            cancelled = true
            removeTimeout()
            context.mainExecutor.execute {
                animator?.cancel()
                controller.onLaunchAnimationCancelled()
            }
        }

        private fun invokeCallback(iCallback: IRemoteAnimationFinishedCallback) {
            try {
                iCallback.onAnimationFinished()
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        private fun lerp(start: Int, stop: Int, amount: Float): Float {
            return MathUtils.lerp(start.toFloat(), stop.toFloat(), amount)
        }
    }
}
