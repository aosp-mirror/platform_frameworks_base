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

package com.android.systemui.recordissue

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Switch
import android.widget.TextView
import com.android.systemui.recordissue.IssueRecordingState.Companion.TAG_TITLE_DELIMITER
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.traceur.PresetTraceConfigs
import com.android.traceur.TraceConfig
import com.android.traceur.res.R as T

class CustomTraceSettingsDialogDelegate(
    private val factory: SystemUIDialog.Factory,
    private val state: IssueRecordingState,
    private val onSave: Runnable,
) : SystemUIDialog.Delegate {

    private val builder = TraceConfig.Builder(PresetTraceConfigs.getDefaultConfig())

    override fun createDialog(): SystemUIDialog = factory.create(this)

    override fun beforeCreate(dialog: SystemUIDialog?, savedInstanceState: Bundle?) {
        super.beforeCreate(dialog, savedInstanceState)

        dialog?.apply {
            setTitle(R.string.custom_trace_settings_dialog_title)
            setView(
                LayoutInflater.from(context).inflate(R.layout.custom_trace_settings_dialog, null)
            )
            setPositiveButton(R.string.save) { _, _ -> onSave.run() }
            setNegativeButton(R.string.cancel) { _, _ -> }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(dialog: SystemUIDialog?, savedInstanceState: Bundle?) {
        super.onCreate(dialog, savedInstanceState)

        dialog?.apply {
            requireViewById<TextView>(R.id.categories).apply {
                text =
                    context.getString(T.string.categories) +
                        "\n" +
                        context.getString(R.string.notification_alert_title)
                setOnClickListener { showCategorySelector(this) }
            }
            requireViewById<Switch>(R.id.attach_to_bugreport_switch).apply {
                isChecked = builder.attachToBugreport
            }
            requireViewById<TextView>(R.id.cpu_buffer_size)
                .setupSingleChoiceText(
                    T.array.buffer_size_values,
                    T.array.buffer_size_names,
                    builder.bufferSizeKb,
                    T.string.buffer_size,
                )
            val longTraceSizeText: TextView =
                requireViewById<TextView>(R.id.long_trace_size)
                    .setupSingleChoiceText(
                        T.array.long_trace_size_values,
                        T.array.long_trace_size_names,
                        builder.maxLongTraceSizeMb,
                        T.string.max_long_trace_size,
                    )
            val longTraceDurationText: TextView =
                requireViewById<TextView>(R.id.long_trace_duration)
                    .setupSingleChoiceText(
                        T.array.long_trace_duration_values,
                        T.array.long_trace_duration_names,
                        builder.maxLongTraceDurationMinutes,
                        T.string.max_long_trace_duration,
                    )
            requireViewById<Switch>(R.id.long_traces_switch).apply {
                isChecked = builder.longTrace
                val disabledAlpha by lazy { getDisabledAlpha(context) }
                val alpha = if (isChecked) 1f else disabledAlpha
                longTraceDurationText.alpha = alpha
                longTraceSizeText.alpha = alpha

                setOnCheckedChangeListener { _, isChecked ->
                    longTraceDurationText.isEnabled = isChecked
                    longTraceSizeText.isEnabled = isChecked

                    val newAlpha = if (isChecked) 1f else disabledAlpha
                    longTraceDurationText.alpha = newAlpha
                    longTraceSizeText.alpha = newAlpha
                }
            }
            requireViewById<Switch>(R.id.winscope_switch).apply { isChecked = builder.winscope }
            requireViewById<Switch>(R.id.trace_debuggable_apps_switch).apply {
                isChecked = builder.apps
            }
            requireViewById<TextView>(R.id.long_traces_switch_label).text =
                context.getString(T.string.long_traces)
            requireViewById<TextView>(R.id.debuggable_apps_switch_label).text =
                context.getString(T.string.trace_debuggable_applications)
            requireViewById<TextView>(R.id.winscope_switch_label).text =
                context.getString(T.string.winscope_tracing)
            requireViewById<TextView>(R.id.attach_to_bugreport_switch_label).text =
                context.getString(T.string.attach_to_bug_report)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showCategorySelector(root: TextView) {
        showDialog(root.context) {
            val titlesToCheckmarks =
                state.tagTitles.associateBy(
                    { it },
                    { builder.tags.contains(it.substringBefore(TAG_TITLE_DELIMITER)) }
                )
            val titles = titlesToCheckmarks.keys.toTypedArray()
            val checkmarks = titlesToCheckmarks.values.toBooleanArray()
            val checkedTitleSuffixes =
                titlesToCheckmarks.entries
                    .filter { it.value }
                    .map { it.key.substringAfter(TAG_TITLE_DELIMITER) }
                    .toMutableSet()

            val newTags = builder.tags.toMutableSet()
            setMultiChoiceItems(titles, checkmarks) { _, i, isChecked ->
                val tag = titles[i].substringBefore(TAG_TITLE_DELIMITER)
                val titleSuffix = titles[i].substringAfter(TAG_TITLE_DELIMITER)
                if (isChecked) {
                    newTags.add(tag)
                    checkedTitleSuffixes.add(titleSuffix)
                } else {
                    newTags.remove(tag)
                    checkedTitleSuffixes.remove(titleSuffix)
                }
            }
            setPositiveButton(R.string.save) { _, _ ->
                root.text =
                    root.context.resources.getString(T.string.categories) +
                        "\n" +
                        checkedTitleSuffixes.fold("") { acc, s -> "$acc, $s" }.substringAfter(", ")
            }
            setNeutralButton(R.string.restore_default) { _, _ ->
                root.text =
                    context.getString(T.string.categories) +
                        "\n" +
                        context.getString(R.string.notification_alert_title)
            }
            setNegativeButton(R.string.cancel) { _, _ -> }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun TextView.setupSingleChoiceText(
        resValues: Int,
        resNames: Int,
        startingValue: Int,
        alertTitleRes: Int,
    ): TextView {
        val values = resources.getStringArray(resValues).map { Integer.parseInt(it) }
        val names = resources.getStringArray(resNames)
        val startingIndex = values.indexOf(startingValue)
        text = resources.getString(alertTitleRes) + "\n${names[startingIndex]}"

        setOnClickListener {
            showDialog(context) {
                setTitle(alertTitleRes)
                setSingleChoiceItems(names, startingIndex) { d, i ->
                    text = resources.getString(alertTitleRes) + "\n${names[i]}"
                    d.dismiss()
                }
            }
        }
        return this
    }

    private fun showDialog(context: Context, onBuilder: AlertDialog.Builder.() -> Unit) =
        AlertDialog.Builder(context, R.style.Theme_SystemUI_Dialog_Alert)
            .apply { onBuilder() }
            .create()
            .also { SystemUIDialog.applyFlags(it) }
            .show()

    private fun getDisabledAlpha(context: Context): Float {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.disabledAlpha))
        val alpha = ta.getFloat(0, 0f)
        ta.recycle()
        return alpha
    }
}
