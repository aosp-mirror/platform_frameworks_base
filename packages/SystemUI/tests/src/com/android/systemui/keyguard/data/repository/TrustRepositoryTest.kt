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

package com.android.systemui.keyguard.data.repository

import android.app.trust.TrustManager
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.logging.TrustRepositoryLogger
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.user.data.repository.FakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class TrustRepositoryTest : SysuiTestCase() {
    @Mock private lateinit var trustManager: TrustManager
    @Captor private lateinit var listener: ArgumentCaptor<TrustManager.TrustListener>
    private lateinit var userRepository: FakeUserRepository
    private lateinit var testScope: TestScope
    private val users = listOf(UserInfo(1, "user 1", 0), UserInfo(2, "user 1", 0))

    private lateinit var underTest: TrustRepository
    private lateinit var isCurrentUserTrusted: FlowValue<Boolean?>
    private lateinit var isCurrentUserTrustManaged: FlowValue<Boolean?>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        userRepository = FakeUserRepository()
        userRepository.setUserInfos(users)

        val logger =
            TrustRepositoryLogger(
                LogBuffer("TestBuffer", 1, mock(LogcatEchoTracker::class.java), false)
            )
        underTest =
            TrustRepositoryImpl(testScope.backgroundScope, userRepository, trustManager, logger)
    }

    fun TestScope.init() {
        runCurrent()
        verify(trustManager).registerTrustListener(listener.capture())
        isCurrentUserTrustManaged = collectLastValue(underTest.isCurrentUserTrustManaged)
        isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)
    }

    @Test
    fun isCurrentUserTrustManaged_whenItChanges_emitsLatestValue() =
        testScope.runTest {
            init()

            val currentUserId = users[0].id
            userRepository.setSelectedUserInfo(users[0])

            listener.value.onTrustManagedChanged(true, currentUserId)
            assertThat(isCurrentUserTrustManaged()).isTrue()

            listener.value.onTrustManagedChanged(false, currentUserId)

            assertThat(isCurrentUserTrustManaged()).isFalse()
        }

    @Test
    fun isCurrentUserTrustManaged_isFalse_byDefault() =
        testScope.runTest {
            runCurrent()

            assertThat(collectLastValue(underTest.isCurrentUserTrustManaged)()).isFalse()
        }

    @Test
    fun isCurrentUserTrustManaged_whenItChangesForDifferentUser_noops() =
        testScope.runTest {
            init()
            userRepository.setSelectedUserInfo(users[0])

            // current user's trust is managed.
            listener.value.onTrustManagedChanged(true, users[0].id)
            // some other user's trust is not managed.
            listener.value.onTrustManagedChanged(false, users[1].id)

            assertThat(isCurrentUserTrustManaged()).isTrue()
        }

    @Test
    fun isCurrentUserTrustManaged_whenUserChangesWithoutRecentTrustChange_defaultsToFalse() =
        testScope.runTest {
            init()

            userRepository.setSelectedUserInfo(users[0])
            listener.value.onTrustManagedChanged(true, users[0].id)

            userRepository.setSelectedUserInfo(users[1])

            assertThat(isCurrentUserTrustManaged()).isFalse()
        }

    @Test
    fun isCurrentUserTrustManaged_itChangesFirstBeforeUserInfoChanges_emitsCorrectValue() =
        testScope.runTest {
            init()
            userRepository.setSelectedUserInfo(users[1])

            listener.value.onTrustManagedChanged(true, users[0].id)
            assertThat(isCurrentUserTrustManaged()).isFalse()

            userRepository.setSelectedUserInfo(users[0])

            assertThat(isCurrentUserTrustManaged()).isTrue()
        }

    @Test
    fun isCurrentUserTrusted_whenTrustChanges_emitsLatestValue() =
        testScope.runTest {
            init()

            val currentUserId = users[0].id
            userRepository.setSelectedUserInfo(users[0])

            listener.value.onTrustChanged(true, false, currentUserId, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isTrue()

            listener.value.onTrustChanged(false, false, currentUserId, 0, emptyList())

            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_isFalse_byDefault() =
        testScope.runTest {
            runCurrent()

            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)

            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_whenTrustChangesForDifferentUser_noop() =
        testScope.runTest {
            init()

            userRepository.setSelectedUserInfo(users[0])

            // current user is trusted.
            listener.value.onTrustChanged(true, true, users[0].id, 0, emptyList())
            // some other user is not trusted.
            listener.value.onTrustChanged(false, false, users[1].id, 0, emptyList())

            assertThat(isCurrentUserTrusted()).isTrue()
        }

    @Test
    fun isCurrentUserTrusted_whenTrustChangesForCurrentUser_emitsNewValue() =
        testScope.runTest {
            init()
            userRepository.setSelectedUserInfo(users[0])

            listener.value.onTrustChanged(true, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isTrue()

            listener.value.onTrustChanged(false, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_whenUserChangesWithoutRecentTrustChange_defaultsToFalse() =
        testScope.runTest {
            init()

            userRepository.setSelectedUserInfo(users[0])
            listener.value.onTrustChanged(true, true, users[0].id, 0, emptyList())

            userRepository.setSelectedUserInfo(users[1])

            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_trustChangesFirstBeforeUserInfoChanges_emitsCorrectValue() =
        testScope.runTest {
            init()

            listener.value.onTrustChanged(true, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isFalse()

            userRepository.setSelectedUserInfo(users[0])

            assertThat(isCurrentUserTrusted()).isTrue()
        }
}
