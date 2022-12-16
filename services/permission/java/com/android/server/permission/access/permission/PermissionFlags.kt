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

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.os.Build
import android.permission.PermissionManager
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits

/**
 * A set of internal permission flags that's better than the set of `FLAG_PERMISSION_*` constants on
 * [PackageManager].
 *
 * The old binary permission state is now tracked by multiple `*_GRANTED` and `*_REVOKED` flags, so
 * that:
 *
 * - With [INSTALL_GRANTED] and [INSTALL_REVOKED], we can now get rid of the old per-package
 *   `areInstallPermissionsFixed` attribute and correctly track it per-permission, finally fixing
 *   edge cases during module rollbacks.
 *
 * - With [LEGACY_GRANTED] and [IMPLICIT_GRANTED], we can now ensure that legacy permissions and
 *   implicit permissions split from non-runtime permissions are never revoked, without checking
 *   split permissions and package state everywhere slowly and in slightly different ways.
 *
 * - With [RESTRICTION_REVOKED], we can now get rid of the error-prone logic about revoking and
 *   potentially re-granting permissions upon restriction state changes.
 *
 * Permission grants due to protection level are now tracked by [PROTECTION_GRANTED], and permission
 * grants due to [PackageManager.grantRuntimePermission] are now tracked by [RUNTIME_GRANTED].
 *
 * The [PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED] and
 * [PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED] flags are now unified into [IMPLICIT], and
 * they can be differentiated by the presence of [LEGACY_GRANTED].
 *
 * The rest of the permission flags have a 1:1 mapping to the old `FLAG_PERMISSION_*` constants, and
 * don't have any effect on the binary permission state.
 */
object PermissionFlags {
    /**
     * Permission flag for a normal permission that is granted at package installation.
     */
    const val INSTALL_GRANTED = 1 shl 0

    /**
     * Permission flag for a normal permission that is revoked at package installation.
     *
     * Normally packages that have already been installed cannot be granted new normal permissions
     * until its next installation (update), so this flag helps track that the normal permission was
     * revoked upon its most recent installation.
     */
    const val INSTALL_REVOKED = 1 shl 1

    /**
     * Permission flag for a signature or internal permission that is granted based on the
     * permission's protection level, including its protection and protection flags.
     *
     * For example, this flag may be set when the permission is a signature permission and the
     * package is having a compatible signing certificate with the package defining the permission,
     * or when the permission is a privileged permission and the package is a privileged app with
     * its permission in the
     * [privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist).
     */
    const val PROTECTION_GRANTED = 1 shl 2

    /**
     * Permission flag for a role or runtime permission that is or was granted by a role.
     *
     * @see PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE
     */
    const val ROLE = 1 shl 3

    /**
     * Permission flag for a development, role or runtime permission that is granted via
     * [PackageManager.grantRuntimePermission].
     */
    const val RUNTIME_GRANTED = 1 shl 4

    /**
     * Permission flag for a runtime permission whose state is set by the user.
     *
     * For example, this flag may be set when the permission is allowed by the user in the
     * request permission dialog, or managed in the permission settings.
     *
     * @see PackageManager.FLAG_PERMISSION_USER_SET
     */
    const val USER_SET = 1 shl 5

    /**
     * Permission flag for a runtime permission whose state is (revoked and) fixed by the user.
     *
     * For example, this flag may be set when the permission is denied twice by the user in the
     * request permission dialog.
     *
     * @see PackageManager.FLAG_PERMISSION_USER_FIXED
     */
    const val USER_FIXED = 1 shl 6

    /**
     * Permission flag for a runtime permission whose state is set and fixed by the device policy
     * via [DevicePolicyManager.setPermissionGrantState].
     *
     * @see PackageManager.FLAG_PERMISSION_POLICY_FIXED
     */
    const val POLICY_FIXED = 1 shl 7

    /**
     * Permission flag for a runtime permission that is (pregranted and) fixed by the system.
     *
     * For example, this flag may be set in
     * [com.android.server.pm.permission.DefaultPermissionGrantPolicy].
     *
     * @see PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
     */
    const val SYSTEM_FIXED = 1 shl 8

