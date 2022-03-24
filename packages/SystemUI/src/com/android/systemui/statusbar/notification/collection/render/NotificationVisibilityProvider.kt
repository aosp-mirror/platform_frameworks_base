/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.statusbar.notification.collection.NotificationEntry

/**
 * An interface for getting the current [NotificationVisibility] object for a notification.
 */
interface NotificationVisibilityProvider {
    /** Given a notification entry, return the visibility object */
    fun obtain(entry: NotificationEntry, visible: Boolean): NotificationVisibility
    /** Given a notification key, return the visibility object */
    fun obtain(key: String, visible: Boolean): NotificationVisibility
    /** Given a notification key, return the location */
    fun getLocation(key: String): NotificationVisibility.NotificationLocation
}