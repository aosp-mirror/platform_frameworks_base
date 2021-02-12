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

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import javax.inject.Inject

/**
 * Show the controls space inside a dialog, as from the lock screen.
 */
class ControlsDialog @Inject constructor(
    thisContext: Context,
    val broadcastDispatcher: BroadcastDispatcher
) : Dialog(thisContext, R.style.Theme_SystemUI_Dialog_Control_LockScreen) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getAction()
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                dismiss()
            }
        }
    }

    init {
        window.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

        setContentView(R.layout.controls_in_dialog)

        requireViewById<ViewGroup>(R.id.control_detail_root).apply {
            setOnClickListener { dismiss() }
            (getParent() as View).setOnClickListener { dismiss() }
        }
    }

    fun show(
        controller: ControlsUiController
    ): ControlsDialog {
        super.show()

        val vg = requireViewById<ViewGroup>(com.android.systemui.R.id.global_actions_controls)
        vg.alpha = 0f
        controller.show(vg, { /* do nothing */ }, false /* startedFromGlobalActions */)

        vg.animate()
            .alpha(1f)
            .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
            .setDuration(300)

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        broadcastDispatcher.registerReceiver(receiver, filter)

        return this
    }

    override fun dismiss() {
        broadcastDispatcher.unregisterReceiver(receiver)

        if (!isShowing()) return

        super.dismiss()
    }
}
