/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.service.dreams.IDreamManager
import android.util.Log
import android.util.MathUtils
import android.view.GhostView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.widget.FrameLayout
import kotlin.math.roundToInt

private const val TAG = "DialogLaunchAnimator"

/**
 * A class that allows dialogs to be started in a seamless way from a view that is transforming
 * nicely into the starting dialog.
 *
 * This animator also allows to easily animate a dialog into an activity.
 *
 * @see showFromView
 * @see showFromDialog
 * @see createActivityLaunchController
 */
class DialogLaunchAnimator @JvmOverloads constructor(
    private val dreamManager: IDreamManager,
    private val launchAnimator: LaunchAnimator = LaunchAnimator(TIMINGS, INTERPOLATORS),
    private val isForTesting: Boolean = false
) {
    private companion object {
        private val TIMINGS = ActivityLaunchAnimator.TIMINGS

        // We use the same interpolator for X and Y axis to make sure the dialog does not move out
        // of the screen bounds during the animation.
        private val INTERPOLATORS = ActivityLaunchAnimator.INTERPOLATORS.copy(
            positionXInterpolator = ActivityLaunchAnimator.INTERPOLATORS.positionInterpolator
        )

        private val TAG_LAUNCH_ANIMATION_RUNNING = R.id.tag_launch_animation_running
    }

    /**
     * The set of dialogs that were animated using this animator and that are still opened (not
     * dismissed, but can be hidden).
     */
    // TODO(b/201264644): Remove this set.
    private val openedDialogs = hashSetOf<AnimatedDialog>()

    /**
     * Show [dialog] by expanding it from [view]. If [view] is a view inside another dialog that was
     * shown using this method, then we will animate from that dialog instead.
     *
     * If [animateBackgroundBoundsChange] is true, then the background of the dialog will be
     * animated when the dialog bounds change.
     *
     * Note: The background of [view] should be a (rounded) rectangle so that it can be properly
     * animated.
     *
     * Caveats: When calling this function and [dialog] is not a fullscreen dialog, then it will be
     * made fullscreen and 2 views will be inserted between the dialog DecorView and its children.
     */
    @JvmOverloads
    fun showFromView(
        dialog: Dialog,
        view: View,
        animateBackgroundBoundsChange: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException(
                "showFromView must be called from the main thread and dialog must be created in " +
                    "the main thread")
        }

        // If the view we are launching from belongs to another dialog, then this means the caller
        // intent is to launch a dialog from another dialog.
        val animatedParent = openedDialogs
            .firstOrNull { it.dialog.window.decorView.viewRootImpl == view.viewRootImpl }
        val animateFrom = animatedParent?.dialogContentWithBackground ?: view

        // Make sure we don't run the launch animation from the same view twice at the same time.
        if (animateFrom.getTag(TAG_LAUNCH_ANIMATION_RUNNING) != null) {
            Log.e(TAG, "Not running dialog launch animation as there is already one running")
            dialog.show()
            return
        }

        animateFrom.setTag(TAG_LAUNCH_ANIMATION_RUNNING, true)

        val animatedDialog = AnimatedDialog(
                launchAnimator,
                dreamManager,
                animateFrom,
                onDialogDismissed = { openedDialogs.remove(it) },
                dialog = dialog,
                animateBackgroundBoundsChange,
                animatedParent,
                isForTesting
        )

        openedDialogs.add(animatedDialog)
        animatedDialog.start()
    }

    /**
     * Launch [dialog] from [another dialog][animateFrom] that was shown using [showFromView]. This
     * will allow for dismissing the whole stack.
     *
     * @see dismissStack
     */
    fun showFromDialog(
        dialog: Dialog,
        animateFrom: Dialog,
        animateBackgroundBoundsChange: Boolean = false
    ) {
        val view = openedDialogs
            .firstOrNull { it.dialog == animateFrom }
            ?.dialogContentWithBackground
            ?: throw IllegalStateException(
                "The animateFrom dialog was not animated using " +
                    "DialogLaunchAnimator.showFrom(View|Dialog)")
        showFromView(dialog, view, animateBackgroundBoundsChange)
    }

    /**
     * Create an [ActivityLaunchAnimator.Controller] that can be used to launch an activity from the
     * dialog that contains [View]. Note that the dialog must have been show using [showFromView]
     * and be currently showing, otherwise this will return null.
     *
     * The returned controller will take care of dismissing the dialog at the right time after the
     * activity started, when the dialog to app animation is done (or when it is cancelled). If this
     * method returns null, then the dialog won't be dismissed.
     *
     * Note: The background of [view] should be a (rounded) rectangle so that it can be properly
     * animated.
     *
     * @param view any view inside the dialog to animate.
     */
    @JvmOverloads
    fun createActivityLaunchController(
        view: View,
        cujType: Int? = null
    ): ActivityLaunchAnimator.Controller? {
        val animatedDialog = openedDialogs
            .firstOrNull { it.dialog.window.decorView.viewRootImpl == view.viewRootImpl }
            ?: return null

        // At this point, we know that the intent of the caller is to dismiss the dialog to show
        // an app, so we disable the exit animation into the touch surface because we will never
        // want to run it anyways.
        animatedDialog.exitAnimationDisabled = true

        val dialog = animatedDialog.dialog

        // Don't animate if the dialog is not showing.
        if (!dialog.isShowing) {
            return null
        }

        val dialogContentWithBackground = animatedDialog.dialogContentWithBackground ?: return null
        val controller =
            ActivityLaunchAnimator.Controller.fromView(dialogContentWithBackground, cujType)
                ?: return null

        // Wrap the controller into one that will instantly dismiss the dialog when the animation is
        // done or dismiss it normally (fading it out) if the animation is cancelled.
        return object : ActivityLaunchAnimator.Controller by controller {
            override val isDialogLaunch = true

            override fun onIntentStarted(willAnimate: Boolean) {
                controller.onIntentStarted(willAnimate)

                if (!willAnimate) {
                    dialog.dismiss()
                }
            }

            override fun onLaunchAnimationCancelled() {
                controller.onLaunchAnimationCancelled()
                enableDialogDismiss()
                dialog.dismiss()
            }

            override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
                controller.onLaunchAnimationStart(isExpandingFullyAbove)

                // Make sure the dialog is not dismissed during the animation.
                disableDialogDismiss()

                // If this dialog was shown from a cascade of other dialogs, make sure those ones
                // are dismissed too.
                animatedDialog.touchSurface = animatedDialog.prepareForStackDismiss()

                // Remove the dim.
                dialog.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }

            override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
                controller.onLaunchAnimationEnd(isExpandingFullyAbove)

                // Hide the dialog then dismiss it to instantly dismiss it without playing the
                // animation.
                dialog.hide()
                enableDialogDismiss()
                dialog.dismiss()
            }

            private fun disableDialogDismiss() {
                dialog.setDismissOverride { /* Do nothing */ }
            }

            private fun enableDialogDismiss() {
                // We don't set the override to null given that [AnimatedDialog.OnDialogDismissed]
                // will still properly dismiss the dialog but will also make sure to clean up
                // everything (like making sure that the touched view that triggered the dialog is
                // made VISIBLE again).
                dialog.setDismissOverride(animatedDialog::onDialogDismissed)
            }
        }
    }

    /**
     * Ensure that all dialogs currently shown won't animate into their touch surface when
     * dismissed.
     *
     * This is a temporary API meant to be called right before we both dismiss a dialog and start
     * an activity, which currently does not look good if we animate the dialog into the touch
     * surface at the same time as the activity starts.
     *
     * TODO(b/193634619): Remove this function and animate dialog into opening activity instead.
     */
    fun disableAllCurrentDialogsExitAnimations() {
        openedDialogs.forEach { it.exitAnimationDisabled = true }
    }

    /**
     * Dismiss [dialog]. If it was launched from another dialog using [showFromView], also dismiss
     * the stack of dialogs, animating back to the original touchSurface.
     */
    fun dismissStack(dialog: Dialog) {
        openedDialogs
            .firstOrNull { it.dialog == dialog }
            ?.let { it.touchSurface = it.prepareForStackDismiss() }
        dialog.dismiss()
    }
}

