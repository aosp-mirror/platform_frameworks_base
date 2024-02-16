/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.Choreographer
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import android.view.Surface.Rotation
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.SurfaceSession
import android.view.WindowManager
import android.view.WindowlessWindowManager
import com.android.app.tracing.traceSection
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.unfold.updates.RotationChangeProvider
import com.android.systemui.util.concurrency.ThreadFactory
import com.android.wm.shell.displayareahelper.DisplayAreaHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

interface FullscreenLightRevealAnimation {
    fun init()

    fun onScreenTurningOn(onOverlayReady: Runnable)
}

class FullscreenLightRevealAnimationController
@AssistedInject
constructor(
    private val context: Context,
    private val displayManager: DisplayManager,
    private val threadFactory: ThreadFactory,
    @UnfoldBg private val bgHandler: Handler,
    @UnfoldBg private val rotationChangeProvider: RotationChangeProvider,
    private val displayAreaHelper: Optional<DisplayAreaHelper>,
    private val displayTracker: DisplayTracker,
    @Background private val applicationScope: CoroutineScope,
    @Main private val executor: Executor,
    @Assisted private val displaySelector: List<DisplayInfo>.() -> DisplayInfo?,
    @Assisted private val lightRevealEffectFactory: (rotation: Int) -> LightRevealEffect,
    @Assisted private val overlayContainerName: String
) {

    private lateinit var bgExecutor: Executor
    private lateinit var wwm: WindowlessWindowManager

    private var currentRotation: Int = context.display.rotation
    private var root: SurfaceControlViewHost? = null
    private var scrimView: LightRevealScrim? = null

    private val rotationWatcher = RotationWatcher()
    private val internalDisplayInfos: List<DisplayInfo> =
        displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
            .map { DisplayInfo().apply { it.getDisplayInfo(this) } }
            .filter { it.type == Display.TYPE_INTERNAL }

    var isTouchBlocked: Boolean = false
        set(value) {
            if (value != field) {
                traceSection("$TAG#relayoutToUpdateTouch") { root?.relayout(getLayoutParams()) }
                field = value
            }
        }

    fun init() {
        bgExecutor = threadFactory.buildDelayableExecutorOnHandler(bgHandler)
        rotationChangeProvider.addCallback(rotationWatcher)

        buildSurface { builder ->
            applicationScope.launch(executor.asCoroutineDispatcher()) {
                val overlayContainer = builder.build()

                SurfaceControl.Transaction()
                    .setLayer(overlayContainer, OVERLAY_LAYER_Z_INDEX)
                    .show(overlayContainer)
                    .apply()

                wwm =
                    WindowlessWindowManager(context.resources.configuration, overlayContainer, null)
            }
        }
    }

    fun addOverlay(
        initialAlpha: Float,
        onOverlayReady: Runnable? = null,
    ) {
        if (!::wwm.isInitialized) {
            // Surface overlay is not created yet on the first SysUI launch
            onOverlayReady?.run()
            return
        }
        ensureInBackground()
        ensureOverlayRemoved()
        prepareOverlay(onOverlayReady, wwm, bgExecutor, initialAlpha)
    }

    fun ensureOverlayRemoved() {
        ensureInBackground()

        traceSection("ensureOverlayRemoved") {
            root?.release()
            root = null
            scrimView = null
        }
    }

    fun isOverlayVisible(): Boolean {
        return scrimView == null
    }

    fun updateRevealAmount(revealAmount: Float) {
        scrimView?.revealAmount = revealAmount
    }

    private fun buildSurface(onUpdated: Consumer<SurfaceControl.Builder>) {
        val containerBuilder =
            SurfaceControl.Builder(SurfaceSession())
                .setContainerLayer()
                .setName(overlayContainerName)

        displayAreaHelper
            .get()
            .attachToRootDisplayArea(displayTracker.defaultDisplayId, containerBuilder, onUpdated)
    }

    private fun prepareOverlay(
        onOverlayReady: Runnable? = null,
        wwm: WindowlessWindowManager,
        bgExecutor: Executor,
        initialAlpha: Float,
    ) {
        val newRoot = SurfaceControlViewHost(context, context.display, wwm, javaClass.simpleName)

        val params = getLayoutParams()
        val newView =
            LightRevealScrim(
                    context,
                    attrs = null,
                    initialWidth = params.width,
                    initialHeight = params.height
                )
                .apply {
                    revealEffect = lightRevealEffectFactory(currentRotation)
                    revealAmount = initialAlpha
                }

        newRoot.setView(newView, params)

        if (onOverlayReady != null) {
            Trace.beginAsyncSection("$TAG#relayout", 0)

            newRoot.relayout(params) { transaction ->
                val vsyncId = Choreographer.getSfInstance().vsyncId
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
        root = newRoot
        scrimView = newView
    }

    private fun ensureInBackground() {
        check(Looper.myLooper() == bgHandler.looper) { "Not being executed in the background!" }
    }

    private fun getLayoutParams(): WindowManager.LayoutParams {
        val displayInfo =
            internalDisplayInfos.displaySelector()
                ?: throw IllegalArgumentException("No internal displays found!")
        return WindowManager.LayoutParams().apply {
            if (currentRotation.isVerticalRotation()) {
                height = displayInfo.naturalHeight
                width = displayInfo.naturalWidth
            } else {
                height = displayInfo.naturalWidth
                width = displayInfo.naturalHeight
            }
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
            title = javaClass.simpleName
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0

            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            setTrustedOverlay()

            packageName = context.opPackageName
        }
    }

    private inner class RotationWatcher : RotationChangeProvider.RotationListener {
        override fun onRotationChanged(newRotation: Int) {
            traceSection("$TAG#onRotationChanged") {
                if (currentRotation != newRotation) {
                    currentRotation = newRotation
                    scrimView?.revealEffect = lightRevealEffectFactory(currentRotation)
                    root?.relayout(getLayoutParams())
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            displaySelector: List<DisplayInfo>.() -> DisplayInfo?,
            effectFactory: (rotation: Int) -> LightRevealEffect,
            overlayContainerName: String
        ): FullscreenLightRevealAnimationController
    }

    companion object {
        private const val TAG = "FullscreenLightRevealAnimation"
        private const val ROTATION_ANIMATION_OVERLAY_Z_INDEX = Integer.MAX_VALUE
        private const val OVERLAY_LAYER_Z_INDEX = ROTATION_ANIMATION_OVERLAY_Z_INDEX - 1
        const val ALPHA_TRANSPARENT = 1f
        const val ALPHA_OPAQUE = 0f

        fun @receiver:Rotation Int.isVerticalRotation(): Boolean =
            this == Surface.ROTATION_0 || this == Surface.ROTATION_180
    }
}
