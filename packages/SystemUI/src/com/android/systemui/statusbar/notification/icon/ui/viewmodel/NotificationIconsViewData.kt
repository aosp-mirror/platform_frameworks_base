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
    val visibleIcons: List<NotificationIconInfo> = emptyList(),
    /** Limit applied to the [visibleIcons]; can be interpreted different based on [limitType]. */
    val iconLimit: Int = visibleIcons.size,
    /** How [iconLimit] is applied to [visibleIcons]. */
    val limitType: LimitType = LimitType.MaximumAmount,
) {
    // TODO(b/305739416): This can be removed once we are no longer looking up the StatusBarIconView
    //  instances from outside of the view-binder layer. Ideally, we would just use MaximumAmount,
    //  and apply it directly to the list of visibleIcons by truncating the list to that amount.
    //  At the time of this writing, we cannot do that because looking up the SBIV can fail, and so
    //  we need additional icons to fall-back to.
    /** Determines how a limit to the icons is to be applied. */
    enum class LimitType {
        /** The [Int] limit is a maximum amount of icons to be displayed. */
        MaximumAmount,
        /**
         * The [Int] limit is a maximum index into the
         * [list of visible icons][NotificationIconsViewData.visibleIcons] to be displayed; any
         * icons beyond that index should be omitted.
         */
        MaximumIndex,
    }

    /** The difference between two [NotificationIconsViewData]s. */
    data class Diff(
        /** Icons added in the newer dataset. */
        val added: List<String> = emptyList(),
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
        val groupReplacements: Map<String, String> = emptyMap(),
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
            val prevKeys = prev.visibleIcons.asSequence().map { it.notifKey }.toSet()
            val newKeys = new.visibleIcons.asSequence().map { it.notifKey }.toSet()
            val added: List<String> = newKeys.mapNotNull { key -> key.takeIf { it !in prevKeys } }
            val removed: List<NotificationIconInfo> =
                prev.visibleIcons.filter { it.notifKey !in newKeys }
            val groupsToShow: Set<IconGroupInfo> =
                new.visibleIcons.asSequence().map { it.groupInfo }.toSet()
            val replacements: ArrayMap<String, String> =
                removed
                    .asSequence()
                    .filter { keyToRemove -> keyToRemove.groupInfo in groupsToShow }
                    .groupBy { it.groupInfo.groupKey }
                    .mapValuesNotNullTo(ArrayMap()) { (_, vs) ->
                        vs.takeIf { it.size == 1 }?.get(0)?.notifKey
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