private class AnimatedDialog(
    private val launchAnimator: LaunchAnimator,
    private val dreamManager: IDreamManager,

    /** The view that triggered the dialog after being tapped. */
    var touchSurface: View,

    /**
     * A callback that will be called with this [AnimatedDialog] after the dialog was
     * dismissed and the exit animation is done.
     */
    private val onDialogDismissed: (AnimatedDialog) -> Unit,

    /** The dialog to show and animate. */
    val dialog: Dialog,

    /** Whether we should animate the dialog background when its bounds change. */
    animateBackgroundBoundsChange: Boolean,

    /** Launch animation corresponding to the parent [AnimatedDialog]. */
    private val parentAnimatedDialog: AnimatedDialog? = null,

    /**
     * Whether synchronization should be disabled, which can be useful if we are running in a test.
     */
    private val forceDisableSynchronization: Boolean
) {
    /**
     * The DecorView of this dialog window.
     *
     * Note that we access this DecorView lazily to avoid accessing it before the dialog is created,
     * which can sometimes cause crashes (e.g. with the Cast dialog).
      */
    private val decorView by lazy { dialog.window!!.decorView as ViewGroup }

    /**
     * The dialog content with its background. When animating a fullscreen dialog, this is just the
     * first ViewGroup of the dialog that has a background. When animating a normal (not fullscreen)
     * dialog, this is an additional view that serves as a fake window that will have the same size
     * as the dialog window initially had and to which we will set the dialog window background.
     */
    var dialogContentWithBackground: ViewGroup? = null

    /**
     * The background color of [dialog], taking into consideration its window background color.
     */
    private var originalDialogBackgroundColor = Color.BLACK

    /**
     * Whether we are currently launching/showing the dialog by animating it from [touchSurface].
     */
    private var isLaunching = true

    /** Whether we are currently dismissing/hiding the dialog by animating into [touchSurface]. */
    private var isDismissing = false

    private var dismissRequested = false
    var exitAnimationDisabled = false

    private var isTouchSurfaceGhostDrawn = false
    private var isOriginalDialogViewLaidOut = false

    /** A layout listener to animate the dialog height change. */
    private val backgroundLayoutListener = if (animateBackgroundBoundsChange) {
        AnimatedBoundsLayoutListener()
    } else {
        null
    }

    /*
     * A layout listener in case the dialog (window) size changes (for instance because of a
     * configuration change) to ensure that the dialog stays full width.
     */
    private var decorViewLayoutListener: View.OnLayoutChangeListener? = null

    fun start() {
        // Create the dialog so that its onCreate() method is called, which usually sets the dialog
        // content.
        dialog.create()

        val window = dialog.window!!
        val isWindowFullScreen =
            window.attributes.width == MATCH_PARENT && window.attributes.height == MATCH_PARENT
        val dialogContentWithBackground = if (isWindowFullScreen) {
            // If the dialog window is already fullscreen, then we look for the first ViewGroup that
            // has a background (and is not the DecorView, which always has a background) and
            // animate towards that ViewGroup given that this is probably what represents the actual
            // dialog view.
            var viewGroupWithBackground: ViewGroup? = null
            for (i in 0 until decorView.childCount) {
                viewGroupWithBackground = findFirstViewGroupWithBackground(decorView.getChildAt(i))
                if (viewGroupWithBackground != null) {
                    break
                }
            }

            // Animate that view with the background. Throw if we didn't find one, because otherwise
            // it's not clear what we should animate.
            viewGroupWithBackground
                ?: throw IllegalStateException("Unable to find ViewGroup with background")
        } else {
            // We will make the dialog window (and therefore its DecorView) fullscreen to make it
            // possible to animate outside its bounds.
            //
            // Before that, we add a new View as a child of the DecorView with the same size and
            // gravity as that DecorView, then we add all original children of the DecorView to that
            // new View. Finally we remove the background of the DecorView and add it to the new
            // View, then we make the DecorView fullscreen. This new View now acts as a fake (non
            // fullscreen) window.
            //
            // On top of that, we also add a fullscreen transparent background between the DecorView
            // and the view that we added so that we can dismiss the dialog when this view is
            // clicked. This is necessary because DecorView overrides onTouchEvent and therefore we
            // can't set the click listener directly on the (now fullscreen) DecorView.
            val fullscreenTransparentBackground = FrameLayout(dialog.context)
            decorView.addView(
                fullscreenTransparentBackground,
                0 /* index */,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )

            val dialogContentWithBackground = FrameLayout(dialog.context)
            dialogContentWithBackground.background = decorView.background

            // Make the window background transparent. Note that setting the window (or DecorView)
            // background drawable to null leads to issues with background color (not being
            // transparent) or with insets that are not refreshed. Therefore we need to set it to
            // something not null, hence we are using android.R.color.transparent here.
            window.setBackgroundDrawableResource(android.R.color.transparent)

            // Close the dialog when clicking outside of it.
            fullscreenTransparentBackground.setOnClickListener { dialog.dismiss() }
            dialogContentWithBackground.isClickable = true

            // Make sure the transparent and dialog backgrounds are not focusable by accessibility
            // features.
            fullscreenTransparentBackground.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            dialogContentWithBackground.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO

            fullscreenTransparentBackground.addView(
                dialogContentWithBackground,
                FrameLayout.LayoutParams(
                    window.attributes.width,
                    window.attributes.height,
                    window.attributes.gravity
                )
            )

            // Move all original children of the DecorView to the new View we just added.
            for (i in 1 until decorView.childCount) {
                val view = decorView.getChildAt(1)
                decorView.removeViewAt(1)
                dialogContentWithBackground.addView(view)
            }

            // Make the window fullscreen and add a layout listener to ensure it stays fullscreen.
            window.setLayout(MATCH_PARENT, MATCH_PARENT)
            decorViewLayoutListener = View.OnLayoutChangeListener {
                v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (window.attributes.width != MATCH_PARENT ||
                    window.attributes.height != MATCH_PARENT) {
                    // The dialog size changed, copy its size to dialogContentWithBackground and
                    // make the dialog window full screen again.
                    val layoutParams = dialogContentWithBackground.layoutParams
                    layoutParams.width = window.attributes.width
                    layoutParams.height = window.attributes.height
                    dialogContentWithBackground.layoutParams = layoutParams
                    window.setLayout(MATCH_PARENT, MATCH_PARENT)
                }
            }
            decorView.addOnLayoutChangeListener(decorViewLayoutListener)

            dialogContentWithBackground
        }
        this.dialogContentWithBackground = dialogContentWithBackground
        dialogContentWithBackground.setTag(R.id.tag_dialog_background, true)

        val background = dialogContentWithBackground.background
        originalDialogBackgroundColor =
            GhostedViewLaunchAnimatorController.findGradientDrawable(background)
                ?.color
                ?.defaultColor ?: Color.BLACK

        // Make the background view invisible until we start the animation. We use the transition
        // visibility like GhostView does so that we don't mess up with the accessibility tree (see
        // b/204944038#comment17).
        dialogContentWithBackground.setTransitionVisibility(View.INVISIBLE)

        // Make sure the dialog is visible instantly and does not do any window animation.
        window.attributes.windowAnimations = R.style.Animation_LaunchAnimation

        // Ensure that the animation is not clipped by the display cut-out when animating this
        // dialog into an app.
        window.attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        window.attributes = window.attributes

        // We apply the insets ourselves to make sure that the paddings are set on the correct
        // View.
        window.setDecorFitsSystemWindows(false)
        val viewWithInsets = (dialogContentWithBackground.parent as ViewGroup)
        viewWithInsets.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsets.Type.displayCutout())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsets.CONSUMED
        }

        // Start the animation once the background view is properly laid out.
        dialogContentWithBackground.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                dialogContentWithBackground.removeOnLayoutChangeListener(this)

                isOriginalDialogViewLaidOut = true
                maybeStartLaunchAnimation()
            }
        })

        // Disable the dim. We will enable it once we start the animation.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Override the dialog dismiss() so that we can animate the exit before actually dismissing
        // the dialog.
        dialog.setDismissOverride(this::onDialogDismissed)

        // Show the dialog.
        dialog.show()

        addTouchSurfaceGhost()
    }

    private fun addTouchSurfaceGhost() {
        if (decorView.viewRootImpl == null) {
            // Make sure that we have access to the dialog view root to synchronize the creation of
            // the ghost.
            decorView.post(::addTouchSurfaceGhost)
            return
        }

        // Create a ghost of the touch surface (which will make the touch surface invisible) and add
        // it to the host dialog. We trigger a one off synchronization to make sure that this is
        // done in sync between the two different windows.
        synchronizeNextDraw(then = {
            isTouchSurfaceGhostDrawn = true
            maybeStartLaunchAnimation()
        })
        GhostView.addGhost(touchSurface, decorView)

        // The ghost of the touch surface was just created, so the touch surface is currently
        // invisible. We need to make sure that it stays invisible as long as the dialog is shown or
        // animating.
        (touchSurface as? LaunchableView)?.setShouldBlockVisibilityChanges(true)
    }

    /**
     * Synchronize the next draw of the touch surface and dialog view roots so that they are
     * performed at the same time, in the same transaction. This is necessary to make sure that the
     * ghost of the touch surface is drawn at the same time as the touch surface is made invisible
     * (or inversely, removed from the UI when the touch surface is made visible).
     */
    private fun synchronizeNextDraw(then: () -> Unit) {
        if (forceDisableSynchronization) {
            then()
            return
        }

        ViewRootSync.synchronizeNextDraw(touchSurface, decorView, then)
    }

    private fun findFirstViewGroupWithBackground(view: View): ViewGroup? {
        if (view !is ViewGroup) {
            return null
        }

        if (view.background != null) {
            return view
        }

        for (i in 0 until view.childCount) {
            val match = findFirstViewGroupWithBackground(view.getChildAt(i))
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun maybeStartLaunchAnimation() {
        if (!isTouchSurfaceGhostDrawn || !isOriginalDialogViewLaidOut) {
            return
        }

        // Show the background dim.
        dialog.window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        startAnimation(
            isLaunching = true,
            onLaunchAnimationStart = {
                // Remove the temporary ghost. Another ghost (that ghosts only the touch surface
                // content, and not its background) will be added right after this and will be
                // animated.
                GhostView.removeGhost(touchSurface)
            },
            onLaunchAnimationEnd = {
                touchSurface.setTag(R.id.tag_launch_animation_running, null)

                // We hide the touch surface when the dialog is showing. We will make this
                // view visible again when dismissing the dialog.
                touchSurface.visibility = View.INVISIBLE

                isLaunching = false

                // dismiss was called during the animation, dismiss again now to actually
                // dismiss.
                if (dismissRequested) {
                    dialog.dismiss()
                }

                // If necessary, we animate the dialog background when its bounds change. We do it
                // at the end of the launch animation, because the lauch animation already correctly
                // handles bounds changes.
                if (backgroundLayoutListener != null) {
                    dialogContentWithBackground!!
                        .addOnLayoutChangeListener(backgroundLayoutListener)
                }
            }
        )
    }

    fun onDialogDismissed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            dialog.context.mainExecutor.execute { onDialogDismissed() }
            return
        }

        // TODO(b/193634619): Support interrupting the launch animation in the middle.
        if (isLaunching) {
            dismissRequested = true
            return
        }

        if (isDismissing) {
            return
        }

        isDismissing = true
        hideDialogIntoView { animationRan: Boolean ->
            if (animationRan) {
                // Instantly dismiss the dialog if we ran the animation into view. If it was
                // skipped, dismiss() will run the window animation (which fades out the dialog).
                dialog.hide()
            }

            dialog.setDismissOverride(null)
            dialog.dismiss()
        }
    }

    /**
     * Hide the dialog into the touch surface and call [onAnimationFinished] when the animation is
     * done (passing animationRan=true) or if it's skipped (passing animationRan=false) to actually
     * dismiss the dialog.
     */
    private fun hideDialogIntoView(onAnimationFinished: (Boolean) -> Unit) {
        // Remove the layout change listener we have added to the DecorView earlier.
        if (decorViewLayoutListener != null) {
            decorView.removeOnLayoutChangeListener(decorViewLayoutListener)
        }

        if (!shouldAnimateDialogIntoView()) {
            Log.i(TAG, "Skipping animation of dialog into the touch surface")

            // Make sure we allow the touch surface to change its visibility again.
            (touchSurface as? LaunchableView)?.setShouldBlockVisibilityChanges(false)

            // If the view is invisible it's probably because of us, so we make it visible again.
            if (touchSurface.visibility == View.INVISIBLE) {
                touchSurface.visibility = View.VISIBLE
            }

            onAnimationFinished(false /* instantDismiss */)
            onDialogDismissed(this@AnimatedDialog)
            return
        }

        startAnimation(
            isLaunching = false,
            onLaunchAnimationStart = {
                // Remove the dim background as soon as we start the animation.
                dialog.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            },
            onLaunchAnimationEnd = {
                // Make sure we allow the touch surface to change its visibility again.
                (touchSurface as? LaunchableView)?.setShouldBlockVisibilityChanges(false)

                touchSurface.visibility = View.VISIBLE
                val dialogContentWithBackground = this.dialogContentWithBackground!!
                dialogContentWithBackground.visibility = View.INVISIBLE

                if (backgroundLayoutListener != null) {
                    dialogContentWithBackground
                        .removeOnLayoutChangeListener(backgroundLayoutListener)
                }

                // Make sure that the removal of the ghost and making the touch surface visible is
                // done at the same time.
                synchronizeNextDraw(then = {
                    onAnimationFinished(true /* instantDismiss */)
                    onDialogDismissed(this@AnimatedDialog)
                })
            }
        )
    }

    private fun startAnimation(
        isLaunching: Boolean,
        onLaunchAnimationStart: () -> Unit = {},
        onLaunchAnimationEnd: () -> Unit = {}
    ) {
        // Create 2 ghost controllers to animate both the dialog and the touch surface in the
        // dialog.
        val startView = if (isLaunching) touchSurface else dialogContentWithBackground!!
        val endView = if (isLaunching) dialogContentWithBackground!! else touchSurface
        val startViewController = GhostedViewLaunchAnimatorController(startView)
        val endViewController = GhostedViewLaunchAnimatorController(endView)
        startViewController.launchContainer = decorView
        endViewController.launchContainer = decorView

        val endState = endViewController.createAnimatorState()
        val controller = object : LaunchAnimator.Controller {
            override var launchContainer: ViewGroup
                get() = startViewController.launchContainer
                set(value) {
                    startViewController.launchContainer = value
                    endViewController.launchContainer = value
                }

            override fun createAnimatorState(): LaunchAnimator.State {
                return startViewController.createAnimatorState()
            }

            override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
                // During launch, onLaunchAnimationStart will be used to remove the temporary touch
                // surface ghost so it is important to call this before calling
                // onLaunchAnimationStart on the controller (which will create its own ghost).
                onLaunchAnimationStart()

                startViewController.onLaunchAnimationStart(isExpandingFullyAbove)
                endViewController.onLaunchAnimationStart(isExpandingFullyAbove)
            }

            override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
                startViewController.onLaunchAnimationEnd(isExpandingFullyAbove)
                endViewController.onLaunchAnimationEnd(isExpandingFullyAbove)

                onLaunchAnimationEnd()
            }

            override fun onLaunchAnimationProgress(
                state: LaunchAnimator.State,
                progress: Float,
                linearProgress: Float
            ) {
                startViewController.onLaunchAnimationProgress(state, progress, linearProgress)

                // The end view is visible only iff the starting view is not visible.
                state.visible = !state.visible
                endViewController.onLaunchAnimationProgress(state, progress, linearProgress)

                // If the dialog content is complex, its dimension might change during the launch
                // animation. The animation end position might also change during the exit
                // animation, for instance when locking the phone when the dialog is open. Therefore
                // we update the end state to the new position/size. Usually the dialog dimension or
                // position will change in the early frames, so changing the end state shouldn't
                // really be noticeable.
                endViewController.fillGhostedViewState(endState)
            }
        }

        launchAnimator.startAnimation(controller, endState, originalDialogBackgroundColor)
    }

    private fun shouldAnimateDialogIntoView(): Boolean {
        // Don't animate if the dialog was previously hidden using hide() or if we disabled the exit
        // animation.
        if (exitAnimationDisabled || !dialog.isShowing) {
            return false
        }

        // If we are dreaming, the dialog was probably closed because of that so we don't animate
        // into the touchSurface.
        if (dreamManager.isDreaming) {
            return false
        }

        // The touch surface should be invisible by now, if it's not then something else changed its
        // visibility and we probably don't want to run the animation.
        if (touchSurface.visibility != View.INVISIBLE) {
            return false
        }

        // If the touch surface is not attached or one of its ancestors is not visible, then we
        // don't run the animation either.
        if (!touchSurface.isAttachedToWindow) {
            return false
        }

        return (touchSurface.parent as? View)?.isShown ?: true
    }

    /** A layout listener to animate the change of bounds of the dialog background.  */
    class AnimatedBoundsLayoutListener : View.OnLayoutChangeListener {
        companion object {
            private const val ANIMATION_DURATION = 500L
        }

        private var lastBounds: Rect? = null
        private var currentAnimator: ValueAnimator? = null

        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            // Don't animate if bounds didn't actually change.
            if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
                // Make sure that we that the last bounds set by the animator were not overridden.
                lastBounds?.let { bounds ->
                    view.left = bounds.left
                    view.top = bounds.top
                    view.right = bounds.right
                    view.bottom = bounds.bottom
                }
                return
            }

            if (lastBounds == null) {
                lastBounds = Rect(oldLeft, oldTop, oldRight, oldBottom)
            }

            val bounds = lastBounds!!
            val startLeft = bounds.left
            val startTop = bounds.top
            val startRight = bounds.right
            val startBottom = bounds.bottom

            currentAnimator?.cancel()
            currentAnimator = null

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_DURATION
                interpolator = Interpolators.STANDARD

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentAnimator = null
                    }
                })

                addUpdateListener { animatedValue ->
                    val progress = animatedValue.animatedFraction

                    // Compute new bounds.
                    bounds.left = MathUtils.lerp(startLeft, left, progress).roundToInt()
                    bounds.top = MathUtils.lerp(startTop, top, progress).roundToInt()
                    bounds.right = MathUtils.lerp(startRight, right, progress).roundToInt()
                    bounds.bottom = MathUtils.lerp(startBottom, bottom, progress).roundToInt()

                    // Set the new bounds.
                    view.left = bounds.left
                    view.top = bounds.top
                    view.right = bounds.right
                    view.bottom = bounds.bottom
                }
            }

            currentAnimator = animator
            animator.start()
        }
    }

    fun prepareForStackDismiss(): View {
        if (parentAnimatedDialog == null) {
            return touchSurface
        }
        parentAnimatedDialog.exitAnimationDisabled = true
        parentAnimatedDialog.dialog.hide()
        val view = parentAnimatedDialog.prepareForStackDismiss()
        parentAnimatedDialog.dialog.dismiss()
        // Make the touch surface invisible, so we end up animating to it when we actually
        // dismiss the stack
        view.visibility = View.INVISIBLE
        return view
    }
}
