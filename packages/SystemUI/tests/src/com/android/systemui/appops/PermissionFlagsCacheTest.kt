/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.appops

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PermissionFlagsCacheTest : SysuiTestCase() {

    companion object {
        const val TEST_PERMISSION = "test_permission"
        const val TEST_PACKAGE = "test_package"
    }

    @Mock
    private lateinit var mPackageManager: PackageManager
    @Mock
    private lateinit var mUserHandle: UserHandle
    private lateinit var flagsCache: TestPermissionFlagsCache

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mContext.setMockPackageManager(mPackageManager)
        flagsCache = TestPermissionFlagsCache(mContext)
    }

    @Test
    fun testCallsPackageManager_exactlyOnce() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
        flagsCache.time = CACHE_EXPIRATION - 1
        verify(mPackageManager).getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
    }

    @Test
    fun testCallsPackageManager_cacheExpired() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
        flagsCache.time = CACHE_EXPIRATION + 1
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
        verify(mPackageManager, times(2))
                .getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
    }

    @Test
    fun testCallsPackageMaanger_multipleKeys() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, mUserHandle)
        flagsCache.getPermissionFlags(TEST_PERMISSION, "", mUserHandle)
        verify(mPackageManager, times(2))
                .getPermissionFlags(anyString(), anyString(), any())
    }

    private class TestPermissionFlagsCache(context: Context) : PermissionFlagsCache(context) {
        var time = 0L

        override fun getCurrentTime(): Long {
            return time
        }
    }
}