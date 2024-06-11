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
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView.OnSeekBarWithIconButtonsChangeListener
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView.OnSeekBarWithIconButtonsChangeListener.ControlUnitType
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.roundToInt

/** The Dialog that contains a seekbar for changing the font size. */
class FontScalingDialogDelegate
@Inject
constructor(
    private val context: Context,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    private val layoutInflater: LayoutInflater,
    private val systemSettings: SystemSettings,
    private val secureSettings: SecureSettings,
    private val systemClock: SystemClock,
    private val userTracker: UserTracker,
    @Main mainHandler: Handler,
    @Background private val backgroundDelayableExecutor: DelayableExecutor,
) : SystemUIDialog.Delegate {
    private val MIN_UPDATE_INTERVAL_MS: Long = 800
    private val CHANGE_BY_SEEKBAR_DELAY_MS: Long = 100
    private val CHANGE_BY_BUTTON_DELAY_MS: Long = 300
    private val strEntryValues: Array<String> =
        context.resources.getStringArray(com.android.settingslib.R.array.entryvalues_font_size)
    private lateinit var title: TextView
    private lateinit var doneButton: Button
    private lateinit var seekBarWithIconButtonsView: SeekBarWithIconButtonsView
    private var lastProgress: AtomicInteger = AtomicInteger(-1)
    private var lastUpdateTime: Long = 0
    private var cancelUpdateFontScaleRunnable: Runnable? = null

    private val configuration: Configuration = Configuration(context.resources.configuration)

    private val fontSizeObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                lastUpdateTime = systemClock.elapsedRealtime()
            }
        }

    override fun createDialog(): SystemUIDialog = systemUIDialogFactory.create(this)

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.setTitle(R.string.font_scaling_dialog_title)
        dialog.setView(layoutInflater.inflate(R.layout.font_scaling_dialog, null))
        dialog.setPositiveButton(
            R.string.quick_settings_done,
            /* onClick = */ null,
            /* dismissOnClick = */ true
        )
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        title = dialog.requireViewById(com.android.internal.R.id.alertTitle)
        doneButton = dialog.requireViewById(com.android.internal.R.id.button1)
        seekBarWithIconButtonsView = dialog.requireViewById(R.id.font_scaling_slider)

        val labelArray = arrayOfNulls<String>(strEntryValues.size)
        for (i in strEntryValues.indices) {
            labelArray[i] =
                context.resources.getString(
                    com.android.settingslib.R.string.font_scale_percentage,
                    (strEntryValues[i].toFloat() * 100).roundToInt()
                )
        }
        seekBarWithIconButtonsView.setProgressStateLabels(labelArray)

        seekBarWithIconButtonsView.setMax((strEntryValues).size - 1)

        val currentScale =
            systemSettings.getFloatForUser(Settings.System.FONT_SCALE, 1.0f, userTracker.userId)
        lastProgress.set(fontSizeValueToIndex(currentScale))
        seekBarWithIconButtonsView.setProgress(lastProgress.get())

        seekBarWithIconButtonsView.setOnSeekBarWithIconButtonsChangeListener(
            object : OnSeekBarWithIconButtonsChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    // Always provide preview configuration for text first when there is a change
                    // in the seekbar progress.
                    createTextPreview(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    // Do nothing
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    // Do nothing
                }

                override fun onUserInteractionFinalized(
                    seekBar: SeekBar,
                    @ControlUnitType control: Int
                ) {
                    if (control == ControlUnitType.BUTTON) {
                        // The seekbar progress is changed by icon buttons
                        changeFontSize(seekBar.progress, CHANGE_BY_BUTTON_DELAY_MS)
                    } else {
                        changeFontSize(seekBar.progress, CHANGE_BY_SEEKBAR_DELAY_MS)
                    }
                }
            }
        )
        doneButton.setOnClickListener { dialog.dismiss() }
        systemSettings.registerContentObserverSync(Settings.System.FONT_SCALE, fontSizeObserver)
    }

    /**
     * Avoid SeekBar flickers when changing font scale. See the description from Setting at {@link
     * TextReadingPreviewController#postCommitDelayed} for the reasons of flickers.
     */
    @MainThread
    fun updateFontScaleDelayed(delayMsFromSource: Long) {
        doneButton.isEnabled = false

        var delayMs = delayMsFromSource
        if (systemClock.elapsedRealtime() - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            delayMs += MIN_UPDATE_INTERVAL_MS
        }
        cancelUpdateFontScaleRunnable?.run()
        cancelUpdateFontScaleRunnable =
            backgroundDelayableExecutor.executeDelayed({ updateFontScale() }, delayMs)
    }

    override fun onStop(dialog: SystemUIDialog) {
        cancelUpdateFontScaleRunnable?.run()
        cancelUpdateFontScaleRunnable = null
        systemSettings.unregisterContentObserverSync(fontSizeObserver)
    }

    @MainThread
    private fun changeFontSize(progress: Int, changedWithDelay: Long) {
        if (progress != lastProgress.get()) {
            lastProgress.set(progress)

            if (!fontSizeHasBeenChangedFromTile) {
                backgroundDelayableExecutor.execute { updateSecureSettingsIfNeeded() }
                fontSizeHasBeenChangedFromTile = true
            }

            updateFontScaleDelayed(changedWithDelay)
        }
    }

    @WorkerThread
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

    override fun onConfigurationChanged(dialog: SystemUIDialog, configuration: Configuration) {
        val configDiff = configuration.diff(this.configuration)
        this.configuration.setTo(configuration)

        if (configDiff and ActivityInfo.CONFIG_FONT_SCALE != 0) {
            title.post {
                title.setTextAppearance(R.style.TextAppearance_Dialog_Title)
                doneButton.setTextAppearance(R.style.Widget_Dialog_Button)
                doneButton.isEnabled = true
            }
        }
    }

    @WorkerThread
    fun updateFontScale() {
        if (
            !systemSettings.putStringForUser(
                Settings.System.FONT_SCALE,
                strEntryValues[lastProgress.get()],
                userTracker.userId
            )
        ) {
            title.post { doneButton.isEnabled = true }
        }
    }

    @WorkerThread
    fun updateSecureSettingsIfNeeded() {
        if (
            secureSettings.getStringForUser(
                Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
                userTracker.userId
            ) != ON
        ) {
            secureSettings.putStringForUser(
                Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
                ON,
                userTracker.userId
            )
        }
    }

    /** Provides font size preview for text before putting the final settings to the system. */
    fun createTextPreview(index: Int) {
        val previewConfig = Configuration(configuration)
        previewConfig.fontScale = strEntryValues[index].toFloat()

        val previewConfigContext = context.createConfigurationContext(previewConfig)
        previewConfigContext.theme.setTo(context.theme)

        title.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            previewConfigContext.resources.getDimension(R.dimen.dialog_title_text_size)
        )
    }

    companion object {
        private const val ON = "1"
        private const val OFF = "0"
        private var fontSizeHasBeenChangedFromTile = false
    }
}
