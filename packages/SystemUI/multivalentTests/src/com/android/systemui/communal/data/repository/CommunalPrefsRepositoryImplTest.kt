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
import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_MAIN
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
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.fakeUserFileManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.kotlin.spy

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalPrefsRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val userFileManager: UserFileManager = spy(kosmos.fakeUserFileManager)

    private val underTest: CommunalPrefsRepositoryImpl by lazy {
        CommunalPrefsRepositoryImpl(
            kosmos.testDispatcher,
            userFileManager,
            kosmos.broadcastDispatcher,
            logcatLogBuffer("CommunalPrefsRepositoryImplTest"),
        )
    }

    @Test
    fun isCtaDismissedValue_byDefault_isFalse() =
        testScope.runTest {
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed(MAIN_USER))
            assertThat(isCtaDismissed).isFalse()
        }

    @Test
    fun isCtaDismissedValue_onSet_isTrue() =
        testScope.runTest {
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed(MAIN_USER))

            underTest.setCtaDismissed(MAIN_USER)
            assertThat(isCtaDismissed).isTrue()
        }

    @Test
    fun isCtaDismissedValue_onSetForDifferentUser_isStillFalse() =
        testScope.runTest {
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed(MAIN_USER))

            underTest.setCtaDismissed(SECONDARY_USER)
            assertThat(isCtaDismissed).isFalse()
        }

    @Test
    fun getSharedPreferences_whenFileRestored() =
        testScope.runTest {
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed(MAIN_USER))
            assertThat(isCtaDismissed).isFalse()
            clearInvocations(userFileManager)

            // Received restore finished event.
            kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(BackupHelper.ACTION_RESTORE_FINISHED),
            )
            runCurrent()

            // Get shared preferences from the restored file.
            verify(userFileManager, atLeastOnce())
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, MAIN_USER.id)
        }

    companion object {
        val MAIN_USER = UserInfo(0, "main", FLAG_MAIN)
        val SECONDARY_USER = UserInfo(1, "secondary", 0)
    }
}
