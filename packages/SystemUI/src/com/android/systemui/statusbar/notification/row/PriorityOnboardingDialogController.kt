/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsets.Type.statusBars
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Prefs
import com.android.systemui.R
import java.lang.IllegalStateException
import javax.inject.Inject

/**
 * Controller to handle presenting the priority conversations onboarding dialog
 */
class PriorityOnboardingDialogController @Inject constructor(
    val view: View,
    val context: Context,
    val ignoresDnd: Boolean,
    val showsAsBubble: Boolean
) {

    private lateinit var dialog: Dialog

    fun init() {
        initDialog()
    }

    fun show() {
        dialog.show()
    }

    private fun done() {
        // Log that the user has seen the onboarding
        Prefs.putBoolean(context, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, true)
        dialog.dismiss()
    }

    class Builder @Inject constructor() {
        private lateinit var view: View
        private lateinit var context: Context
        private var ignoresDnd = false
        private var showAsBubble = false

        fun setView(v: View): Builder {
            view = v
            return this
        }

        fun setContext(c: Context): Builder {
            context = c
            return this
        }

        fun setIgnoresDnd(ignore: Boolean): Builder {
            ignoresDnd = ignore
            return this
        }

        fun setShowsAsBubble(bubble: Boolean): Builder {
            showAsBubble = bubble
            return this
        }

        fun build(): PriorityOnboardingDialogController {
            val controller = PriorityOnboardingDialogController(
                    view, context, ignoresDnd, showAsBubble)
            return controller
        }
    }

    private fun initDialog() {
        dialog = Dialog(context)

        if (dialog.window == null) {
            throw IllegalStateException("Need a window for the onboarding dialog to show")
        }

        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        // Prevent a11y readers from reading the first element in the dialog twice
        dialog.setTitle("\u00A0")
        dialog.apply {
            setContentView(view)
            setCanceledOnTouchOutside(true)

            findViewById<TextView>(R.id.done_button)?.setOnClickListener {
                done()
            }

            if (!ignoresDnd) {
                findViewById<LinearLayout>(R.id.ignore_dnd_tip).visibility = GONE
            }

            if (!showsAsBubble) {
                findViewById<LinearLayout>(R.id.floating_bubble_tip).visibility = GONE
            }

            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(wmFlags)
                setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
                setWindowAnimations(com.android.internal.R.style.Animation_InputMethod)

                attributes = attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    title = ChannelEditorDialogController::class.java.simpleName
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    fitInsetsTypes = attributes.fitInsetsTypes and statusBars().inv()
                    width = MATCH_PARENT
                    height = WRAP_CONTENT
                }
            }
        }
    }

    private val wmFlags = (WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
}
