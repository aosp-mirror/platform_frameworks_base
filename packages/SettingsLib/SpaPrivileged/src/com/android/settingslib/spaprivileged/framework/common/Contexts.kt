package com.android.settingslib.spaprivileged.framework.common

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager

/** The [UserManager] instance. */
val Context.userManager get() = getSystemService(UserManager::class.java)!!

/** The [DevicePolicyManager] instance. */
val Context.devicePolicyManager get() = getSystemService(DevicePolicyManager::class.java)!!
