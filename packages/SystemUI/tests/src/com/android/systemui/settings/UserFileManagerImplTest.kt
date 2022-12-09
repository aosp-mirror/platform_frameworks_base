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

package com.android.systemui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.os.UserManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.isNull
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class UserFileManagerImplTest : SysuiTestCase() {
    companion object {
        const val TEST_FILE_NAME = "abc.txt"
    }

    lateinit var userFileManager: UserFileManagerImpl
    lateinit var backgroundExecutor: FakeExecutor
    @Mock
    lateinit var userManager: UserManager
    @Mock
    lateinit var broadcastDispatcher: BroadcastDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        backgroundExecutor = FakeExecutor(FakeSystemClock())
        userFileManager = UserFileManagerImpl(context, userManager,
            broadcastDispatcher, backgroundExecutor)
    }

    @After
    fun end() {
        val dir = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID)
        dir.deleteRecursively()
    }

    @Test
    fun testGetFile() {
        assertThat(userFileManager.getFile(TEST_FILE_NAME, 0).path)
            .isEqualTo("${context.filesDir}/$TEST_FILE_NAME")
        assertThat(userFileManager.getFile(TEST_FILE_NAME, 11).path)
            .isEqualTo("${context.filesDir}/${UserFileManagerImpl.ID}/11/files/$TEST_FILE_NAME")
    }

    @Test
    fun testGetSharedPreferences() {
        val secondarySharedPref = userFileManager.getSharedPreferences(TEST_FILE_NAME, 0, 11)
        val secondaryUserDir = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID,
            "11",
            UserFileManagerImpl.SHARED_PREFS,
            TEST_FILE_NAME
        )

        assertThat(secondarySharedPref).isNotNull()
        assertThat(secondaryUserDir.exists())
        assertThat(userFileManager.getSharedPreferences(TEST_FILE_NAME, 0, 0))
            .isNotEqualTo(secondarySharedPref)
    }

    @Test
    fun testUserFileManagerStart() {
        val userFileManager = spy(userFileManager)
        userFileManager.start()
        verify(userFileManager).clearDeletedUserData()
        verify(broadcastDispatcher).registerReceiver(any(BroadcastReceiver::class.java),
            any(IntentFilter::class.java),
            any(Executor::class.java), isNull(), eq(Context.RECEIVER_EXPORTED), isNull())
    }

    @Test
    fun testClearDeletedUserData() {
        val dir = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID,
            "11",
            "files"
        )
        dir.mkdirs()
        val file = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID,
            "11",
            "files",
            TEST_FILE_NAME
        )
        val secondaryUserDir = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID,
            "11",
        )
        file.createNewFile()
        assertThat(secondaryUserDir.exists()).isTrue()
        assertThat(file.exists()).isTrue()
        userFileManager.clearDeletedUserData()
        assertThat(backgroundExecutor.runAllReady()).isGreaterThan(0)
        verify(userManager).aliveUsers
        assertThat(secondaryUserDir.exists()).isFalse()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun testEnsureParentDirExists() {
        val file = Environment.buildPath(
            context.filesDir,
            UserFileManagerImpl.ID,
            "11",
            "files",
            TEST_FILE_NAME
        )
        assertThat(file.parentFile.exists()).isFalse()
        userFileManager.ensureParentDirExists(file)
        assertThat(file.parentFile.exists()).isTrue()
    }
}
