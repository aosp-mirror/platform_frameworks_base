/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.inflation

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.provider.Settings.Secure.SHOW_NOTIFICATION_SNOOZE
import com.android.server.notification.Flags.screenshareNotificationHiding
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

/**
 * A class which provides an adjustment object to the preparation coordinator which is uses
 * to ensure that notifications are reinflated when ranking-derived information changes.
 */
@SysUISingleton
class NotifUiAdjustmentProvider @Inject constructor(
    @Main private val handler: Handler,
    private val secureSettings: SecureSettings,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val sensitiveNotifProtectionController: SensitiveNotificationProtectionController,
    private val sectionStyleProvider: SectionStyleProvider,
    private val userTracker: UserTracker,
    private val groupMembershipManager: GroupMembershipManager,
) {
    private val dirtyListeners = ListenerSet<Runnable>()
    private var isSnoozeSettingsEnabled = false

    /**
     *  Update the snooze enabled value on user switch
     */
    private val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            updateSnoozeEnabled()
        }
    }

    init {
        userTracker.addCallback(userTrackerCallback, HandlerExecutor(handler))
    }

    fun addDirtyListener(listener: Runnable) {
        if (dirtyListeners.isEmpty()) {
            lockscreenUserManager.addNotificationStateChangedListener(notifStateChangedListener)
            if (screenshareNotificationHiding()) {
                sensitiveNotifProtectionController.registerSensitiveStateListener(
                    onSensitiveStateChangedListener
                )
            }
            updateSnoozeEnabled()
            secureSettings.registerContentObserverForUser(
                SHOW_NOTIFICATION_SNOOZE,
                settingsObserver,
                UserHandle.USER_ALL
            )
        }
        dirtyListeners.addIfAbsent(listener)
    }

    fun removeDirtyListener(listener: Runnable) {
        dirtyListeners.remove(listener)
        if (dirtyListeners.isEmpty()) {
            lockscreenUserManager.removeNotificationStateChangedListener(notifStateChangedListener)
            if (screenshareNotificationHiding()) {
                sensitiveNotifProtectionController.unregisterSensitiveStateListener(
                    onSensitiveStateChangedListener
                )
            }
            secureSettings.unregisterContentObserver(settingsObserver)
        }
    }

    private val notifStateChangedListener =
        NotificationLockscreenUserManager.NotificationStateChangedListener {
            dirtyListeners.forEach(Runnable::run)
        }

    private val onSensitiveStateChangedListener = Runnable { dirtyListeners.forEach(Runnable::run) }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateSnoozeEnabled()
            dirtyListeners.forEach(Runnable::run)
        }
    }

    private fun updateSnoozeEnabled() {
        isSnoozeSettingsEnabled =
            secureSettings.getIntForUser(SHOW_NOTIFICATION_SNOOZE, 0, UserHandle.USER_CURRENT) == 1
    }

    private fun isEntryMinimized(entry: NotificationEntry): Boolean {
        val section = entry.section ?: error("Entry must have a section to determine if minimized")
        val parent = entry.parent ?: error("Entry must have a parent to determine if minimized")
        val isMinimizedSection = sectionStyleProvider.isMinimizedSection(section)
        val isTopLevelEntry = parent == GroupEntry.ROOT_ENTRY
        val isGroupSummary = parent.summary == entry
        return isMinimizedSection && (isTopLevelEntry || isGroupSummary)
    }

    /**
     * Returns a adjustment object for the given entry.  This can be compared to a previous instance
     * from the same notification using [NotifUiAdjustment.needReinflate] to determine if it should
     * be reinflated.
     */
    fun calculateAdjustment(entry: NotificationEntry) = NotifUiAdjustment(
        key = entry.key,
        smartActions = entry.ranking.smartActions,
        smartReplies = entry.ranking.smartReplies,
        isConversation = entry.ranking.isConversation,
        isSnoozeEnabled = isSnoozeSettingsEnabled && !entry.isCanceled,
        isMinimized = isEntryMinimized(entry),
        needsRedaction =
            lockscreenUserManager.needsRedaction(entry) ||
                (screenshareNotificationHiding() &&
                    sensitiveNotifProtectionController.shouldProtectNotification(entry)),
        isChildInGroup = entry.sbn.isAppOrSystemGroupChild,
        isGroupSummary = entry.sbn.isAppOrSystemGroupSummary,
    )
}
