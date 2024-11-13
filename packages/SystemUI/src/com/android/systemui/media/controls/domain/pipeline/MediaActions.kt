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

package com.android.systemui.media.controls.domain.pipeline

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.media.utils.MediaConstants
import com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl.Companion.MAX_COMPACT_ACTIONS
import com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl.Companion.MAX_NOTIFICATION_ACTIONS
import com.android.systemui.media.controls.shared.MediaControlDrawables
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaNotificationAction
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationMediaManager.isConnectingState
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.util.kotlin.logI

private const val TAG = "MediaActions"

/**
 * Generates action button info for this media session based on the PlaybackState
 *
 * @param packageName Package name for the media app
 * @param controller MediaController for the current session
 * @return a Pair consisting of a list of media actions, and a list of ints representing which of
 *   those actions should be shown in the compact player
 */
fun createActionsFromState(
    context: Context,
    packageName: String,
    controller: MediaController,
): MediaButton? {
    val state = controller.playbackState ?: return null
    // First, check for standard actions
    val playOrPause =
        if (isConnectingState(state.state)) {
            // Spinner needs to be animating to render anything. Start it here.
            val drawable =
                context.getDrawable(com.android.internal.R.drawable.progress_small_material)
            (drawable as Animatable).start()
            MediaAction(
                drawable,
                null, // no action to perform when clicked
                context.getString(R.string.controls_media_button_connecting),
                context.getDrawable(R.drawable.ic_media_connecting_container),
                // Specify a rebind id to prevent the spinner from restarting on later binds.
                com.android.internal.R.drawable.progress_small_material
            )
        } else if (isPlayingState(state.state)) {
            getStandardAction(context, controller, state.actions, PlaybackState.ACTION_PAUSE)
        } else {
            getStandardAction(context, controller, state.actions, PlaybackState.ACTION_PLAY)
        }
    val prevButton =
        getStandardAction(context, controller, state.actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
    val nextButton =
        getStandardAction(context, controller, state.actions, PlaybackState.ACTION_SKIP_TO_NEXT)

    // Then, create a way to build any custom actions that will be needed
    val customActions =
        state.customActions
            .asSequence()
            .filterNotNull()
            .map { getCustomAction(context, packageName, controller, it) }
            .iterator()
    fun nextCustomAction() = if (customActions.hasNext()) customActions.next() else null

    // Finally, assign the remaining button slots: play/pause A B C D
    // A = previous, else custom action (if not reserved)
    // B = next, else custom action (if not reserved)
    // C and D are always custom actions
    val reservePrev =
        controller.extras?.getBoolean(
            MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV
        ) == true
    val reserveNext =
        controller.extras?.getBoolean(
            MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT
        ) == true

    val prevOrCustom =
        if (prevButton != null) {
            prevButton
        } else if (!reservePrev) {
            nextCustomAction()
        } else {
            null
        }

    val nextOrCustom =
        if (nextButton != null) {
            nextButton
        } else if (!reserveNext) {
            nextCustomAction()
        } else {
            null
        }

    return MediaButton(
        playOrPause,
        nextOrCustom,
        prevOrCustom,
        nextCustomAction(),
        nextCustomAction(),
        reserveNext,
        reservePrev
    )
}

/**
 * Create a [MediaAction] for a given action and media session
 *
 * @param controller MediaController for the session
 * @param stateActions The actions included with the session's [PlaybackState]
 * @param action A [PlaybackState.Actions] value representing what action to generate. One of:
 *   [PlaybackState.ACTION_PLAY] [PlaybackState.ACTION_PAUSE]
 *   [PlaybackState.ACTION_SKIP_TO_PREVIOUS] [PlaybackState.ACTION_SKIP_TO_NEXT]
 * @return A [MediaAction] with correct values set, or null if the state doesn't support it
 */
private fun getStandardAction(
    context: Context,
    controller: MediaController,
    stateActions: Long,
    @PlaybackState.Actions action: Long
): MediaAction? {
    if (!includesAction(stateActions, action)) {
        return null
    }

    return when (action) {
        PlaybackState.ACTION_PLAY -> {
            MediaAction(
                context.getDrawable(R.drawable.ic_media_play),
                { controller.transportControls.play() },
                context.getString(R.string.controls_media_button_play),
                context.getDrawable(R.drawable.ic_media_play_container)
            )
        }
        PlaybackState.ACTION_PAUSE -> {
            MediaAction(
                context.getDrawable(R.drawable.ic_media_pause),
                { controller.transportControls.pause() },
                context.getString(R.string.controls_media_button_pause),
                context.getDrawable(R.drawable.ic_media_pause_container)
            )
        }
        PlaybackState.ACTION_SKIP_TO_PREVIOUS -> {
            MediaAction(
                MediaControlDrawables.getPrevIcon(context),
                { controller.transportControls.skipToPrevious() },
                context.getString(R.string.controls_media_button_prev),
                null
            )
        }
        PlaybackState.ACTION_SKIP_TO_NEXT -> {
            MediaAction(
                MediaControlDrawables.getNextIcon(context),
                { controller.transportControls.skipToNext() },
                context.getString(R.string.controls_media_button_next),
                null
            )
        }
        else -> null
    }
}

/** Get a [MediaAction] representing a [PlaybackState.CustomAction] */
private fun getCustomAction(
    context: Context,
    packageName: String,
    controller: MediaController,
    customAction: PlaybackState.CustomAction
): MediaAction {
    return MediaAction(
        Icon.createWithResource(packageName, customAction.icon).loadDrawable(context),
        { controller.transportControls.sendCustomAction(customAction, customAction.extras) },
        customAction.name,
        null
    )
}

/** Check whether the actions from a [PlaybackState] include a specific action */
private fun includesAction(stateActions: Long, @PlaybackState.Actions action: Long): Boolean {
    if (
        (action == PlaybackState.ACTION_PLAY || action == PlaybackState.ACTION_PAUSE) &&
            (stateActions and PlaybackState.ACTION_PLAY_PAUSE > 0L)
    ) {
        return true
    }
    return (stateActions and action != 0L)
}

/** Generate action buttons based on notification actions */
fun createActionsFromNotification(
    context: Context,
    sbn: StatusBarNotification
): Pair<List<MediaNotificationAction>, List<Int>> {
    val notif = sbn.notification
    val actionIcons: MutableList<MediaNotificationAction> = ArrayList()
    val actions = notif.actions
    var actionsToShowCollapsed =
        notif.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)?.toMutableList()
            ?: mutableListOf()
    if (actionsToShowCollapsed.size > MAX_COMPACT_ACTIONS) {
        Log.e(
            TAG,
            "Too many compact actions for ${sbn.key}, limiting to first $MAX_COMPACT_ACTIONS"
        )
        actionsToShowCollapsed = actionsToShowCollapsed.subList(0, MAX_COMPACT_ACTIONS)
    }

    actions?.let {
        if (it.size > MAX_NOTIFICATION_ACTIONS) {
            Log.w(
                TAG,
                "Too many notification actions for ${sbn.key}, " +
                    "limiting to first $MAX_NOTIFICATION_ACTIONS"
            )
        }

        for ((index, action) in it.take(MAX_NOTIFICATION_ACTIONS).withIndex()) {
            if (action.getIcon() == null) {
                logI(TAG) { "No icon for action $index ${action.title}" }
                actionsToShowCollapsed.remove(index)
                continue
            }

            val themeText =
                com.android.settingslib.Utils.getColorAttr(
                        context,
                        com.android.internal.R.attr.textColorPrimary
                    )
                    .defaultColor

            val mediaActionIcon =
                when (action.getIcon().type) {
                        Icon.TYPE_RESOURCE ->
                            Icon.createWithResource(sbn.packageName, action.getIcon()!!.getResId())
                        else -> action.getIcon()
                    }
                    .setTint(themeText)
                    .loadDrawable(context)

            val mediaAction =
                MediaNotificationAction(
                    action.isAuthenticationRequired,
                    action.actionIntent,
                    mediaActionIcon,
                    action.title
                )
            actionIcons.add(mediaAction)
        }
    }
    return Pair(actionIcons, actionsToShowCollapsed)
}

