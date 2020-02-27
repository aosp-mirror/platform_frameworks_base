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

package com.android.systemui.controls.ui

import android.app.ActivityView
import android.app.ActivityOptions
import android.app.Dialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

import com.android.systemui.R

/**
 * A dialog that provides an {@link ActivityView}, allowing the application to provide
 * additional information and actions pertaining to a {@link android.service.controls.Control}.
 * The activity being launched is specified by {@link android.service.controls.Control#getAppIntent}.
 */
class DetailDialog(
    val parentContext: Context,
    val intent: PendingIntent
) : Dialog(parentContext) {

    var activityView: ActivityView

    val stateCallback: ActivityView.StateCallback = object : ActivityView.StateCallback() {
        override fun onActivityViewReady(view: ActivityView) {
            val fillInIntent = Intent()

            // Apply flags to make behaviour match documentLaunchMode=always.
            fillInIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fillInIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            view.startActivity(intent, fillInIntent, ActivityOptions.makeBasic())
        }

        override fun onActivityViewDestroyed(view: ActivityView) {}

        override fun onTaskCreated(taskId: Int, componentName: ComponentName) {}

        override fun onTaskRemovalStarted(taskId: Int) {}
    }

    @Suppress("DEPRECATION")
    private fun Window.setWindowParams() {
        requestFeature(Window.FEATURE_NO_TITLE)

        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        decorView
        attributes.systemUiVisibility =
                (attributes.systemUiVisibility
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        setLayout(MATCH_PARENT, MATCH_PARENT)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
        getAttributes().setFitInsetsTypes(0 /* types */)
    }

    init {
        getWindow()?.setWindowParams()

        setContentView(R.layout.controls_detail_dialog)

        activityView = ActivityView(context, null, 0, false)
        requireViewById<ViewGroup>(R.id.controls_activity_view).apply {
            addView(activityView)
        }
    }

    override fun show() {
        val attrs = getWindow()?.attributes
        attrs?.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        getWindow()?.attributes = attrs

        activityView.setCallback(stateCallback)

        super.show()
    }

    override fun dismiss() {
        activityView.release()

        super.dismiss()
    }
}
