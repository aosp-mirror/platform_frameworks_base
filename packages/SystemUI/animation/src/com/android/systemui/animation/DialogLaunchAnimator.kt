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
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.util.Log
import android.util.MathUtils
import android.view.GhostView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManagerPolicyConstants
import android.widget.FrameLayout
import kotlin.math.roundToInt

private const val TAG = "DialogLaunchAnimator"

/**
 * A class that allows dialogs to be started in a seamless way from a view that is transforming
 * nicely into the starting dialog.
 */
class DialogLaunchAnimator(
    private val context: Context,
    private val launchAnimator: LaunchAnimator,
    private val hostDialogProvider: HostDialogProvider
) {
    private companion object {
        private val TAG_LAUNCH_ANIMATION_RUNNING = R.id.launch_animation_running
    }

    // TODO(b/201264644): Remove this set.
    private val currentAnimations = hashSetOf<DialogLaunchAnimation>()

    /**
     * Show [dialog] by expanding it from [view]. If [animateBackgroundBoundsChange] is true, then
     * the background of the dialog will be animated when the dialog bounds change.
     *
     * Caveats: When calling this function, the dialog content view will actually be stolen and
     * attached to a different dialog (and thus a different window) which means that the actual
     * dialog window will never be drawn. Moreover, unless [dialog] is a [ListenableDialog], you
     * must call dismiss(), hide() and show() on the [Dialog] returned by this function to actually
     * dismiss, hide or show the dialog.
     */
    @JvmOverloads
    fun showFromView(
        dialog: Dialog,
        view: View,
        animateBackgroundBoundsChange: Boolean = false
    ): Dialog {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException(
                "showFromView must be called from the main thread and dialog must be created in " +
                    "the main thread")
        }

        // Make sure we don't run the launch animation from the same view twice at the same time.
        if (view.getTag(TAG_LAUNCH_ANIMATION_RUNNING) != null) {
            Log.e(TAG, "Not running dialog launch animation as there is already one running")
            dialog.show()
            return dialog
        }

        view.setTag(TAG_LAUNCH_ANIMATION_RUNNING, true)

        val launchAnimation = DialogLaunchAnimation(
            context, launchAnimator, hostDialogProvider, view,
            onDialogDismissed = { currentAnimations.remove(it) }, originalDialog = dialog,
            animateBackgroundBoundsChange)
        val hostDialog = launchAnimation.hostDialog
        currentAnimations.add(launchAnimation)

        // If the dialog is dismissed/hidden/shown, then we should actually dismiss/hide/show the
        // host dialog.
        if (dialog is ListenableDialog) {
            dialog.addListener(object : DialogListener {
                override fun onDismiss(reason: DialogListener.DismissReason) {
                    dialog.removeListener(this)

                    // We disable the exit animation if we are dismissing the dialog because the
                    // device is being locked, otherwise the animation looks bad if AOD is enabled.
                    // If AOD is disabled the screen will directly becomes black and we won't see
                    // the animation anyways.
                    if (reason == DialogListener.DismissReason.DEVICE_LOCKED) {
                        launchAnimation.exitAnimationDisabled = true
                    }

                    hostDialog.dismiss()
                }

                override fun onHide() {
                    if (launchAnimation.ignoreNextCallToHide) {
                        launchAnimation.ignoreNextCallToHide = false
                        return
                    }

                    hostDialog.hide()
                }

                override fun onShow() {
                    hostDialog.show()

                    // We don't actually want to show the original dialog, so hide it.
                    launchAnimation.ignoreNextCallToHide = true
                    dialog.hide()
                }

                override fun onSizeChanged() {
                    launchAnimation.onOriginalDialogSizeChanged()
                }
            })
        }

        launchAnimation.start()
        return hostDialog
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
        currentAnimations.forEach { it.exitAnimationDisabled = true }
    }
}

interface HostDialogProvider {
    /**
     * Create a host dialog that will be used to host a launch animation. This host dialog must:
     *   1. call [onCreateCallback] in its onCreate() method, e.g. right after calling
     *      super.onCreate().
     *   2. call [dismissOverride] instead of doing any dismissing logic. The actual dismissing
     *      logic should instead be done inside the lambda passed to [dismissOverride], which will
     *      be called after the exit animation.
     *   3. Be full screen, i.e. have a window matching its parent size.
     *
     * See SystemUIHostDialogProvider for an example of implementation.
     */
    fun createHostDialog(
        context: Context,
        theme: Int,
        onCreateCallback: () -> Unit,
        dismissOverride: (() -> Unit) -> Unit
    ): Dialog
}

/** A dialog to/from which we can add/remove listeners. */
interface ListenableDialog {
    /** Add [listener] to the listeners. */
    fun addListener(listener: DialogListener)

    /** Remove [listener] from the listeners. */
    fun removeListener(listener: DialogListener)
}

