/*
 *
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

package com.android.systemui.statusbar.notification.logging

import android.app.Notification

/** Describes usage of a notification. */
data class NotificationMemoryUsage(
    val packageName: String,
    val uid: Int,
    val notificationKey: String,
    val notification: Notification,
    val objectUsage: NotificationObjectUsage,
    val viewUsage: List<NotificationViewUsage>
)

/**
 * Describes current memory usage of a [android.app.Notification] object.
 *
 * The values are in bytes.
 */
data class NotificationObjectUsage(
    val smallIcon: Int,
    val largeIcon: Int,
    val extras: Int,
    /** Style type, integer from [android.stats.sysui.NotificationEnums] */
    val style: Int,
    val styleIcon: Int,
    val bigPicture: Int,
    val extender: Int,
    val hasCustomView: Boolean,
)

enum class ViewType {
    PUBLIC_VIEW,
    PRIVATE_CONTRACTED_VIEW,
    PRIVATE_EXPANDED_VIEW,
    PRIVATE_HEADS_UP_VIEW,
    TOTAL
}

/**
 * Describes current memory of a notification view hierarchy.
 *
 * The values are in bytes.
 */
data class NotificationViewUsage(
    val viewType: ViewType,
    val smallIcon: Int,
    val largeIcon: Int,
    val systemIcons: Int,
    val style: Int,
    val customViews: Int,
    val softwareBitmapsPenalty: Int,
)
