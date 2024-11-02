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

package com.android.systemui.communal.shared.model

import android.os.UserHandle
import android.os.UserManager
import com.android.systemui.Flags.secondaryUserWidgetHost
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Helper for multi-user / HSUM related functionality for the Glanceable Hub. */
interface GlanceableHubMultiUserHelper {
    /** Whether the Glanceable Hub in HSUM flag is enabled. */
    val glanceableHubHsumFlagEnabled: Boolean

    /** Whether the device is in headless system user mode. */
    fun isHeadlessSystemUserMode(): Boolean

    /** Whether the current process is running in the headless system user. */
    fun isInHeadlessSystemUser(): Boolean

    /**
     * Asserts that the current process is running in the headless system user.
     *
     * Only throws an exception if [glanceableHubHsumFlagEnabled] is true.
     */
    @Throws(IllegalStateException::class) fun assertInHeadlessSystemUser()

    /**
     * Asserts that the current process is NOT running in the headless system user.
     *
     * Only throws an exception if [glanceableHubHsumFlagEnabled] is true.
     */
    @Throws(IllegalStateException::class) fun assertNotInHeadlessSystemUser()
}

@SysUISingleton
class GlanceableHubMultiUserHelperImpl @Inject constructor(private val userHandle: UserHandle) :
    GlanceableHubMultiUserHelper {

    override val glanceableHubHsumFlagEnabled: Boolean = secondaryUserWidgetHost()

    override fun isHeadlessSystemUserMode(): Boolean = UserManager.isHeadlessSystemUserMode()

    override fun isInHeadlessSystemUser(): Boolean {
        return isHeadlessSystemUserMode() && userHandle.isSystem
    }

    override fun assertInHeadlessSystemUser() {
        if (glanceableHubHsumFlagEnabled) {
            check(isInHeadlessSystemUser())
        }
    }

    override fun assertNotInHeadlessSystemUser() {
        if (glanceableHubHsumFlagEnabled) {
            check(!isInHeadlessSystemUser())
        }
    }
}