interface DialogListener {
    /** The reason why a dialog was dismissed. */
    enum class DismissReason {
        UNKNOWN,

        /** The device was locked, which dismissed this dialog. */
        DEVICE_LOCKED,
    }

    /** Called when this dialog dismiss() is called. */
    fun onDismiss(reason: DismissReason)

    /** Called when this dialog hide() is called. */
    fun onHide()

    /** Called when this dialog show() is called. */
    fun onShow()

    /** Called when this dialog size might have changed, e.g. because of configuration changes. */
    fun onSizeChanged()
}

private class DialogLaunchAnimation(
    private val context: Context,
    private val launchAnimator: LaunchAnimator,
    hostDialogProvider: HostDialogProvider,

    /** The view that triggered the dialog after being tapped. */
    private val touchSurface: View,

    /**
     * A callback that will be called with this [DialogLaunchAnimation] after the dialog was
     * dismissed and the exit animation is done.
     */
    private val onDialogDismissed: (DialogLaunchAnimation) -> Unit,

    /** The original dialog whose content will be shown and animate in/out in [hostDialog]. */
    private val originalDialog: Dialog,

    /** Whether we should animate the dialog background when its bounds change. */
    private val animateBackgroundBoundsChange: Boolean
) {
    /**
     * The fullscreen dialog to which we will add the content view [originalDialogView] of
     * [originalDialog].
     */
    val hostDialog = hostDialogProvider.createHostDialog(
        context, R.style.HostDialogTheme, this::onHostDialogCreated, this::onHostDialogDismissed)

    /** The root content view of [hostDialog]. */
    private val hostDialogRoot = FrameLayout(context)

    /**
     * The parent of the original dialog content view, that serves as a fake window that will have
     * the same size as the original dialog window and to which we will set the original dialog
     * window background.
     */
    private val dialogContentParent = FrameLayout(context)

    /**
     * The background color of [originalDialogView], taking into consideration the [originalDialog]
     * window background color.
     */
    private var originalDialogBackgroundColor = Color.BLACK

    /**
     * Whether we are currently launching/showing the dialog by animating it from [touchSurface].
     */
    private var isLaunching = true

    /** Whether we are currently dismissing/hiding the dialog by animating into [touchSurface]. */
    private var isDismissing = false

    private var dismissRequested = false
    var ignoreNextCallToHide = false
    var exitAnimationDisabled = false

    private var isTouchSurfaceGhostDrawn = false
    private var isOriginalDialogViewLaidOut = false
    private var backgroundLayoutListener = if (animateBackgroundBoundsChange) {
        AnimatedBoundsLayoutListener()
    } else {
        null
    }

    fun start() {
        // Show the host (fullscreen) dialog, to which we will add the stolen dialog view.
        hostDialog.show()

        // Steal the dialog view. We do that by showing it but preventing it from drawing, then
        // hiding it as soon as its content is available.
        stealOriginalDialogContentView(then = this::showDialogFromView)
    }

    private fun onHostDialogCreated() {
        // Make the dialog fullscreen with a transparent background.
        hostDialog.setContentView(
            hostDialogRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val window = hostDialog.window
            ?: throw IllegalStateException("There is no window associated to the host dialog")
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // If we are using gesture navigation, then we can overlay the navigation/task bars with
        // the host dialog.
        val navigationMode = context.resources.getInteger(
            com.android.internal.R.integer.config_navBarInteractionMode)
        if (navigationMode == WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL) {
            window.attributes.fitInsetsTypes = window.attributes.fitInsetsTypes and
                WindowInsets.Type.navigationBars().inv()
            window.addFlags(FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_INSET_DECOR)
            window.setDecorFitsSystemWindows(false)
        }

        // Disable the dim. We will enable it once we start the animation.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Add a temporary touch surface ghost as soon as the window is ready to draw. This
        // temporary ghost will be drawn together with the touch surface, but in the host dialog
        // window. Once it is drawn, we will make the touch surface invisible, and then start the
        // animation. We do all this synchronization to avoid flicker that would occur if we made
        // the touch surface invisible too early (before its ghost is drawn), leading to one or more
        // frames with a hole instead of the touch surface (or its ghost).
        hostDialogRoot.viewTreeObserver.addOnPreDrawListener(object : OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                hostDialogRoot.viewTreeObserver.removeOnPreDrawListener(this)
                addTemporaryTouchSurfaceGhost()
                return true
            }
        })
        hostDialogRoot.invalidate()
    }

    private fun addTemporaryTouchSurfaceGhost() {
        // Create a ghost of the touch surface (which will make the touch surface invisible) and add
        // it to the host dialog. We will wait for this ghost to be drawn before starting the
        // animation.
        val ghost = GhostView.addGhost(touchSurface, hostDialogRoot)

        // The ghost of the touch surface was just created, so the touch surface was made invisible.
        // We make it visible again until the ghost is actually drawn.
        touchSurface.visibility = View.VISIBLE

        // Wait for the ghost to be drawn before continuing.
        ghost.viewTreeObserver.addOnPreDrawListener(object : OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                ghost.viewTreeObserver.removeOnPreDrawListener(this)
                onTouchSurfaceGhostDrawn()
                return true
            }
        })
        ghost.invalidate()
    }

    private fun onTouchSurfaceGhostDrawn() {
        // Make the touch surface invisible and make sure that it stays invisible as long as the
        // dialog is shown or animating.
        touchSurface.visibility = View.INVISIBLE
        if (touchSurface is LaunchableView) {
            touchSurface.setShouldBlockVisibilityChanges(true)
        }

        // Add a pre draw listener to (maybe) start the animation once the touch surface is
        // actually invisible.
        touchSurface.viewTreeObserver.addOnPreDrawListener(object : OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                touchSurface.viewTreeObserver.removeOnPreDrawListener(this)
                isTouchSurfaceGhostDrawn = true
                maybeStartLaunchAnimation()
                return true
            }
        })
        touchSurface.invalidate()
    }

    /** Get the content view of [originalDialog] and pass it to [then]. */
    private fun stealOriginalDialogContentView(then: (View) -> Unit) {
        // The original dialog content view will be attached to android.R.id.content when the dialog
        // is shown, so we show the dialog and add an observer to get the view but also prevents the
        // original dialog from being drawn.
        val androidContent = originalDialog.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("Dialog does not have any android.R.id.content view")

        androidContent.viewTreeObserver.addOnPreDrawListener(
            object : OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (androidContent.childCount == 1) {
                        androidContent.viewTreeObserver.removeOnPreDrawListener(this)

                        // Hide the animated dialog. Because of the dialog listener set up
                        // earlier, this would also hide the host dialog, but in this case we
                        // need to keep the host dialog visible.
                        ignoreNextCallToHide = true
                        originalDialog.hide()

                        then(androidContent.getChildAt(0))
                        return false
                    }

                    // Never draw the original dialog content.
                    return false
                }
            })
        originalDialog.show()
    }

    private fun showDialogFromView(dialogView: View) {
        // Close the dialog when clicking outside of it.
        hostDialogRoot.setOnClickListener { hostDialog.dismiss() }
        dialogView.isClickable = true

        // Set the background of the window dialog to the dialog itself.
        // TODO(b/193634619): Support dialog windows without background.
        // TODO(b/193634619): Support dialog whose background comes from the content view instead of
        // the window.
        val typedArray =
            originalDialog.context.obtainStyledAttributes(com.android.internal.R.styleable.Window)
        val backgroundRes =
            typedArray.getResourceId(com.android.internal.R.styleable.Window_windowBackground, 0)
        typedArray.recycle()
        if (backgroundRes == 0) {
            throw IllegalStateException("Dialogs with no backgrounds on window are not supported")
        }

        // Add a parent view to the original dialog view to which we will set the original dialog
        // window background. This View serves as a fake window with background, so that we are sure
        // that we don't override the dialog view paddings with the window background that usually
        // has insets.
        dialogContentParent.setBackgroundResource(backgroundRes)
        hostDialogRoot.addView(
            dialogContentParent,

            // We give it the size of its original dialog window.
            FrameLayout.LayoutParams(
                originalDialog.window.attributes.width,
                originalDialog.window.attributes.height,
                Gravity.CENTER
            )
        )

        // Make the dialog view parent invisible for now, to make sure it's not drawn yet.
        dialogContentParent.visibility = View.INVISIBLE

        val background = dialogContentParent.background!!
        originalDialogBackgroundColor =
            GhostedViewLaunchAnimatorController.findGradientDrawable(background)
                ?.color
                ?.defaultColor ?: Color.BLACK

        // Add the dialog view to its parent (that has the original window background).
        (dialogView.parent as? ViewGroup)?.removeView(dialogView)
        dialogContentParent.addView(
            dialogView,

            // It should match its parent size, which is sized the same as the original dialog
            // window.
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Start the animation when the dialog is laid out in the center of the host dialog.
        dialogContentParent.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
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
                dialogContentParent.removeOnLayoutChangeListener(this)

                isOriginalDialogViewLaidOut = true
                maybeStartLaunchAnimation()
            }
        })
    }

    fun onOriginalDialogSizeChanged() {
        // The dialog is the single child of the root.
        if (hostDialogRoot.childCount != 1) {
            return
        }

        val dialogView = hostDialogRoot.getChildAt(0)
        val layoutParams = dialogView.layoutParams as? FrameLayout.LayoutParams ?: return
        layoutParams.width = originalDialog.window.attributes.width
        layoutParams.height = originalDialog.window.attributes.height
        dialogView.layoutParams = layoutParams
    }

    private fun maybeStartLaunchAnimation() {
        if (!isTouchSurfaceGhostDrawn || !isOriginalDialogViewLaidOut) {
            return
        }

        // Show the background dim.
        hostDialog.window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        startAnimation(
            isLaunching = true,
            onLaunchAnimationStart = {
                // Remove the temporary ghost. Another ghost (that ghosts only the touch surface
                // content, and not its background) will be added right after this and will be
                // animated.
                GhostView.removeGhost(touchSurface)
            },
            onLaunchAnimationEnd = {
                touchSurface.setTag(R.id.launch_animation_running, null)

                // We hide the touch surface when the dialog is showing. We will make this
                // view visible again when dismissing the dialog.
                touchSurface.visibility = View.INVISIBLE

                isLaunching = false

                // dismiss was called during the animation, dismiss again now to actually
                // dismiss.
                if (dismissRequested) {
                    hostDialog.dismiss()
                }

                // If necessary, we animate the dialog background when its bounds change. We do it
                // at the end of the launch animation, because the lauch animation already correctly
                // handles bounds changes.
                if (backgroundLayoutListener != null) {
                    dialogContentParent.addOnLayoutChangeListener(backgroundLayoutListener)
                }
            }
        )
    }

    private fun onHostDialogDismissed(actualDismiss: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            context.mainExecutor.execute { onHostDialogDismissed(actualDismiss) }
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
        hideDialogIntoView { instantDismiss: Boolean ->
            if (instantDismiss) {
                originalDialog.hide()
                hostDialog.hide()
            }

            originalDialog.dismiss()
            actualDismiss()
        }
    }

    /**
     * Hide the dialog into the touch surface and call [dismissDialogs] when the animation is done
     * (passing instantDismiss=true) or if it's skipped (passing instantDismiss=false) to actually
     * dismiss the dialogs.
     */
    private fun hideDialogIntoView(dismissDialogs: (Boolean) -> Unit) {
        if (!shouldAnimateDialogIntoView()) {
            Log.i(TAG, "Skipping animation of dialog into the touch surface")

            // Make sure we allow the touch surface to change its visibility again.
            if (touchSurface is LaunchableView) {
                touchSurface.setShouldBlockVisibilityChanges(false)
            }

            // If the view is invisible it's probably because of us, so we make it visible again.
            if (touchSurface.visibility == View.INVISIBLE) {
                touchSurface.visibility = View.VISIBLE
            }

            dismissDialogs(false /* instantDismiss */)
            onDialogDismissed(this@DialogLaunchAnimation)
            return
        }

        startAnimation(
            isLaunching = false,
            onLaunchAnimationStart = {
                // Remove the dim background as soon as we start the animation.
                hostDialog.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            },
            onLaunchAnimationEnd = {
                // Make sure we allow the touch surface to change its visibility again.
                if (touchSurface is LaunchableView) {
                    touchSurface.setShouldBlockVisibilityChanges(false)
                }

                touchSurface.visibility = View.VISIBLE
                dialogContentParent.visibility = View.INVISIBLE

                if (backgroundLayoutListener != null) {
                    dialogContentParent.removeOnLayoutChangeListener(backgroundLayoutListener)
                }

                // The animated ghost was just removed. We create a temporary ghost that will be
                // removed only once we draw the touch surface, to avoid flickering that would
                // happen when removing the ghost too early (before the touch surface is drawn).
                GhostView.addGhost(touchSurface, hostDialogRoot)

                touchSurface.viewTreeObserver.addOnPreDrawListener(object : OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        touchSurface.viewTreeObserver.removeOnPreDrawListener(this)

                        // Now that the touch surface was drawn, we can remove the temporary ghost
                        // and instantly dismiss the dialog.
                        GhostView.removeGhost(touchSurface)
                        dismissDialogs(true /* instantDismiss */)
                        onDialogDismissed(this@DialogLaunchAnimation)

                        return true
                    }
                })
                touchSurface.invalidate()
            }
        )
    }

    private fun startAnimation(
        isLaunching: Boolean,
        onLaunchAnimationStart: () -> Unit = {},
        onLaunchAnimationEnd: () -> Unit = {}
    ) {
        // Create 2 ghost controllers to animate both the dialog and the touch surface in the host
        // dialog.
        val startView = if (isLaunching) touchSurface else dialogContentParent
        val endView = if (isLaunching) dialogContentParent else touchSurface
        val startViewController = GhostedViewLaunchAnimatorController(startView)
        val endViewController = GhostedViewLaunchAnimatorController(endView)
        startViewController.launchContainer = hostDialogRoot
        endViewController.launchContainer = hostDialogRoot

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
        if (exitAnimationDisabled) {
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
}
