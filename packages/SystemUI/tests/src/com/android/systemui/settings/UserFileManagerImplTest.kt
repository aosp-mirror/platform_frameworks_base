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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
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
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var broadcastDispatcher: BroadcastDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        backgroundExecutor = FakeExecutor(FakeSystemClock())
        userFileManager =
            UserFileManagerImpl(context, userManager, broadcastDispatcher, backgroundExecutor)
    }

    @Test
    fun testGetFile() {
        assertThat(userFileManager.getFile(TEST_FILE_NAME, 0).path)
            .isEqualTo("${context.filesDir}/$TEST_FILE_NAME")
        assertThat(userFileManager.getFile(TEST_FILE_NAME, 11).path)
            .isEqualTo("${context.filesDir}/__USER_11_$TEST_FILE_NAME")
    }

    @Test
    fun testGetSharedPreferences() {
        val primarySharedPref = userFileManager.getSharedPreferences(TEST_FILE_NAME, 0, 0)
        val secondarySharedPref = userFileManager.getSharedPreferences(TEST_FILE_NAME, 0, 11)

        assertThat(primarySharedPref).isNotEqualTo(secondarySharedPref)

        // Make sure these are different files
        primarySharedPref.edit().putString("TEST", "ABC").commit()
        assertThat(secondarySharedPref.getString("TEST", null)).isNull()

        context.deleteSharedPreferences("TEST")
        context.deleteSharedPreferences("__USER_11_TEST")
    }

    @Test
    fun testMigrateFile() {
        val userId = 12
        val fileName = "myFile.txt"
        val fileContents = "TestingFile"
        val legacyFile =
            UserFileManagerImpl.createLegacyFile(
                context,
                UserFileManagerImpl.FILES,
                fileName,
                userId
            )!!

        // Write file to legacy area
        Files.createDirectories(legacyFile.getParentFile().toPath())
        Files.write(legacyFile.toPath(), fileContents.toByteArray())
        assertThat(legacyFile.exists()).isTrue()

        // getFile() should migrate the legacy file to the new location
        val file = userFileManager.getFile(fileName, userId)
        val newContents = String(Files.readAllBytes(file.toPath()))

        assertThat(newContents).isEqualTo(fileContents)
        assertThat(legacyFile.exists()).isFalse()
        assertThat(File(context.filesDir, UserFileManagerImpl.ROOT_DIR).exists()).isFalse()
    }

    @Test
    fun testMigrateSharedPrefs() {
        val userId = 13
        val fileName = "myFile"
        val contents = "TestingSharedPrefs"
        val legacyFile =
            UserFileManagerImpl.createLegacyFile(
                context,
                UserFileManagerImpl.SHARED_PREFS,
                "$fileName.xml",
                userId
            )!!

        // Write a valid shared prefs xml file to legacy area
        val tmpPrefs = context.getSharedPreferences("tmp", Context.MODE_PRIVATE)
        tmpPrefs.edit().putString(contents, contents).commit()
        Files.createDirectories(legacyFile.getParentFile().toPath())
        val tmpFile =
            Environment.buildPath(context.dataDir, UserFileManagerImpl.SHARED_PREFS, "tmp.xml")
        tmpFile.renameTo(legacyFile)
        assertThat(legacyFile.exists()).isTrue()

        // getSharedpreferences() should migrate the legacy file to the new location
        val prefs = userFileManager.getSharedPreferences(fileName, Context.MODE_PRIVATE, userId)
        assertThat(prefs.getString(contents, "")).isEqualTo(contents)
        assertThat(legacyFile.exists()).isFalse()
        assertThat(File(context.filesDir, UserFileManagerImpl.ROOT_DIR).exists()).isFalse()
    }

    @Test
    fun testUserFileManagerStart() {
        val userFileManager = spy(userFileManager)
        userFileManager.start()
        verify(userFileManager).clearDeletedUserData()
        verify(broadcastDispatcher)
            .registerReceiver(
                any(BroadcastReceiver::class.java),
                any(IntentFilter::class.java),
                any(Executor::class.java),
                isNull(),
                eq(Context.RECEIVER_EXPORTED),
                isNull()
            )
    }

    @Test
    fun testClearDeletedUserData() {
        val file = userFileManager.getFile(TEST_FILE_NAME, 11)
        file.createNewFile()

        assertThat(file.exists()).isTrue()
        userFileManager.clearDeletedUserData()
        assertThat(backgroundExecutor.runAllReady()).isGreaterThan(0)
        verify(userManager, atLeastOnce()).aliveUsers

        assertThat(file.exists()).isFalse()
    }
}
