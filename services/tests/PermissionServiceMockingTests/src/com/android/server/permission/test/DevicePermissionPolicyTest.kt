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

import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableDevicePermissionFlags
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.DevicePermissionPolicy
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.testutils.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * This class tests permissions for external devices, we have separate policy to
 * manage external device permissions.
 */
class DevicePermissionPolicyTest : BasePermissionPolicyTest() {
    private val devicePermissionPolicy = DevicePermissionPolicy()

    @Test
    fun testOnAppIdRemoved_clearPermissionFlags() {
        val packageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_0))
        )
        addPackageState(packageState)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            with(devicePermissionPolicy) {
                onAppIdRemoved(APP_ID_1)
            }
        }
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(0)
    }

    @Test
    fun testOnDeviceIdRemoved_clearPermissionFlags() {
        val requestingPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_0))
        )
        addPackageState(requestingPackageState)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            with(devicePermissionPolicy) {
                onDeviceIdRemoved(DEVICE_ID_1)
            }
        }
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(0)
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(PermissionFlags.RUNTIME_GRANTED)
    }

    @Test
    fun testRemoveInactiveDevicesPermission_clearPermissionFlags() {
        val requestingPackageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_0))
        )
        addPackageState(requestingPackageState)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            with(devicePermissionPolicy) {
                trimDevicePermissionStates(setOf(DEVICE_ID_2))
            }
        }
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(0)
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_2, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(PermissionFlags.RUNTIME_GRANTED)
    }

    @Test
    fun testOnStateMutated_notEmpty_isCalledForEachListener() {
        val mockListener =
                mock<DevicePermissionPolicy.OnDevicePermissionFlagsChangedListener> {}
        devicePermissionPolicy.addOnPermissionFlagsChangedListener(mockListener)

        GetStateScope(oldState).apply {
            with(devicePermissionPolicy) {
                onStateMutated()
            }
        }

        verify(mockListener, times(1)).onStateMutated()
    }

    @Test
    fun testOnStorageVolumeMounted_trimsPermissionsNotRequestAnymore() {
        val packageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(
                PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_1, PERMISSION_NAME_0)
            )
        )
        addPackageState(packageState)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, PermissionFlags.RUNTIME_GRANTED
        )
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        val installedPackageState = mockPackageState(
                APP_ID_1,
                mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_1))
        )
        addPackageState(installedPackageState)

        mutateState {
            with(devicePermissionPolicy) {
                onStorageVolumeMounted(null, listOf(PACKAGE_NAME_1), false)
            }
        }

        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1))
            .isEqualTo(PermissionFlags.RUNTIME_GRANTED)
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0))
            .isEqualTo(0)
    }


    @Test
    fun testResetRuntimePermissions_trimsPermissionStates() {
        val packageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_1, requestedPermissions = setOf(PERMISSION_NAME_1))
        )
        addPackageState(packageState)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            with(devicePermissionPolicy) {
                resetRuntimePermissions(PACKAGE_NAME_1, USER_ID_0)
            }
        }
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1))
                .isEqualTo(0)
    }

    @Test
    fun testResetRuntimePermissions_keepsPermissionStates() {
        val packageState = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(
                PACKAGE_NAME_1,
                requestedPermissions = setOf(PERMISSION_NAME_1, PERMISSION_NAME_0)
            )
        )
        val packageState2 = mockPackageState(
            APP_ID_1,
            mockAndroidPackage(PACKAGE_NAME_2, requestedPermissions = setOf(PERMISSION_NAME_0))
        )
        addPackageState(packageState)
        addPackageState(packageState2)
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, PermissionFlags.RUNTIME_GRANTED
        )
        setPermissionFlags(
            APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, PermissionFlags.RUNTIME_GRANTED
        )
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)
        assertThat(
            getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0, oldState)
        ).isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        mutateState {
            with(devicePermissionPolicy) {
                resetRuntimePermissions(PACKAGE_NAME_1, USER_ID_0)
            }
        }
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_1))
                .isEqualTo(0)
        assertThat(getPermissionFlags(APP_ID_1, DEVICE_ID_1, USER_ID_0, PERMISSION_NAME_0))
                .isEqualTo(PermissionFlags.RUNTIME_GRANTED)
    }

    private fun getPermissionFlags(
        appId: Int,
        deviceId: String,
        userId: Int,
        permissionName: String,
        state: MutableAccessState = newState
    ): Int = state.userStates[userId]
                ?.appIdDevicePermissionFlags
                ?.get(appId)
                ?.get(deviceId)
                ?.getWithDefault(permissionName, 0)
                ?: 0


   private fun setPermissionFlags(
        appId: Int,
        deviceId: String,
        userId: Int,
        permissionName: String,
        newFlags: Int,
        state: MutableAccessState = oldState
    ) {
       val appIdDevicePermissionFlags =
           state.mutateUserState(userId)!!.mutateAppIdDevicePermissionFlags()
       val devicePermissionFlags =
           appIdDevicePermissionFlags.mutateOrPut(appId) { MutableDevicePermissionFlags() }
       val permissionFlags =
           devicePermissionFlags.mutateOrPut(deviceId) { MutableIndexedMap() }
       permissionFlags.putWithDefault(permissionName, newFlags, 0)
       if (permissionFlags.isEmpty()) {
           devicePermissionFlags -= deviceId
           if (devicePermissionFlags.isEmpty()) {
               appIdDevicePermissionFlags -= appId
           }
       }
   }

    companion object {
        private const val DEVICE_ID_1 = "cdm:1"
        private const val DEVICE_ID_2 = "cdm:2"
    }
}
