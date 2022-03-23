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

/**
 * This is an object which provides callbacks for certain important events related to the
 * notification shade, such as notifications being removed by the user, or the shade becoming empty.
 */
interface NotifShadeEventSource {
    /**
     * Registers a callback to be invoked when the last notification has been removed from
     * the shade for any reason
     */
    fun setShadeEmptiedCallback(callback: Runnable)

    /**
     * Registers a callback to be invoked when a notification has been removed from
     * the shade by a user action
     */
    fun setNotifRemovedByUserCallback(callback: Runnable)
}