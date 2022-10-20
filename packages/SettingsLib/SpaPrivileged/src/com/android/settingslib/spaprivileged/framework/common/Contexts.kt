package com.android.settingslib.spaprivileged.framework.common

import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.UserHandle
import android.os.UserManager

/** The [UserManager] instance. */
val Context.userManager get() = getSystemService(UserManager::class.java)!!

/** The [DevicePolicyManager] instance. */
val Context.devicePolicyManager get() = getSystemService(DevicePolicyManager::class.java)!!

/** The [StorageStatsManager] instance. */
val Context.storageStatsManager get() = getSystemService(StorageStatsManager::class.java)!!

/** The [DomainVerificationManager] instance. */
val Context.domainVerificationManager
    get() = getSystemService(DomainVerificationManager::class.java)!!

/** Gets a new [Context] for the given [UserHandle]. */
fun Context.asUser(userHandle: UserHandle): Context = createContextAsUser(userHandle, 0)
