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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout

private const val TAG = "DialogLaunchAnimator"

/**
 * A class that allows dialogs to be started in a seamless way from a view that is transforming
 * nicely into the starting dialog.
 *
 * Important: Don't forget to call [DialogLaunchAnimator.onDozeAmountChanged] when the doze amount
 * changes to gracefully handle dialogs fading out when the device is dozing.
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
     * Show [dialog] by expanding it from [view].
     *
     * Caveats: When calling this function, the dialog content view will actually be stolen and
     * attached to a different dialog (and thus a different window) which means that the actual
     * dialog window will never be drawn. Moreover, unless [dialog] is a [ListenableDialog], you
     * must call dismiss(), hide() and show() on the [Dialog] returned by this function to actually
     * dismiss, hide or show the dialog.
     */
    fun showFromView(dialog: Dialog, view: View): Dialog {
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
            onDialogDismissed = { currentAnimations.remove(it) }, originalDialog = dialog)
        val hostDialog = launchAnimation.hostDialog
        currentAnimations.add(launchAnimation)

        // If the dialog is dismissed/hidden/shown, then we should actually dismiss/hide/show the
        // host dialog.
        if (dialog is ListenableDialog) {
            dialog.addListener(object : DialogListener {
                override fun onDismiss() {
                    dialog.removeListener(this)
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
            })
        }

        launchAnimation.start()
        return hostDialog
    }

    /** Notify the current doze amount, to ensure that dialogs fade out when dozing. */
    // TODO(b/193634619): Replace this by some mandatory constructor parameter to make sure that we
    // don't forget to call this when the doze amount changes.
    fun onDozeAmountChanged(amount: Float) {
        currentAnimations.forEach { it.onDozeAmountChanged(amount) }
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
     *
     * See SystemUIHostDialogProvider for an example of implementation.
     */
    fun createHostDialog(
        context: Context,
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
    /** Called when this dialog dismiss() is called. */
    fun onDismiss()

    /** Called when this dialog hide() is called. */
    fun onHide()

    /** Called when this dialog show() is called. */
    fun onShow()
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
    private val originalDialog: Dialog
) {
    /**
     * The fullscreen dialog to which we will add the content view [originalDialogView] of
     * [originalDialog].
     */
    val hostDialog = hostDialogProvider.createHostDialog(
        context, this::onHostDialogCreated, this::onHostDialogDismissed)

    /** The root content view of [hostDialog]. */
    private val hostDialogRoot = FrameLayout(context)

    /**
     * The content view of [originalDialog], which will be stolen from that dialog and added to
     * [hostDialogRoot].
     */
    private var originalDialogView: View? = null

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
    private var drawHostDialog = false
    var ignoreNextCallToHide = false

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
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // The host dialog animation is a translation of 0px so that it is shown directly. The
        // translation lasts X ms, so that the scrim fades in during that amount of time.
        window.attributes.windowAnimations = R.style.Animation_LaunchHostDialog

        // Prevent the host dialog from drawing until the animation starts.
        hostDialogRoot.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (drawHostDialog) {
                        hostDialogRoot.viewTreeObserver.removeOnPreDrawListener(this)
                        return true
                    }

                    return false
                }
            }
        )
    }

    /** Get the content view of [originalDialog] and pass it to [then]. */
    private fun stealOriginalDialogContentView(then: (View) -> Unit) {
        // The original dialog content view will be attached to android.R.id.content when the dialog
        // is shown, so we show the dialog and add an observer to get the view but also prevents the
        // original dialog from being drawn.
        val androidContent = originalDialog.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("Dialog does not have any android.R.id.content view")

        androidContent.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
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
        // Save the dialog view for later as we will need it for the close animation.
        this.originalDialogView = dialogView

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

        dialogView.setBackgroundResource(backgroundRes)
        originalDialogBackgroundColor =
            GhostedViewLaunchAnimatorController.findGradientDrawable(dialogView.background!!)
                ?.color
                ?.defaultColor ?: Color.BLACK

        // Add the dialog view to the host (fullscreen) dialog and make it invisible to make sure
        // it's not drawn yet.
        (dialogView.parent as? ViewGroup)?.removeView(dialogView)
        hostDialogRoot.addView(
            dialogView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        dialogView.visibility = View.INVISIBLE

        // Start the animation when the dialog is laid out in the center of the host dialog.
        dialogView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
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
                dialogView.removeOnLayoutChangeListener(this)
                startAnimation(
                    isLaunching = true,
                    onLaunchAnimationStart = { drawHostDialog = true },
                    onLaunchAnimationEnd = {
                        touchSurface.setTag(R.id.launch_animation_running, null)

                        // We hide the touch surface when the dialog is showing. We will make this
                        // view visible again when dismissing the dialog.
                        // TODO(b/193634619): Provide an easy way for views to check if they should
                        // be hidden because of a dialog launch so that they don't override this
                        // visibility when updating/refreshing itself.
                        touchSurface.visibility = View.INVISIBLE

                        isLaunching = false

                        // dismiss was called during the animation, dismiss again now to actually
                        // dismiss.
                        if (dismissRequested) {
                            hostDialog.dismiss()
                        }
                    }
                )
            }
        })
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
                touchSurface.visibility = View.VISIBLE
                originalDialogView!!.visibility = View.INVISIBLE
                dismissDialogs(true /* instantDismiss */)
                onDialogDismissed(this@DialogLaunchAnimation)
            }
        )
    }

    private fun startAnimation(
        isLaunching: Boolean,
        onLaunchAnimationStart: () -> Unit = {},
        onLaunchAnimationEnd: () -> Unit = {}
    ) {
        val dialogView = this.originalDialogView!!

        // Create 2 ghost controllers to animate both the dialog and the touch surface in the host
        // dialog.
        val startView = if (isLaunching) touchSurface else dialogView
        val endView = if (isLaunching) dialogView else touchSurface
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
                startViewController.onLaunchAnimationStart(isExpandingFullyAbove)
                endViewController.onLaunchAnimationStart(isExpandingFullyAbove)

                onLaunchAnimationStart()
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

    internal fun onDozeAmountChanged(amount: Float) {
        val alpha = Interpolators.ALPHA_OUT.getInterpolation(1 - amount)
        val decorView = this.hostDialog.window?.decorView ?: return
        if (decorView.hasOverlappingRendering() && alpha > 0.0f &&
            alpha < 1.0f && decorView.layerType != View.LAYER_TYPE_HARDWARE) {
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        decorView.alpha = alpha
    }
}
