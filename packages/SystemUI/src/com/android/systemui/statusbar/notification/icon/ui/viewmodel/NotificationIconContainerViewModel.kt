/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.graphics.Rect
import android.graphics.drawable.Icon
import androidx.collection.ArrayMap
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconInfo
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.util.kotlin.mapValuesNotNullTo
import com.android.systemui.util.ui.AnimatedValue
import kotlinx.coroutines.flow.Flow

/**
 * View-model for the row of notification icons displayed in the NotificationShelf, StatusBar, and
 * AOD.
 */
interface NotificationIconContainerViewModel {

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean>

    /** Should icons be rendered in "dozing" mode? */
    val isDozing: Flow<AnimatedValue<Boolean>>

    /** Is the icon container visible? */
    val isVisible: Flow<AnimatedValue<Boolean>>

    /** The colors with which to display the notification icons. */
    val iconColors: Flow<ColorLookup>

    /** [IconsViewData] indicating which icons to display in the view. */
    val iconsViewData: Flow<IconsViewData>

    /**
     * Signal completion of the [isDozing] animation; if [isDozing]'s [AnimatedValue.isAnimating]
     * property was `true`, calling this method will update it to `false`.
     */
    fun completeDozeAnimation()

    /**
     * Signal completion of the [isVisible] animation; if [isVisible]'s [AnimatedValue.isAnimating]
     * property was `true`, calling this method will update it to `false`.
     */
    fun completeVisibilityAnimation()

    /**
     * Lookup the colors to use for the notification icons based on the bounds of the icon
     * container. A result of `null` indicates that no color changes should be applied.
     */
    fun interface ColorLookup {
        fun iconColors(viewBounds: Rect): IconColors?
    }

    /** Colors to apply to notification icons. */
    interface IconColors {

        /** A tint to apply to the icons. */
        val tint: Int

        /**
         * Returns the color to be applied to an icon, based on that icon's view bounds and whether
         * or not the notification icon is colorized.
         */
        fun staticDrawableColor(viewBounds: Rect, isColorized: Boolean): Int
    }

    /** Encapsulates the collection of notification icons present on the device. */
    data class IconsViewData(
        /** Icons that are visible in the container. */
        val visibleKeys: List<IconInfo> = emptyList(),
        /** Keys of icons that are "behind" the overflow dot. */
        val collapsedKeys: Set<String> = emptySet(),
        /** Whether the overflow dot should be shown regardless if [collapsedKeys] is empty. */
        val forceShowDot: Boolean = false,
    ) {
        /** The difference between two [IconsViewData]s. */
        data class Diff(
            /** Icons added in the newer dataset. */
            val added: List<IconInfo> = emptyList(),
            /** Icons removed from the older dataset. */
            val removed: List<String> = emptyList(),
            /**
             * Groups whose icon was replaced with a single new notification icon. The key of the
             * [Map] is the notification group key, and the value is the new icon.
             *
             * Specifically, this models a difference where the older dataset had notification
             * groups with a single icon in the set, and the newer dataset has a single, different
             * icon for the same group. A view binder can use this information for special
             * animations for this specific change.
             */
            val groupReplacements: Map<String, IconInfo> = emptyMap(),
        )

        companion object {
            /**
             * Returns an [IconsViewData.Diff] calculated from a [new] and [previous][prev]
             * [IconsViewData] state.
             */
            fun computeDifference(new: IconsViewData, prev: IconsViewData): Diff {
                val added: List<IconInfo> =
                    new.visibleKeys.filter {
                        it.notifKey !in prev.visibleKeys.asSequence().map { it.notifKey }
                    }
                val removed: List<IconInfo> =
                    prev.visibleKeys.filter {
                        it.notifKey !in new.visibleKeys.asSequence().map { it.notifKey }
                    }
                val groupsToShow: Set<IconGroupInfo> =
                    new.visibleKeys.asSequence().map { it.groupInfo }.toSet()
                val replacements: ArrayMap<String, IconInfo> =
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
    data class IconInfo(
        val sourceIcon: Icon,
        val notifKey: String,
        val groupKey: String,
    )
}

/**
 * Construct an [IconInfo] out of an [ActiveNotificationModel], or return `null` if one cannot be
 * created due to missing information.
 */
fun ActiveNotificationModel.toIconInfo(sourceIcon: Icon?): IconInfo? {
    return sourceIcon?.let {
        groupKey?.let { groupKey ->
            IconInfo(
                sourceIcon = sourceIcon,
                notifKey = key,
                groupKey = groupKey,
            )
        }
    }
}

private val IconInfo.groupInfo: IconGroupInfo
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