/**
 * Converts [MediaNotificationAction] list into [MediaAction] list
 *
 * @param actions list of [MediaNotificationAction]
 * @param activityStarter starter for activities
 * @return list of [MediaAction]
 */
fun getNotificationActions(
    actions: List<MediaNotificationAction>,
    activityStarter: ActivityStarter
): List<MediaAction> {
    return actions.map { action ->
        val runnable =
            action.actionIntent?.let { actionIntent ->
                Runnable {
                    when {
                        actionIntent.isActivity ->
                            activityStarter.startPendingIntentDismissingKeyguard(
                                action.actionIntent
                            )
                        action.isAuthenticationRequired ->
                            activityStarter.dismissKeyguardThenExecute(
                                { sendPendingIntent(action.actionIntent) },
                                {},
                                true
                            )
                        else -> sendPendingIntent(actionIntent)
                    }
                }
            }
        MediaAction(action.icon, runnable, action.contentDescription, background = null)
    }
}

private fun sendPendingIntent(intent: PendingIntent): Boolean {
    return try {
        intent.send(
            BroadcastOptions.makeBasic()
                .apply {
                    setInteractive(true)
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                .toBundle()
        )
        true
    } catch (e: PendingIntent.CanceledException) {
        Log.d(TAG, "Intent canceled", e)
        false
    }
}
