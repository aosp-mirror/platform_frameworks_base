/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DialogDelegate

/** Base permission dialog for screen share and recording */
abstract class BaseMediaProjectionPermissionDialogDelegate<T : AlertDialog>(
    private val screenShareOptions: List<ScreenShareOption>,
    private val appName: String?,
    private val hostUid: Int,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @DrawableRes private val dialogIconDrawable: Int? = null,
    @ColorRes private val dialogIconTint: Int? = null,
) : DialogDelegate<T>, AdapterView.OnItemSelectedListener {
    private lateinit var dialogTitle: TextView
    private lateinit var startButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var warning: TextView
    private lateinit var screenShareModeSpinner: Spinner
    private var hasCancelBeenLogged: Boolean = false
    protected lateinit var dialog: AlertDialog
    var selectedScreenShareOption: ScreenShareOption = screenShareOptions.first()

    @CallSuper
    override fun onStop(dialog: T) {
        // onStop can be called multiple times and we only want to log once.
        if (hasCancelBeenLogged) {
            return
        }

        mediaProjectionMetricsLogger.notifyProjectionRequestCancelled(hostUid)
        hasCancelBeenLogged = true
    }

    @CallSuper
    override fun onCreate(dialog: T, savedInstanceState: Bundle?) {
        this.dialog = dialog
        dialog.window?.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setContentView(R.layout.screen_share_dialog)
        dialogTitle = dialog.requireViewById(R.id.screen_share_dialog_title)
        warning = dialog.requireViewById(R.id.text_warning)
        startButton = dialog.requireViewById(android.R.id.button1)
        cancelButton = dialog.requireViewById(android.R.id.button2)
        updateIcon()
        initScreenShareOptions()
        createOptionsView(getOptionsViewLayoutId())
    }

    private fun updateIcon() {
        val icon = dialog.requireViewById<ImageView>(R.id.screen_share_dialog_icon)
        if (dialogIconTint != null) {
            icon.setColorFilter(dialog.context.getColor(dialogIconTint))
        }
        if (dialogIconDrawable != null) {
            icon.setImageDrawable(dialog.context.getDrawable(dialogIconDrawable))
        }
    }

    private fun initScreenShareOptions() {
        selectedScreenShareOption = screenShareOptions.first()
        warning.text = warningText
        initScreenShareSpinner()
    }

    private val warningText: String
        get() = dialog.context.getString(selectedScreenShareOption.warningText, appName)

    private fun initScreenShareSpinner() {
        val adapter = OptionsAdapter(dialog.context.applicationContext, screenShareOptions)
        screenShareModeSpinner = dialog.requireViewById(R.id.screen_share_mode_spinner)
        screenShareModeSpinner.adapter = adapter
        screenShareModeSpinner.onItemSelectedListener = this
    }

    override fun onItemSelected(adapterView: AdapterView<*>?, view: View, pos: Int, id: Long) {
        selectedScreenShareOption = screenShareOptions[pos]
        warning.text = warningText
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    /** Protected methods for the text updates & functionality */
    protected fun setDialogTitle(@StringRes stringId: Int) {
        val title = dialog.context.getString(stringId, appName)
        dialogTitle.text = title
    }

    protected fun setStartButtonText(@StringRes stringId: Int) {
        startButton.setText(stringId)
    }

    protected fun setStartButtonOnClickListener(listener: View.OnClickListener?) {
        startButton.setOnClickListener(listener)
    }

    protected fun setCancelButtonOnClickListener(listener: View.OnClickListener?) {
        cancelButton.setOnClickListener(listener)
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

private class OptionsAdapter(
    context: Context,
    private val options: List<ScreenShareOption>,
) :
    ArrayAdapter<String>(
        context,
        R.layout.screen_share_dialog_spinner_text,
        options.map { context.getString(it.spinnerText) }
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
