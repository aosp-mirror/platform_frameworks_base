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

package com.android.systemui.mediaprojection.permission

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R

open class BaseMediaProjectionPermissionViewBinder(
    private val screenShareOptions: List<ScreenShareOption>,
    private val appName: String?,
    private val hostUid: Int,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @ScreenShareMode val defaultSelectedMode: Int = screenShareOptions.first().mode,
    private val dialog: AlertDialog,
) : AdapterView.OnItemSelectedListener {
    private lateinit var warning: TextView
    private lateinit var startButton: TextView
    private lateinit var screenShareModeSpinner: Spinner
    var selectedScreenShareOption: ScreenShareOption =
        screenShareOptions.first { it.mode == defaultSelectedMode }
    private var shouldLogCancel: Boolean = true

    fun unbind() {
        // unbind can be called multiple times and we only want to log once.
        if (shouldLogCancel) {
            mediaProjectionMetricsLogger.notifyProjectionRequestCancelled(hostUid)
            shouldLogCancel = false
        }
    }

    open fun bind() {
        warning = dialog.requireViewById(R.id.text_warning)
        startButton = dialog.requireViewById(android.R.id.button1)
        initScreenShareOptions()
        createOptionsView(getOptionsViewLayoutId())
    }

    private fun initScreenShareOptions() {
        selectedScreenShareOption = screenShareOptions.first { it.mode == defaultSelectedMode }
        setOptionSpecificFields()
        initScreenShareSpinner()
    }

    /** Sets fields on the dialog that change based on which option is selected. */
    private fun setOptionSpecificFields() {
        warning.text = warningText
        startButton.text = startButtonText
    }

    private fun initScreenShareSpinner() {
        val adapter = OptionsAdapter(dialog.context.applicationContext, screenShareOptions)
        screenShareModeSpinner = dialog.requireViewById(R.id.screen_share_mode_options)
        screenShareModeSpinner.adapter = adapter
        screenShareModeSpinner.onItemSelectedListener = this

        // disable redundant Touch & Hold accessibility action for Switch Access
        screenShareModeSpinner.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }
        screenShareModeSpinner.isLongClickable = false
        val defaultModePosition = screenShareOptions.indexOfFirst { it.mode == defaultSelectedMode }
        screenShareModeSpinner.setSelection(defaultModePosition, /* animate= */ false)
    }

    override fun onItemSelected(adapterView: AdapterView<*>?, view: View, pos: Int, id: Long) {
        selectedScreenShareOption = screenShareOptions[pos]
        setOptionSpecificFields()
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    private val warningText: String
        get() = dialog.context.getString(selectedScreenShareOption.warningText, appName)

    private val startButtonText: String
        get() = dialog.context.getString(selectedScreenShareOption.startButtonText)

    fun setStartButtonOnClickListener(listener: View.OnClickListener?) {
        startButton.setOnClickListener { view ->
            shouldLogCancel = false
            listener?.onClick(view)
        }
    }

    // Create additional options that is shown under the share mode spinner
    // Eg. the audio and tap toggles in SysUI Recorder
    @LayoutRes protected open fun getOptionsViewLayoutId(): Int? = null

    private fun createOptionsView(@LayoutRes layoutId: Int?) {
        if (layoutId == null) return
        val stub = dialog.requireViewById<View>(R.id.options_stub) as ViewStub
        stub.layoutResource = layoutId
        stub.inflate()
    }
}

private class OptionsAdapter(context: Context, private val options: List<ScreenShareOption>) :
    ArrayAdapter<String>(
        context,
        R.layout.screen_share_dialog_spinner_text,
        options.map { context.getString(it.spinnerText, it.displayName) },
    ) {

    override fun isEnabled(position: Int): Boolean {
        return options[position].spinnerDisabledText == null
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.screen_share_dialog_spinner_item_text, parent, false)
        val titleTextView = view.requireViewById<TextView>(android.R.id.text1)
        val errorTextView = view.requireViewById<TextView>(android.R.id.text2)
        titleTextView.text = getItem(position)
        errorTextView.text = options[position].spinnerDisabledText
        if (isEnabled(position)) {
            errorTextView.visibility = View.GONE
            titleTextView.isEnabled = true
        } else {
            errorTextView.visibility = View.VISIBLE
            titleTextView.isEnabled = false
        }
        return view
    }
}
