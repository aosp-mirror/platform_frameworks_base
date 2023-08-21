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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.android.keyguard.KeyguardStatusView
import com.android.keyguard.LockIconView
import com.android.systemui.R
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.common.ui.view.LongPressHandlingView

/** Provides a container for all keyguard ui content. */
class KeyguardRootView(
    context: Context,
    private val attrs: AttributeSet?,
) :
    ConstraintLayout(
        context,
        attrs,
    ) {

    var motionEventSpy: ((MotionEvent) -> Unit)? = null
    private var statusView: KeyguardStatusView? = null

    init {
        addLongPressHandlingView()
        addIndicationTextArea()
        addLockIconView()
        addAmbientIndicationArea()
        addLeftShortcut()
        addRightShortcut()
        addSettingsPopupMenu()
        addStatusView()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        motionEventSpy?.invoke(event)
        return super.onInterceptTouchEvent(event)
    }

    private fun addLongPressHandlingView() {
        addView(LongPressHandlingView(context, attrs).apply { id = R.id.keyguard_long_press })
    }

    private fun addIndicationTextArea() {
        val view = KeyguardIndicationArea(context, attrs)
        addView(view)
    }

    private fun addLockIconView() {
        val view = LockIconView(context, attrs).apply { id = R.id.lock_icon_view }
        addView(view)
    }

    private fun addAmbientIndicationArea() {
        LayoutInflater.from(context).inflate(R.layout.ambient_indication, this)
    }

    private fun addLeftShortcut() {
        val padding = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_padding)
        val view =
            LaunchableImageView(context, attrs).apply {
                id = R.id.start_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        addView(view)
    }

    private fun addRightShortcut() {
        val padding = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_padding)
        val view =
            LaunchableImageView(context, attrs).apply {
                id = R.id.end_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        addView(view)
    }

    private fun addSettingsPopupMenu() {
        val view =
            LayoutInflater.from(context)
                .inflate(R.layout.keyguard_settings_popup_menu, this, false)
                .apply {
                    id = R.id.keyguard_settings_button
                    visibility = GONE
                }
        addView(view)
    }

    fun addStatusView(): KeyguardStatusView {
        // StatusView may need to be rebuilt on config changes. Remove and reinflate
        statusView?.let { removeView(it) }
        val view =
            (LayoutInflater.from(context).inflate(R.layout.keyguard_status_view, this, false)
                    as KeyguardStatusView)
                .apply {
                    setClipChildren(false)
                    statusView = this
                }

        addView(view)
        return view
    }
}
