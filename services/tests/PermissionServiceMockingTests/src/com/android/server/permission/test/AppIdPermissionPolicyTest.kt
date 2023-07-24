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

import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.util.ArrayMap
import android.util.ArraySet
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
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.PackageUserState
import com.android.server.pm.pkg.component.ParsedPermission
import com.android.server.pm.pkg.component.ParsedPermissionGroup
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mocking unit test for AppIdPermissionPolicy.
 */
@RunWith(AndroidJUnit4::class)
class AppIdPermissionPolicyTest {
    private lateinit var oldState: MutableAccessState
    private lateinit var newState: MutableAccessState

    private lateinit var androidPackage0: AndroidPackage
    private lateinit var androidPackage1: AndroidPackage

    private lateinit var packageState0: PackageState
    private lateinit var packageState1: PackageState

    private val appIdPermissionPolicy = AppIdPermissionPolicy()

    @Rule
    @JvmField
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .spyStatic(PackageInfoUtils::class.java)
        .build()

    @Before
    fun init() {
        oldState = MutableAccessState()
        createUserState(USER_ID_0)
        createUserState(USER_ID_1)
        oldState.mutateExternalState().setPackageStates(ArrayMap())

        androidPackage0 = mockAndroidPackage(
            PACKAGE_NAME_0,
            PERMISSION_GROUP_NAME_0,
            PERMISSION_NAME_0
        )
        androidPackage1 = mockAndroidPackage(
            PACKAGE_NAME_1,
            PERMISSION_GROUP_NAME_1,
            PERMISSION_NAME_1
        )

        packageState0 = mockPackageState(APP_ID_0, androidPackage0)
        packageState1 = mockPackageState(APP_ID_1, androidPackage1)
    }

    private fun mockAndroidPackage(
        packageName: String,
        permissionGroupName: String,
        permissionName: String,
    ): AndroidPackage {
        val parsedPermissionGroup = mock<ParsedPermissionGroup> {
            whenever(name).thenReturn(permissionGroupName)
            whenever(metaData).thenReturn(Bundle())
        }

        @Suppress("DEPRECATION")
        val permissionGroupInfo = PermissionGroupInfo().apply {
            name = permissionGroupName
            this.packageName = packageName
        }
        wheneverStatic {
            PackageInfoUtils.generatePermissionGroupInfo(
                parsedPermissionGroup,
                PackageManager.GET_META_DATA.toLong()
            )
        }.thenReturn(permissionGroupInfo)

        val parsedPermission = mock<ParsedPermission> {
            whenever(name).thenReturn(permissionName)
            whenever(isTree).thenReturn(false)
            whenever(metaData).thenReturn(Bundle())
        }

        @Suppress("DEPRECATION")
        val permissionInfo = PermissionInfo().apply {
            name = permissionName
            this.packageName = packageName
        }
        wheneverStatic {
            PackageInfoUtils.generatePermissionInfo(
                parsedPermission,
                PackageManager.GET_META_DATA.toLong()
            )
        }.thenReturn(permissionInfo)

        val requestedPermissions = ArraySet<String>()
        return mock {
            whenever(this.packageName).thenReturn(packageName)
            whenever(this.requestedPermissions).thenReturn(requestedPermissions)
            whenever(permissionGroups).thenReturn(listOf(parsedPermissionGroup))
            whenever(permissions).thenReturn(listOf(parsedPermission))
            whenever(signingDetails).thenReturn(mock {})
        }
    }

    private fun mockPackageState(
        appId: Int,
        androidPackage: AndroidPackage,
    ): PackageState {
        val packageName = androidPackage.packageName
        oldState.mutateExternalState().mutateAppIdPackageNames().mutateOrPut(appId) {
            MutableIndexedListSet()
        }.add(packageName)

        val userStates = SparseArray<PackageUserState>().apply {
            put(USER_ID_0, mock { whenever(isInstantApp).thenReturn(false) })
        }
        val mockPackageState: PackageState = mock {
            whenever(this.packageName).thenReturn(packageName)
            whenever(this.appId).thenReturn(appId)
            whenever(this.androidPackage).thenReturn(androidPackage)
            whenever(isSystem).thenReturn(false)
            whenever(this.userStates).thenReturn(userStates)
        }
        oldState.mutateExternalState().setPackageStates(
            oldState.mutateExternalState().packageStates.toMutableMap().apply {
                put(packageName, mockPackageState)
            }
        )
        return mockPackageState
    }

