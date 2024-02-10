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

package com.android.systemui.media.dialog

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.PowerExemptionManager
import android.view.View
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.media.nearby.NearbyMediaDevicesManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import javax.inject.Inject

/**
 * Factory to create [MediaOutputBroadcastDialog] objects.
 */
class MediaOutputBroadcastDialogFactory @Inject constructor(
    private val context: Context,
    private val mediaSessionManager: MediaSessionManager,
    private val lbm: LocalBluetoothManager?,
    private val starter: ActivityStarter,
    private val broadcastSender: BroadcastSender,
    private val notifCollection: CommonNotifCollection,
    private val uiEventLogger: UiEventLogger,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val nearbyMediaDevicesManager: NearbyMediaDevicesManager,
    private val audioManager: AudioManager,
    private val powerExemptionManager: PowerExemptionManager,
    private val keyGuardManager: KeyguardManager,
    private val featureFlags: FeatureFlags,
    private val userTracker: UserTracker
) {
    var mediaOutputBroadcastDialog: MediaOutputBroadcastDialog? = null

    /** Creates a [MediaOutputBroadcastDialog] for the given package. */
    fun create(packageName: String, aboveStatusBar: Boolean, view: View? = null) {
        // Dismiss the previous dialog, if any.
        mediaOutputBroadcastDialog?.dismiss()

        val controller = MediaOutputController(context, packageName,
                mediaSessionManager, lbm, starter, notifCollection,
                dialogTransitionAnimator, nearbyMediaDevicesManager, audioManager,
                powerExemptionManager, keyGuardManager, featureFlags, userTracker)
        val dialog =
                MediaOutputBroadcastDialog(context, aboveStatusBar, broadcastSender, controller)
        mediaOutputBroadcastDialog = dialog

        // Show the dialog.
        if (view != null) {
            dialogTransitionAnimator.showFromView(dialog, view)
        } else {
            dialog.show()
        }
    }

    /** dismiss [MediaOutputBroadcastDialog] if exist. */
    fun dismiss() {
        mediaOutputBroadcastDialog?.dismiss()
        mediaOutputBroadcastDialog = null
    }
}
