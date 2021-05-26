package com.android.systemui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.util.MathUtils
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SyncRtSurfaceTransactionApplier
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.policy.ScreenDecorationsUtils
import kotlin.math.roundToInt

/**
 * A class that allows activities to be started in a seamless way from a view that is transforming
 * nicely into the starting window.
 */
class ActivityLaunchAnimator(context: Context) {
    private val TAG = this::class.java.simpleName

    companion object {
        const val ANIMATION_DURATION = 500L
        const val ANIMATION_DURATION_FADE_OUT_CONTENT = 183L
        const val ANIMATION_DURATION_FADE_IN_WINDOW = 217L
        const val ANIMATION_DELAY_FADE_IN_WINDOW = 167L
        private const val ANIMATION_DURATION_NAV_FADE_IN = 266L
        private const val ANIMATION_DURATION_NAV_FADE_OUT = 133L
        private const val ANIMATION_DELAY_NAV_FADE_IN =
                ANIMATION_DURATION - ANIMATION_DURATION_NAV_FADE_IN
        private const val LAUNCH_TIMEOUT = 1000L

        @JvmField val CONTENT_FADE_OUT_INTERPOLATOR = PathInterpolator(0f, 0f, 0.2f, 1f)
        private val WINDOW_FADE_IN_INTERPOLATOR = PathInterpolator(0f, 0f, 0.6f, 1f)
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

    /** The interpolator used for the width, height, Y position and corner radius. */
    private val animationInterpolator = AnimationUtils.loadInterpolator(context,
            R.interpolator.launch_animation_interpolator_y)

    /** The interpolator used for the X position. */
    private val animationInterpolatorX = AnimationUtils.loadInterpolator(context,
            R.interpolator.launch_animation_interpolator_x)

    /**
     * Start an intent and animate the opening window. The intent will be started by running
     * [intentStarter], which should use the provided [RemoteAnimationAdapter] and return the launch
     * result. [controller] is responsible from animating the view from which the intent was started
     * in [Controller.onLaunchAnimationProgress]. No animation will start if there is no window
     * opening.
     *
     * If [controller] is null or [animate] is false, then the intent will be started and no
     * animation will run.
     *
     * If possible, you should pass the [packageName] of the intent that will be started so that
     * trampoline activity launches will also be animated.
     *
     * This method will throw any exception thrown by [intentStarter].
     */
    @JvmOverloads
    fun startIntentWithAnimation(
        controller: Controller?,
        animate: Boolean = true,
        packageName: String? = null,
        intentStarter: (RemoteAnimationAdapter?) -> Int
    ) {
        if (controller == null || !animate) {
            Log.d(TAG, "Starting intent with no animation")
            intentStarter(null)
            controller?.callOnIntentStartedOnMainThread(willAnimate = false)
            return
        }

        Log.d(TAG, "Starting intent with a launch animation")
        val runner = Runner(controller)
        val animationAdapter = RemoteAnimationAdapter(
            runner,
            ANIMATION_DURATION,
            ANIMATION_DURATION - 150 /* statusBarTransitionDelay */
        )

        // Register the remote animation for the given package to also animate trampoline
        // activity launches.
        if (packageName != null) {
            try {
                ActivityTaskManager.getService().registerRemoteAnimationForNextActivityStart(
                    packageName, animationAdapter)
            } catch (e: RemoteException) {
                Log.w(TAG, "Unable to register the remote animation", e)
            }
        }

        val launchResult = intentStarter(animationAdapter)
        val willAnimate = launchResult == ActivityManager.START_TASK_TO_FRONT ||
            launchResult == ActivityManager.START_SUCCESS

        Log.d(TAG, "launchResult=$launchResult willAnimate=$willAnimate")
        controller.callOnIntentStartedOnMainThread(willAnimate)

        // If we expect an animation, post a timeout to cancel it in case the remote animation is
        // never started.
        if (willAnimate) {
            runner.postTimeout()
        }
    }

    internal fun Controller.callOnIntentStartedOnMainThread(willAnimate: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            this.launchContainer.context.mainExecutor.execute {
                this.onIntentStarted(willAnimate)
            }
        } else {
            this.onIntentStarted(willAnimate)
        }
    }

    /**
     * Same as [startIntentWithAnimation] but allows [intentStarter] to throw a
     * [PendingIntent.CanceledException] which must then be handled by the caller. This is useful
     * for Java caller starting a [PendingIntent].
     *
     * If possible, you should pass the [packageName] of the intent that will be started so that
     * trampoline activity launches will also be animated.
     */
    @Throws(PendingIntent.CanceledException::class)
    @JvmOverloads
    fun startPendingIntentWithAnimation(
        controller: Controller?,
        animate: Boolean = true,
        packageName: String? = null,
        intentStarter: PendingIntentStarter
    ) {
        startIntentWithAnimation(controller, animate, packageName) {
            intentStarter.startPendingIntent(it)
        }
    }

    /** Create a new animation [Runner] controlled by [controller]. */
    @VisibleForTesting
    fun createRunner(controller: Controller): Runner = Runner(controller)

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
         * The container in which the view that started the intent will be animating together with
         * the opening window.
         *
         * This will be used to:
         *  - Get the associated [Context].
         *  - Compute whether we are expanding fully above the current window.
         *  - Apply surface transactions in sync with RenderThread.
         *
         * This container can be changed to force this [Controller] to animate the expanding view
         * inside a different location, for instance to ensure correct layering during the
         * animation.
         */
        var launchContainer: ViewGroup

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
         * The animation was cancelled. Note that [onLaunchAnimationEnd] will still be called after
         * this if the animation was already started, i.e. if [onLaunchAnimationStart] was called
         * before the cancellation.
         */
        fun onLaunchAnimationCancelled() {}
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
        private val startBottom = bottom
        private val startLeft = left
        private val startRight = right
        private val startWidth = width
        private val startHeight = height
        val startCenterX = centerX
        val startCenterY = centerY

