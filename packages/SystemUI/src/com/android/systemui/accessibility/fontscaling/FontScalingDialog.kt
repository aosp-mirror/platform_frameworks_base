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
package com.android.systemui.accessibility.fontscaling

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.settings.SystemSettings

/** The Dialog that contains a seekbar for changing the font size. */
class FontScalingDialog(context: Context, private val systemSettings: SystemSettings) :
    SystemUIDialog(context) {
    private val strEntryValues: Array<String> =
        context.resources.getStringArray(com.android.settingslib.R.array.entryvalues_font_size)
    private lateinit var title: TextView
    private lateinit var doneButton: Button
    private lateinit var seekBarWithIconButtonsView: SeekBarWithIconButtonsView

    private val configuration: Configuration =
        Configuration(context.getResources().getConfiguration())

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(R.string.font_scaling_dialog_title)
        setView(LayoutInflater.from(context).inflate(R.layout.font_scaling_dialog, null))
        setPositiveButton(
            R.string.quick_settings_done,
            /* onClick = */ null,
            /* dismissOnClick = */ true
        )
        super.onCreate(savedInstanceState)

        title = requireViewById(com.android.internal.R.id.alertTitle)
        doneButton = requireViewById(com.android.internal.R.id.button1)
        seekBarWithIconButtonsView = requireViewById(R.id.font_scaling_slider)

        seekBarWithIconButtonsView.setMax((strEntryValues).size - 1)

        val currentScale = systemSettings.getFloat(Settings.System.FONT_SCALE, 1.0f)
        seekBarWithIconButtonsView.setProgress(fontSizeValueToIndex(currentScale))

        seekBarWithIconButtonsView.setOnSeekBarChangeListener(
            object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    systemSettings.putString(Settings.System.FONT_SCALE, strEntryValues[progress])
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    // Do nothing
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    // Do nothing
                }
            }
        )
        doneButton.setOnClickListener { dismiss() }
    }

    private fun fontSizeValueToIndex(value: Float): Int {
        var lastValue = strEntryValues[0].toFloat()
        for (i in 1 until strEntryValues.size) {
            val thisValue = strEntryValues[i].toFloat()
            if (value < lastValue + (thisValue - lastValue) * .5f) {
                return i - 1
            }
            lastValue = thisValue
        }
        return strEntryValues.size - 1
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)

        val configDiff = configuration.diff(this.configuration)
        this.configuration.setTo(configuration)

        if (configDiff and ActivityInfo.CONFIG_FONT_SCALE != 0) {
            title.post {
                title.setTextAppearance(R.style.TextAppearance_Dialog_Title)
                doneButton.setTextAppearance(R.style.Widget_Dialog_Button)
            }
        }
    }
}
