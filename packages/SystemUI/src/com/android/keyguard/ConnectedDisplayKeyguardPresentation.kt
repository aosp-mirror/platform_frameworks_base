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
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.android.keyguard.dagger.KeyguardStatusViewComponent
import com.android.systemui.res.R
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
) :
    Presentation(
        context,
        display,
        R.style.Theme_SystemUI_KeyguardPresentation,
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
    ) {

    private lateinit var keyguardStatusViewController: KeyguardStatusViewController
    private lateinit var clock: KeyguardStatusView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            LayoutInflater.from(context)
                .inflate(R.layout.keyguard_clock_presentation, /* root= */ null)
        )
        val window = window ?: error("no window available.")

        // Logic to make the lock screen fullscreen
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        window.attributes.fitInsetsTypes = 0
        window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = Color.TRANSPARENT

        clock = requireViewById(R.id.clock)
        keyguardStatusViewController =
            keyguardStatusViewComponentFactory
                .build(clock, display)
                .keyguardStatusViewController
                .apply {
                    setDisplayedOnSecondaryDisplay()
                    init()
                }
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
