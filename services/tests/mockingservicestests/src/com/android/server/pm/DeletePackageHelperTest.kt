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

package com.android.server.pm

import android.Manifest.permission.CONTROL_KEYGUARD
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.UserInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserHandle.USER_SYSTEM
import android.util.Log
import com.android.server.testutils.any
import com.android.server.testutils.spy
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

@RunWith(JUnit4::class)
class DeletePackageHelperTest {

    @Rule
    @JvmField
    val rule = MockSystemRule()

    private lateinit var mPms: PackageManagerService
    private lateinit var mUserManagerInternal: UserManagerInternal

    @Before
    @Throws(Exception::class)
    fun setup() {
        Log.i("system.out", "setup", Exception())
        rule.system().stageNominalSystemState()
        rule.system().stageScanExistingPackage(
            "a.data.package", 1L, rule.system().dataAppDirectory)

        mUserManagerInternal = rule.mocks().injector.userManagerInternal
        whenever(mUserManagerInternal.getUserIds()).thenReturn(intArrayOf(0, 1))
        whenever(mUserManagerInternal.getUserTypesForStatsd(any())).thenReturn(intArrayOf(1, 1))

        mPms = createPackageManagerService()
        doAnswer { false }.`when`(mPms).isPackageDeviceAdmin(any(), any())
        doAnswer { null }.`when`(mPms).freezePackageForDelete(any(), any(), any(), any(), any())
    }

    private fun createPackageManagerService(): PackageManagerService {
        return spy(PackageManagerService(rule.mocks().injector,
            false /*factoryTest*/,
            MockSystem.DEFAULT_VERSION_INFO.fingerprint,
            false /*isEngBuild*/,
            false /*isUserDebugBuild*/,
            Build.VERSION_CODES.CUR_DEVELOPMENT,
            Build.VERSION.INCREMENTAL))
    }

    @Test
    fun deleteSystemPackageFailsIfNotAdminAndNotProfile() {
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isUpdatedSystemApp(ps)).thenReturn(true)
        whenever(mUserManagerInternal.getUserInfo(1)).thenReturn(UserInfo(1, "test", 0))
        whenever(mUserManagerInternal.getProfileParentId(1)).thenReturn(1)

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, 1, 0, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_FAILED_USER_RESTRICTED)
    }

    @Test
    fun deleteSystemPackageFailsIfProfileOfNonAdmin() {
        val userId = 1
        val parentId = 5
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isUpdatedSystemApp(ps)).thenReturn(true)
        whenever(mUserManagerInternal.getUserInfo(userId)).thenReturn(
            UserInfo(userId, "test", UserInfo.FLAG_PROFILE))
        whenever(mUserManagerInternal.getProfileParentId(userId)).thenReturn(parentId)
        whenever(mUserManagerInternal.getUserInfo(parentId)).thenReturn(
            UserInfo(userId, "testparent", 0))

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, userId, 0, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_FAILED_USER_RESTRICTED)
    }

    @Test
    fun deleteSystemPackageSucceedsIfAdmin() {
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isSystemApp(ps)).thenReturn(true)
        whenever(mUserManagerInternal.getUserInfo(1)).thenReturn(
            UserInfo(1, "test", UserInfo.FLAG_ADMIN))
        whenever(mPms.checkPermission(CONTROL_KEYGUARD, "a.data.package", USER_SYSTEM))
            .thenReturn(PERMISSION_DENIED)

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, 1,
            PackageManager.DELETE_SYSTEM_APP, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_SUCCEEDED)
    }

    @Test
    fun deleteSystemPackageSucceedsIfProfileOfAdmin() {
        val userId = 1
        val parentId = 5
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isSystemApp(ps)).thenReturn(true)
        whenever(mUserManagerInternal.getUserInfo(userId)).thenReturn(
            UserInfo(userId, "test", UserInfo.FLAG_PROFILE))
        whenever(mUserManagerInternal.getProfileParentId(userId)).thenReturn(parentId)
        whenever(mUserManagerInternal.getUserInfo(parentId)).thenReturn(
            UserInfo(userId, "testparent", UserInfo.FLAG_ADMIN))
        whenever(mPms.checkPermission(CONTROL_KEYGUARD, "a.data.package", USER_SYSTEM))
            .thenReturn(PERMISSION_DENIED)

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, userId,
            PackageManager.DELETE_SYSTEM_APP, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_SUCCEEDED)
    }

    @Test
    fun deleteSystemPackageSucceedsIfNotAdminButDeleteSystemAppSpecified() {
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isUpdatedSystemApp(ps)).thenReturn(true)
        whenever(mUserManagerInternal.getUserInfo(1)).thenReturn(UserInfo(1, "test", 0))
        whenever(mUserManagerInternal.getProfileParentId(1)).thenReturn(1)
        whenever(PackageManagerServiceUtils.isSystemApp(ps)).thenReturn(true)
        whenever(mPms.checkPermission(CONTROL_KEYGUARD, "a.data.package", USER_SYSTEM))
            .thenReturn(PERMISSION_DENIED)

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, 1,
                PackageManager.DELETE_SYSTEM_APP, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_SUCCEEDED)
    }

    @Test
    fun deleteSystemPackageWithKeyguard_fails() {
        val ps = mPms.mSettings.getPackageLPr("a.data.package")
        whenever(PackageManagerServiceUtils.isSystemApp(ps)).thenReturn(true)
        whenever(mPms.checkPermission(CONTROL_KEYGUARD, "a.data.package", USER_SYSTEM))
            .thenReturn(PERMISSION_GRANTED)

        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
            mock(BroadcastHelper::class.java))
        val result = dph.deletePackageX("a.data.package", 1L, 1,
            PackageManager.DELETE_SYSTEM_APP, false)

        assertThat(result).isEqualTo(PackageManager.DELETE_FAILED_INTERNAL_ERROR)
    }

    @Test
    fun deletePackageLIFWithNonExistantPackage_isFalse() {
        val dph = DeletePackageHelper(mPms, mock(RemovePackageHelper::class.java),
                                      mock(BroadcastHelper::class.java))
        val result = dph.deletePackageLIF("a.nonexistent.package", UserHandle.of(USER_SYSTEM), true,
                                          intArrayOf(0), 0, PackageRemovedInfo(), true)
        assertFalse(result)
    }
}
