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

import android.annotation.WorkerThread
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import com.android.systemui.Flags.mediaControlsPostsOptimization
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.media.controls.shared.model.MediaData

private const val TAG = "MediaProcessingHelper"

/**
 * Compares [new] media data to [old] media data.
 *
 * @param context Context
 * @param newController media controller of the new media data.
 * @param new new media data.
 * @param old old media data.
 * @return whether new and old contain same data
 */
fun isSameMediaData(
    context: Context,
    newController: MediaController,
    new: MediaData,
    old: MediaData?,
): Boolean {
    if (old == null || !mediaControlsPostsOptimization()) return false

    return new.userId == old.userId &&
        new.app == old.app &&
        new.artist == old.artist &&
        new.song == old.song &&
        new.packageName == old.packageName &&
        new.isExplicit == old.isExplicit &&
        new.appUid == old.appUid &&
        new.notificationKey == old.notificationKey &&
        new.isPlaying == old.isPlaying &&
        new.isClearable == old.isClearable &&
        new.playbackLocation == old.playbackLocation &&
        new.device == old.device &&
        new.initialized == old.initialized &&
        new.resumption == old.resumption &&
        new.token == old.token &&
        new.resumeProgress == old.resumeProgress &&
        areClickIntentsEqual(new.clickIntent, old.clickIntent) &&
        areActionsEqual(context, newController, new, old) &&
        areIconsEqual(context, new.artwork, old.artwork) &&
        areIconsEqual(context, new.appIcon, old.appIcon)
}

/** Returns whether actions lists are equal. */
fun areCustomActionListsEqual(
    first: List<PlaybackState.CustomAction>?,
    second: List<PlaybackState.CustomAction>?,
): Boolean {
    // Same object, or both null
    if (first === second) {
        return true
    }

    // Only one null, or different number of actions
    if ((first == null || second == null) || (first.size != second.size)) {
        return false
    }

    // Compare individual actions
    first.asSequence().zip(second.asSequence()).forEach { (firstAction, secondAction) ->
        if (!areCustomActionsEqual(firstAction, secondAction)) {
            return false
        }
    }
    return true
}

private fun areCustomActionsEqual(
    firstAction: PlaybackState.CustomAction,
    secondAction: PlaybackState.CustomAction,
): Boolean {
    if (
        firstAction.action != secondAction.action ||
            firstAction.name != secondAction.name ||
            firstAction.icon != secondAction.icon
    ) {
        return false
    }

    if ((firstAction.extras == null) != (secondAction.extras == null)) {
        return false
    }
    if (firstAction.extras != null) {
        firstAction.extras.keySet().forEach { key ->
            if (firstAction.extras[key] != secondAction.extras[key]) {
                return false
            }
        }
    }
    return true
}

@WorkerThread
private fun areIconsEqual(context: Context, new: Icon?, old: Icon?): Boolean {
    if (new == old) return true
    if (new == null || old == null || new.type != old.type) return false
    return if (new.type == Icon.TYPE_BITMAP || new.type == Icon.TYPE_ADAPTIVE_BITMAP) {
        if (new.bitmap.isRecycled || old.bitmap.isRecycled) {
            Log.e(TAG, "Cannot compare recycled bitmap")
            return false
        }
        new.bitmap.sameAs(old.bitmap)
    } else {
        val newDrawable = new.loadDrawable(context)
        val oldDrawable = old.loadDrawable(context)

        return newDrawable?.toBitmap()?.sameAs(oldDrawable?.toBitmap()) ?: false
    }
}

private fun areActionsEqual(
    context: Context,
    newController: MediaController,
    new: MediaData,
    old: MediaData,
): Boolean {
    val oldState = MediaController(context, old.token!!).playbackState
    return if (
        new.semanticActions == null &&
            old.semanticActions == null &&
            new.actions.size == old.actions.size
    ) {
        var same = true
        new.actions.asSequence().zip(old.actions.asSequence()).forEach {
            if (
                it.first.actionIntent?.intent != it.second.actionIntent?.intent ||
                    it.first.icon != it.second.icon ||
                    it.first.contentDescription != it.second.contentDescription
            ) {
                same = false
                return@forEach
            }
        }
        same
    } else if (new.semanticActions != null && old.semanticActions != null) {
        oldState?.actions == newController.playbackState?.actions &&
            areCustomActionListsEqual(
                oldState?.customActions,
                newController.playbackState?.customActions,
            )
    } else {
        false
    }
}

private fun areClickIntentsEqual(newIntent: PendingIntent?, oldIntent: PendingIntent?): Boolean {
    return newIntent == oldIntent
}
