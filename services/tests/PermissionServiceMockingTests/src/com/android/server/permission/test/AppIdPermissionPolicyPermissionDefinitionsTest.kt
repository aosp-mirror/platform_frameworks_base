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
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.permission.Permission
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.pm.pkg.PackageState
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterized test for testing permission definitions (adopt permissions, add permission groups,
 * add permissions, trim permissions, trim permission states and revoke permissions on
 * package update) for onStorageVolumeAdded() and onPackageAdded() in AppIdPermissionPolicy.
 *
 * Note that the evaluatePermissionState() call with permission changes
 * (i.e. changedPermissionNames in AppIdPermissionPolicy) and the evaluatePermissionState() call
 * with an installedPackageState is put in this test instead of
 * AppIdPermissionPolicyPermissionStatesTest because these concepts don't apply to onUserAdded().
 */
@RunWith(Parameterized::class)
class AppIdPermissionPolicyPermissionDefinitionsTest : BasePermissionPolicyTest() {
    @Parameterized.Parameter(0) lateinit var action: Action

    @Test
    fun testAdoptPermissions_permissionsOfMissingSystemApp_getsAdopted() {
        testAdoptPermissions(hasMissingPackage = true, isSystem = true)

        assertWithMessage(
            "After $action is called for a null adopt permission package," +
                " the permission package name: ${getPermission(PERMISSION_NAME_0)?.packageName}" +
                " did not match the expected package name: $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testAdoptPermissions_permissionsOfExistingSystemApp_notAdopted() {
        testAdoptPermissions(isSystem = true)

        assertWithMessage(
            "After $action is called for a non-null adopt permission" +
                " package, the permission package name:" +
                " ${getPermission(PERMISSION_NAME_0)?.packageName} should not match the" +
                " package name: $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isNotEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testAdoptPermissions_permissionsOfNonSystemApp_notAdopted() {
        testAdoptPermissions(hasMissingPackage = true)

        assertWithMessage(
            "After $action is called for a non-system adopt permission" +
                " package, the permission package name:" +
                " ${getPermission(PERMISSION_NAME_0)?.packageName} should not match the" +
                " package name: $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isNotEqualTo(PACKAGE_NAME_0)
    }

    private fun testAdoptPermissions(
        hasMissingPackage: Boolean = false,
        isSystem: Boolean = false
    ) {
        val parsedPermission = mockParsedPermission(PERMISSION_NAME_0, PACKAGE_NAME_1)
        val packageToAdoptPermission = if (hasMissingPackage) {
            mockPackageState(APP_ID_1, PACKAGE_NAME_1, isSystem = isSystem)
        } else {
            mockPackageState(
                APP_ID_1,
                mockAndroidPackage(
                    PACKAGE_NAME_1,
                    permissions = listOf(parsedPermission)
                ),
                isSystem = isSystem
            )
        }
        addPackageState(packageToAdoptPermission)
        addPermission(parsedPermission)

        mutateState {
            val installedPackage = mockPackageState(
                APP_ID_0,
                mockAndroidPackage(
                    PACKAGE_NAME_0,
                    permissions = listOf(defaultPermission),
                    adoptPermissions = listOf(PACKAGE_NAME_1)
                )
            )
            addPackageState(installedPackage, newState)
            testAction(installedPackage)
        }
    }

    @Test
    fun testPermissionGroupDefinition_newPermissionGroup_getsDeclared() {
        mutateState {
            val packageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addPackageState(packageState, newState)
            testAction(packageState)
        }

        assertWithMessage(
            "After $action is called when there is no existing" +
                " permission groups, the new permission group $PERMISSION_GROUP_NAME_0 is not added"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.name)
            .isEqualTo(PERMISSION_GROUP_NAME_0)
    }

    @Test
    fun testPermissionGroupDefinition_systemAppTakingOverPermissionGroupDefinition_getsTakenOver() {
        testTakingOverPermissionAndPermissionGroupDefinitions(newPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After $action is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover the" +
                " ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testPermissionGroupDefinition_instantApps_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(
            newPermissionOwnerIsInstant = true,
            permissionGroupAlreadyExists = false
        )

        assertWithMessage(
            "After $action is called for an instant app," +
                " the new permission group $PERMISSION_GROUP_NAME_0 should not be added"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0))
            .isNull()
    }

    @Test
    fun testPermissionGroupDefinition_nonSystemAppTakingOverGroupDefinition_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions()

        assertWithMessage(
            "After $action is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system, non-system app $PACKAGE_NAME_0 shouldn't takeover" +
                " ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testPermissionGroupDefinition_takingOverGroupDeclaredBySystemApp_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(oldPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After $action is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system and is owned by a system app, app $PACKAGE_NAME_0" +
                " shouldn't takeover ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testPermissionDefinition_newPermission_getsDeclared() {
        mutateState {
            val packageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addPackageState(packageState, newState)
            testAction(packageState)
        }

        assertWithMessage(
            "After $action is called when there is no existing" +
                " permissions, the new permission $PERMISSION_NAME_0 is not added"
        )
            .that(getPermission(PERMISSION_NAME_0)?.name)
            .isEqualTo(PERMISSION_NAME_0)
    }

    @Test
    fun testPermissionDefinition_systemAppTakingOverPermissionDefinition_getsTakenOver() {
        testTakingOverPermissionAndPermissionGroupDefinitions(newPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After $action is called when $PERMISSION_NAME_0 already" +
                " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover ownership" +
                " of this permission"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testPermissionDefinition_nonSystemAppTakingOverPermissionDefinition_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions()

        assertWithMessage(
            "After $action is called when $PERMISSION_NAME_0 already" +
                " exists in the system, the non-system app $PACKAGE_NAME_0 shouldn't takeover" +
                " ownership of this permission"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testPermissionDefinition_takingOverPermissionDeclaredBySystemApp_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(oldPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After $action is called when $PERMISSION_NAME_0 already" +
                " exists in system and is owned by a system app, the $PACKAGE_NAME_0 shouldn't" +
                " takeover ownership of this permission"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    private fun testTakingOverPermissionAndPermissionGroupDefinitions(
        oldPermissionOwnerIsSystem: Boolean = false,
        newPermissionOwnerIsSystem: Boolean = false,
        newPermissionOwnerIsInstant: Boolean = false,
        permissionGroupAlreadyExists: Boolean = true,
        permissionAlreadyExists: Boolean = true,
        type: Int = Permission.TYPE_MANIFEST,
        isReconciled: Boolean = true,
    ) {
        val oldPermissionOwnerPackageState = mockPackageState(
            APP_ID_1,
            PACKAGE_NAME_1,
            isSystem = oldPermissionOwnerIsSystem
        )
        addPackageState(oldPermissionOwnerPackageState)
        if (permissionGroupAlreadyExists) {
            addPermissionGroup(mockParsedPermissionGroup(PERMISSION_GROUP_NAME_0, PACKAGE_NAME_1))
        }
        if (permissionAlreadyExists) {
            addPermission(
                mockParsedPermission(PERMISSION_NAME_0, PACKAGE_NAME_1),
                type = type,
                isReconciled = isReconciled
            )
        }

        mutateState {
            val newPermissionOwnerPackageState = mockPackageState(
                APP_ID_0,
                mockSimpleAndroidPackage(),
                isSystem = newPermissionOwnerIsSystem,
                isInstantApp = newPermissionOwnerIsInstant
            )
            addPackageState(newPermissionOwnerPackageState, newState)
            testAction(newPermissionOwnerPackageState)
        }
    }

    @Test
    fun testPermissionChanged_permissionGroupChanged_getsRevoked() {
        testPermissionChanged(
            oldPermissionGroup = PERMISSION_GROUP_NAME_1,
            newPermissionGroup = PERMISSION_GROUP_NAME_0
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that has a permission group change" +
                " for a permission it defines, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testPermissionChanged_protectionLevelChanged_getsRevoked() {
        testPermissionChanged(newProtectionLevel = PermissionInfo.PROTECTION_INTERNAL)

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that has a protection level change" +
                " for a permission it defines, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun testPermissionChanged(
        oldPermissionGroup: String? = null,
        newPermissionGroup: String? = null,
        newProtectionLevel: Int = PermissionInfo.PROTECTION_DANGEROUS
    ) {
        val oldPermission = mockParsedPermission(
            PERMISSION_NAME_0,
            PACKAGE_NAME_0,
            group = oldPermissionGroup,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        )
        val oldPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(oldPermission))
        )
        addPackageState(oldPackageState)
        addPermission(oldPermission)
        setPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            val newPermission = mockParsedPermission(
                PERMISSION_NAME_0,
                PACKAGE_NAME_0,
                group = newPermissionGroup,
                protectionLevel = newProtectionLevel
            )
            val newPackageState = mockPackageState(
                APP_ID_0,
                mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(newPermission))
            )
            addPackageState(newPackageState, newState)
            testAction(newPackageState)
        }
    }

    @Test
    fun testPermissionDeclaration_permissionTreeNoLongerDeclared_getsDefinitionRemoved() {
        testPermissionDeclaration {}

        assertWithMessage(
            "After $action is called for a package that no longer defines a permission" +
                " tree, the permission tree: $PERMISSION_NAME_0 in system state should be removed"
        )
            .that(getPermissionTree(PERMISSION_NAME_0))
            .isNull()
    }

    @Test
    fun testPermissionDeclaration_permissionTreeByDisabledSystemPackage_remainsUnchanged() {
        testPermissionDeclaration {
            val disabledSystemPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addDisabledSystemPackageState(disabledSystemPackageState)
        }

        assertWithMessage(
            "After $action is called for a package that no longer defines" +
                " a permission tree while this permission tree is still defined by" +
                " a disabled system package, the permission tree: $PERMISSION_NAME_0 in" +
                " system state should not be removed"
        )
            .that(getPermissionTree(PERMISSION_TREE_NAME))
            .isNotNull()
    }

    @Test
    fun testPermissionDeclaration_permissionNoLongerDeclared_getsDefinitionRemoved() {
        testPermissionDeclaration {}

        assertWithMessage(
            "After $action is called for a package that no longer defines a permission," +
                " the permission: $PERMISSION_NAME_0 in system state should be removed"
        )
            .that(getPermission(PERMISSION_NAME_0))
            .isNull()
    }

    @Test
    fun testPermissionDeclaration_permissionByDisabledSystemPackage_remainsUnchanged() {
        testPermissionDeclaration {
            val disabledSystemPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addDisabledSystemPackageState(disabledSystemPackageState)
        }

        assertWithMessage(
            "After $action is called for a disabled system package and it's updated apk" +
                " no longer defines a permission, the permission: $PERMISSION_NAME_0 in" +
                " system state should not be removed"
        )
            .that(getPermission(PERMISSION_NAME_0))
            .isNotNull()
    }

    private fun testPermissionDeclaration(additionalSetup: () -> Unit) {
        val oldPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
        addPackageState(oldPackageState)
        addPermission(defaultPermissionTree)
        addPermission(defaultPermission)

        additionalSetup()

        mutateState {
            val newPackageState = mockPackageState(APP_ID_0, mockAndroidPackage(PACKAGE_NAME_0))
            addPackageState(newPackageState, newState)
            testAction(newPackageState)
        }
    }

    @Test
    fun testTrimPermissionStates_permissionsNoLongerRequested_getsFlagsRevoked() {
        val parsedPermission = mockParsedPermission(
            PERMISSION_NAME_0,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        )
        val oldPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(
                PACKAGE_NAME_0,
                permissions = listOf(parsedPermission),
                requestedPermissions = setOf(PERMISSION_NAME_0)
            )
        )
        addPackageState(oldPackageState)
        addPermission(parsedPermission)
        setPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            val newPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addPackageState(newPackageState, newState)
            testAction(newPackageState)
        }

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that no longer requests a permission" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testRevokePermissionsOnPackageUpdate_storageAndMediaDowngradingPastQ_getsRuntimeRevoked() {
        testRevokePermissionsOnPackageUpdate(
            PermissionFlags.RUNTIME_GRANTED,
            newTargetSdkVersion = Build.VERSION_CODES.P
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package that's downgrading past Q" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testRevokePermissionsOnPackageUpdate_storageAndMediaNotDowngradingPastQ_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        testRevokePermissionsOnPackageUpdate(
            oldFlags,
            oldTargetSdkVersion = Build.VERSION_CODES.P,
            newTargetSdkVersion = Build.VERSION_CODES.P
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that's not downgrading past Q" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testRevokePermissionsOnPackageUpdate_policyFixedDowngradingPastQ_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED and PermissionFlags.POLICY_FIXED
        testRevokePermissionsOnPackageUpdate(oldFlags, newTargetSdkVersion = Build.VERSION_CODES.P)

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that's downgrading past Q" +
                " the actual permission flags with PermissionFlags.POLICY_FIXED $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testRevokePermissionsOnPackageUpdate_newlyRequestingLegacyExternalStorage_runtimeRevoked() {
        testRevokePermissionsOnPackageUpdate(
            PermissionFlags.RUNTIME_GRANTED,
            oldTargetSdkVersion = Build.VERSION_CODES.P,
            newTargetSdkVersion = Build.VERSION_CODES.P,
            oldIsRequestLegacyExternalStorage = false
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = 0
        assertWithMessage(
            "After $action is called for a package with" +
                " newlyRequestingLegacyExternalStorage, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testRevokePermissionsOnPackageUpdate_missingOldPackage_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        testRevokePermissionsOnPackageUpdate(
            oldFlags,
            newTargetSdkVersion = Build.VERSION_CODES.P,
            isOldPackageMissing = true
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After $action is called for a package that's downgrading past Q" +
                " and doesn't have the oldPackage, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun testRevokePermissionsOnPackageUpdate(
        oldFlags: Int,
        oldTargetSdkVersion: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        newTargetSdkVersion: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        oldIsRequestLegacyExternalStorage: Boolean = true,
        newIsRequestLegacyExternalStorage: Boolean = true,
        isOldPackageMissing: Boolean = false
    ) {
        val parsedPermission = mockParsedPermission(
            PERMISSION_READ_EXTERNAL_STORAGE,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        )
        val oldPackageState = if (isOldPackageMissing) {
            mockPackageState(APP_ID_0, PACKAGE_NAME_0)
        } else {
            mockPackageState(
                APP_ID_0,
                mockAndroidPackage(
                    PACKAGE_NAME_0,
                    targetSdkVersion = oldTargetSdkVersion,
                    isRequestLegacyExternalStorage = oldIsRequestLegacyExternalStorage,
                    requestedPermissions = setOf(PERMISSION_READ_EXTERNAL_STORAGE),
                    permissions = listOf(parsedPermission)
                )
            )
        }
        addPackageState(oldPackageState)
        addPermission(parsedPermission)
        setPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE, oldFlags)

        mutateState {
            val newPackageState = mockPackageState(
                APP_ID_0,
                mockAndroidPackage(
                    PACKAGE_NAME_0,
                    targetSdkVersion = newTargetSdkVersion,
                    isRequestLegacyExternalStorage = newIsRequestLegacyExternalStorage,
                    requestedPermissions = setOf(PERMISSION_READ_EXTERNAL_STORAGE),
                    permissions = listOf(parsedPermission)
                )
            )
            addPackageState(newPackageState, newState)
            testAction(newPackageState)
        }
    }

    @Test
    fun testEvaluatePermissionState_normalPermissionRequestedByInstalledPackage_getsGranted() {
        val oldFlags = PermissionFlags.INSTALL_REVOKED
        val permissionOwnerPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
        val installedPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_0))
        )
        addPackageState(permissionOwnerPackageState)
        addPackageState(installedPackageState)
        addPermission(defaultPermission)
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0, oldFlags)

