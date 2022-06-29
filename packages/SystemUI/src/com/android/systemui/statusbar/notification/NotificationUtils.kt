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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification

import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.notification.collection.ListEntry

/** Get the notification key, reformatted for logging, for the (optional) entry  */
val ListEntry?.logKey: String?
    get() = this?.let { NotificationUtils.logKey(it) }

/** Get the notification key, reformatted for logging, for the (optional) sbn  */
val StatusBarNotification?.logKey: String?
    get() = this?.key?.let { NotificationUtils.logKey(it) }

/** Removes newlines from the notification key to prettify apps that have these in the tag  */
fun logKey(key: String?): String? = NotificationUtils.logKey(key)