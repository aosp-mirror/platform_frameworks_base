/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

/**
 * Modifies a NotificationEntry
 *
 * The [modifier] function will be passed an instance of a NotificationEntryBuilder. Any
 * modifications made to the builder will be applied to the [entry].
 */
inline fun modifyEntry(
    entry: NotificationEntry,
    crossinline modifier: NotificationEntryBuilder.() -> Unit
) {
    val builder = NotificationEntryBuilder(entry)
    modifier(builder)
    builder.apply(entry)
}
