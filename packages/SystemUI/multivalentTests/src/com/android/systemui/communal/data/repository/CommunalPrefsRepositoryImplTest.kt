/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal.data.repository

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.backup.BackupHelper
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.data.repository.CommunalPrefsRepositoryImpl.Companion.FILE_NAME
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.settings.UserFileManager
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.FakeSharedPreferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalPrefsRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var tableLogBuffer: TableLogBuffer

    private lateinit var underTest: CommunalPrefsRepositoryImpl

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var userRepository: FakeUserRepository
    private lateinit var userFileManager: UserFileManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        userRepository = kosmos.fakeUserRepository
        userRepository.setUserInfos(USER_INFOS)

        userFileManager =
            FakeUserFileManager(
                mapOf(
                    USER_INFOS[0].id to FakeSharedPreferences(),
                    USER_INFOS[1].id to FakeSharedPreferences()
                )
            )
    }

    @Test
    fun isCtaDismissedValue_byDefault_isFalse() =
        testScope.runTest {
            underTest = createCommunalPrefsRepositoryImpl(userFileManager)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)
            assertThat(isCtaDismissed).isFalse()
        }

    @Test
    fun isCtaDismissedValue_onSet_isTrue() =
        testScope.runTest {
            underTest = createCommunalPrefsRepositoryImpl(userFileManager)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)

            underTest.setCtaDismissedForCurrentUser()
            assertThat(isCtaDismissed).isTrue()
        }

    @Test
    fun isCtaDismissedValue_whenSwitchUser() =
        testScope.runTest {
            underTest = createCommunalPrefsRepositoryImpl(userFileManager)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)
            underTest.setCtaDismissedForCurrentUser()

            // dismissed true for primary user
            assertThat(isCtaDismissed).isTrue()

            // switch to secondary user
            userRepository.setSelectedUserInfo(USER_INFOS[1])

            // dismissed is false for secondary user
            assertThat(isCtaDismissed).isFalse()

            // switch back to primary user
            userRepository.setSelectedUserInfo(USER_INFOS[0])

            // dismissed is true for primary user
            assertThat(isCtaDismissed).isTrue()
        }

    @Test
    fun getSharedPreferences_whenFileRestored() =
        testScope.runTest {
            val userFileManagerSpy = Mockito.spy(userFileManager)
            underTest = createCommunalPrefsRepositoryImpl(userFileManagerSpy)

            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            assertThat(isCtaDismissed).isFalse()
            clearInvocations(userFileManagerSpy)

            // Received restore finished event.
            kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(BackupHelper.ACTION_RESTORE_FINISHED),
            )
            runCurrent()

            // Get shared preferences from the restored file.
            verify(userFileManagerSpy, atLeastOnce())
                .getSharedPreferences(
                    FILE_NAME,
                    Context.MODE_PRIVATE,
                    userRepository.getSelectedUserInfo().id
                )
        }

    private fun createCommunalPrefsRepositoryImpl(userFileManager: UserFileManager) =
        CommunalPrefsRepositoryImpl(
            testScope.backgroundScope,
            kosmos.testDispatcher,
            userRepository,
            userFileManager,
            kosmos.broadcastDispatcher,
            logcatLogBuffer("CommunalPrefsRepositoryImplTest"),
            tableLogBuffer,
        )

    private class FakeUserFileManager(private val sharedPrefs: Map<Int, SharedPreferences>) :
        UserFileManager {
        override fun getFile(fileName: String, userId: Int): File {
            throw UnsupportedOperationException()
        }

        override fun getSharedPreferences(
            fileName: String,
            mode: Int,
            userId: Int
        ): SharedPreferences {
            if (fileName != FILE_NAME) {
                throw IllegalArgumentException("Preference files must be $FILE_NAME")
            }
            return sharedPrefs.getValue(userId)
        }
    }

    companion object {
        val USER_INFOS =
            listOf(
                UserInfo(/* id= */ 0, "zero", /* flags= */ 0),
                UserInfo(/* id= */ 1, "secondary", /* flags= */ 0),
            )
    }
}
