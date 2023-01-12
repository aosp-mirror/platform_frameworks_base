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
package com.android.systemui.screenrecord

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewStub
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.android.systemui.R
import com.android.systemui.statusbar.phone.SystemUIDialog

/** Base permission dialog for screen share and recording */
open class BaseScreenSharePermissionDialog(
    context: Context?,
    private val screenShareOptions: List<ScreenShareOption>,
    private val appName: String?,
    @DrawableRes private val dialogIconDrawable: Int? = null,
    @ColorRes private val dialogIconTint: Int? = null
) : SystemUIDialog(context), AdapterView.OnItemSelectedListener {
    private lateinit var dialogTitle: TextView
    private lateinit var startButton: TextView
    private lateinit var warning: TextView
    private lateinit var screenShareModeSpinner: Spinner
    var selectedScreenShareOption: ScreenShareOption = screenShareOptions.first()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
            setGravity(Gravity.CENTER)
        }
        setContentView(R.layout.screen_share_dialog)
        dialogTitle = findViewById(R.id.screen_share_dialog_title)
        warning = findViewById(R.id.text_warning)
        startButton = findViewById(R.id.button_start)
        findViewById<TextView>(R.id.button_cancel).setOnClickListener { dismiss() }
        updateIcon()
        initScreenShareOptions()
        createOptionsView(getOptionsViewLayoutId())
    }

    private fun updateIcon() {
        val icon = findViewById<ImageView>(R.id.screen_share_dialog_icon)
        if (dialogIconTint != null) {
            icon.setColorFilter(context.getColor(dialogIconTint))
        }
        if (dialogIconDrawable != null) {
            icon.setImageDrawable(context.getDrawable(dialogIconDrawable))
        }
    }

    protected fun initScreenShareOptions() {
        selectedScreenShareOption = screenShareOptions.first()
        warning.text = warningText
        initScreenShareSpinner()
    }

    private val warningText: String
        get() = context.getString(selectedScreenShareOption.warningText, appName)

    private fun initScreenShareSpinner() {
        val options = screenShareOptions.map { context.getString(it.spinnerText) }.toTypedArray()
        val adapter =
            ArrayAdapter(
                context.applicationContext,
                R.layout.screen_share_dialog_spinner_text,
                options
            )
        adapter.setDropDownViewResource(R.layout.screen_share_dialog_spinner_item_text)
        screenShareModeSpinner = findViewById(R.id.screen_share_mode_spinner)
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
        val title = context.getString(stringId, appName)
        dialogTitle.text = title
    }

    protected fun setStartButtonText(@StringRes stringId: Int) {
        startButton.setText(stringId)
    }

    protected fun setStartButtonOnClickListener(listener: View.OnClickListener?) {
        startButton.setOnClickListener(listener)
    }

    // Create additional options that is shown under the share mode spinner
    // Eg. the audio and tap toggles in SysUI Recorder
    @LayoutRes protected open fun getOptionsViewLayoutId(): Int? = null

    private fun createOptionsView(@LayoutRes layoutId: Int?) {
        if (layoutId == null) return
        val stub = findViewById<View>(R.id.options_stub) as ViewStub
        stub.layoutResource = layoutId
        stub.inflate()
    }
}
