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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import com.android.settingslib.spaprivileged.framework.common.devicePolicyManager
import com.android.settingslib.spaprivileged.framework.common.userManager

/** The user id for a given application. */
val ApplicationInfo.userId: Int
    get() = UserHandle.getUserId(uid)

/** The [UserHandle] for a given application. */
val ApplicationInfo.userHandle: UserHandle
    get() = UserHandle.getUserHandleForUid(uid)

/** Checks whether a flag is associated with the application. */
fun ApplicationInfo.hasFlag(flag: Int): Boolean = (flags and flag) > 0

/** Checks whether the application is currently installed. */
val ApplicationInfo.installed: Boolean get() = hasFlag(ApplicationInfo.FLAG_INSTALLED)

/** Checks whether the application is disabled until used. */
val ApplicationInfo.isDisabledUntilUsed: Boolean
    get() = enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED

/** Checks whether the application is disallowed control. */
fun ApplicationInfo.isDisallowControl(context: Context) =
    context.userManager.hasBaseUserRestriction(UserManager.DISALLOW_APPS_CONTROL, userHandle)

/** Checks whether the application is an active admin. */
fun ApplicationInfo.isActiveAdmin(context: Context): Boolean =
    context.devicePolicyManager.packageHasActiveAdmins(packageName, userId)

/** Converts to the route string which used in navigation. */
fun ApplicationInfo.toRoute() = "$packageName/$userId"
