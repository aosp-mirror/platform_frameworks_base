/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.keyguard

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import com.android.keyguard.dagger.KeyguardStatusViewComponent
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.ClockRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** [Presentation] shown in connected displays while on keyguard. */
class ConnectedDisplayKeyguardPresentation
@AssistedInject
constructor(
    @Assisted display: Display,
    context: Context,
    private val keyguardStatusViewComponentFactory: KeyguardStatusViewComponent.Factory,
    private val clockRegistry: ClockRegistry,
    private val clockEventController: ClockEventController,
) :
    Presentation(
        context,
        display,
        R.style.Theme_SystemUI_KeyguardPresentation,
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
    ) {

    private lateinit var rootView: FrameLayout
    private var clock: View? = null
    private lateinit var keyguardStatusViewController: KeyguardStatusViewController
    private lateinit var faceController: ClockFaceController
    private lateinit var clockFrame: FrameLayout

    private val clockChangedListener =
        object : ClockRegistry.ClockChangeListener {
            override fun onCurrentClockChanged() {
                setClock(clockRegistry.createCurrentClock())
            }

            override fun onAvailableClocksChanged() {}
        }

    private val layoutChangeListener =
        object : View.OnLayoutChangeListener {
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
                clock?.let {
                    faceController.events.onTargetRegionChanged(
                        Rect(it.left, it.top, it.width, it.height)
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (migrateClocksToBlueprint()) {
            onCreateV2()
        } else {
            onCreate()
        }
    }

    fun onCreateV2() {
        rootView = FrameLayout(getContext(), null)
        rootView.setClipChildren(false)
        setContentView(rootView)

        setFullscreen()

        setClock(clockRegistry.createCurrentClock())
    }

    fun onCreate() {
        setContentView(
            LayoutInflater.from(context)
                .inflate(R.layout.keyguard_clock_presentation, /* root= */ null)
        )

        setFullscreen()

        clock = requireViewById(R.id.clock)
        keyguardStatusViewController =
            keyguardStatusViewComponentFactory
                .build(clock as KeyguardStatusView, display)
                .keyguardStatusViewController
                .apply {
                    setDisplayedOnSecondaryDisplay()
                    init()
                }
    }

    override fun onAttachedToWindow() {
        if (migrateClocksToBlueprint()) {
            clockRegistry.registerClockChangeListener(clockChangedListener)
            clockEventController.registerListeners(clock!!)

            faceController.animations.enter()
        }
    }

    override fun onDetachedFromWindow() {
        if (migrateClocksToBlueprint()) {
            clockEventController.unregisterListeners()
            clockRegistry.unregisterClockChangeListener(clockChangedListener)
        }

        super.onDetachedFromWindow()
    }

    override fun onDisplayChanged() {
        val window = window ?: error("no window available.")
        window.getDecorView().requestLayout()
    }

    private fun setClock(clockController: ClockController) {
        clock?.removeOnLayoutChangeListener(layoutChangeListener)
        rootView.removeAllViews()

        faceController = clockController.largeClock
        clock = faceController.view.also { it.addOnLayoutChangeListener(layoutChangeListener) }
        rootView.addView(
            clock,
            FrameLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.keyguard_presentation_width),
                WRAP_CONTENT,
                Gravity.CENTER,
            )
        )

        clockEventController.clock = clockController
        clockEventController.setLargeClockOnSecondaryDisplay(true)
        faceController.events.onSecondaryDisplayChanged(true)
    }

    private fun setFullscreen() {
        val window = window ?: error("no window available.")
        // Logic to make the lock screen fullscreen
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        window.attributes.fitInsetsTypes = 0
        window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = Color.TRANSPARENT
    }

    /** [ConnectedDisplayKeyguardPresentation] factory. */
    @AssistedFactory
    interface Factory {
        /** Creates a new [Presentation] for the given [display]. */
        fun create(
            display: Display,
        ): ConnectedDisplayKeyguardPresentation
    }
}
