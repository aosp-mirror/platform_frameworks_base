package com.android.systemui.sensorprivacy

import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.android.internal.widget.DialogTitle
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog

class SensorUseDialog(
    context: Context,
    val sensor: Int,
    val clickListener: DialogInterface.OnClickListener,
    val dismissListener: DialogInterface.OnDismissListener
) : SystemUIDialog(context) {

    // TODO move to onCreate (b/200815309)
    init {
        window!!.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window!!.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)

        val layoutInflater = LayoutInflater.from(context)
        val customTitleView = layoutInflater.inflate(R.layout.sensor_use_started_title, null)
        customTitleView.requireViewById<DialogTitle>(R.id.sensor_use_started_title_message)
                .setText(when (sensor) {
                    SensorUseStartedActivity.MICROPHONE ->
                        R.string.sensor_privacy_start_use_mic_dialog_title
                    SensorUseStartedActivity.CAMERA ->
                        R.string.sensor_privacy_start_use_camera_dialog_title
                    SensorUseStartedActivity.ALL_SENSORS ->
                        R.string.sensor_privacy_start_use_mic_camera_dialog_title
                    else -> Resources.ID_NULL
                })
        customTitleView.requireViewById<ImageView>(R.id.sensor_use_microphone_icon).visibility =
                if (sensor == SensorUseStartedActivity.MICROPHONE ||
                        sensor == SensorUseStartedActivity.ALL_SENSORS) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        customTitleView.requireViewById<ImageView>(R.id.sensor_use_camera_icon).visibility =
                if (sensor == SensorUseStartedActivity.CAMERA ||
                        sensor == SensorUseStartedActivity.ALL_SENSORS) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

        setCustomTitle(customTitleView)
        setMessage(Html.fromHtml(context.getString(when (sensor) {
            SensorUseStartedActivity.MICROPHONE ->
                R.string.sensor_privacy_start_use_mic_dialog_content
            SensorUseStartedActivity.CAMERA ->
                R.string.sensor_privacy_start_use_camera_dialog_content
            SensorUseStartedActivity.ALL_SENSORS ->
                R.string.sensor_privacy_start_use_mic_camera_dialog_content
            else -> Resources.ID_NULL
        }), 0))

        setButton(BUTTON_POSITIVE,
                context.getString(com.android.internal.R.string
                        .sensor_privacy_start_use_dialog_turn_on_button), clickListener)
        setButton(BUTTON_NEGATIVE,
                context.getString(com.android.internal.R.string
                        .cancel), clickListener)

        setOnDismissListener(dismissListener)

        setCancelable(false)
    }
}
