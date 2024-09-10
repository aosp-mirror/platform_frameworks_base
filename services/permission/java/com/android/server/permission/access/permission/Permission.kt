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

package com.android.server.permission.access.permission

import android.content.pm.PermissionInfo
import android.os.UserHandle
import com.android.server.permission.access.util.hasBits
import libcore.util.EmptyArray

data class Permission(
    val permissionInfo: PermissionInfo,
    val isReconciled: Boolean,
    val type: Int,
    val appId: Int,
    @Suppress("ArrayInDataClass") val gids: IntArray = EmptyArray.INT,
    val areGidsPerUser: Boolean = false
) {
    inline val name: String
        get() = permissionInfo.name

    inline val packageName: String
        get() = permissionInfo.packageName

    inline val groupName: String?
        get() = permissionInfo.group

    inline val isDynamic: Boolean
        get() = type == TYPE_DYNAMIC

    inline val protectionLevel: Int
        @Suppress("DEPRECATION") get() = permissionInfo.protectionLevel

    inline val protection: Int
        get() = permissionInfo.protection

    inline val isInternal: Boolean
        get() = protection == PermissionInfo.PROTECTION_INTERNAL

    inline val isNormal: Boolean
        get() = protection == PermissionInfo.PROTECTION_NORMAL

    inline val isRuntime: Boolean
        get() = protection == PermissionInfo.PROTECTION_DANGEROUS

    inline val isSignature: Boolean
        get() = protection == PermissionInfo.PROTECTION_SIGNATURE

    inline val protectionFlags: Int
        get() = permissionInfo.protectionFlags

    inline val isAppOp: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_APPOP)

    inline val isAppPredictor: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR)

    inline val isCompanion: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_COMPANION)

    inline val isConfigurator: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_CONFIGURATOR)

    inline val isDevelopment: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_DEVELOPMENT)

    inline val isIncidentReportApprover: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER)

    inline val isInstaller: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_INSTALLER)

    inline val isInstant: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_INSTANT)

    inline val isKnownSigner: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_KNOWN_SIGNER)

    inline val isModule: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_MODULE)

    inline val isOem: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_OEM)

    inline val isPre23: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PRE23)

    inline val isPreInstalled: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PREINSTALLED)

    inline val isPrivileged: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PRIVILEGED)

    inline val isRecents: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_RECENTS)

    inline val isRetailDemo: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO)

    inline val isRole: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_ROLE)

    inline val isRuntimeOnly: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY)

    inline val isSetup: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_SETUP)

    inline val isSystemTextClassifier: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER)

    inline val isVendorPrivileged: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED)

    inline val isVerifier: Boolean
        get() = protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_VERIFIER)

    inline val isHardRestricted: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_HARD_RESTRICTED)

    inline val isRemoved: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_REMOVED)

    inline val isSoftRestricted: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_SOFT_RESTRICTED)

    inline val isHardOrSoftRestricted: Boolean
        get() = isHardRestricted || isSoftRestricted

    inline val isImmutablyRestricted: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_IMMUTABLY_RESTRICTED)

    inline val knownCerts: Set<String>
        get() = permissionInfo.knownCerts

    inline val hasGids: Boolean
        get() = gids.isNotEmpty()

    inline val footprint: Int
        get() = name.length + permissionInfo.calculateFootprint()

    fun getGidsForUser(userId: Int): IntArray =
        if (areGidsPerUser) {
            IntArray(gids.size) { i -> UserHandle.getUid(userId, gids[i]) }
        } else {
            gids.copyOf()
        }

    companion object {
        // The permission is defined in an application manifest.
        const val TYPE_MANIFEST = 0
        // The permission is defined dynamically.
        const val TYPE_DYNAMIC = 2

        fun typeToString(type: Int): String =
            when (type) {
                TYPE_MANIFEST -> "TYPE_MANIFEST"
                TYPE_DYNAMIC -> "TYPE_DYNAMIC"
                else -> type.toString()
            }
    }
}
