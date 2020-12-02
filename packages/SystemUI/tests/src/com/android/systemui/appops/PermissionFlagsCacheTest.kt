/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.pm.PackageManager
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PermissionFlagsCacheTest : SysuiTestCase() {

    companion object {
        const val TEST_PERMISSION = "test_permission"
        const val TEST_PACKAGE = "test_package"
        const val TEST_UID1 = 1000
        const val TEST_UID2 = UserHandle.PER_USER_RANGE + 1000
    }

    @Mock
    private lateinit var packageManager: PackageManager

    private lateinit var executor: FakeExecutor
    private lateinit var flagsCache: PermissionFlagsCache

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        executor = FakeExecutor(FakeSystemClock())

        flagsCache = PermissionFlagsCache(packageManager, executor)
        executor.runAllReady()
    }

    @Test
    fun testNotListeningByDefault() {
        verify(packageManager, never()).addOnPermissionsChangeListener(any())
    }

    @Test
    fun testGetCorrectFlags() {
        `when`(packageManager.getPermissionFlags(anyString(), anyString(), any())).thenReturn(0)
        `when`(packageManager.getPermissionFlags(
                TEST_PERMISSION,
                TEST_PACKAGE,
                UserHandle.getUserHandleForUid(TEST_UID1))
        ).thenReturn(1)

        assertEquals(1, flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1))
        assertNotEquals(1, flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID2))
    }

    @Test
    fun testFlagIsCached() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1)

        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1)

        verify(packageManager, times(1)).getPermissionFlags(
                TEST_PERMISSION,
                TEST_PACKAGE,
                UserHandle.getUserHandleForUid(TEST_UID1)
        )
    }

    @Test
    fun testListeningAfterFirstRequest() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1)

        verify(packageManager).addOnPermissionsChangeListener(any())
    }

    @Test
    fun testListeningOnlyOnce() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1)

        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID2)

        verify(packageManager, times(1)).addOnPermissionsChangeListener(any())
    }

    @Test
    fun testUpdateFlag() {
        assertEquals(0, flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1))

        `when`(packageManager.getPermissionFlags(
                TEST_PERMISSION,
                TEST_PACKAGE,
                UserHandle.getUserHandleForUid(TEST_UID1))
        ).thenReturn(1)

        flagsCache.onPermissionsChanged(TEST_UID1)

        executor.runAllReady()

        assertEquals(1, flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1))
    }

    @Test
    fun testUpdateFlag_notUpdatedIfUidHasNotBeenRequestedBefore() {
        flagsCache.getPermissionFlags(TEST_PERMISSION, TEST_PACKAGE, TEST_UID1)

        flagsCache.onPermissionsChanged(TEST_UID2)

        executor.runAllReady()

        verify(packageManager, never()).getPermissionFlags(
                TEST_PERMISSION,
                TEST_PACKAGE,
                UserHandle.getUserHandleForUid(TEST_UID2)
        )
    }
}