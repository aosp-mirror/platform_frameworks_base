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
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.permission.PermissionFlags
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A parameterized test for testing resetting runtime permissions for onPackageUninstalled()
 * and resetRuntimePermissions() in AppIdPermissionPolicy
 */
@RunWith(Parameterized::class)
class AppIdPermissionPolicyPermissionResetTest : BasePermissionPolicyTest() {
    @Parameterized.Parameter(0) lateinit var action: Action

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

        mutateState { testAction() }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_0)
        assertWithMessage(
            "After resetting runtime permissions, permission flags did not match" +
                " expected values: expectedNewFlags is $expectedNewFlags," +
                " actualFlags is $actualFlags, while the oldFlags is $oldFlags"
        )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    private fun MutateStateScope.testAction(
        packageName: String = PACKAGE_NAME_1,
        appId: Int = APP_ID_1,
        userId: Int = USER_ID_0
    ) {
        with(appIdPermissionPolicy) {
            when (action) {
                Action.ON_PACKAGE_UNINSTALLED -> onPackageUninstalled(packageName, appId, userId)
                Action.RESET_RUNTIME_PERMISSIONS -> resetRuntimePermissions(packageName, userId)
            }
        }
    }

    enum class Action { ON_PACKAGE_UNINSTALLED, RESET_RUNTIME_PERMISSIONS }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Array<Action> = Action.values()
    }
}
