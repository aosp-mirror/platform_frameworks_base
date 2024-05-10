/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageManagersTest {

    private val fakePackageManagerWrapper = FakePackageManagerWrapper()

    private val packageManagersImpl = PackageManagersImpl(fakePackageManagerWrapper)

    @Test
    fun getPackageInfoAsUser_notFound() {
        fakePackageManagerWrapper.fakePackageInfo = null

        val packageInfo = packageManagersImpl.getPackageInfoAsUser(PACKAGE_NAME, 0)

        assertThat(packageInfo).isNull()
    }

    @Test
    fun getPackageInfoAsUser_found() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo()

        val packageInfo = packageManagersImpl.getPackageInfoAsUser(PACKAGE_NAME, 0)

        assertThat(packageInfo).isSameInstanceAs(fakePackageManagerWrapper.fakePackageInfo)
    }

    @Test
    fun hasRequestPermission_packageInfoIsNull_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = null

        val hasRequestPermission = with(packageManagersImpl) {
            APP.hasRequestPermission(PERMISSION_A)
        }

        assertThat(hasRequestPermission).isFalse()
    }

    @Test
    fun hasRequestPermission_requestedPermissionsIsNull_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = null
        }

        val hasRequestPermission = with(packageManagersImpl) {
            APP.hasRequestPermission(PERMISSION_A)
        }

        assertThat(hasRequestPermission).isFalse()
    }

    @Test
    fun hasRequestPermission_notRequested_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = emptyArray()
        }

        val hasRequestPermission = with(packageManagersImpl) {
            APP.hasRequestPermission(PERMISSION_A)
        }

        assertThat(hasRequestPermission).isFalse()
    }

    @Test
    fun hasRequestPermission_requested_returnTrue() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf(PERMISSION_A)
        }

        val hasRequestPermission = with(packageManagersImpl) {
            APP.hasRequestPermission(PERMISSION_A)
        }

        assertThat(hasRequestPermission).isTrue()
    }

    @Test
    fun hasGrantPermission_packageInfoIsNull_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = null

        val hasGrantPermission = with(packageManagersImpl) {
            APP.hasGrantPermission(PERMISSION_A)
        }

        assertThat(hasGrantPermission).isFalse()
    }

    @Test
    fun hasGrantPermission_requestedPermissionsIsNull_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = null
        }

        val hasGrantPermission = with(packageManagersImpl) {
            APP.hasGrantPermission(PERMISSION_A)
        }

        assertThat(hasGrantPermission).isFalse()
    }

    @Test
    fun hasGrantPermission_notRequested_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf(PERMISSION_B)
            requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
        }

        val hasGrantPermission = with(packageManagersImpl) {
            APP.hasGrantPermission(PERMISSION_A)
        }

        assertThat(hasGrantPermission).isFalse()
    }

    @Test
    fun hasGrantPermission_notGranted_returnFalse() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf(PERMISSION_A, PERMISSION_B)
            requestedPermissionsFlags = intArrayOf(0, PackageInfo.REQUESTED_PERMISSION_GRANTED)
        }

        val hasGrantPermission = with(packageManagersImpl) {
            APP.hasGrantPermission(PERMISSION_A)
        }

        assertThat(hasGrantPermission).isFalse()
    }

    @Test
    fun hasGrantPermission_granted_returnTrue() {
        fakePackageManagerWrapper.fakePackageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf(PERMISSION_A, PERMISSION_B)
            requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED, 0)
        }

        val hasGrantPermission = with(packageManagersImpl) {
            APP.hasGrantPermission(PERMISSION_A)
        }

        assertThat(hasGrantPermission).isTrue()
    }

    private companion object {
        const val PACKAGE_NAME = "package.name"
        const val PERMISSION_A = "permission.A"
        const val PERMISSION_B = "permission.B"
        const val UID = 123
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = UID
        }
    }
}

private class FakePackageManagerWrapper : PackageManagerWrapper {
    var fakePackageInfo: PackageInfo? = null

    override fun getPackageInfoAsUserCached(packageName: String, flags: Long, userId: Int) =
        fakePackageInfo
}
