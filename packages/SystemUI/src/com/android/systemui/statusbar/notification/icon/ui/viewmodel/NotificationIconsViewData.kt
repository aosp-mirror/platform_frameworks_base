/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.graphics.drawable.Icon
import androidx.collection.ArrayMap
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.util.kotlin.mapValuesNotNullTo

/** Encapsulates the collection of notification icons present on the device. */
data class NotificationIconsViewData(
    /** Icons that are visible in the container. */
    val visibleKeys: List<NotificationIconInfo> = emptyList(),
    /** Keys of icons that are "behind" the overflow dot. */
    val collapsedKeys: Set<String> = emptySet(),
    /** Whether the overflow dot should be shown regardless if [collapsedKeys] is empty. */
    val forceShowDot: Boolean = false,
) {
    /** The difference between two [NotificationIconsViewData]s. */
    data class Diff(
        /** Icons added in the newer dataset. */
        val added: List<NotificationIconInfo> = emptyList(),
        /** Icons removed from the older dataset. */
        val removed: List<String> = emptyList(),
        /**
         * Groups whose icon was replaced with a single new notification icon. The key of the [Map]
         * is the notification group key, and the value is the new icon.
         *
         * Specifically, this models a difference where the older dataset had notification groups
         * with a single icon in the set, and the newer dataset has a single, different icon for the
         * same group. A view binder can use this information for special animations for this
         * specific change.
         */
        val groupReplacements: Map<String, NotificationIconInfo> = emptyMap(),
    )

    companion object {
        /**
         * Returns an [NotificationIconsViewData.Diff] calculated from a [new] and [previous][prev]
         * [NotificationIconsViewData] state.
         */
        fun computeDifference(
            new: NotificationIconsViewData,
            prev: NotificationIconsViewData
        ): Diff {
            val added: List<NotificationIconInfo> =
                new.visibleKeys.filter {
                    it.notifKey !in prev.visibleKeys.asSequence().map { it.notifKey }
                }
            val removed: List<NotificationIconInfo> =
                prev.visibleKeys.filter {
                    it.notifKey !in new.visibleKeys.asSequence().map { it.notifKey }
                }
            val groupsToShow: Set<IconGroupInfo> =
                new.visibleKeys.asSequence().map { it.groupInfo }.toSet()
            val replacements: ArrayMap<String, NotificationIconInfo> =
                removed
                    .asSequence()
                    .filter { keyToRemove -> keyToRemove.groupInfo in groupsToShow }
                    .groupBy { it.groupInfo.groupKey }
                    .mapValuesNotNullTo(ArrayMap()) { (_, vs) ->
                        vs.takeIf { it.size == 1 }?.get(0)
                    }
            return Diff(added, removed.map { it.notifKey }, replacements)
        }
    }
}

/** An Icon, and keys for unique identification. */
data class NotificationIconInfo(
    val sourceIcon: Icon,
    val notifKey: String,
    val groupKey: String,
)

/**
 * Construct an [NotificationIconInfo] out of an [ActiveNotificationModel], or return `null` if one
 * cannot be created due to missing information.
 */
fun ActiveNotificationModel.toIconInfo(sourceIcon: Icon?): NotificationIconInfo? {
    return sourceIcon?.let {
        groupKey?.let { groupKey ->
            NotificationIconInfo(
                sourceIcon = sourceIcon,
                notifKey = key,
                groupKey = groupKey,
            )
        }
    }
}

private val NotificationIconInfo.groupInfo: IconGroupInfo
    get() = IconGroupInfo(sourceIcon, groupKey)

private data class IconGroupInfo(
    val sourceIcon: Icon,
    val groupKey: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IconGroupInfo

        if (groupKey != other.groupKey) return false
        return sourceIcon.sameAs(other.sourceIcon)
    }

    override fun hashCode(): Int {
        var result = groupKey.hashCode()
        result = 31 * result + sourceIcon.type.hashCode()
        when (sourceIcon.type) {
            Icon.TYPE_BITMAP,
            Icon.TYPE_ADAPTIVE_BITMAP -> {
                result = 31 * result + sourceIcon.bitmap.hashCode()
            }
            Icon.TYPE_DATA -> {
                result = 31 * result + sourceIcon.dataLength.hashCode()
                result = 31 * result + sourceIcon.dataOffset.hashCode()
            }
            Icon.TYPE_RESOURCE -> {
                result = 31 * result + sourceIcon.resId.hashCode()
                result = 31 * result + sourceIcon.resPackage.hashCode()
            }
            Icon.TYPE_URI,
            Icon.TYPE_URI_ADAPTIVE_BITMAP -> {
                result = 31 * result + sourceIcon.uriString.hashCode()
            }
        }
        return result
    }
}
