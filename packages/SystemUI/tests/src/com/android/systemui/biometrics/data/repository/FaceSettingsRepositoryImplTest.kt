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

package com.android.systemui.biometrics.data.repository

import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.captureMany
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any

private const val USER_ID = 8

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class FaceSettingsRepositoryImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()
    private val testScope = TestScope()

    @Mock private lateinit var mainHandler: Handler
    @Mock private lateinit var secureSettings: SecureSettings

    private lateinit var repository: FaceSettingsRepositoryImpl

    @Before
    fun setup() {
        repository = FaceSettingsRepositoryImpl(mainHandler, secureSettings)
    }

    @Test
    fun createsOneRepositoryPerUser() =
        testScope.runTest {
            val userRepo = repository.forUser(USER_ID)

            assertThat(userRepo.userId).isEqualTo(USER_ID)

            assertThat(repository.forUser(USER_ID)).isSameInstanceAs(userRepo)
            assertThat(repository.forUser(USER_ID + 1)).isNotSameInstanceAs(userRepo)
        }

    @Test
    fun startsRepoImmediatelyWithAllSettingKeys() =
        testScope.runTest {
            val userRepo = repository.forUser(USER_ID)

            val keys =
                captureMany<String> {
                    verify(secureSettings)
                        .registerContentObserverForUser(capture(), anyBoolean(), any(), eq(USER_ID))
                }

            assertThat(keys).containsExactly(FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION)
        }

    @Test
    fun forwardsSettingsValues() = runTest {
        val userRepo = repository.forUser(USER_ID)

        val intAsBooleanSettings =
            listOf(
                FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION to
                    collectLastValue(userRepo.alwaysRequireConfirmationInApps)
            )

        for ((setting, accessor) in intAsBooleanSettings) {
            val observer =
                withArgCaptor<ContentObserver> {
                    verify(secureSettings)
                        .registerContentObserverForUser(
                            eq(setting),
                            anyBoolean(),
                            capture(),
                            eq(USER_ID)
                        )
                }

            for (value in listOf(true, false)) {
                secureSettings.mockIntSetting(setting, if (value) 1 else 0)
                observer.onChange(false)
                assertThat(accessor()).isEqualTo(value)
            }
        }
    }

    private fun SecureSettings.mockIntSetting(key: String, value: Int) {
        whenever(getIntForUser(eq(key), anyInt(), eq(USER_ID))).thenReturn(value)
    }
}