    /**
     * Permission flag for a runtime permission that is or was pregranted by the system.
     *
     * For example, this flag may be set in
     * [com.android.server.pm.permission.DefaultPermissionGrantPolicy].
     *
     * @see PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
     */
    const val PREGRANT = 1 shl 9

    /**
     * Permission flag for a runtime permission that is granted because the package targets a legacy
     * SDK version before [Build.VERSION_CODES.M] and doesn't support runtime permissions.
     *
     * As long as this flag is set, the permission should always be considered granted, although
     * [APP_OP_REVOKED] may cause the app op for the runtime permission to be revoked. Once the
     * package targets a higher SDK version so that it started supporting runtime permissions, this
     * flag should be removed and the remaining flags should take effect.
     *
     * @see PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
     * @see PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
     */
    const val LEGACY_GRANTED = 1 shl 10

    /**
     * Permission flag for a runtime permission that is granted because the package targets a lower
     * SDK version and the permission is implicit to it as a
     * [split permission][PermissionManager.SplitPermissionInfo] from other non-runtime permissions.
     *
     * As long as this flag is set, the permission should always be considered granted, although
     * [APP_OP_REVOKED] may cause the app op for the runtime permission to be revoked. Once the
     * package targets a higher SDK version so that the permission is no longer implicit to it, this
     * flag should be removed and the remaining flags should take effect.
     *
     * @see PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED
     * @see PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
     */
    const val IMPLICIT_GRANTED = 1 shl 11

    /**
     * Permission flag for a runtime permission that is granted because the package targets a legacy
     * SDK version before [Build.VERSION_CODES.M] and doesn't support runtime permissions, so that
     * it needs to be reviewed by the user; or granted because the package targets a lower SDK
     * version and the permission is implicit to it as a
     * [split permission][PermissionManager.SplitPermissionInfo] from other non-runtime permissions,
     * so that it needs to be revoked when it's no longer implicit.
     *
     * @see PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
     * @see PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED
     */
    const val IMPLICIT = 1 shl 12

    /**
     * Permission flag for a runtime permission that is user-sensitive when it's granted.
     *
     * This flag is informational and managed by PermissionController.
     *
     * @see PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED
     */
    const val USER_SENSITIVE_WHEN_GRANTED = 1 shl 13

    /**
     * Permission flag for a runtime permission that is user-sensitive when it's revoked.
     *
     * This flag is informational and managed by PermissionController.
     *
     * @see PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED
     */
    const val USER_SENSITIVE_WHEN_REVOKED = 1 shl 14

    /**
     * Permission flag for a restricted runtime permission that is exempt by the package's
     * installer.
     *
     * For example, this flag may be set when the installer applied the exemption as part of the
     * [session parameters](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(java.util.Set%3Cjava.lang.String%3E)).
     *
     * The permission will be restricted when none of the exempt flags is set.
     *
     * @see PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT
     */
    const val INSTALLER_EXEMPT = 1 shl 15

    /**
     * Permission flag for a restricted runtime permission that is exempt by the system.
     *
     * For example, this flag may be set when the package is a system app.
     *
     * The permission will be restricted when none of the exempt flags is set.
     *
     * @see PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
     */
    const val SYSTEM_EXEMPT = 1 shl 16

    /**
     * Permission flag for a restricted runtime permission that is exempt due to system upgrade.
     *
     * For example, this flag may be set when the package was installed before the system was
     * upgraded to [Build.VERSION_CODES.Q], when restricted permissions were introduced.
     *
     * The permission will be restricted when none of the exempt flags is set.
     *
     * @see PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT
     */
    const val UPGRADE_EXEMPT = 1 shl 17

    /**
     * Permission flag for a restricted runtime permission that is revoked due to being hard
     * restricted.
     *
     * @see PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION
     */
    const val RESTRICTION_REVOKED = 1 shl 18

    /**
     * Permission flag for a restricted runtime permission that is soft restricted.
     *
     * @see PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION
     */
    const val SOFT_RESTRICTED = 1 shl 19

    /**
     * Permission flag for a runtime permission whose app op is revoked.
     *
     * For example, this flag may be set when the runtime permission is legacy or implicit but still
     * "revoked" by the user in permission settings, or when the app op mode for the runtime
     * permission is set to revoked via [AppOpsManager.setUidMode].
     *
     * @see PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
     */
    const val APP_OP_REVOKED = 1 shl 20

