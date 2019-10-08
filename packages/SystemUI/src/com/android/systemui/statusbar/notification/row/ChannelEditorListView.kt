/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row

import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_NONE
import android.app.NotificationManager.IMPORTANCE_UNSPECIFIED
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.Transition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

import com.android.systemui.R

/**
 * Half-shelf for notification channel controls
 */
class ChannelEditorListView(c: Context, attrs: AttributeSet) : LinearLayout(c, attrs) {
    lateinit var controller: ChannelEditorDialogController
    var appIcon: Drawable? = null
    var appName: String? = null
    var channels = mutableListOf<NotificationChannel>()
        set(newValue) {
            field = newValue
            updateRows()
        }

    // The first row is for the entire app
    private lateinit var appControlRow: AppControlView

    override fun onFinishInflate() {
        super.onFinishInflate()

        appControlRow = findViewById(R.id.app_control)
    }

    private fun updateRows() {
        val enabled = controller.appNotificationsEnabled

        val transition = AutoTransition()
        transition.duration = 200
        transition.addListener(object : Transition.TransitionListener {
            override fun onTransitionEnd(p0: Transition?) {
                notifySubtreeAccessibilityStateChangedIfNeeded()
            }

            override fun onTransitionResume(p0: Transition?) {
            }

            override fun onTransitionPause(p0: Transition?) {
            }

            override fun onTransitionCancel(p0: Transition?) {
            }

            override fun onTransitionStart(p0: Transition?) {
            }
        })
        TransitionManager.beginDelayedTransition(this, transition)

        // Remove any rows
        val n = childCount
        for (i in n.downTo(0)) {
            val child = getChildAt(i)
            if (child is ChannelRow) {
                removeView(child)
            }
        }

        updateAppControlRow(enabled)

        if (enabled) {
            val inflater = LayoutInflater.from(context)
            for (channel in channels) {
                addChannelRow(channel, inflater)
            }
        }
    }

    private fun addChannelRow(channel: NotificationChannel, inflater: LayoutInflater) {
        val row = inflater.inflate(R.layout.notif_half_shelf_row, null) as ChannelRow
        row.controller = controller
        row.channel = channel
        addView(row)
    }

    private fun updateAppControlRow(enabled: Boolean) {
        appControlRow.iconView.setImageDrawable(appIcon)
        appControlRow.channelName.text = context.resources
                .getString(R.string.notification_channel_dialog_title, appName)
        appControlRow.switch.isChecked = enabled
        appControlRow.switch.setOnCheckedChangeListener { _, b ->
            controller.appNotificationsEnabled = b
            updateRows()
        }
    }
}

class AppControlView(c: Context, attrs: AttributeSet) : LinearLayout(c, attrs) {
    lateinit var iconView: ImageView
    lateinit var channelName: TextView
    lateinit var switch: Switch

    override fun onFinishInflate() {
        iconView = findViewById(R.id.icon)
        channelName = findViewById(R.id.app_name)
        switch = findViewById(R.id.toggle)
    }
}

class ChannelRow(c: Context, attrs: AttributeSet) : LinearLayout(c, attrs) {

    lateinit var controller: ChannelEditorDialogController
    private lateinit var channelName: TextView
    private lateinit var channelDescription: TextView
    private lateinit var switch: Switch
    var gentle = false

    var channel: NotificationChannel? = null
        set(newValue) {
            field = newValue
            updateImportance()
            updateViews()
        }

    override fun onFinishInflate() {
        channelName = findViewById(R.id.channel_name)
        channelDescription = findViewById(R.id.channel_description)
        switch = findViewById(R.id.toggle)
        switch.setOnCheckedChangeListener { _, b ->
            channel?.let {
                controller.proposeEditForChannel(it, if (b) it.importance else IMPORTANCE_NONE)
            }
        }
    }

    private fun updateViews() {
        val nc = channel ?: return

        channelName.text = nc.name ?: ""

        nc.group?.let { groupId ->
            channelDescription.text = controller.groupNameForId(groupId)
        }

        if (nc.group == null || TextUtils.isEmpty(channelDescription.text)) {
            channelDescription.visibility = View.GONE
        } else {
            channelDescription.visibility = View.VISIBLE
        }

        switch.isChecked = nc.importance != IMPORTANCE_NONE
    }

    private fun updateImportance() {
        val importance = channel?.importance ?: 0
        gentle = importance != IMPORTANCE_UNSPECIFIED && importance < IMPORTANCE_DEFAULT
    }
}