        val width: Int
            get() = right - left

        val height: Int
            get() = bottom - top

        open val topChange: Int
            get() = top - startTop

        open val bottomChange: Int
            get() = bottom - startBottom

        val leftChange: Int
            get() = left - startLeft

        val rightChange: Int
            get() = right - startRight

        val widthRatio: Float
            get() = width.toFloat() / startWidth

        val heightRatio: Float
            get() = height.toFloat() / startHeight

        val centerX: Float
            get() = left + width / 2f

        val centerY: Float
            get() = top + height / 2f
    }

    @VisibleForTesting
    inner class Runner(private val controller: Controller) : IRemoteAnimationRunner.Stub() {
        private val launchContainer = controller.launchContainer
        private val context = launchContainer.context
        private val transactionApplier = SyncRtSurfaceTransactionApplier(launchContainer)
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

        internal fun postTimeout() {
            launchContainer.postDelayed(onTimeout, LAUNCH_TIMEOUT)
        }

        private fun removeTimeout() {
            launchContainer.removeCallbacks(onTimeout)
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
                startAnimation(remoteAnimationTargets, remoteAnimationNonAppTargets,
                        iRemoteAnimationFinishedCallback)
            }
        }

        private fun startAnimation(
            remoteAnimationTargets: Array<out RemoteAnimationTarget>,
            remoteAnimationNonAppTargets: Array<out RemoteAnimationTarget>,
            iCallback: IRemoteAnimationFinishedCallback
        ) {
            Log.d(TAG, "Remote animation started")
            val window = remoteAnimationTargets.firstOrNull {
                it.mode == RemoteAnimationTarget.MODE_OPENING
            }

            if (window == null) {
                Log.d(TAG, "Aborting the animation as no window is opening")
                removeTimeout()
                invokeCallback(iCallback)
                controller.onLaunchAnimationCancelled()
                return
            }

            val navigationBar = remoteAnimationNonAppTargets.firstOrNull {
                it.windowType == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
            }

            // Start state.
            val state = controller.createAnimatorState()

            val startTop = state.top
            val startBottom = state.bottom
            val startLeft = state.left
            val startRight = state.right
            val startXCenter = (startLeft + startRight) / 2f
            val startWidth = startRight - startLeft

            val startTopCornerRadius = state.topCornerRadius
            val startBottomCornerRadius = state.bottomCornerRadius

            // End state.
            val windowBounds = window.screenSpaceBounds
            val endTop = windowBounds.top
            val endBottom = windowBounds.bottom
            val endLeft = windowBounds.left
            val endRight = windowBounds.right
            val endXCenter = (endLeft + endRight) / 2f
            val endWidth = endRight - endLeft

            // TODO(b/184121838): Ensure that we are launching on the same screen.
            val rootViewLocation = launchContainer.locationOnScreen
            val isExpandingFullyAbove = endTop <= rootViewLocation[1] &&
                endBottom >= rootViewLocation[1] + launchContainer.height &&
                endLeft <= rootViewLocation[0] &&
                endRight >= rootViewLocation[0] + launchContainer.width

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
                    Log.d(TAG, "Animation started")
                    controller.onLaunchAnimationStart(isExpandingFullyAbove)
                }

                override fun onAnimationEnd(animation: Animator?) {
                    Log.d(TAG, "Animation ended")
                    invokeCallback(iCallback)
                    controller.onLaunchAnimationEnd(isExpandingFullyAbove)
                }
            })

            animator.addUpdateListener { animation ->
                if (cancelled) {
                    return@addUpdateListener
                }

                val linearProgress = animation.animatedFraction
                val progress = animationInterpolator.getInterpolation(linearProgress)
                val xProgress = animationInterpolatorX.getInterpolation(linearProgress)
                val xCenter = MathUtils.lerp(startXCenter, endXCenter, xProgress)
                val halfWidth = lerp(startWidth, endWidth, progress) / 2

                state.top = lerp(startTop, endTop, progress).roundToInt()
                state.bottom = lerp(startBottom, endBottom, progress).roundToInt()
                state.left = (xCenter - halfWidth).roundToInt()
                state.right = (xCenter + halfWidth).roundToInt()

                state.topCornerRadius = MathUtils.lerp(startTopCornerRadius, endRadius, progress)
                state.bottomCornerRadius =
                    MathUtils.lerp(startBottomCornerRadius, endRadius, progress)

                val contentAlphaProgress = getProgress(linearProgress, 0,
                        ANIMATION_DURATION_FADE_OUT_CONTENT)
                state.contentAlpha =
                        1 - CONTENT_FADE_OUT_INTERPOLATOR.getInterpolation(contentAlphaProgress)

                val backgroundAlphaProgress = getProgress(linearProgress,
                        ANIMATION_DELAY_FADE_IN_WINDOW, ANIMATION_DURATION_FADE_IN_WINDOW)
                state.backgroundAlpha =
                        1 - WINDOW_FADE_IN_INTERPOLATOR.getInterpolation(backgroundAlphaProgress)

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

            Log.d(TAG, "Remote animation timed out")
            timedOut = true
            controller.onLaunchAnimationCancelled()
        }

        override fun onAnimationCancelled() {
            if (timedOut) {
                return
            }

            Log.d(TAG, "Remote animation was cancelled")
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
