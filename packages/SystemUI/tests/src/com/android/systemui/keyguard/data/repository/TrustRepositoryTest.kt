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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogcatEchoTracker
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
@RunWith(AndroidJUnit4::class)
class TrustRepositoryTest : SysuiTestCase() {
    @Mock private lateinit var trustManager: TrustManager
    @Captor private lateinit var listenerCaptor: ArgumentCaptor<TrustManager.TrustListener>
    private lateinit var userRepository: FakeUserRepository
    private lateinit var testScope: TestScope
    private val users = listOf(UserInfo(1, "user 1", 0), UserInfo(2, "user 1", 0))

    private lateinit var underTest: TrustRepository

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

    @Test
    fun isCurrentUserTrusted_whenTrustChanges_emitsLatestValue() =
        testScope.runTest {
            runCurrent()
            verify(trustManager).registerTrustListener(listenerCaptor.capture())
            val listener = listenerCaptor.value

            val currentUserId = users[0].id
            userRepository.setSelectedUserInfo(users[0])
            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)

            listener.onTrustChanged(true, false, currentUserId, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isTrue()

            listener.onTrustChanged(false, false, currentUserId, 0, emptyList())

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
            runCurrent()
            verify(trustManager).registerTrustListener(listenerCaptor.capture())
            userRepository.setSelectedUserInfo(users[0])
            val listener = listenerCaptor.value

            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)
            // current user is trusted.
            listener.onTrustChanged(true, true, users[0].id, 0, emptyList())
            // some other user is not trusted.
            listener.onTrustChanged(false, false, users[1].id, 0, emptyList())

            assertThat(isCurrentUserTrusted()).isTrue()
        }

    @Test
    fun isCurrentUserTrusted_whenTrustChangesForCurrentUser_emitsNewValue() =
        testScope.runTest {
            runCurrent()
            verify(trustManager).registerTrustListener(listenerCaptor.capture())
            val listener = listenerCaptor.value
            userRepository.setSelectedUserInfo(users[0])

            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)
            listener.onTrustChanged(true, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isTrue()

            listener.onTrustChanged(false, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_whenUserChangesWithoutRecentTrustChange_defaultsToFalse() =
        testScope.runTest {
            runCurrent()
            verify(trustManager).registerTrustListener(listenerCaptor.capture())
            val listener = listenerCaptor.value
            userRepository.setSelectedUserInfo(users[0])
            listener.onTrustChanged(true, true, users[0].id, 0, emptyList())

            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)
            userRepository.setSelectedUserInfo(users[1])

            assertThat(isCurrentUserTrusted()).isFalse()
        }

    @Test
    fun isCurrentUserTrusted_trustChangesFirstBeforeUserInfoChanges_emitsCorrectValue() =
        testScope.runTest {
            runCurrent()
            verify(trustManager).registerTrustListener(listenerCaptor.capture())
            val listener = listenerCaptor.value
            val isCurrentUserTrusted = collectLastValue(underTest.isCurrentUserTrusted)

            listener.onTrustChanged(true, true, users[0].id, 0, emptyList())
            assertThat(isCurrentUserTrusted()).isFalse()

            userRepository.setSelectedUserInfo(users[0])

            assertThat(isCurrentUserTrusted()).isTrue()
        }
}