    private fun createUserState(userId: Int) {
        oldState.mutateUserStatesNoWrite().put(userId, MutableUserState())
    }

    @Test
    fun testResetRuntimePermissions_runtimeGranted_getsRevoked() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        val expectedNewFlags = 0
        testResetRuntimePermissions(oldFlags, expectedNewFlags) {}
    }

    @Test
    fun testResetRuntimePermissions_roleGranted_getsGranted() {
        val oldFlags = PermissionFlags.ROLE
        val expectedNewFlags = PermissionFlags.ROLE or PermissionFlags.RUNTIME_GRANTED
        testResetRuntimePermissions(oldFlags, expectedNewFlags) {}
    }

    @Test
    fun testResetRuntimePermissions_nullAndroidPackage_remainsUnchanged() {
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        val expectedNewFlags = PermissionFlags.RUNTIME_GRANTED
        testResetRuntimePermissions(oldFlags, expectedNewFlags) {
            whenever(packageState0.androidPackage).thenReturn(null)
        }
    }

    private inline fun testResetRuntimePermissions(
        oldFlags: Int,
        expectedNewFlags: Int,
        additionalSetup: () -> Unit
    ) {
        createSystemStatePermission(
            APP_ID_0,
            PACKAGE_NAME_0,
            PERMISSION_NAME_0,
            PermissionInfo.PROTECTION_DANGEROUS
        )
        androidPackage0.requestedPermissions.add(PERMISSION_NAME_0)
        oldState.mutateUserState(USER_ID_0)!!.mutateAppIdPermissionFlags().mutateOrPut(APP_ID_0) {
            MutableIndexedMap()
        }.put(PERMISSION_NAME_0, oldFlags)

        additionalSetup()

        mutateState {
            with(appIdPermissionPolicy) {
                resetRuntimePermissions(PACKAGE_NAME_0, USER_ID_0)
            }
        }

        val actualFlags = getPermissionFlags(APP_ID_0, USER_ID_0, PERMISSION_NAME_0)
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
        testOnPackageAdded {
            adoptPermissionTestSetup()
            whenever(packageState1.androidPackage).thenReturn(null)
        }

        val permission0 = newState.systemState.permissions[PERMISSION_NAME_0]
        assertWithMessage(
            "After onPackageAdded() is called for a null adopt permission package," +
            " the permission package name: ${permission0!!.packageName} did not match" +
            " the expected package name: $PACKAGE_NAME_0"
        )
            .that(permission0.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_permissionsOfExistingSystemApp_notAdopted() {
        testOnPackageAdded {
            adoptPermissionTestSetup()
        }

        val permission0 = newState.systemState.permissions[PERMISSION_NAME_0]
        assertWithMessage(
            "After onPackageAdded() is called for a non-null adopt permission" +
            " package, the permission package name: ${permission0!!.packageName} should" +
            " not match the package name: $PACKAGE_NAME_0"
        )
            .that(permission0.packageName)
            .isNotEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_permissionsOfNonSystemApp_notAdopted() {
        testOnPackageAdded {
            adoptPermissionTestSetup()
            whenever(packageState1.isSystem).thenReturn(false)
        }

        val permission0 = newState.systemState.permissions[PERMISSION_NAME_0]
        assertWithMessage(
            "After onPackageAdded() is called for a non-system adopt permission" +
                " package, the permission package name: ${permission0!!.packageName} should" +
                " not match the package name: $PACKAGE_NAME_0"
        )
            .that(permission0.packageName)
            .isNotEqualTo(PACKAGE_NAME_0)
    }

    private fun adoptPermissionTestSetup() {
        createSystemStatePermission(
            APP_ID_1,
            PACKAGE_NAME_1,
            PERMISSION_NAME_0,
            PermissionInfo.PROTECTION_SIGNATURE
        )
        whenever(androidPackage0.adoptPermissions).thenReturn(listOf(PACKAGE_NAME_1))
        whenever(packageState1.isSystem).thenReturn(true)
    }

    @Test
    fun testOnPackageAdded_newPermissionGroup_getsDeclared() {
        testOnPackageAdded {}

        assertWithMessage(
            "After onPackageAdded() is called when there is no existing" +
            " permission groups, the new permission group $PERMISSION_GROUP_NAME_0 is not added"
        )
            .that(newState.systemState.permissionGroups[PERMISSION_GROUP_NAME_0]?.name)
            .isEqualTo(PERMISSION_GROUP_NAME_0)
    }

    @Test
    fun testOnPackageAdded_systemAppTakingOverPermissionGroupDefinition_getsTakenOver() {
        testOnPackageAdded {
            whenever(packageState0.isSystem).thenReturn(true)
            createSystemStatePermissionGroup(PACKAGE_NAME_1, PERMISSION_GROUP_NAME_0)
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
            " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover the ownership" +
            " of this permission group"
        )
            .that(newState.systemState.permissionGroups[PERMISSION_GROUP_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_instantApps_remainsUnchanged() {
        testOnPackageAdded {
            (packageState0.userStates as SparseArray<PackageUserState>).apply {
                put(0, mock { whenever(isInstantApp).thenReturn(true) })
            }
        }

        assertWithMessage(
            "After onPackageAdded() is called for an instant app," +
            " the new permission group $PERMISSION_GROUP_NAME_0 should not be added"
        )
            .that(newState.systemState.permissionGroups[PERMISSION_GROUP_NAME_0])
            .isNull()
    }

    @Test
    fun testOnPackageAdded_nonSystemAppTakingOverPermissionGroupDefinition_remainsUnchanged() {
        testOnPackageAdded {
            createSystemStatePermissionGroup(PACKAGE_NAME_1, PERMISSION_GROUP_NAME_0)
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
            " exists in the system, non-system app $PACKAGE_NAME_0 shouldn't takeover ownership" +
            " of this permission group"
        )
            .that(newState.systemState.permissionGroups[PERMISSION_GROUP_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_takingOverPermissionGroupDeclaredBySystemApp_remainsUnchanged() {
        testOnPackageAdded {
            whenever(packageState1.isSystem).thenReturn(true)
            createSystemStatePermissionGroup(PACKAGE_NAME_1, PERMISSION_GROUP_NAME_0)
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_GROUP_NAME_0 already" +
            " exists in the system and is owned by a system app, app $PACKAGE_NAME_0 shouldn't" +
            " takeover ownership of this permission group"
        )
            .that(newState.systemState.permissionGroups[PERMISSION_GROUP_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_newPermission_getsDeclared() {
        testOnPackageAdded {}

        assertWithMessage(
            "After onPackageAdded() is called when there is no existing" +
            " permissions, the new permission $PERMISSION_NAME_0 is not added"
        )
            .that(newState.systemState.permissions[PERMISSION_NAME_0]?.name)
            .isEqualTo(PERMISSION_NAME_0)
    }

    @Test
    fun testOnPackageAdded_configPermission_getsTakenOver() {
        testOnPackageAdded {
            whenever(packageState0.isSystem).thenReturn(true)
            createSystemStatePermission(
                APP_ID_0,
                PACKAGE_NAME_1,
                PERMISSION_NAME_0,
                PermissionInfo.PROTECTION_DANGEROUS,
                Permission.TYPE_CONFIG,
                false
            )
        }

        assertWithMessage(
            "After onPackageAdded() is called for a config permission with" +
            " no owner, the ownership is not taken over by a system app $PACKAGE_NAME_0"
        )
            .that(newState.systemState.permissions[PERMISSION_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_systemAppTakingOverPermissionDefinition_getsTakenOver() {
        testOnPackageAdded {
            whenever(packageState0.isSystem).thenReturn(true)
            createSystemStatePermission(
                APP_ID_1,
                PACKAGE_NAME_1,
                PERMISSION_NAME_0,
                PermissionInfo.PROTECTION_DANGEROUS
            )
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
            " exists in the system, the system app $PACKAGE_NAME_0 didn't takeover the ownership" +
            " of this permission"
        )
            .that(newState.systemState.permissions[PERMISSION_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_0)
    }

    @Test
    fun testOnPackageAdded_nonSystemAppTakingOverPermissionDefinition_remainsUnchanged() {
        testOnPackageAdded {
            createSystemStatePermission(
                APP_ID_1,
                PACKAGE_NAME_1,
                PERMISSION_NAME_0,
                PermissionInfo.PROTECTION_DANGEROUS
            )
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
            " exists in the system, the non-system app $PACKAGE_NAME_0 shouldn't takeover" +
            " ownership of this permission"
        )
            .that(newState.systemState.permissions[PERMISSION_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    @Test
    fun testOnPackageAdded_takingOverPermissionDeclaredBySystemApp_remainsUnchanged() {
        testOnPackageAdded {
            whenever(packageState1.isSystem).thenReturn(true)
            createSystemStatePermission(
                APP_ID_1,
                PACKAGE_NAME_1,
                PERMISSION_NAME_0,
                PermissionInfo.PROTECTION_DANGEROUS
            )
        }

        assertWithMessage(
            "After onPackageAdded() is called when $PERMISSION_NAME_0 already" +
            " exists in system and is owned by a system app, the app $PACKAGE_NAME_0 shouldn't" +
            " takeover ownership of this permission"
        )
            .that(newState.systemState.permissions[PERMISSION_NAME_0]?.packageName)
            .isEqualTo(PACKAGE_NAME_1)
    }

    private inline fun testOnPackageAdded(mockBehaviorOverride: () -> Unit) {
        mockBehaviorOverride()

        mutateState {
            with(appIdPermissionPolicy) {
                onPackageAdded(packageState0)
            }
        }
    }

    private inline fun mutateState(action: MutateStateScope.() -> Unit) {
        newState = oldState.toMutable()
        MutateStateScope(oldState, newState).action()
    }

    private fun createSystemStatePermission(
        appId: Int,
        packageName: String,
        permissionName: String,
        protectionLevel: Int,
        type: Int = Permission.TYPE_MANIFEST,
        isReconciled: Boolean = true,
        isTree: Boolean = false
    ) {
        @Suppress("DEPRECATION")
        val permissionInfo = PermissionInfo().apply {
            name = permissionName
            this.packageName = packageName
            this.protectionLevel = protectionLevel
        }
        val permission = Permission(permissionInfo, isReconciled, type, appId)
        if (isTree) {
            oldState.mutateSystemState().mutatePermissionTrees().put(permissionName, permission)
        } else {
            oldState.mutateSystemState().mutatePermissions().put(permissionName, permission)
        }
    }

    private fun createSystemStatePermissionGroup(packageName: String, permissionGroupName: String) {
        @Suppress("DEPRECATION")
        val permissionGroupInfo = PermissionGroupInfo().apply {
            name = permissionGroupName
            this.packageName = packageName
        }
        oldState.mutateSystemState().mutatePermissionGroups()[permissionGroupName] =
            permissionGroupInfo
    }

    fun getPermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        state: MutableAccessState = newState
    ): Int =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId).getWithDefault(permissionName, 0)

    companion object {
        private const val PACKAGE_NAME_0 = "packageName0"
        private const val PACKAGE_NAME_1 = "packageName1"

        private const val APP_ID_0 = 0
        private const val APP_ID_1 = 1

        private const val PERMISSION_NAME_0 = "permissionName0"
        private const val PERMISSION_NAME_1 = "permissionName1"

        private const val PERMISSION_GROUP_NAME_0 = "permissionGroupName0"
        private const val PERMISSION_GROUP_NAME_1 = "permissionGroupName1"

        private const val USER_ID_0 = 0
        private const val USER_ID_1 = 1
    }
}
