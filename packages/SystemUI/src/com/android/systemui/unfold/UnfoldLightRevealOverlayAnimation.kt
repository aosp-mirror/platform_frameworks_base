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
package com.android.systemui.unfold

import android.annotation.BinderThread
import android.content.ContentResolver
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.Choreographer
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.SurfaceSession
import android.view.WindowManager
import android.view.WindowlessWindowManager
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.LinearLightRevealEffect
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation.AddOverlayReason.FOLD
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation.AddOverlayReason.UNFOLD
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.updates.RotationChangeProvider
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import com.android.systemui.util.concurrency.ThreadFactory
import com.android.systemui.util.traceSection
import com.android.wm.shell.displayareahelper.DisplayAreaHelper
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

@SysUIUnfoldScope
class UnfoldLightRevealOverlayAnimation
@Inject
constructor(
    private val context: Context,
    private val deviceStateManager: DeviceStateManager,
    private val contentResolver: ContentResolver,
    private val displayManager: DisplayManager,
    private val unfoldTransitionProgressProvider: UnfoldTransitionProgressProvider,
    private val displayAreaHelper: Optional<DisplayAreaHelper>,
    @Main private val executor: Executor,
    private val threadFactory: ThreadFactory,
    private val rotationChangeProvider: RotationChangeProvider,
) {

    private val transitionListener = TransitionListener()
    private val rotationWatcher = RotationWatcher()

    private lateinit var bgHandler: Handler
    private lateinit var bgExecutor: Executor

    private lateinit var wwm: WindowlessWindowManager
    private lateinit var unfoldedDisplayInfo: DisplayInfo
    private lateinit var overlayContainer: SurfaceControl

    private var root: SurfaceControlViewHost? = null
    private var scrimView: LightRevealScrim? = null
    private var isFolded: Boolean = false
    private var isUnfoldHandled: Boolean = true

    private var currentRotation: Int = context.display!!.rotation

    fun init() {
        // This method will be called only on devices where this animation is enabled,
        // so normally this thread won't be created
        bgHandler = threadFactory.buildHandlerOnNewThread(TAG)
        bgExecutor = threadFactory.buildDelayableExecutorOnHandler(bgHandler)

        deviceStateManager.registerCallback(bgExecutor, FoldListener())
        unfoldTransitionProgressProvider.addCallback(transitionListener)
        rotationChangeProvider.addCallback(rotationWatcher)

        val containerBuilder =
            SurfaceControl.Builder(SurfaceSession())
                .setContainerLayer()
                .setName("unfold-overlay-container")

        displayAreaHelper.get().attachToRootDisplayArea(
            Display.DEFAULT_DISPLAY,
            containerBuilder
        ) { builder ->
            executor.execute {
                overlayContainer = builder.build()

                SurfaceControl.Transaction()
                    .setLayer(overlayContainer, UNFOLD_OVERLAY_LAYER_Z_INDEX)
                    .show(overlayContainer)
                    .apply()

                wwm =
                    WindowlessWindowManager(context.resources.configuration, overlayContainer, null)
            }
        }

        // Get unfolded display size immediately as 'current display info' might be
        // not up-to-date during unfolding
        unfoldedDisplayInfo = getUnfoldedDisplayInfo()
    }

    /**
     * Called when screen starts turning on, the contents of the screen might not be visible yet.
     * This method reports back that the overlay is ready in [onOverlayReady] callback.
     *
     * @param onOverlayReady callback when the overlay is drawn and visible on the screen
     * @see [com.android.systemui.keyguard.KeyguardViewMediator]
     */
    @BinderThread
    fun onScreenTurningOn(onOverlayReady: Runnable) {
        executeInBackground {
            Trace.beginSection("$TAG#onScreenTurningOn")
            try {
                // Add the view only if we are unfolding and this is the first screen on
                if (!isFolded && !isUnfoldHandled && contentResolver.areAnimationsEnabled()) {
                    addOverlay(onOverlayReady, reason = UNFOLD)
                    isUnfoldHandled = true
                } else {
                    // No unfold transition, immediately report that overlay is ready
                    ensureOverlayRemoved()
                    onOverlayReady.run()
                }
            } finally {
                Trace.endSection()
            }
        }
    }

    private fun addOverlay(onOverlayReady: Runnable? = null, reason: AddOverlayReason) {
        if (!::wwm.isInitialized) {
            // Surface overlay is not created yet on the first SysUI launch
            onOverlayReady?.run()
            return
        }

        ensureInBackground()
        ensureOverlayRemoved()

        val newRoot = SurfaceControlViewHost(context, context.display!!, wwm)
        val params = getLayoutParams()
        val newView =
            LightRevealScrim(
                    context,
                    attrs = null,
                    initialWidth = params.width,
                    initialHeight = params.height
                )
                .apply {
                    revealEffect = createLightRevealEffect()
                    isScrimOpaqueChangedListener = Consumer {}
                    revealAmount =
                        when (reason) {
                            FOLD -> TRANSPARENT
                            UNFOLD -> BLACK
                        }
                }

        newRoot.setView(newView, params)

        if (onOverlayReady != null) {
            Trace.beginAsyncSection("$TAG#relayout", 0)

            newRoot.relayout(params) { transaction ->
                val vsyncId = Choreographer.getSfInstance().vsyncId

                // Apply the transaction that contains the first frame of the overlay and apply
                // another empty transaction with 'vsyncId + 1' to make sure that it is actually
                // displayed on the screen. The second transaction is necessary to remove the screen
                // blocker (turn on the brightness) only when the content is actually visible as it
                // might be presented only in the next frame.
                // See b/197538198
                transaction.setFrameTimelineVsync(vsyncId).apply()

                transaction
                    .setFrameTimelineVsync(vsyncId + 1)
                    .addTransactionCommittedListener(bgExecutor) {
                        Trace.endAsyncSection("$TAG#relayout", 0)
                        onOverlayReady.run()
                    }
                    .apply()
            }
        }

        scrimView = newView
        root = newRoot
    }

    private fun getLayoutParams(): WindowManager.LayoutParams {
        val params: WindowManager.LayoutParams = WindowManager.LayoutParams()

        val rotation = currentRotation
        val isNatural = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

        params.height =
            if (isNatural) unfoldedDisplayInfo.naturalHeight else unfoldedDisplayInfo.naturalWidth
        params.width =
            if (isNatural) unfoldedDisplayInfo.naturalWidth else unfoldedDisplayInfo.naturalHeight

        params.format = PixelFormat.TRANSLUCENT
        params.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
        params.title = "Unfold Light Reveal Animation"
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.fitInsetsTypes = 0
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.setTrustedOverlay()

        val packageName: String = context.opPackageName
        params.packageName = packageName

        return params
    }

    private fun createLightRevealEffect(): LightRevealEffect {
        val isVerticalFold =
            currentRotation == Surface.ROTATION_0 || currentRotation == Surface.ROTATION_180
        return LinearLightRevealEffect(isVertical = isVerticalFold)
    }

    private fun ensureOverlayRemoved() {
        ensureInBackground()
        traceSection("ensureOverlayRemoved") {
            root?.release()
            root = null
            scrimView = null
        }
    }

    private fun getUnfoldedDisplayInfo(): DisplayInfo =
        displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
            .asSequence()
            .map { DisplayInfo().apply { it.getDisplayInfo(this) } }
            .filter { it.type == Display.TYPE_INTERNAL }
            .maxByOrNull { it.naturalWidth }!!

    private inner class TransitionListener : TransitionProgressListener {

        override fun onTransitionProgress(progress: Float) {
            executeInBackground { scrimView?.revealAmount = progress }
        }

        override fun onTransitionFinished() {
            executeInBackground { ensureOverlayRemoved() }
        }

        override fun onTransitionStarted() {
            // Add view for folding case (when unfolding the view is added earlier)
            if (scrimView == null) {
                executeInBackground { addOverlay(reason = FOLD) }
            }
            // Disable input dispatching during transition.
            InputManager.getInstance().cancelCurrentTouch()
        }
    }

    private inner class RotationWatcher : RotationChangeProvider.RotationListener {
        override fun onRotationChanged(newRotation: Int) {
            executeInBackground {
                traceSection("$TAG#onRotationChanged") {
                    if (currentRotation != newRotation) {
                        currentRotation = newRotation
                        scrimView?.revealEffect = createLightRevealEffect()
                        root?.relayout(getLayoutParams())
                    }
                }
            }
        }
    }

    private fun executeInBackground(f: () -> Unit) {
        check(Looper.myLooper() != bgHandler.looper) {
            "Trying to execute using background handler while already running" +
                " in the background handler"
        }
        // The UiBackground executor is not used as it doesn't have a prepared looper.
        bgHandler.post(f)
    }

    private fun ensureInBackground() {
        check(Looper.myLooper() == bgHandler.looper) { "Not being executed in the background!" }
    }

    private inner class FoldListener :
        FoldStateListener(
            context,
            Consumer { isFolded ->
                if (isFolded) {
                    ensureOverlayRemoved()
                    isUnfoldHandled = false
                }
                this.isFolded = isFolded
            }
        )

    private enum class AddOverlayReason {
        FOLD,
        UNFOLD
    }

    private companion object {
        const val TAG = "UnfoldLightRevealOverlayAnimation"
        const val ROTATION_ANIMATION_OVERLAY_Z_INDEX = Integer.MAX_VALUE

        // Put the unfold overlay below the rotation animation screenshot to hide the moment
        // when it is rotated but the rotation of the other windows hasn't happen yet
        const val UNFOLD_OVERLAY_LAYER_Z_INDEX = ROTATION_ANIMATION_OVERLAY_Z_INDEX - 1

        // constants for revealAmount.
        const val TRANSPARENT = 1f
        const val BLACK = 0f
    }
}
