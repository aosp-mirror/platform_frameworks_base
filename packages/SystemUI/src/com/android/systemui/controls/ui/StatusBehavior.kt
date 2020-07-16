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

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.service.controls.Control
import android.view.View
import android.view.WindowManager

import com.android.systemui.R

class StatusBehavior : Behavior {
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        val status = cws.control?.status ?: Control.STATUS_UNKNOWN
        val msg = when (status) {
            Control.STATUS_ERROR -> R.string.controls_error_generic
            Control.STATUS_DISABLED -> R.string.controls_error_timeout
            Control.STATUS_NOT_FOUND -> {
                cvh.layout.setOnClickListener(View.OnClickListener() {
                    showNotFoundDialog(cvh, cws)
                })
                cvh.layout.setOnLongClickListener(View.OnLongClickListener() {
                    showNotFoundDialog(cvh, cws)
                    true
                })
                R.string.controls_error_removed
            }
            else -> {
                cvh.isLoading = true
                com.android.internal.R.string.loading
            }
        }
        cvh.setStatusText(cvh.context.getString(msg))
        cvh.applyRenderInfo(false, colorOffset)
    }

    private fun showNotFoundDialog(cvh: ControlViewHolder, cws: ControlWithState) {
        val pm = cvh.context.getPackageManager()
        val ai = pm.getApplicationInfo(cws.componentName.packageName, PackageManager.GET_META_DATA)
        val appLabel = pm.getApplicationLabel(ai)
        val builder = AlertDialog.Builder(
            cvh.context,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        ).apply {
            val res = cvh.context.resources
            setTitle(res.getString(R.string.controls_error_removed_title))
            setMessage(res.getString(
                R.string.controls_error_removed_message, cvh.title.getText(), appLabel))
            setPositiveButton(
                R.string.controls_open_app,
                DialogInterface.OnClickListener { dialog, _ ->
                    try {
                        cws.control?.getAppIntent()?.send()
                        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    } catch (e: PendingIntent.CanceledException) {
                        cvh.setErrorStatus()
                    }
                    dialog.dismiss()
            })
            setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { dialog, _ ->
                    dialog.cancel()
                }
            )
        }
        cvh.visibleDialog = builder.create().apply {
            getWindow().apply {
                setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
                show()
            }
        }
    }
}
