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

package com.android.systemui.controls.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type

import com.android.systemui.R
import com.android.systemui.controls.management.ControlsAnimations
import com.android.systemui.util.LifecycleActivity
import javax.inject.Inject

/**
 * Displays Device Controls inside an activity
 */
class ControlsActivity @Inject constructor(
    private val uiController: ControlsUiController
) : LifecycleActivity() {

    private lateinit var parent: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.controls_fullscreen)

        getLifecycle().addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById<ViewGroup>(R.id.control_detail_root),
                window,
                intent
            )
        )

        requireViewById<ViewGroup>(R.id.control_detail_root).apply {
            setOnApplyWindowInsetsListener {
                v: View, insets: WindowInsets ->
                    v.apply {
                        val l = getPaddingLeft()
                        val t = getPaddingTop()
                        val r = getPaddingRight()
                        setPadding(l, t, r, insets.getInsets(Type.systemBars()).bottom)
                    }

                WindowInsets.CONSUMED
            }
        }
    }

    override fun onResume() {
        super.onResume()

        parent = requireViewById<ViewGroup>(R.id.global_actions_controls)
        parent.alpha = 0f
        uiController.show(parent, { finish() }, this)

        ControlsAnimations.enterAnimation(parent).start()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onPause() {
        super.onPause()

        uiController.hide()
    }
}
