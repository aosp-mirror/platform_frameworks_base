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

import android.app.Dialog
import android.app.INotificationManager
import android.app.NotificationChannel
import android.app.NotificationChannel.DEFAULT_CHANNEL_ID
import android.app.NotificationChannelGroup
import android.app.NotificationManager.IMPORTANCE_NONE
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting

import com.android.systemui.R

import javax.inject.Inject
import javax.inject.Singleton

const val TAG = "ChannelDialogController"

/**
 * ChannelEditorDialogController is the controller for the dialog half-shelf
 * that allows users to quickly turn off channels. It is launched from the NotificationInfo
 * guts view and displays controls for toggling app notifications as well as up to 4 channels
 * from that app like so:
 *
 *   APP TOGGLE                                                 <on/off>
 *   - Channel from which we launched                           <on/off>
 *   -                                                          <on/off>
 *   - the next 3 channels sorted alphabetically for that app   <on/off>
 *   -                                                          <on/off>
 */
@Singleton
class ChannelEditorDialogController @Inject constructor(
    c: Context,
    private val noMan: INotificationManager
) {
    val context: Context = c.applicationContext

    lateinit var dialog: Dialog

    private var appIcon: Drawable? = null
    private var appUid: Int? = null
    private var packageName: String? = null
    private var appName: String? = null
    private var onSettingsClickListener: NotificationInfo.OnSettingsClickListener? = null

    // Caller should set this if they care about when we dismiss
    var onFinishListener: OnChannelEditorDialogFinishedListener? = null

    // Channels handed to us from NotificationInfo
    @VisibleForTesting
    internal val providedChannels = mutableListOf<NotificationChannel>()

    // Map from NotificationChannel to importance
    private val edits = mutableMapOf<NotificationChannel, Int>()
    var appNotificationsEnabled = true

    // Keep a mapping of NotificationChannel.getGroup() to the actual group name for display
    @VisibleForTesting
    internal val groupNameLookup = hashMapOf<String, CharSequence>()
    private val channelGroupList = mutableListOf<NotificationChannelGroup>()

    /**
     * Give the controller all of the information it needs to present the dialog
     * for a given app. Does a bunch of querying of NoMan, but won't present anything yet
     */
    fun prepareDialogForApp(
        appName: String,
        packageName: String,
        uid: Int,
        channels: Set<NotificationChannel>,
        appIcon: Drawable,
        onSettingsClickListener: NotificationInfo.OnSettingsClickListener?
    ) {
        this.appName = appName
        this.packageName = packageName
        this.appUid = uid
        this.appIcon = appIcon
        this.appNotificationsEnabled = checkAreAppNotificationsOn()
        this.onSettingsClickListener = onSettingsClickListener

        channelGroupList.clear()
        channelGroupList.addAll(fetchNotificationChannelGroups())
        buildGroupNameLookup()
        padToFourChannels(channels)
    }

    private fun buildGroupNameLookup() {
        channelGroupList.forEach { group ->
            if (group.id != null) {
                groupNameLookup[group.id] = group.name
            }
        }
    }

    private fun padToFourChannels(channels: Set<NotificationChannel>) {
        providedChannels.clear()
        // First, add all of the given channels
        providedChannels.addAll(channels.asSequence().take(4))

        // Then pad to 4 if we haven't been given that many
        providedChannels.addAll(getDisplayableChannels(channelGroupList.asSequence())
                .filterNot { providedChannels.contains(it) }
                .distinct()
                .take(4 - providedChannels.size))

        // If we only got one channel and it has the default miscellaneous tag, then we actually
        // are looking at an app with a targetSdk <= O, and it doesn't make much sense to show the
        // channel
        if (providedChannels.size == 1 && DEFAULT_CHANNEL_ID == providedChannels[0].id) {
            providedChannels.clear()
        }
    }

    private fun getDisplayableChannels(
        groupList: Sequence<NotificationChannelGroup>
    ): Sequence<NotificationChannel> {

        val channels = groupList
                .flatMap { group ->
                    group.channels.asSequence().filterNot { channel ->
                        channel.isImportanceLockedByOEM
                                || channel.importance == IMPORTANCE_NONE
                                || channel.isImportanceLockedByCriticalDeviceFunction
                    }
                }

        // TODO: sort these by avgSentWeekly, but for now let's just do alphabetical (why not)
        return channels.sortedWith(compareBy { it.name?.toString() ?: it.id })
    }

    fun show() {
        initDialog()
        dialog.show()
    }

    /**
     * Close the dialog without saving. For external callers
     */
    fun close() {
        done()
    }

    private fun done() {
        resetState()
        dialog.dismiss()
        onFinishListener?.onChannelEditorDialogFinished()
    }

    private fun resetState() {
        appIcon = null
        appUid = null
        packageName = null
        appName = null

        edits.clear()
        providedChannels.clear()
        groupNameLookup.clear()
    }

    fun groupNameForId(groupId: String?): CharSequence {
        return groupNameLookup[groupId] ?: ""
    }

    fun proposeEditForChannel(channel: NotificationChannel, edit: Int) {
        if (channel.importance == edit) {
            edits.remove(channel)
        } else {
            edits[channel] = edit
        }
    }

    @Suppress("unchecked_cast")
    private fun fetchNotificationChannelGroups(): List<NotificationChannelGroup> {
        return try {
            noMan.getNotificationChannelGroupsForPackage(packageName!!, appUid!!, false)
                    .list as? List<NotificationChannelGroup> ?: listOf()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channel groups", e)
            listOf()
        }
    }

    private fun checkAreAppNotificationsOn(): Boolean {
        return try {
            noMan.areNotificationsEnabledForPackage(packageName!!, appUid!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling NoMan", e)
            false
        }
    }

    private fun applyAppNotificationsOn(b: Boolean) {
        try {
            noMan.setNotificationsEnabledForPackage(packageName!!, appUid!!, b)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling NoMan", e)
        }
    }

    private fun setChannelImportance(channel: NotificationChannel, importance: Int) {
        try {
            channel.importance = importance
            noMan.updateNotificationChannelForPackage(packageName!!, appUid!!, channel)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to update notification importance", e)
        }
    }

    @VisibleForTesting
    fun apply() {
        for ((channel, importance) in edits) {
            if (channel.importance != importance) {
                setChannelImportance(channel, importance)
            }
        }

        if (appNotificationsEnabled != checkAreAppNotificationsOn()) {
            applyAppNotificationsOn(appNotificationsEnabled)
        }
    }

    @VisibleForTesting
    fun launchSettings(sender: View) {
        onSettingsClickListener?.onClick(sender, null, appUid!!)
    }

    private fun initDialog() {
        dialog = Dialog(context)

        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        // Prevent a11y readers from reading the first element in the dialog twice
        dialog.setTitle("\u00A0")
        dialog.apply {
            setContentView(R.layout.notif_half_shelf)
            setCanceledOnTouchOutside(true)
            findViewById<ChannelEditorListView>(R.id.half_shelf_container).apply {
                controller = this@ChannelEditorDialogController
                appIcon = this@ChannelEditorDialogController.appIcon
                appName = this@ChannelEditorDialogController.appName
                channels = providedChannels
            }

            findViewById<TextView>(R.id.done_button)?.setOnClickListener {
                apply()
                done()
            }

            findViewById<TextView>(R.id.see_more_button)?.setOnClickListener {
                launchSettings(it)
                done()
            }

            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(wmFlags)
                setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL)
                setWindowAnimations(com.android.internal.R.style.Animation_InputMethod)

                attributes = attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    title = ChannelEditorDialogController::class.java.simpleName
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    width = MATCH_PARENT
                    height = WRAP_CONTENT
                }
            }
        }
    }

    private val wmFlags = (WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
}

interface OnChannelEditorDialogFinishedListener {
    fun onChannelEditorDialogFinished()
}
