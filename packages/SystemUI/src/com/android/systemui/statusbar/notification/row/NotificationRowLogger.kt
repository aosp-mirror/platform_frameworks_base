/*
 * Copyright (c) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar.notification.row

import android.view.ViewGroup
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class NotificationRowLogger
@Inject
constructor(
    @NotificationLog private val buffer: LogBuffer,
    @NotificationRenderLog private val notificationRenderBuffer: LogBuffer,
) {
    fun logKeepInParentChildDetached(child: NotificationEntry, oldParent: NotificationEntry?) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = child.logKey
                str2 = oldParent.logKey
            },
            { "Detach child $str1 kept in parent $str2" },
        )
    }

    fun logSkipAttachingKeepInParentChild(child: NotificationEntry, newParent: NotificationEntry?) {
        buffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = child.logKey
                str2 = newParent.logKey
            },
            { "Skipping to attach $str1 to $str2, because it still flagged to keep in parent" },
        )
    }

    fun logRemoveTransientFromContainer(
        childEntry: NotificationEntry,
        containerEntry: NotificationEntry,
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = childEntry.logKey
                str2 = containerEntry.logKey
            },
            { "RemoveTransientRow from ChildrenContainer: childKey: $str1 -- containerKey: $str2" },
        )
    }

    fun logRemoveTransientFromNssl(childEntry: NotificationEntry) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = childEntry.logKey },
            { "RemoveTransientRow from Nssl: childKey: $str1" },
        )
    }

    fun logRemoveTransientFromViewGroup(childEntry: NotificationEntry, containerView: ViewGroup) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = childEntry.logKey
                str2 = containerView.toString()
            },
            { "RemoveTransientRow from other ViewGroup: childKey: $str1 -- ViewGroup: $str2" },
        )
    }

    fun logAddTransientRow(
        childEntry: NotificationEntry,
        containerEntry: NotificationEntry,
        index: Int,
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.ERROR,
            {
                str1 = childEntry.logKey
                str2 = containerEntry.logKey
                int1 = index
            },
            { "addTransientRow to row: childKey: $str1 -- containerKey: $str2 -- index: $int1" },
        )
    }

    fun logRemoveTransientRow(childEntry: NotificationEntry, containerEntry: NotificationEntry) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.ERROR,
            {
                str1 = childEntry.logKey
                str2 = containerEntry.logKey
            },
            { "removeTransientRow from row: childKey: $str1 -- containerKey: $str2" },
        )
    }

    fun logResetAllContentAlphas(entry: NotificationEntry) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = entry.logKey },
            { "resetAllContentAlphas: $str1" },
        )
    }

    fun logSkipResetAllContentAlphas(entry: NotificationEntry) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = entry.logKey },
            { "Skip resetAllContentAlphas: $str1" },
        )
    }

    fun logStartAppearAnimation(entry: NotificationEntry, isAppear: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                bool1 = isAppear
            },
            { "startAppearAnimation childKey: $str1 isAppear:$bool1" },
        )
    }

    fun logCancelAppearDrawing(entry: NotificationEntry, wasDrawing: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = entry.logKey
                bool1 = wasDrawing
            },
            { "cancelAppearDrawing childKey: $str1 wasDrawing:$bool1" },
        )
    }

    fun logAppearAnimationStarted(entry: NotificationEntry, isAppear: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                bool1 = isAppear
            },
            { "onAppearAnimationStarted childKey: $str1 isAppear:$bool1" },
        )
    }

    fun logAppearAnimationSkipped(entry: NotificationEntry, isAppear: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = entry.logKey
                bool1 = isAppear
            },
            { "Skipped an appear animation childKey: $str1 isAppear:$bool1" },
        )
    }

    fun logAppearAnimationFinished(
        entry: NotificationEntry,
        isAppear: Boolean,
        cancelled: Boolean,
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = entry.logKey
                bool1 = isAppear
                bool2 = cancelled
            },
            { "onAppearAnimationFinished childKey: $str1 isAppear:$bool1 cancelled:$bool2" },
        )
    }
}

private const val TAG = "NotifRow"
