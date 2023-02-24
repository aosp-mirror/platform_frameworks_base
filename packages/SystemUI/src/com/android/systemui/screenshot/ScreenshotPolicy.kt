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
 * limitations under the License.
 */

package com.android.systemui.screenshot

import android.annotation.UserIdInt
import android.content.ComponentName
import android.graphics.Rect
import android.os.UserHandle
import android.view.Display

/**
 * Provides policy decision-making information to screenshot request handling.
 */
interface ScreenshotPolicy {

    /** @return true if the user is a managed profile (a.k.a. work profile) */
    suspend fun isManagedProfile(@UserIdInt userId: Int): Boolean

    /**
     * Requests information about the owner of display content which occupies a majority of the
     * screenshot and/or has most recently been interacted with at the time the screenshot was
     * requested.
     *
     * @param displayId the id of the display to inspect
     * @return content info for the primary content on the display
     */
    suspend fun findPrimaryContent(displayId: Int): DisplayContentInfo

    data class DisplayContentInfo(
        val component: ComponentName,
        val bounds: Rect,
        val user: UserHandle,
        val taskId: Int,
    )

    fun getDefaultDisplayId(): Int = Display.DEFAULT_DISPLAY
}
