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

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.SigningDetails
import android.os.Build
import android.os.Bundle
import android.util.ArrayMap
import android.util.SparseArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableUserState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.permission.Permission
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.PackageUserState
import com.android.server.pm.pkg.component.ParsedPermission
import com.android.server.pm.pkg.component.ParsedPermissionGroup
import com.android.server.testutils.any
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong

/**
 * Mocking unit test for AppIdPermissionPolicy.
 */
@RunWith(AndroidJUnit4::class)
class AppIdPermissionPolicyTest {
    private lateinit var oldState: MutableAccessState
    private lateinit var newState: MutableAccessState

    private val defaultPermissionGroup = mockParsedPermissionGroup(
        PERMISSION_GROUP_NAME_0,
        PACKAGE_NAME_0
    )
    private val defaultPermissionTree = mockParsedPermission(
        PERMISSION_TREE_NAME,
        PACKAGE_NAME_0,
        isTree = true
    )
    private val defaultPermission = mockParsedPermission(PERMISSION_NAME_0, PACKAGE_NAME_0)

    private val appIdPermissionPolicy = AppIdPermissionPolicy()

    @Rule
    @JvmField
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .spyStatic(PackageInfoUtils::class.java)
        .build()

    @Before
    fun setUp() {
        oldState = MutableAccessState()
        createUserState(USER_ID_0)
        oldState.mutateExternalState().setPackageStates(ArrayMap())
        oldState.mutateExternalState().setDisabledSystemPackageStates(ArrayMap())
        mockPackageInfoUtilsGeneratePermissionInfo()
        mockPackageInfoUtilsGeneratePermissionGroupInfo()
    }

    private fun createUserState(userId: Int) {
        oldState.mutateExternalState().mutateUserIds().add(userId)
        oldState.mutateUserStatesNoWrite().put(userId, MutableUserState())
    }

    private fun mockPackageInfoUtilsGeneratePermissionInfo() {
        wheneverStatic {
            PackageInfoUtils.generatePermissionInfo(any(ParsedPermission::class.java), anyLong())
        }.thenAnswer { invocation ->
            val parsedPermission = invocation.getArgument<ParsedPermission>(0)
            val generateFlags = invocation.getArgument<Long>(1)
            PermissionInfo(parsedPermission.backgroundPermission).apply {
                name = parsedPermission.name
                packageName = parsedPermission.packageName
                metaData = if (generateFlags.toInt().hasBits(PackageManager.GET_META_DATA)) {
                    parsedPermission.metaData
                } else {
                    null
                }
                @Suppress("DEPRECATION")
                protectionLevel = parsedPermission.protectionLevel
                group = parsedPermission.group
                flags = parsedPermission.flags
            }
        }
    }

    private fun mockPackageInfoUtilsGeneratePermissionGroupInfo() {
        wheneverStatic {
            PackageInfoUtils.generatePermissionGroupInfo(
                any(ParsedPermissionGroup::class.java),
                anyLong()
            )
        }.thenAnswer { invocation ->
            val parsedPermissionGroup = invocation.getArgument<ParsedPermissionGroup>(0)
            val generateFlags = invocation.getArgument<Long>(1)
            @Suppress("DEPRECATION")
            PermissionGroupInfo().apply {
                name = parsedPermissionGroup.name
                packageName = parsedPermissionGroup.packageName
                metaData = if (generateFlags.toInt().hasBits(PackageManager.GET_META_DATA)) {
                    parsedPermissionGroup.metaData
                } else {
                    null
                }
                flags = parsedPermissionGroup.flags
            }
        }
    }

