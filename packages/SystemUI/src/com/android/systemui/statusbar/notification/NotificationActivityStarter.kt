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
package com.android.systemui.statusbar.notification

import android.content.Intent
import android.provider.Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS
import android.provider.Settings.ACTION_NOTIFICATION_HISTORY
import android.provider.Settings.ACTION_NOTIFICATION_SETTINGS
import android.provider.Settings.ACTION_ZEN_MODE_SETTINGS
import android.provider.Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID
import android.view.View
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/**
 * Component responsible for handling actions on a notification which cause activites to start.
 * (e.g. clicking on a notification, tapping on the settings icon in the notification guts)
 */
interface NotificationActivityStarter {

    /** Called when the user clicks on the notification bubble icon. */
    fun onNotificationBubbleIconClicked(entry: NotificationEntry)

    /** Called when the user clicks on the surface of a notification. */
    fun onNotificationClicked(entry: NotificationEntry, row: ExpandableNotificationRow)

    /** Called when the user clicks on a button in the notification guts which fires an intent. */
    fun startNotificationGutsIntent(intent: Intent, appUid: Int, row: ExpandableNotificationRow)

    /**
     * Called when the user clicks "Manage" or "History" in the Shade. Prefer using
     * [startSettingsIntent] instead.
     */
    fun startHistoryIntent(view: View?, showHistory: Boolean)

    /**
     * Called to open a settings intent from a launchable view (such as the "Manage" or "History"
     * button in the shade, or the "No notifications" text).
     *
     * @param view the view to perform the launch animation from (must extend [LaunchableView])
     * @param intentInfo information about the (settings) intent to be launched
     */
    fun startSettingsIntent(view: View, intentInfo: SettingsIntent)

    /** Called when the user succeed to drop notification to proper target view. */
    fun onDragSuccess(entry: NotificationEntry)

    val isCollapsingToShowActivityOverLockscreen: Boolean
        get() = false

    /**
     * Information about a settings intent to be launched.
     *
     * If the [targetIntent] is T and [backStack] is [A, B, C], the stack will look like
     * [A, B, C, T].
     */
    data class SettingsIntent(
        var targetIntent: Intent,
        var backStack: List<Intent> = emptyList(),
        var cujType: Int? = null,
    ) {
        // Utility factory methods for known intents
        companion object {
            fun forNotificationSettings(cujType: Int? = null) =
                SettingsIntent(
                    targetIntent = Intent(ACTION_NOTIFICATION_SETTINGS),
                    cujType = cujType,
                )

            fun forNotificationHistory(cujType: Int? = null) =
                SettingsIntent(
                    targetIntent = Intent(ACTION_NOTIFICATION_HISTORY),
                    backStack = listOf(Intent(ACTION_NOTIFICATION_SETTINGS)),
                    cujType = cujType,
                )

            fun forModesSettings(cujType: Int? = null) =
                SettingsIntent(targetIntent = Intent(ACTION_ZEN_MODE_SETTINGS), cujType = cujType)

            fun forModeSettings(modeId: String, cujType: Int? = null) =
                SettingsIntent(
                    targetIntent =
                        Intent(ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
                            .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, modeId),
                    backStack = listOf(Intent(ACTION_ZEN_MODE_SETTINGS)),
                    cujType = cujType,
                )
        }
    }
}
