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

package com.android.systemui.statusbar.events

import android.view.Display
import android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM
import android.view.DisplayCutout.BOUNDS_POSITION_LEFT
import android.view.DisplayCutout.BOUNDS_POSITION_RIGHT
import android.view.DisplayCutout.BOUNDS_POSITION_TOP
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.ScreenDecorations
import com.android.systemui.ScreenDecorationsThread
import com.android.systemui.decor.DecorProvider
import com.android.systemui.decor.PrivacyDotCornerDecorProviderImpl
import com.android.systemui.decor.PrivacyDotDecorProviderFactory
import com.android.systemui.statusbar.events.PrivacyDotCorner.BottomLeft
import com.android.systemui.statusbar.events.PrivacyDotCorner.BottomRight
import com.android.systemui.statusbar.events.PrivacyDotCorner.TopLeft
import com.android.systemui.statusbar.events.PrivacyDotCorner.TopRight
import com.android.systemui.util.containsExactly
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor

/**
 * Responsible for adding the privacy dot to a window.
 *
 * It will create one window per corner (top left, top right, bottom left, bottom right), which are
 * used dependant on the display's rotation.
 */
class PrivacyDotWindowController
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Assisted private val privacyDotViewController: PrivacyDotViewController,
    @Assisted private val viewCaptureAwareWindowManager: ViewCaptureAwareWindowManager,
    @Assisted private val inflater: LayoutInflater,
    @ScreenDecorationsThread private val uiExecutor: Executor,
    private val dotFactory: PrivacyDotDecorProviderFactory,
) {

    fun start() {
        uiExecutor.execute { startOnUiThread() }
    }

    private fun startOnUiThread() {
        val providers = dotFactory.providers

        val topLeft = providers.inflate(BOUNDS_POSITION_TOP, BOUNDS_POSITION_LEFT)
        val topRight = providers.inflate(BOUNDS_POSITION_TOP, BOUNDS_POSITION_RIGHT)
        val bottomLeft = providers.inflate(BOUNDS_POSITION_BOTTOM, BOUNDS_POSITION_LEFT)
        val bottomRight = providers.inflate(BOUNDS_POSITION_BOTTOM, BOUNDS_POSITION_RIGHT)

        topLeft.addToWindow(TopLeft)
        topRight.addToWindow(TopRight)
        bottomLeft.addToWindow(BottomLeft)
        bottomRight.addToWindow(BottomRight)

        privacyDotViewController.initialize(topLeft, topRight, bottomLeft, bottomRight)
    }

    private fun List<DecorProvider>.inflate(alignedBound1: Int, alignedBound2: Int): View {
        val provider =
            first { it.alignedBounds.containsExactly(alignedBound1, alignedBound2) }
                as PrivacyDotCornerDecorProviderImpl
        return inflater.inflate(/* resource= */ provider.layoutId, /* root= */ null)
    }

    private fun View.addToWindow(corner: PrivacyDotCorner) {
        val excludeFromScreenshots = displayId == Display.DEFAULT_DISPLAY
        val params =
            ScreenDecorations.getWindowLayoutBaseParams(excludeFromScreenshots).apply {
                width = WRAP_CONTENT
                height = WRAP_CONTENT
                gravity = corner.rotatedCorner(context.display.rotation).gravity
                title = "PrivacyDot${corner.title}$displayId"
            }
        // PrivacyDotViewController expects the dot view to have a FrameLayout parent.
        val rootView = FrameLayout(context)
        rootView.addView(this)
        viewCaptureAwareWindowManager.addView(rootView, params)
    }

    @AssistedFactory
    fun interface Factory {
        fun create(
            displayId: Int,
            privacyDotViewController: PrivacyDotViewController,
            viewCaptureAwareWindowManager: ViewCaptureAwareWindowManager,
            inflater: LayoutInflater,
        ): PrivacyDotWindowController
    }
}
