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

package com.android.systemui.screenshot.proxy

/**
 * Provides a mechanism to interact with the main SystemUI process.
 *
 * ScreenshotService runs in an isolated process. Because of this, interactions with an outside
 * component with shared state must be accessed through this proxy to reach the correct instance.
 *
 * TODO: Rename and relocate 'ScreenshotProxyService' to this package and remove duplicate clients.
 */
interface SystemUiProxy {
    /** Indicate if the notification shade is "open"... (not in the fully collapsed position) */
    suspend fun isNotificationShadeExpanded(): Boolean

    /**
     * Request keyguard dismissal, raising keyguard credential entry if required and waits for
     * completion.
     */
    suspend fun dismissKeyguard()
}