    /**
     * Permission flag for a runtime permission that is granted as one-time.
     *
     * For example, this flag may be set when the user selected "Only this time" in the request
     * permission dialog.
     *
     * This flag, along with other user decisions when it is set, should never be persisted, and
     * should be removed once the permission is revoked.
     *
     * @see PackageManager.FLAG_PERMISSION_ONE_TIME
     */
    const val ONE_TIME = 1 shl 21

    /**
     * Permission flag for a runtime permission that was revoked due to app hibernation.
     *
     * This flag is informational and added by PermissionController, and should be removed once the
     * permission is granted again.
     *
     * @see PackageManager.FLAG_PERMISSION_AUTO_REVOKED
     */
    const val HIBERNATION = 1 shl 22

    /**
     * Permission flag for a runtime permission that is selected by the user.
     *
     * For example, this flag may be set when one of the coarse/fine location accuracies is
     * selected by the user.
     *
     * This flag is informational and managed by PermissionController.
     *
     * @see PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY
     */
    const val USER_SELECTED = 1 shl 23

    /**
     * Mask for all permission flags.
     */
    const val MASK_ALL = 0.inv()

    /**
     * Mask for all permission flags that may be applied to a runtime permission.
     */
    const val MASK_RUNTIME = ROLE or RUNTIME_GRANTED or USER_SET or USER_FIXED or POLICY_FIXED or
        SYSTEM_FIXED or PREGRANT or LEGACY_GRANTED or IMPLICIT_GRANTED or IMPLICIT or
        USER_SENSITIVE_WHEN_GRANTED or USER_SENSITIVE_WHEN_REVOKED or INSTALLER_EXEMPT or
        SYSTEM_EXEMPT or UPGRADE_EXEMPT or RESTRICTION_REVOKED or SOFT_RESTRICTED or
        APP_OP_REVOKED or ONE_TIME or HIBERNATION or USER_SELECTED

    /**
     * Mask for all API permission flags about permission restriction.
     */
    private const val API_MASK_RESTRICTION =
        PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT or
            PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT or
            PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT or
            PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION

    /**
     * Mask for all permission flags about permission restriction.
     */
    private const val MASK_RESTRICTION = INSTALLER_EXEMPT or SYSTEM_EXEMPT or
        UPGRADE_EXEMPT or RESTRICTION_REVOKED or SOFT_RESTRICTED

    fun isPermissionGranted(policyFlags: Int): Boolean {
        if (policyFlags.hasBits(INSTALL_GRANTED)) {
            return true
        }
        if (policyFlags.hasBits(INSTALL_REVOKED)) {
            return false
        }
        if (policyFlags.hasBits(PROTECTION_GRANTED)) {
            return true
        }
        if (policyFlags.hasBits(LEGACY_GRANTED) || policyFlags.hasBits(IMPLICIT_GRANTED)) {
            return true
        }
        if (policyFlags.hasBits(RESTRICTION_REVOKED)) {
            return false
        }
        return policyFlags.hasBits(RUNTIME_GRANTED)
    }

    fun isAppOpGranted(policyFlags: Int): Boolean =
        isPermissionGranted(policyFlags) && !policyFlags.hasBits(APP_OP_REVOKED)

    fun isReviewRequired(policyFlags: Int): Boolean =
        policyFlags.hasBits(LEGACY_GRANTED) && policyFlags.hasBits(IMPLICIT)