        mutateState {
            testAction(installedPackageState)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a normal permission" +
                " with the INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags since it's a new install"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    /**
     * We set up a permission protection level change from SIGNATURE to NORMAL in order to make
     * the permission a "changed permission" in order to test evaluatePermissionState() called by
     * evaluatePermissionStateForAllPackages(). This makes the requestingPackageState not the
     * installedPackageState so that we can test whether requesting by system package will give us
     * the expected permission flags.
     *
     * Besides, this also helps us test evaluatePermissionStateForAllPackages(). Since both
     * evaluatePermissionStateForAllPackages() and evaluateAllPermissionStatesForPackage() call
     * evaluatePermissionState() in their implementations, we use these tests as the only tests
     * that test evaluatePermissionStateForAllPackages()
     */
    @Test
    fun testEvaluatePermissionState_normalPermissionRequestedBySystemPackage_getsGranted() {
        testEvaluateNormalPermissionStateWithPermissionChanges(requestingPackageIsSystem = true)

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After $action is called for a system package that requests a normal" +
                " permission with INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_normalCompatibilityPermission_getsGranted() {
        testEvaluateNormalPermissionStateWithPermissionChanges(
            permissionName = PERMISSION_POST_NOTIFICATIONS,
            requestingPackageTargetSdkVersion = Build.VERSION_CODES.S
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_POST_NOTIFICATIONS)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After $action is called for a package that requests a normal compatibility" +
                " permission with INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testEvaluatePermissionState_normalPermissionPreviouslyRevoked_getsInstallRevoked() {
        testEvaluateNormalPermissionStateWithPermissionChanges()

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_REVOKED
        assertWithMessage(
            "After $action is called for a package that requests a normal" +
                " permission with INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun testEvaluateNormalPermissionStateWithPermissionChanges(
        permissionName: String = PERMISSION_NAME_0,
        requestingPackageTargetSdkVersion: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        requestingPackageIsSystem: Boolean = false
    ) {
        val oldParsedPermission = mockParsedPermission(
            permissionName,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_SIGNATURE
        )
        val oldPermissionOwnerPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(oldParsedPermission))
        )
        val requestingPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(
                PACKAGE_NAME_1,
                requestedPermissions = setOf(permissionName),
                targetSdkVersion = requestingPackageTargetSdkVersion
            ),
            isSystem = requestingPackageIsSystem,
        )
        addPackageState(oldPermissionOwnerPackageState)
        addPackageState(requestingPackageState)
        addPermission(oldParsedPermission)
        val oldFlags = PermissionFlags.INSTALL_REVOKED
        setPermissionFlags(APP_ID_1, USER_ID_0, permissionName, oldFlags)

        mutateState {
            val newParsedPermission = mockParsedPermission(permissionName, PACKAGE_NAME_0)
            val newPermissionOwnerPackageState = mockPackageState(
                APP_ID_0,
                mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(newParsedPermission))
            )
            addPackageState(newPermissionOwnerPackageState, newState)
            testAction(newPermissionOwnerPackageState)
        }
    }

    private fun MutateStateScope.testAction(packageState: PackageState) {
        with(appIdPermissionPolicy) {
            when (action) {
                Action.ON_PACKAGE_ADDED -> onPackageAdded(packageState)
                Action.ON_STORAGE_VOLUME_ADDED -> onStorageVolumeMounted(
                    null,
                    listOf(packageState.packageName),
                    true
                )
            }
        }
    }

    enum class Action { ON_PACKAGE_ADDED, ON_STORAGE_VOLUME_ADDED }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Array<Action> = Action.values()
    }
}