    @Test
    fun testResetRuntimePermissions_runtimeGranted_getsRevoked() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        val expectedNewFlags = 0
        testResetRuntimePermissions(oldFlags, expectedNewFlags)
    }

    @Test
    fun testResetRuntimePermissions_roleGranted_getsGranted() {
        val oldFlags = PermissionFlags.ROLE
        val expectedNewFlags = PermissionFlags.ROLE or PermissionFlags.RUNTIME_GRANTED
        testResetRuntimePermissions(oldFlags, expectedNewFlags)
    }

    @Test
    fun testResetRuntimePermissions_nullAndroidPackage_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        testResetRuntimePermissions(oldFlags, expectedNewFlags, isAndroidPackageMissing = true)
    }

    private fun testResetRuntimePermissions(
        oldFlags: Int,
        expectedNewFlags: Int,
        isAndroidPackageMissing: Boolean = false
    ) {
        val parsedPermission = mockParsedPermission(
            PERMISSION_NAME_0,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS,
        )
        val permissionOwnerPackageState = mockPackageState(
            APP_ID_0,
            mockAndroidPackage(PACKAGE_NAME_0, permissions = listOf(parsedPermission))
        )
        val requestingPackageState = if (isAndroidPackageMissing) {
            mockPackageState(APP_ID_1, PACKAGE_NAME_1)
        } else {
            mockPackageState(
                APP_ID_1,
                mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_0))
            )
        }
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0, oldFlags)
        addPackageState(permissionOwnerPackageState)
        addPackageState(requestingPackageState)
        addPermission(parsedPermission)

        mutateState {
            with(appIdPermissionPolicy) {
                resetRuntimePermissions(PACKAGE_NAME_1, USER_ID_0)
            }
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        assertWithMessage(
            "After resetting runtime permissions, permission flags did not match" +
                " expected values: expectedNewFlags is $expectedNewFlags," +
                " actualFlags is $actualFlags, while the oldFlags is $oldFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_permissionsOfMissingSystemApp_getsAdopted() {
        testAdoptPermissions(hasMissingPackage = true, isSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called for a null adopt permission package," +
                " the permission package name: ${getPermission(PERMISSION_NAME_0)?.packageName}" +
                " did not match the expected package name: $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_permissionsOfExistingSystemApp_notAdopted() {
        testAdoptPermissions(isSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called for a non-null adopt permission" +
                " package, the permission package name:" +
                " ${getPermission(PERMISSION_NAME_0)?.packageName} should not match the" +
                " package name: $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isNotEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_permissionsOfNonSystemApp_notAdopted() {
        testAdoptPermissions(hasMissingPackage = true)

        assertWithMessage(
            "After onPackageAdded() is called for a non-system adopt permission" +
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
            with(appIdPermissionPolicy) {
                onPackageAdded(installedPackage)
            }
        }
    }

    @Test
    fun testOnPackageAdded_newPermissionGroup_getsDeclared() {
        mutateState {
            val packageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addPackageState(packageState, newState)
            with(appIdPermissionPolicy) {
                onPackageAdded(packageState)
            }
        }

        assertWithMessage(
            "After onPackageAdded() is called when there is no existing" +
                " permission groups, the new permission group $PERMISSION_GROUP_NAME_0 is not added"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.name)
            .isEqualTo(PERMISSION_GROUP_NAME_0)
    }

    @Test
    fun testOnPackageAdded_systemAppTakingOverPermissionGroupDefinition_getsTakenOver() {
        testTakingOverPermissionAndPermissionGroupDefinitions(newPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover the" +
                " ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_instantApps_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(
            newPermissionOwnerIsInstant = true,
            permissionGroupAlreadyExists = false
        )

        assertWithMessage(
            "After onPackageAdded() is called for an instant app," +
                " the new permission group $PERMISSION_GROUP_NAME_0 should not be added"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0))
            .isNull()
    }

    @Test
    fun testOnPackageAdded_nonSystemAppTakingOverPermissionGroupDefinition_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions()

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system, non-system app $PACKAGE_NAME_0 shouldn't takeover" +
                " ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_takingOverPermissionGroupDeclaredBySystemApp_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(oldPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
                " exists in the system and is owned by a system app, app $PACKAGE_NAME_0" +
                " shouldn't takeover ownership of this permission group"
        )
            .that(getPermissionGroup(PERMISSION_GROUP_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_newPermission_getsDeclared() {
        mutateState {
            val packageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addPackageState(packageState, newState)
            with(appIdPermissionPolicy) {
                onPackageAdded(packageState)
            }
        }

        assertWithMessage(
            "After onPackageAdded() is called when there is no existing" +
                " permissions, the new permission $PERMISSION_NAME_0 is not added"
        )
            .that(getPermission(PERMISSION_NAME_0)?.name)
            .isEqualTo(PERMISSION_NAME_0)
    }

    @Test
    fun testOnPackageAdded_configPermission_getsTakenOver() {
        testTakingOverPermissionAndPermissionGroupDefinitions(
            oldPermissionOwnerIsSystem = true,
            newPermissionOwnerIsSystem = true,
            type = Permission.TYPE_CONFIG,
            isReconciled = false
        )

        assertWithMessage(
            "After onPackageAdded() is called for a config permission with" +
                " no owner, the ownership is not taken over by a system app $PACKAGE_NAME_0"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_systemAppTakingOverPermissionDefinition_getsTakenOver() {
        testTakingOverPermissionAndPermissionGroupDefinitions(newPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
                " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover ownership" +
                " of this permission"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_nonSystemAppTakingOverPermissionDefinition_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions()

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
                " exists in the system, the non-system app $PACKAGE_NAME_0 shouldn't takeover" +
                " ownership of this permission"
        )
            .that(getPermission(PERMISSION_NAME_0)?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_takingOverPermissionDeclaredBySystemApp_remainsUnchanged() {
        testTakingOverPermissionAndPermissionGroupDefinitions(oldPermissionOwnerIsSystem = true)

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
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
            with(appIdPermissionPolicy) {
                onPackageAdded(newPermissionOwnerPackageState)
            }
        }
    }

    @Test
    fun testOnPackageAdded_permissionGroupChanged_getsRevoked() {
        testPermissionChanged(
            oldPermissionGroup = PERMISSION_GROUP_NAME_1,
            newPermissionGroup = PERMISSION_GROUP_NAME_0
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that has a permission group change" +
                " for a permission it defines, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_protectionLevelChanged_getsRevoked() {
        testPermissionChanged(newProtectionLevel = PermissionInfo.PROTECTION_INTERNAL)

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that has a protection level change" +
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
            with(appIdPermissionPolicy) {
                onPackageAdded(newPackageState)
            }
        }
    }

    @Test
    fun testOnPackageAdded_permissionTreeNoLongerDeclared_getsDefinitionRemoved() {
        testPermissionDeclaration {}

        assertWithMessage(
            "After onPackageAdded() is called for a package that no longer defines a permission" +
                " tree, the permission tree: $PERMISSION_NAME_0 in system state should be removed"
        )
            .that(getPermissionTree(PERMISSION_NAME_0))
            .isNull()
    }

    @Test
    fun testOnPackageAdded_permissionTreeByDisabledSystemPackage_remainsUnchanged() {
        testPermissionDeclaration {
            val disabledSystemPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addDisabledSystemPackageState(disabledSystemPackageState)
        }

        assertWithMessage(
            "After onPackageAdded() is called for a package that no longer defines" +
                " a permission tree while this permission tree is still defined by" +
                " a disabled system package, the permission tree: $PERMISSION_NAME_0 in" +
                " system state should not be removed"
        )
            .that(getPermissionTree(PERMISSION_TREE_NAME))
            .isNotNull()
    }

    @Test
    fun testOnPackageAdded_permissionNoLongerDeclared_getsDefinitionRemoved() {
        testPermissionDeclaration {}

        assertWithMessage(
            "After onPackageAdded() is called for a package that no longer defines a permission," +
                " the permission: $PERMISSION_NAME_0 in system state should be removed"
        )
            .that(getPermission(PERMISSION_NAME_0))
            .isNull()
    }

    @Test
    fun testOnPackageAdded_permissionByDisabledSystemPackage_remainsUnchanged() {
        testPermissionDeclaration {
            val disabledSystemPackageState = mockPackageState(APP_ID_0, mockSimpleAndroidPackage())
            addDisabledSystemPackageState(disabledSystemPackageState)
        }

        assertWithMessage(
            "After onPackageAdded() is called for a disabled system package and it's updated apk" +
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
            with(appIdPermissionPolicy) {
                onPackageAdded(newPackageState)
            }
        }
    }

    @Test
    fun testOnPackageAdded_permissionsNoLongerRequested_getsFlagsRevoked() {
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
            with(appIdPermissionPolicy) {
                onPackageAdded(newPackageState)
            }
        }

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that no longer requests a permission" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_storageAndMediaPermissionsDowngradingPastQ_getsRuntimeRevoked() {
        testRevokePermissionsOnPackageUpdate(
            PermissionFlags.RUNTIME_GRANTED,
            newTargetSdkVersion = Build.VERSION_CODES.P
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that's downgrading past Q" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_storageAndMediaPermissionsNotDowngradingPastQ_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        testRevokePermissionsOnPackageUpdate(
            oldFlags,
            oldTargetSdkVersion = Build.VERSION_CODES.P,
            newTargetSdkVersion = Build.VERSION_CODES.P
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that's not downgrading past Q" +
                " the actual permission flags $actualFlags should match the" +
                " expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_policyFixedDowngradingPastQ_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED and PermissionFlags.POLICY_FIXED
        testRevokePermissionsOnPackageUpdate(oldFlags, newTargetSdkVersion = Build.VERSION_CODES.P)

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that's downgrading past Q" +
                " the actual permission flags with PermissionFlags.POLICY_FIXED $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_newlyRequestingLegacyExternalStorage_getsRuntimeRevoked() {
        testRevokePermissionsOnPackageUpdate(
            PermissionFlags.RUNTIME_GRANTED,
            oldTargetSdkVersion = Build.VERSION_CODES.P,
            newTargetSdkVersion = Build.VERSION_CODES.P,
            oldIsRequestLegacyExternalStorage = false
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package with" +
                " newlyRequestingLegacyExternalStorage, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_missingOldPackage_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        testRevokePermissionsOnPackageUpdate(
            oldFlags,
            newTargetSdkVersion = Build.VERSION_CODES.P,
            isOldPackageMissing = true
        )

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_READ_EXTERNAL_STORAGE)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that's downgrading past Q" +
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
            with(appIdPermissionPolicy) {
                onPackageAdded(newPackageState)
            }
        }
    }

    @Test
    fun testOnPackageAdded_normalPermissionAlreadyGranted_remainsUnchanged() {
        val oldFlags = PermissionFlags.INSTALL_GRANTED or PermissionFlags.INSTALL_REVOKED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_NORMAL) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal permission" +
                " with an existing INSTALL_GRANTED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_normalPermissionNotInstallRevoked_getsGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_NORMAL,
            isNewInstall = true
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal permission" +
                " with no existing flags, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_normalPermissionRequestedByInstalledPackage_getsGranted() {
        val oldFlags = PermissionFlags.INSTALL_REVOKED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_NORMAL) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal permission" +
                " with the INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags since it's a new install"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    /**
     * We setup a permission protection level change from SIGNATURE to NORMAL in order to make
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
    fun testOnPackageAdded_normalPermissionRequestedBySystemPackage_getsGranted() {
        testEvaluateNormalPermissionStateWithPermissionChanges(requestingPackageIsSystem = true)

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a system package that requests a normal" +
                " permission with INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_normalCompatibilityPermission_getsGranted() {
        testEvaluateNormalPermissionStateWithPermissionChanges(
            permissionName = PERMISSION_POST_NOTIFICATIONS,
            requestingPackageTargetSdkVersion = Build.VERSION_CODES.S
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_POST_NOTIFICATIONS)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal compatibility" +
                " permission with INSTALL_REVOKED flag, the actual permission flags $actualFlags" +
                " should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_normalPermissionPreviouslyRevoked_getsInstallRevoked() {
        testEvaluateNormalPermissionStateWithPermissionChanges()

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_REVOKED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal" +
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
            val newPermissionOwnerPackageState = mockPackageState(
                APP_ID_0,
                mockAndroidPackage(
                    PACKAGE_NAME_0,
                    permissions = listOf(mockParsedPermission(permissionName, PACKAGE_NAME_0))
                )
            )
            addPackageState(newPermissionOwnerPackageState, newState)
            with(appIdPermissionPolicy) {
                onPackageAdded(newPermissionOwnerPackageState)
            }
        }
    }

    @Test
    fun testOnPackageAdded_normalAppOpPermission_getsRoleAndUserSetFlagsPreserved() {
        val oldFlags = PermissionFlags.ROLE or PermissionFlags.USER_SET
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_NORMAL or PermissionInfo.PROTECTION_FLAG_APPOP
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED or oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a normal app op" +
                " permission with existing ROLE and USER_SET flags, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_internalPermissionWasGrantedWithMissingPackage_getsProtectionGranted() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_INTERNAL) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_internalAppOpPermission_getsRoleAndUserSetFlagsPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.ROLE or
            PermissionFlags.USER_SET
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_APPOP
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and the permission isAppOp," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_internalDevelopmentPermission_getsRuntimeGrantedPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_DEVELOPMENT
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and permission isDevelopment," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_internalRolePermission_getsRoleAndRuntimeGrantedPreserved() {
        val oldFlags = PermissionFlags.PROTECTION_GRANTED or PermissionFlags.ROLE or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_INTERNAL or PermissionInfo.PROTECTION_FLAG_ROLE
        ) {
            val packageStateWithMissingPackage = mockPackageState(APP_ID_1, MISSING_ANDROID_PACKAGE)
            addPackageState(packageStateWithMissingPackage)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests an internal permission" +
                " with missing android package and $oldFlags flag and the permission isRole," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_signaturePrivilegedPermissionNotAllowlisted_isNotGranted() {
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

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a signature privileged" +
                " permission that's not allowlisted, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_nonPrivilegedPermissionShouldGrantBySignature_getsProtectionGranted() {
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

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PROTECTION_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a signature" +
                " non-privileged permission, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_privilegedAllowlistPermissionShouldGrantByProtectionFlags_getsGranted() {
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

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PROTECTION_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a signature privileged" +
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
    fun testOnPackageAdded_nonRuntimeFlagsOnRuntimePermissions_getsCleared() {
        val oldFlags = PermissionFlags.INSTALL_GRANTED or PermissionFlags.PREGRANT or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.PREGRANT or PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " with existing $oldFlags flags, the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_newPermissionsForPreM_requiresUserReview() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            installedPackageTargetSdkVersion = Build.VERSION_CODES.LOLLIPOP,
            isNewInstall = true
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " with no existing flags in pre M, actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_legacyOrImplicitGrantedPermissionPreviouslyRevoked_getsAppOpRevoked() {
        val oldFlags = PermissionFlags.USER_FIXED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            installedPackageTargetSdkVersion = Build.VERSION_CODES.LOLLIPOP
        ) {
            setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0, oldFlags)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.USER_FIXED or
            PermissionFlags.APP_OP_REVOKED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that should be LEGACY_GRANTED or IMPLICIT_GRANTED that was previously revoked," +
                " the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_legacyGrantedPermissionsForPostM_userReviewRequirementRemoved() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.IMPLICIT
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that used to require user review, the user review requirement should be removed" +
                " if it's upgraded to post M. The actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_legacyGrantedPermissionsAlreadyReviewedForPostM_getsGranted() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that was already reviewed by the user, the permission should be RUNTIME_GRANTED" +
                " if it's upgraded to post M. The actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_leanbackNotificationPermissionsForPostM_getsImplicitGranted() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionName = PERMISSION_POST_NOTIFICATIONS,
            isNewInstall = true
        ) {
            oldState.mutateExternalState().setLeanback(true)
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_POST_NOTIFICATIONS)
        val expectedNewFlags = PermissionFlags.IMPLICIT_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime notification" +
                " permission when isLeanback, the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_implicitSourceFromNonRuntime_getsImplicitGranted() {
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

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.IMPLICIT_GRANTED or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime implicit" +
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
    fun testOnPackageAdded_noLongerLegacyOrImplicitGranted_canBeRequested() {
        val oldFlags = PermissionFlags.LEGACY_GRANTED or PermissionFlags.APP_OP_REVOKED or
            PermissionFlags.RUNTIME_GRANTED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that is no longer LEGACY_GRANTED or IMPLICIT_GRANTED, the actual permission" +
                " flags $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_noLongerImplicitPermissions_getsRuntimeAndImplicitFlagsRemoved() {
        val oldFlags = PermissionFlags.IMPLICIT or PermissionFlags.RUNTIME_GRANTED or
            PermissionFlags.USER_SET or PermissionFlags.USER_FIXED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = 0
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that is no longer implicit and we shouldn't retain as nearby device" +
                " permissions, the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_noLongerImplicitNearbyPermissionsWasGranted_getsRuntimeGranted() {
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
                USER_ID_0,
                PERMISSION_ACCESS_BACKGROUND_LOCATION,
                PermissionFlags.RUNTIME_GRANTED
            )
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_BLUETOOTH_CONNECT)
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime nearby device" +
                " permission that was granted by implicit, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_noLongerImplicitSystemOrPolicyFixedWasGranted_getsRuntimeGranted() {
        val oldFlags = PermissionFlags.IMPLICIT_GRANTED or PermissionFlags.IMPLICIT or
            PermissionFlags.SYSTEM_FIXED
        testEvaluatePermissionState(oldFlags, PermissionInfo.PROTECTION_DANGEROUS) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.SYSTEM_FIXED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime permission" +
                " that was granted and is no longer implicit and is SYSTEM_FIXED or POLICY_FIXED," +
                " the actual permission flags $actualFlags should match the expected" +
                " flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_restrictedPermissionsNotExempt_getsRestrictionFlags() {
        val oldFlags = PermissionFlags.RESTRICTION_REVOKED
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionInfoFlags = PermissionInfo.FLAG_HARD_RESTRICTED
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldFlags
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime hard" +
                " restricted permission that is not exempted, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_restrictedPermissionsIsExempted_clearsRestrictionFlags() {
        val oldFlags = 0
        testEvaluatePermissionState(
            oldFlags,
            PermissionInfo.PROTECTION_DANGEROUS,
            permissionInfoFlags = PermissionInfo.FLAG_SOFT_RESTRICTED
        ) {}

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.UPGRADE_EXEMPT
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a runtime soft" +
                " restricted permission that is exempted, the actual permission flags" +
                " $actualFlags should match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_runtimeExistingImplicitPermissions_sourceFlagsNotInherited() {
        val oldImplicitPermissionFlags = PermissionFlags.USER_FIXED
        testInheritImplicitPermissionStates(
            implicitPermissionFlags = oldImplicitPermissionFlags,
            isNewInstallAndNewPermission = false
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = oldImplicitPermissionFlags or PermissionFlags.IMPLICIT_GRANTED or
            PermissionFlags.APP_OP_REVOKED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a permission that is" +
                " implicit, existing and runtime, it should not inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_nonRuntimeNewImplicitPermissions_sourceFlagsNotInherited() {
        testInheritImplicitPermissionStates(
            implicitPermissionProtectionLevel = PermissionInfo.PROTECTION_NORMAL
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = PermissionFlags.INSTALL_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a permission that is" +
                " implicit, new and non-runtime, it should not inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_runtimeNewImplicitPermissions_sourceFlagsInherited() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(sourceRuntimeFlags = sourceRuntimeFlags)

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT_GRANTED or
            PermissionFlags.IMPLICIT
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a permission that is" +
                " implicit, new and runtime, it should inherit the runtime flags from" +
                " the source permission. Hence the actual permission flags $actualFlags should" +
                " match the expected flags $expectedNewFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnPackageAdded_grantingNewFromRevokeImplicitPermissions_onlySourceFlagsInherited() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(
            implicitPermissionFlags = PermissionFlags.POLICY_FIXED,
            sourceRuntimeFlags = sourceRuntimeFlags,
            isAnySourcePermissionNonRuntime = false
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a permission that is" +
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
    fun testOnPackageAdded_mediaImplicitPermissions_getsImplicitFlagRemoved() {
        val sourceRuntimeFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        testInheritImplicitPermissionStates(
            implicitPermissionName = PERMISSION_ACCESS_MEDIA_LOCATION,
            sourceRuntimeFlags = sourceRuntimeFlags
        )

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_ACCESS_MEDIA_LOCATION)
        val expectedNewFlags = sourceRuntimeFlags or PermissionFlags.IMPLICIT_GRANTED
        assertWithMessage(
            "After onPackageAdded() is called for a package that requests a media permission that" +
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
            setPermissionFlags(APP_ID_1, USER_ID_0, implicitPermissionName, implicitPermissionFlags)
        }
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_2, sourceRuntimeFlags)

        mutateState {
            if (isNewInstallAndNewPermission) {
                addPackageState(installedPackageState)
                setPermissionFlags(
                    APP_ID_1,
                    USER_ID_0,
                    implicitPermissionName,
                    implicitPermissionFlags,
                    newState
                )
            }
            with(appIdPermissionPolicy) {
                onPackageAdded(installedPackageState)
            }
        }
    }

    /**
     * Setup simple package states for testing evaluatePermissionState().
     * permissionOwnerPackageState is definer of permissionName with APP_ID_0.
     * installedPackageState is the installed package that requests permissionName with APP_ID_1.
     *
     * @param oldFlags the existing permission flags for APP_ID_1, USER_ID_0, permissionName
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
    fun testEvaluatePermissionState(
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
            setPermissionFlags(APP_ID_1, USER_ID_0, permissionName, oldFlags)
        }
        addPermission(parsedPermission)

        additionalSetup()

        mutateState {
            if (isNewInstall) {
                addPackageState(installedPackageState, newState)
                setPermissionFlags(APP_ID_1, USER_ID_0, permissionName, oldFlags, newState)
            }
            with(appIdPermissionPolicy) {
                onPackageAdded(installedPackageState)
            }
        }
    }

    /**
     * Mock an AndroidPackage with PACKAGE_NAME_0, PERMISSION_NAME_0 and PERMISSION_GROUP_NAME_0
     */
    private fun mockSimpleAndroidPackage(): AndroidPackage =
        mockAndroidPackage(
            PACKAGE_NAME_0,
            permissionGroups = listOf(defaultPermissionGroup),
            permissions = listOf(defaultPermissionTree, defaultPermission)
        )

    private inline fun mutateState(action: MutateStateScope.() -> Unit) {
        newState = oldState.toMutable()
        MutateStateScope(oldState, newState).action()
    }

    private fun mockPackageState(
        appId: Int,
        packageName: String,
        isSystem: Boolean = false,
    ): PackageState =
        mock {
            whenever(this.appId).thenReturn(appId)
            whenever(this.packageName).thenReturn(packageName)
            whenever(androidPackage).thenReturn(null)
            whenever(this.isSystem).thenReturn(isSystem)
        }

    private fun mockPackageState(
        appId: Int,
        androidPackage: AndroidPackage,
        isSystem: Boolean = false,
        isPrivileged: Boolean = false,
        isProduct: Boolean = false,
        isInstantApp: Boolean = false,
        isVendor: Boolean = false
    ): PackageState =
        mock {
            whenever(this.appId).thenReturn(appId)
            whenever(this.androidPackage).thenReturn(androidPackage)
            val packageName = androidPackage.packageName
            whenever(this.packageName).thenReturn(packageName)
            whenever(this.isSystem).thenReturn(isSystem)
            whenever(this.isPrivileged).thenReturn(isPrivileged)
            whenever(this.isProduct).thenReturn(isProduct)
            whenever(this.isVendor).thenReturn(isVendor)
            val userStates = SparseArray<PackageUserState>().apply {
                put(USER_ID_0, mock { whenever(this.isInstantApp).thenReturn(isInstantApp) })
            }
            whenever(this.userStates).thenReturn(userStates)
        }

    private fun mockAndroidPackage(
        packageName: String,
        targetSdkVersion: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        isRequestLegacyExternalStorage: Boolean = false,
        adoptPermissions: List<String> = emptyList(),
        implicitPermissions: Set<String> = emptySet(),
        requestedPermissions: Set<String> = emptySet(),
        permissionGroups: List<ParsedPermissionGroup> = emptyList(),
        permissions: List<ParsedPermission> = emptyList(),
        isSignatureMatching: Boolean = false
    ): AndroidPackage =
        mock {
            whenever(this.packageName).thenReturn(packageName)
            whenever(this.targetSdkVersion).thenReturn(targetSdkVersion)
            whenever(this.isRequestLegacyExternalStorage).thenReturn(isRequestLegacyExternalStorage)
            whenever(this.adoptPermissions).thenReturn(adoptPermissions)
            whenever(this.implicitPermissions).thenReturn(implicitPermissions)
            whenever(this.requestedPermissions).thenReturn(requestedPermissions)
            whenever(this.permissionGroups).thenReturn(permissionGroups)
            whenever(this.permissions).thenReturn(permissions)
            val signingDetails = mock<SigningDetails> {
                whenever(
                    hasCommonSignerWithCapability(any(), any())
                ).thenReturn(isSignatureMatching)
                whenever(hasAncestorOrSelf(any())).thenReturn(isSignatureMatching)
                whenever(
                    checkCapability(any<SigningDetails>(), any())
                ).thenReturn(isSignatureMatching)
            }
            whenever(this.signingDetails).thenReturn(signingDetails)
        }

    private fun mockParsedPermission(
        permissionName: String,
        packageName: String,
        backgroundPermission: String? = null,
        group: String? = null,
        protectionLevel: Int = PermissionInfo.PROTECTION_NORMAL,
        flags: Int = 0,
        isTree: Boolean = false
    ): ParsedPermission =
        mock {
            whenever(name).thenReturn(permissionName)
            whenever(this.packageName).thenReturn(packageName)
            whenever(metaData).thenReturn(Bundle())
            whenever(this.backgroundPermission).thenReturn(backgroundPermission)
            whenever(this.group).thenReturn(group)
            whenever(this.protectionLevel).thenReturn(protectionLevel)
            whenever(this.flags).thenReturn(flags)
            whenever(this.isTree).thenReturn(isTree)
        }

    private fun mockParsedPermissionGroup(
        permissionGroupName: String,
        packageName: String,
    ): ParsedPermissionGroup =
        mock {
            whenever(name).thenReturn(permissionGroupName)
            whenever(this.packageName).thenReturn(packageName)
            whenever(metaData).thenReturn(Bundle())
        }

    private fun addPackageState(packageState: PackageState, state: MutableAccessState = oldState) {
        state.mutateExternalState().apply {
            setPackageStates(
                packageStates.toMutableMap().apply {
                    put(packageState.packageName, packageState)
                }
            )
            mutateAppIdPackageNames().mutateOrPut(packageState.appId) { MutableIndexedListSet() }
                .add(packageState.packageName)
        }
    }

    private fun addDisabledSystemPackageState(
        packageState: PackageState,
        state: MutableAccessState = oldState
    ) = state.mutateExternalState().apply {
        (disabledSystemPackageStates as ArrayMap)[packageState.packageName] = packageState
    }

    private fun addPermission(
        parsedPermission: ParsedPermission,
        type: Int = Permission.TYPE_MANIFEST,
        isReconciled: Boolean = true,
        state: MutableAccessState = oldState
    ) {
        val permissionInfo = PackageInfoUtils.generatePermissionInfo(
            parsedPermission,
            PackageManager.GET_META_DATA.toLong()
        )!!
        val appId = state.externalState.packageStates[permissionInfo.packageName]!!.appId
        val permission = Permission(permissionInfo, isReconciled, type, appId)
        if (parsedPermission.isTree) {
            state.mutateSystemState().mutatePermissionTrees()[permission.name] = permission
        } else {
            state.mutateSystemState().mutatePermissions()[permission.name] = permission
        }
    }

    private fun addPermissionGroup(
        parsedPermissionGroup: ParsedPermissionGroup,
        state: MutableAccessState = oldState
    ) {
        state.mutateSystemState().mutatePermissionGroups()[parsedPermissionGroup.name] =
            PackageInfoUtils.generatePermissionGroupInfo(
                parsedPermissionGroup,
                PackageManager.GET_META_DATA.toLong()
            )!!
    }

    private fun getPermission(
        permissionName: String,
        state: MutableAccessState = newState
    ): Permission? = state.systemState.permissions[permissionName]

    private fun getPermissionTree(
        permissionTreeName: String,
        state: MutableAccessState = newState
    ): Permission? = state.systemState.permissionTrees[permissionTreeName]

    private fun getPermissionGroup(
        permissionGroupName: String,
        state: MutableAccessState = newState
    ): PermissionGroupInfo? = state.systemState.permissionGroups[permissionGroupName]

    private fun getPermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        state: MutableAccessState = newState
    ): Int =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId).getWithDefault(permissionName, 0)

    private fun setPermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        flags: Int,
        state: MutableAccessState = oldState
    ) =
        state.mutateUserState(userId)!!.mutateAppIdPermissionFlags().mutateOrPut(appId) {
            MutableIndexedMap()
        }.put(permissionName, flags)

    companion object {
        private const val PACKAGE_NAME_0 = "packageName0"
        private const val PACKAGE_NAME_1 = "packageName1"
        private const val MISSING_ANDROID_PACKAGE = "missingAndroidPackage"
        private const val PLATFORM_PACKAGE_NAME = "android"

        private const val APP_ID_0 = 0
        private const val APP_ID_1 = 1
        private const val PLATFORM_APP_ID = 2

        private const val PERMISSION_GROUP_NAME_0 = "permissionGroupName0"
        private const val PERMISSION_GROUP_NAME_1 = "permissionGroupName1"

        private const val PERMISSION_TREE_NAME = "permissionTree"

        private const val PERMISSION_NAME_0 = "permissionName0"
        private const val PERMISSION_NAME_1 = "permissionName1"
        private const val PERMISSION_NAME_2 = "permissionName2"
        private const val PERMISSION_READ_EXTERNAL_STORAGE =
            Manifest.permission.READ_EXTERNAL_STORAGE
        private const val PERMISSION_POST_NOTIFICATIONS =
            Manifest.permission.POST_NOTIFICATIONS
        private const val PERMISSION_BLUETOOTH_CONNECT =
            Manifest.permission.BLUETOOTH_CONNECT
        private const val PERMISSION_ACCESS_BACKGROUND_LOCATION =
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        private const val PERMISSION_ACCESS_MEDIA_LOCATION =
            Manifest.permission.ACCESS_MEDIA_LOCATION

        private const val USER_ID_0 = 0
    }
}
