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

package com.android.systemui.statusbar.notification

import android.content.Context
import android.media.MediaMetadata
import android.provider.Settings
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.tuner.TunerService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class that automatically creates heads up for important notification when bypassing the
 * lockscreen
 */
@Singleton
class BypassHeadsUpNotifier @Inject constructor(
    private val context: Context,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: StatusBarStateController,
    private val headsUpManager: HeadsUpManagerPhone,
    private val notificationLockscreenUserManager: NotificationLockscreenUserManager,
    private val mediaManager: NotificationMediaManager,
    private val entryManager: NotificationEntryManager,
    tunerService: TunerService
) : StatusBarStateController.StateListener, NotificationMediaManager.MediaListener {

    private var currentMediaEntry: NotificationEntry? = null
    private var enabled = true

    var fullyAwake = false
        set(value) {
            field = value
            if (value) {
                updateAutoHeadsUp(currentMediaEntry)
            }
        }

    init {
        statusBarStateController.addCallback(this)
        tunerService.addTunable(
                TunerService.Tunable { _, _ ->
                    enabled = Settings.Secure.getIntForUser(
                            context.contentResolver,
                            Settings.Secure.SHOW_MEDIA_WHEN_BYPASSING,
                            0 /* default */,
                            KeyguardUpdateMonitor.getCurrentUser()) != 0
                }, Settings.Secure.SHOW_MEDIA_WHEN_BYPASSING)
    }

    fun setUp() {
        mediaManager.addCallback(this)
    }

    override fun onMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
        val previous = currentMediaEntry
        var newEntry = entryManager
                .getActiveNotificationUnfiltered(mediaManager.mediaNotificationKey)
        if (!NotificationMediaManager.isPlayingState(state)) {
            newEntry = null
        }
        currentMediaEntry = newEntry
        updateAutoHeadsUp(previous)
        updateAutoHeadsUp(currentMediaEntry)
    }

    private fun updateAutoHeadsUp(entry: NotificationEntry?) {
        entry?.let {
            val autoHeadsUp = it == currentMediaEntry && canAutoHeadsUp(it)
            it.isAutoHeadsUp = autoHeadsUp
            if (autoHeadsUp) {
                headsUpManager.showNotification(it)
            }
        }
    }

    /**
     * @return {@code true} if this entry be autoHeadsUpped right now.
     */
    private fun canAutoHeadsUp(entry: NotificationEntry): Boolean {
        if (!isAutoHeadsUpAllowed()) {
            return false
        }
        if (entry.isSensitive) {
            // filter sensitive notifications
            return false
        }
        if (!notificationLockscreenUserManager.shouldShowOnKeyguard(entry)) {
            // filter notifications invisible on Keyguard
            return false
        }
        if (entryManager.getActiveNotificationUnfiltered(entry.key) != null) {
            // filter notifications not the active list currently
            return false
        }
        return true
    }

    override fun onStatePostChange() {
        updateAutoHeadsUp(currentMediaEntry)
    }

    /**
     * @return {@code true} if autoHeadsUp is possible right now.
     */
    private fun isAutoHeadsUpAllowed(): Boolean {
        if (!enabled) {
            return false
        }
        if (!bypassController.bypassEnabled) {
            return false
        }
        if (statusBarStateController.state != StatusBarState.KEYGUARD) {
            return false
        }
        if (!fullyAwake) {
            return false
        }
        return true
    }
}
