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

package com.android.server.permission.test

import android.content.pm.PermissionInfo
import android.os.Build
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.immutable.IndexedListSet
import com.android.server.permission.access.immutable.MutableIndexedListSet
import com.android.server.permission.access.immutable.MutableIndexedMap
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.PackageState
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A parameterized test for testing evaluating permission states and inheriting implicit permission
 * states for onUserAdded(), onStorageVolumeAdded() and onPackageAdded() in AppIdPermissionPolicy
 */
@RunWith(Parameterized::class)
class AppIdPermissionPolicyPermissionStatesTest : BasePermissionPolicyTest() {
    @Parameterized.Parameter(0) lateinit var action: Action

    @Before
    fun setUp() {
        if (action == Action.ON_USER_ADDED) {
            createUserState(USER_ID_NEW)
        }
    }

    @Test
    fun testEvaluatePermissionState_normalPermissionAlreadyGranted_remainsUnchanged() {
        val oldFlags = PermissionFlags.INSTALL_GRANTED or PermissionFlags.INSTALL_REVOKED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_NORMAL) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests a normal permission" +
                " with an existing INSTALL_GRANTED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_normalPermissionNotInstallRevoked_getsGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_NORMAL,
            isNewInstall = true
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a normal permission" +
                " with no existing flags, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_normalAppOpPermission_getsRoleAndUserSetFlagsPreserved() {
        val oldFlags = PermissionFlags.ROLE or PermissionFlags.USER_SET
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_NORMAL or PermissionInfo.PROTECTION_FLAG_APPOP
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED or oldFlags
        assertWithMessage(
            "After $action is called for a package that requests a normal app op" +
                " permission with existing ROLE and USER_SET flags, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_internalWasGrantedWithMissingPackage_getsProtectionGranted() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_INTERNAL) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_internalAppOpPermission_getsRoleAndUserSetFlagsPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.ROLE or
            PermissionFlags.USER_SET
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_APPOP
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and the permission isAppOp," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_internalDevelopmentPermission_getsRuntimeGrantedPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_DEVELOPMENT
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and permission isDevelopment," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_internalRolePermission_getsRoleAndRuntimeGrantedPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.ROLE or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_ROLE
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and the permission isRole," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_signaturePrivilegedPermissionNotAllowlisted_isNotGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_SIGNATURE or PermissionInfo.PROTECTION_FLAG_PRIVILEGED,
            isInstalledPackageSystem = true,
            isInstalledPackagePrivileged = true,
            isInstalledPackageProduct = true,
            // To mock the return value of shouldGrantPrivilegedOrOemPermission()
            isInstalledPackageVendor = true,
            isNewInstall = true
        ) {
            val platformPackage = mockPackageState(
                PLATFORM_APP_ID,
                mockAndroidPackage(PLATFORM_PACKAGE_NAME)
            )
            setupAllowlist(PACKAGE_NAME_1, false)
            addPackageState(platformPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests a signature privileged" +
                " permission that's not allowlisted, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_nonPrivilegedShouldGrantBySignature_getsProtectionGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_SIGNATURE,
            isInstalledPackageSystem = true,
            isInstalledPackagePrivileged = true,
            isInstalledPackageProduct = true,
            isInstalledPackageSignatureMatching = true,
            isInstalledPackageVendor = true,
            isNewInstall = true
        ) {
            val platformPackage = mockPackageState(
                PLATFORM_APP_ID,
                mockAndroidPackage(PLATFORM_PACKAGE_NAME, isSignatureMatching = true)
            )
            setupAllowlist(PACKAGE_NAME_1, false)
            addPackageState(platformPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PROTECTION_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a signature" +
                " non-privileged permission, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_privilegedAllowlistShouldGrantByProtectionFlags_getsGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_SIGNATURE or PermissionInfo.PROTECTION_FLAG_PRIVILEGED,
            isInstalledPackageSystem = true,
            isInstalledPackagePrivileged = true,
            isInstalledPackageProduct = true,
            isNewInstall = true
        ) {
            val platformPackage = mockPackageState(
                PLATFORM_APP_ID,
                mockAndroidPackage(PLATFORM_PACKAGE_NAME)
            )
            setupAllowlist(PACKAGE_NAME_1, true)
            addPackageState(platformPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PROTECTION_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a signature privileged" +
                " permission that's allowlisted and should grant by protection flags, the actual" +
                " permission flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun setupAllowlist(
        packageName: String,
        allowlistState: Boolean,
        state: MutableAccessState = oldState
    ) {
        state.mutateExternalState().setPrivilegedPermissionAllowlistPackages(
            MutableIndexedListSet<String>().apply { add(packageName) }
        )
        val mockAllowlist = mock<PermissionAllowlist> {
            whenever(
                getProductPrivilegedAppAllowlistState(packageName, PERMISSION_NAME_0)
            ).thenReturn(allowlistState)
        }
        state.mutateExternalState().setPermissionAllowlist(mockAllowlist)
    }

    @Test
    fun testEvaluatePermissionState_nonRuntimeFlagsOnRuntimePermissions_getsCleared() {
        val oldFlags = PermissionFlags.INSTALL_GRANTED or PermissionFlags.PREGRANT or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PREGRANT or PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " with existing $oldFlags flags, the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_newPermissionsForPreM_requiresUserReview() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            installedPackageTargetSdkVersion = Build.VERSION_CODES.LOLLIPOP,
            isNewInstall = true
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " with no existing flags in pre M, actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_legacyOrImplicitGrantedPreviouslyRevoked_getsAppOpRevoked() {
        val oldFlags = PermissionFlags.USER_FIXED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            installedPackageTargetSdkVersion = Build.VERSION_CODES.LOLLIPOP
        ) {
            setPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0, oldFlags)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.USER_FIXED or
            PermissionFlags.APP_OP_REVOKED
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that should be LEGACY_GRANTED or IMPLICIT_GRANTED that was previously revoked," +
                " the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_legacyGrantedForPostM_userReviewRequirementRemoved() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.IMPLICIT
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that used to require user review, the user review requirement should be removed" +
                " if it's upgraded to post M. The actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_legacyGrantedPermissionsAlreadyReviewedForPostM_getsGranted() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that was already reviewed by the user, the permission should be RUNTIME_GRANTED" +
                " if it's upgraded to post M. The actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_leanbackNotificationPermissionsForPostM_getsImplicitGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionName = PERMISSION_POST_NOTIFICATIONS,
            isNewInstall = true
        ) {
            oldState.mutateExternalState().setLeanback(true)
        }

        val actualFlags = getPermissionFlags(
            APP_ID_1,
            getUserIdEvaluated(),
            PERMISSION_POST_NOTIFICATIONS
        )
        val expectedNewFlags = PermissionFlags.IMPLICIT_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a runtime notification" +
                " permission when isLeanback, the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_implicitSourceFromNonRuntime_getsImplicitGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            implicitPermissions = setOf(PERMISSION_NAME_0),
            isNewInstall = true
        ) {
            oldState.mutateExternalState().setImplicitToSourcePermissions(
                MutableIndexedMap<String, IndexedListSet<String>>().apply {
                    put(PERMISSION_NAME_0, MutableIndexedListSet<String>().apply {
                        add(PERMISSION_NAME_1)
                    })
                }
            )
            addPermission(mockParsedPermission(PERMISSION_NAME_1, PACKAGE_NAME_0))
        }

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.IMPLICIT_GRANTED or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After $action is called for a package that requests a runtime implicit" +
                " permission that's source from a non-runtime permission, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    /**
     * For a legacy granted or implicit permission during the app upgrade, when the permission
     * should no longer be legacy or implicit granted, we want to remove the APP_OP_REVOKED flag
     * so that the app can request the permission.
     */
    @Test
    fun testEvaluatePermissionState_noLongerLegacyOrImplicitGranted_canBeRequested() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.APP_OP_REVOKED or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that is no longer LEGACY_GRANTED or IMPLICIT_GRANTED, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_noLongerImplicit_getsRuntimeAndImplicitFlagsRemoved() {
        val oldFlags = PermissionFlags.IMPLICIT or PermissionFlags.RUNTIME_GRANTED or
            PermissionFlags.USER_SET or PermissionFlags.USER_FIXED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that is no longer implicit and we shouldn't retain as nearby device" +
                " permissions, the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_noLongerImplicitNearbyWasGranted_getsRuntimeGranted() {
        val oldFlags = PermissionFlags.IMPLICIT_GRANTED or PermissionFlags.IMPLICIT
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionName = PERMISSION_BLUETOOTH_CONNECT,
            requestedPermissions = setOf(
                PERMISSION_BLUETOOTH_CONNECT,
                PERMISSION_ACCESS_BACKGROUND_LOCATION
            )
        ) {
            setPermissionFlags(
                APP_ID_1,
                getUserIdEvaluated(),
                PERMISSION_ACCESS_BACKGROUND_LOCATION,
                PermissionFlags.RUNTIME_GRANTED
            )
        }

        val actualFlags = getPermissionFlags(
            APP_ID_1,
            getUserIdEvaluated(),
            PERMISSION_BLUETOOTH_CONNECT
        )
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a runtime nearby device" +
                " permission that was granted by implicit, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_noLongerImplicitSystemOrPolicyFixedWasGranted_runtimeGranted() {
        val oldFlags = PermissionFlags.IMPLICIT_GRANTED or PermissionFlags.IMPLICIT or
            PermissionFlags.SYSTEM_FIXED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.SYSTEM_FIXED
        assertWithMessage(
            "After $action is called for a package that requests a runtime permission" +
                " that was granted and is no longer implicit and is SYSTEM_FIXED or POLICY_FIXED," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_restrictedPermissionsNotExempt_getsRestrictionFlags() {
        val oldFlags = PermissionFlags.RESTRICTION_REVOKED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionInfoFlags = PermissionInfo.FLAG_HARD_RESTRICTED
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that requests a runtime hard" +
                " restricted permission that is not exempted, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_restrictedPermissionsIsExempted_clearsRestrictionFlags() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionInfoFlags = PermissionInfo.FLAG_SOFT_RESTRICTED
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.UPGRADE_EXEMPT
        assertWithMessage(
            "After $action is called for a package that requests a runtime soft" +
                " restricted permission that is exempted, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testInheritImplicitPermissionStates_runtimeExistingImplicit_sourceFlagsNotInherited() {
        val oldImplicitPermissionFlags = PermissionFlags.USER_FIXED
        testInheritImplicitPermissionStates(
            implicitPermissionFlags = oldImplicitPermissionFlags,
            isNewInstallAndNewPermission = false
        )

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = oldImplicitPermissionFlags or PermissionFlags.IMPLICIT_GRANTED or
            PermissionFlags.APP_OP_REVOKED
        assertWithMessage(
            "After $action is called for a package that requests a permission that is" +
                " implicit, existing and runtime, it should not inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testInheritImplicitPermissionStates_nonRuntimeNewImplicit_sourceFlagsNotInherited() {
        testInheritImplicitPermissionStates(
            implicitPermissionProtectionLevel = PermissionInfo.PROTECTION_NORMAL
        )

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a permission that is" +
                " implicit, new and non-runtime, it should not inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testInheritImplicitPermissionStates_runtimeNewImplicitPermissions_sourceFlagsInherited() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(sourceRuntimeFlags = sourceRuntimeFlags)

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT_GRANTED or
            PermissionFlags.IMPLICIT
        assertWithMessage(
            "After $action is called for a package that requests a permission that is" +
                " implicit, new and runtime, it should inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testInheritImplicitPermissionStates_grantingNewFromRevokeImplicit_onlyInheritFromSource() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(
            implicitPermissionFlags = PermissionFlags.POLICY_FIXED,
            sourceRuntimeFlags = sourceRuntimeFlags,
            isAnySourcePermissionNonRuntime = false
        )

        val actualFlags = getPermissionFlags(APP_ID_1, getUserIdEvaluated(), PERMISSION_NAME_0)
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After $action is called for a package that requests a permission that is" +
                " implicit, existing, runtime and revoked, it should only inherit runtime flags" +
                " from source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    /**
     * If it's a media implicit permission (one of RETAIN_IMPLICIT_FLAGS_PERMISSIONS), we want to
     * remove the IMPLICIT flag so that they will be granted when they are no longer implicit.
     * (instead of revoking it)
     */
    @Test
    fun testInheritImplicitPermissionStates_mediaImplicitPermissions_getsImplicitFlagRemoved() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(
            implicitPermissionName = PERMISSION_ACCESS_MEDIA_LOCATION,
            sourceRuntimeFlags = sourceRuntimeFlags
        )

        val actualFlags = getPermissionFlags(
            APP_ID_1,
            getUserIdEvaluated(),
            PERMISSION_ACCESS_MEDIA_LOCATION
        )
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a media permission that" +
                " is implicit, new and runtime, it should inherit the runtime flags from" +
                " the source permission and have the IMPLICIT flag removed. Hence the actual" +
                " permission flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun testInheritImplicitPermissionStates(
        implicitPermissionName: String = PERMISSION_NAME_0,
        implicitPermissionFlags: Int = 0,
        implicitPermissionProtectionLevel: Int = PermissionInfo.PROTECTION_DANGEROUS,
        sourceRuntimeFlags: Int = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET,
        isAnySourcePermissionNonRuntime: Boolean = true,
        isNewInstallAndNewPermission: Boolean = true
    ) {
        val userId = getUserIdEvaluated()
        val implicitPermission = mockParsedPermission(
            implicitPermissionName,
            PACKAGE_NAME_0,
            protectionLevel = implicitPermissionProtectionLevel,
        )
        // For source from non-runtime in order to grant by implicit
        val sourcePermission1 = mockParsedPermission(
            PERMISSION_NAME_1,
            PACKAGE_NAME_0,
            protectionLevel = if (isAnySourcePermissionNonRuntime) {
                PermissionInfo.PROTECTION_NORMAL
            } else {
                PermissionInfo.PROTECTION_DANGEROUS
            }
        )
        // For inheriting runtime flags
        val sourcePermission2 = mockParsedPermission(
            PERMISSION_NAME_2,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS,
        )
        val permissionOwnerPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(
                PACKAGE_NAME_0,
                permissions = listOf(implicitPermission, sourcePermission1, sourcePermission2)
            )
        )
        val installedPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(
                PACKAGE_NAME_1,
                requestedPermissions = setOf(
                    implicitPermissionName,
                    PERMISSION_NAME_1,
                    PERMISSION_NAME_2
                ),
                implicitPermissions = setOf(implicitPermissionName)
            )
        )
        oldState.mutateExternalState().setImplicitToSourcePermissions(
            MutableIndexedMap<String, IndexedListSet<String>>().apply {
                put(implicitPermissionName, MutableIndexedListSet<String>().apply {
                    add(PERMISSION_NAME_1)
                    add(PERMISSION_NAME_2)
                })
            }
        )
        addPackageState(permissionOwnerPackageState)
        addPermission(implicitPermission)
        addPermission(sourcePermission1)
        addPermission(sourcePermission2)
        if (!isNewInstallAndNewPermission) {
            addPackageState(installedPackageState)
            setPermissionFlags(APP_ID_1, userId, implicitPermissionName, implicitPermissionFlags)
        }
        setPermissionFlags(APP_ID_1, userId, PERMISSION_NAME_2, sourceRuntimeFlags)

        mutateState {
            if (isNewInstallAndNewPermission) {
                addPackageState(installedPackageState)
                setPermissionFlags(
                    APP_ID_1,
                    userId,
                    implicitPermissionName,
                    implicitPermissionFlags,
                    newState
                )
            }
            testAction(installedPackageState)
        }
    }

    /**
     * Setup simple package states for testing evaluatePermissionState().
     * permissionOwnerPackageState is definer of permissionName with APP_ID_0.
     * installedPackageState is the installed package that requests permissionName with APP_ID_1.
     *
     * @param oldFlags the existing permission flags for APP_ID_1, userId, permissionName
     * @param protectionLevel the protectionLevel for the permission
     * @param permissionName the name of the permission (1) being defined (2) of the oldFlags, and
     *                       (3) requested by installedPackageState
     * @param requestedPermissions the permissions requested by installedPackageState
     * @param implicitPermissions the implicit permissions of installedPackageState
     * @param permissionInfoFlags the flags for the permission itself
     * @param isInstalledPackageSystem whether installedPackageState is a system package
     *
     * @return installedPackageState
     */
    private fun testEvaluatePermissionState(
        oldFlags: Int,
        protectionLevel: Int,
        permissionName: String = PERMISSION_NAME_0,
        requestedPermissions: Set<String> = setOf(permissionName),
        implicitPermissions: Set<String> = emptySet(),
        permissionInfoFlags: Int = 0,
        isInstalledPackageSystem: Boolean = false,
        isInstalledPackagePrivileged: Boolean = false,
        isInstalledPackageProduct: Boolean = false,
        isInstalledPackageSignatureMatching: Boolean = false,
        isInstalledPackageVendor: Boolean = false,
        installedPackageTargetSdkVersion: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        isNewInstall: Boolean = false,
        additionalSetup: () -> Unit
    ) {
        val userId = getUserIdEvaluated()
        val parsedPermission = mockParsedPermission(
            permissionName,
            PACKAGE_NAME_0,
            protectionLevel = protectionLevel,
            flags = permissionInfoFlags
        )
        val permissionOwnerPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(parsedPermission))
        )
        val installedPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(
                PACKAGE_NAME_1,
                requestedPermissions = requestedPermissions,
                implicitPermissions = implicitPermissions,
                targetSdkVersion = installedPackageTargetSdkVersion,
                isSignatureMatching = isInstalledPackageSignatureMatching
            ),
            isSystem = isInstalledPackageSystem,
            isPrivileged = isInstalledPackagePrivileged,
            isProduct = isInstalledPackageProduct,
            isVendor = isInstalledPackageVendor
        )
        addPackageState(permissionOwnerPackageState)
        if (!isNewInstall) {
            addPackageState(installedPackageState)
            setPermissionFlags(APP_ID_1, userId, permissionName, oldFlags)
        }
        addPermission(parsedPermission)

        additionalSetup()

        mutateState {
            if (isNewInstall) {
                addPackageState(installedPackageState, newState)
                setPermissionFlags(APP_ID_1, userId, permissionName, oldFlags, newState)
            }
            testAction(installedPackageState)
        }
    }

    private fun getUserIdEvaluated(): Int = when (action) {
        Action.ON_USER_ADDED -> USER_ID_NEW
        Action.ON_STORAGE_VOLUME_ADDED, Action.ON_PACKAGE_ADDED -> USER_ID_0
    }

    private fun MutateStateScope.testAction(packageState: PackageState) {
        with(appIdPermissionPolicy) {
            when (action) {
                Action.ON_USER_ADDED -> onUserAdded(getUserIdEvaluated())
                Action.ON_STORAGE_VOLUME_ADDED -> onStorageVolumeMounted(
                    null,
                    listOf(packageState.packageName),
                    true
                )
                Action.ON_PACKAGE_ADDED -> onPackageAdded(packageState)
            }
        }
    }

    enum class Action { ON_USER_ADDED, ON_STORAGE_VOLUME_ADDED, ON_PACKAGE_ADDED }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Array<Action> = Action.values()
    }
}
