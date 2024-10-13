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

import com.android.systemui.screenshot.ScreenshotPolicy.DisplayContentInfo

internal class FakeScreenshotPolicy : ScreenshotPolicy {

    private val userTypes = mutableMapOf<Int, Boolean>()
    private val contentInfo = mutableMapOf<Int, DisplayContentInfo?>()

    fun setManagedProfile(userId: Int, managedUser: Boolean) {
        userTypes[userId] = managedUser
    }
    override suspend fun isManagedProfile(userId: Int): Boolean {
        return userTypes[userId] ?: error("No managedProfile value set for userId $userId")
    }

    fun setDisplayContentInfo(userId: Int, contentInfo: DisplayContentInfo) {
        this.contentInfo[userId] = contentInfo
    }

    override suspend fun findPrimaryContent(displayId: Int): DisplayContentInfo {
        return contentInfo[displayId] ?: error("No DisplayContentInfo set for displayId $displayId")
    }
}
