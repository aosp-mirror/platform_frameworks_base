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
import com.android.internal.pm.pkg.component.ParsedPermission
import com.android.internal.pm.pkg.component.ParsedPermissionGroup
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableUserState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.permission.Permission
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.PackageUserState
import com.android.server.testutils.any
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong

/**
 * Mocking unit test for AppIdPermissionPolicy.
 */
@RunWith(AndroidJUnit4::class)
abstract class BaseAppIdPermissionPolicyTest {
    protected lateinit var oldState: MutableAccessState
    protected lateinit var newState: MutableAccessState

    protected val defaultPermissionGroup = mockParsedPermissionGroup(
        PERMISSION_GROUP_NAME_0,
        PACKAGE_NAME_0
    )
    protected val defaultPermissionTree = mockParsedPermission(
        PERMISSION_TREE_NAME,
        PACKAGE_NAME_0,
        isTree = true
    )
    protected val defaultPermission = mockParsedPermission(PERMISSION_NAME_0, PACKAGE_NAME_0)

    protected val appIdPermissionPolicy = AppIdPermissionPolicy()

    @Rule
    @JvmField
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .spyStatic(PackageInfoUtils::class.java)
        .build()

    @Before
    fun baseSetUp() {
        oldState = MutableAccessState()
        createUserState(USER_ID_0)
        oldState.mutateExternalState().setPackageStates(ArrayMap())
        oldState.mutateExternalState().setDisabledSystemPackageStates(ArrayMap())
        mockPackageInfoUtilsGeneratePermissionInfo()
        mockPackageInfoUtilsGeneratePermissionGroupInfo()
    }

    protected fun createUserState(userId: Int) {
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

    /**
     * Mock an AndroidPackage with PACKAGE_NAME_0, PERMISSION_NAME_0 and PERMISSION_GROUP_NAME_0
     */
    protected fun mockSimpleAndroidPackage(): AndroidPackage =
        mockAndroidPackage(
            PACKAGE_NAME_0,
            permissionGroups = listOf(defaultPermissionGroup),
            permissions = listOf(defaultPermissionTree, defaultPermission)
        )

    protected fun createSimplePermission(isTree: Boolean = false): Permission {
        val parsedPermission = if (isTree) { defaultPermissionTree } else { defaultPermission }
        val permissionInfo = PackageInfoUtils.generatePermissionInfo(
            parsedPermission,
            PackageManager.GET_META_DATA.toLong()
        )!!
        return Permission(permissionInfo, true, Permission.TYPE_MANIFEST, APP_ID_0)
    }

    protected inline fun mutateState(action: MutateStateScope.() -> Unit) {
        newState = oldState.toMutable()
        MutateStateScope(oldState, newState).action()
    }

    protected fun mockPackageState(
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

    protected fun mockPackageState(
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

    protected fun mockAndroidPackage(
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

    protected fun mockParsedPermission(
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

    protected fun mockParsedPermissionGroup(
        permissionGroupName: String,
        packageName: String,
    ): ParsedPermissionGroup =
        mock {
            whenever(name).thenReturn(permissionGroupName)
            whenever(this.packageName).thenReturn(packageName)
            whenever(metaData).thenReturn(Bundle())
        }

    protected fun addPackageState(
        packageState: PackageState,
        state: MutableAccessState = oldState
    ) {
        state.mutateExternalState().apply {
            setPackageStates(
                packageStates.toMutableMap().apply { put(packageState.packageName, packageState) }
            )
            mutateAppIdPackageNames().mutateOrPut(packageState.appId) { MutableIndexedListSet() }
                .add(packageState.packageName)
        }
    }

    protected fun removePackageState(
        packageState: PackageState,
        state: MutableAccessState = oldState
    ) {
        state.mutateExternalState().apply {
            setPackageStates(
                packageStates.toMutableMap().apply { remove(packageState.packageName) }
            )
            mutateAppIdPackageNames().mutateOrPut(packageState.appId) { MutableIndexedListSet() }
                .remove(packageState.packageName)
        }
    }

    protected fun addDisabledSystemPackageState(
        packageState: PackageState,
        state: MutableAccessState = oldState
    ) = state.mutateExternalState().apply {
        (disabledSystemPackageStates as ArrayMap)[packageState.packageName] = packageState
    }

    protected fun addPermission(
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

    protected fun addPermissionGroup(
        parsedPermissionGroup: ParsedPermissionGroup,
        state: MutableAccessState = oldState
    ) {
        state.mutateSystemState().mutatePermissionGroups()[parsedPermissionGroup.name] =
            PackageInfoUtils.generatePermissionGroupInfo(
                parsedPermissionGroup,
                PackageManager.GET_META_DATA.toLong()
            )!!
    }

    protected fun getPermission(
        permissionName: String,
        state: MutableAccessState = newState
    ): Permission? = state.systemState.permissions[permissionName]

    protected fun getPermissionTree(
        permissionTreeName: String,
        state: MutableAccessState = newState
    ): Permission? = state.systemState.permissionTrees[permissionTreeName]

    protected fun getPermissionGroup(
        permissionGroupName: String,
        state: MutableAccessState = newState
    ): PermissionGroupInfo? = state.systemState.permissionGroups[permissionGroupName]

    protected fun getPermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        state: MutableAccessState = newState
    ): Int =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId).getWithDefault(permissionName, 0)

    protected fun setPermissionFlags(
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
        @JvmStatic protected val PACKAGE_NAME_0 = "packageName0"
        @JvmStatic protected val PACKAGE_NAME_1 = "packageName1"
        @JvmStatic protected val PACKAGE_NAME_2 = "packageName2"
        @JvmStatic protected val MISSING_ANDROID_PACKAGE = "missingAndroidPackage"
        @JvmStatic protected val PLATFORM_PACKAGE_NAME = "android"

        @JvmStatic protected val APP_ID_0 = 0
        @JvmStatic protected val APP_ID_1 = 1
        @JvmStatic protected val PLATFORM_APP_ID = 2

        @JvmStatic protected val PERMISSION_GROUP_NAME_0 = "permissionGroupName0"
        @JvmStatic protected val PERMISSION_GROUP_NAME_1 = "permissionGroupName1"

        @JvmStatic protected val PERMISSION_TREE_NAME = "permissionTree"

        @JvmStatic protected val PERMISSION_NAME_0 = "permissionName0"
        @JvmStatic protected val PERMISSION_NAME_1 = "permissionName1"
        @JvmStatic protected val PERMISSION_NAME_2 = "permissionName2"
        @JvmStatic protected val PERMISSION_BELONGS_TO_A_TREE = "permissionTree.permission"
        @JvmStatic protected val PERMISSION_READ_EXTERNAL_STORAGE =
            Manifest.permission.READ_EXTERNAL_STORAGE
        @JvmStatic protected val PERMISSION_POST_NOTIFICATIONS =
            Manifest.permission.POST_NOTIFICATIONS
        @JvmStatic protected val PERMISSION_BLUETOOTH_CONNECT =
            Manifest.permission.BLUETOOTH_CONNECT
        @JvmStatic protected val PERMISSION_ACCESS_BACKGROUND_LOCATION =
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        @JvmStatic protected val PERMISSION_ACCESS_MEDIA_LOCATION =
            Manifest.permission.ACCESS_MEDIA_LOCATION

        @JvmStatic protected val USER_ID_0 = 0
        @JvmStatic protected val USER_ID_NEW = 1
    }
}
