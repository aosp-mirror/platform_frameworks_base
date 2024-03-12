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

package com.android.settingslib.spaprivileged.template.common

import android.content.pm.UserInfo
import android.content.pm.UserProperties
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.widget.scaffold.SettingsPager
import com.android.settingslib.spaprivileged.framework.common.userManager
import com.android.settingslib.spaprivileged.model.enterprise.EnterpriseRepository

/**
 * Info about how to group multiple profiles for Settings.
 *
 * @see [UserProperties.ShowInSettings]
 */
data class UserGroup(
    /** The users in this user group, if multiple users, the first one is parent user. */
    val userInfos: List<UserInfo>,
)

@Composable
fun UserProfilePager(content: @Composable (userGroup: UserGroup) -> Unit) {
    val context = LocalContext.current
    val userGroups = remember { context.userManager.getUserGroups() }
    val titles = remember {
        val enterpriseRepository = EnterpriseRepository(context)
        userGroups.map { userGroup ->
            enterpriseRepository.getProfileTitle(
                userGroup.userInfos.first(),
            )
        }
    }

    SettingsPager(titles) { page ->
        content(userGroups[page])
    }
}

private fun UserManager.getUserGroups(): List<UserGroup> {
    val userGroupList = mutableListOf<UserGroup>()
    val showInSettingsMap = getProfiles(UserHandle.myUserId()).groupBy { showInSettings(it) }

    showInSettingsMap[UserProperties.SHOW_IN_SETTINGS_WITH_PARENT]?.let {
        userGroupList += UserGroup(it)
    }

    showInSettingsMap[UserProperties.SHOW_IN_SETTINGS_SEPARATE]?.forEach {
        userGroupList += UserGroup(listOf(it))
    }

    return userGroupList
}

private fun UserManager.showInSettings(userInfo: UserInfo): Int {
    val userProperties = getUserProperties(userInfo.userHandle)
    return if (userInfo.isQuietModeEnabled && userProperties.showInQuietMode
            == UserProperties.SHOW_IN_QUIET_MODE_HIDDEN) {
        UserProperties.SHOW_IN_SETTINGS_NO
    } else {
        userProperties.showInSettings
    }
}