    fun toApiFlags(policyFlags: Int): Int {
        var apiFlags = 0
        if (policyFlags.hasBits(USER_SET)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_USER_SET
        }
        if (policyFlags.hasBits(USER_FIXED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_USER_FIXED
        }
        if (policyFlags.hasBits(POLICY_FIXED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_POLICY_FIXED
        }
        if (policyFlags.hasBits(SYSTEM_FIXED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
        }
        if (policyFlags.hasBits(PREGRANT)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT
        }
        if (policyFlags.hasBits(IMPLICIT)) {
            apiFlags = apiFlags or if (policyFlags.hasBits(LEGACY_GRANTED)) {
                PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
            } else {
                PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED
            }
        }
        if (policyFlags.hasBits(USER_SENSITIVE_WHEN_GRANTED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED
        }
        if (policyFlags.hasBits(USER_SENSITIVE_WHEN_REVOKED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED
        }
        if (policyFlags.hasBits(INSTALLER_EXEMPT)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT
        }
        if (policyFlags.hasBits(SYSTEM_EXEMPT)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
        }
        if (policyFlags.hasBits(UPGRADE_EXEMPT)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT
        }
        if (policyFlags.hasBits(RESTRICTION_REVOKED) || policyFlags.hasBits(SOFT_RESTRICTED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION
        }
        if (policyFlags.hasBits(ROLE)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE
        }
        if (policyFlags.hasBits(APP_OP_REVOKED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
        }
        if (policyFlags.hasBits(ONE_TIME)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_ONE_TIME
        }
        if (policyFlags.hasBits(HIBERNATION)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_AUTO_REVOKED
        }
        if (policyFlags.hasBits(USER_SELECTED)) {
            apiFlags = apiFlags or PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY
        }
        return apiFlags
    }

    fun setRuntimePermissionGranted(policyFlags: Int, isGranted: Boolean): Int =
        if (isGranted) policyFlags or RUNTIME_GRANTED else policyFlags andInv RUNTIME_GRANTED

    fun updatePolicyFlags(policyFlags: Int, apiFlagMask: Int, apiFlagValues: Int): Int {
        check(!apiFlagMask.hasAnyBit(API_MASK_RESTRICTION)) {
            "Permission flags about permission restriction can only be directly mutated by the" +
                " policy"
        }
        val oldApiFlags = toApiFlags(policyFlags)
        val newApiFlags = (oldApiFlags andInv apiFlagMask) or (apiFlagValues and apiFlagMask)
        return toPolicyFlags(policyFlags, newApiFlags)
    }

    private fun toPolicyFlags(oldPolicyFlags: Int, apiFlags: Int): Int {
        var policyFlags = 0
        policyFlags = policyFlags or (oldPolicyFlags and INSTALL_GRANTED)
        policyFlags = policyFlags or (oldPolicyFlags and INSTALL_REVOKED)
        policyFlags = policyFlags or (oldPolicyFlags and PROTECTION_GRANTED)
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE)) {
            policyFlags = policyFlags or ROLE
        }
        policyFlags = policyFlags or (oldPolicyFlags and RUNTIME_GRANTED)
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_USER_SET)) {
            policyFlags = policyFlags or USER_SET
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_USER_FIXED)) {
            policyFlags = policyFlags or USER_FIXED
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_POLICY_FIXED)) {
            policyFlags = policyFlags or POLICY_FIXED
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_SYSTEM_FIXED)) {
            policyFlags = policyFlags or SYSTEM_FIXED
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT)) {
            policyFlags = policyFlags or PREGRANT
        }
        policyFlags = policyFlags or (oldPolicyFlags and LEGACY_GRANTED)
        policyFlags = policyFlags or (oldPolicyFlags and IMPLICIT_GRANTED)
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) ||
            apiFlags.hasBits(PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED)) {
            policyFlags = policyFlags or IMPLICIT
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED)) {
            policyFlags = policyFlags or USER_SENSITIVE_WHEN_GRANTED
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED)) {
            policyFlags = policyFlags or USER_SENSITIVE_WHEN_REVOKED
        }
        // FLAG_PERMISSION_APPLY_RESTRICTION can be either REVOKED_BY_RESTRICTION when the
        // permission is hard restricted, or SOFT_RESTRICTED when the permission is soft restricted.
        // However since we should never allow indirect mutation of restriction state, we can just
        // get the flags about restriction from the old policy flags.
        policyFlags = policyFlags or (oldPolicyFlags and MASK_RESTRICTION)
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_REVOKED_COMPAT)) {
            policyFlags = policyFlags or APP_OP_REVOKED
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_ONE_TIME)) {
            policyFlags = policyFlags or ONE_TIME
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_AUTO_REVOKED)) {
            policyFlags = policyFlags or HIBERNATION
        }
        if (apiFlags.hasBits(PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY)) {
            policyFlags = policyFlags or USER_SELECTED
        }
        return policyFlags
    }
}
