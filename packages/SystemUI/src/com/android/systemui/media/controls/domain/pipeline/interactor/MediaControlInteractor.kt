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

package com.android.systemui.media.controls.domain.pipeline.interactor

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import com.android.internal.jank.Cuj
import com.android.internal.logging.InstanceId
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.BroadcastDialogController
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.shared.model.MediaControlModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.kotlin.pairwiseBy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Encapsulates business logic for single media control. */
class MediaControlInteractor
@AssistedInject
constructor(
    @Application applicationContext: Context,
    @Assisted private val instanceId: InstanceId,
    repository: MediaFilterRepository,
    private val mediaDataProcessor: MediaDataProcessor,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
    private val broadcastDialogController: BroadcastDialogController,
) {

    val mediaControl: Flow<MediaControlModel?> =
        repository.selectedUserEntries
            .map { entries -> entries[instanceId]?.let { toMediaControlModel(it) } }
            .distinctUntilChanged()

    val isStartedPlaying: Flow<Boolean> =
        mediaControl
            .map { mediaControl ->
                mediaControl?.token?.let { token ->
                    MediaController(applicationContext, token).playbackState?.let {
                        it.state == PlaybackState.STATE_PLAYING
                    }
                }
                    ?: false
            }
            .pairwiseBy(initialValue = false) { wasPlaying, isPlaying -> !wasPlaying && isPlaying }
            .distinctUntilChanged()

    fun removeMediaControl(
        token: MediaSession.Token?,
        instanceId: InstanceId,
        delayMs: Long
    ): Boolean {
        val dismissed = mediaDataProcessor.dismissMediaData(instanceId, delayMs)
        if (!dismissed) {
            Log.w(
                TAG,
                "Manager failed to dismiss media of instanceId=$instanceId, Token uid=${token?.uid}"
            )
        }
        return dismissed
    }

    private fun toMediaControlModel(data: MediaData): MediaControlModel {
        return with(data) {
            MediaControlModel(
                uid = appUid,
                packageName = packageName,
                instanceId = instanceId,
                token = token,
                appIcon = appIcon,
                clickIntent = clickIntent,
                appName = app,
                songName = song,
                artistName = artist,
                showExplicit = isExplicit,
                artwork = artwork,
                deviceData = device,
                semanticActionButtons = semanticActions,
                notificationActionButtons = actions,
                actionsToShowInCollapsed = actionsToShowInCompact,
                isDismissible = isClearable,
                isResume = resumption,
                resumeProgress = resumeProgress,
            )
        }
    }

    fun startSettings() {
        activityStarter.startActivity(SETTINGS_INTENT, /* dismissShade= */ true)
    }

    fun startClickIntent(expandable: Expandable, clickIntent: PendingIntent) {
        if (!launchOverLockscreen(clickIntent)) {
            activityStarter.postStartActivityDismissingKeyguard(
                clickIntent,
                expandable.activityTransitionController(Cuj.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER)
            )
        }
    }

    fun startDeviceIntent(deviceIntent: PendingIntent) {
        if (deviceIntent.isActivity) {
            if (!launchOverLockscreen(deviceIntent)) {
                activityStarter.postStartActivityDismissingKeyguard(deviceIntent)
            }
        } else {
            Log.w(TAG, "Device pending intent of instanceId=$instanceId is not an activity.")
        }
    }

    private fun launchOverLockscreen(pendingIntent: PendingIntent): Boolean {
        val showOverLockscreen =
            keyguardStateController.isShowing &&
                activityIntentHelper.wouldPendingShowOverLockscreen(
                    pendingIntent,
                    lockscreenUserManager.currentUserId
                )
        if (showOverLockscreen) {
            try {
                val options = BroadcastOptions.makeBasic()
                options.isInteractive = true
                options.pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                pendingIntent.send(options.toBundle())
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "pending intent of $instanceId was canceled")
            }
            return true
        }
        return false
    }

    fun startMediaOutputDialog(expandable: Expandable, packageName: String) {
        mediaOutputDialogManager.createAndShowWithController(
            packageName,
            true,
            expandable.dialogController()
        )
    }

    fun startBroadcastDialog(expandable: Expandable, broadcastApp: String, packageName: String) {
        broadcastDialogController.createBroadcastDialogWithController(
            broadcastApp,
            packageName,
            expandable.dialogTransitionController()
        )
    }

    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj =
                DialogCuj(Cuj.CUJ_SHADE_DIALOG_OPEN, MediaOutputDialogManager.INTERACTION_JANK_TAG)
        )
    }

    companion object {
        private const val TAG = "MediaControlInteractor"
        private val SETTINGS_INTENT = Intent(Settings.ACTION_MEDIA_CONTROLS_SETTINGS)
    }
}
