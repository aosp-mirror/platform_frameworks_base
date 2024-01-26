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

package com.android.systemui.communal.widgets

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalAppWidgetHostStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost

    private lateinit var underTest: CommunalAppWidgetHostStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        kosmos.fakeUserRepository.setUserInfos(listOf(MAIN_USER_INFO))

        underTest =
            CommunalAppWidgetHostStartable(
                appWidgetHost,
                kosmos.communalInteractor,
                kosmos.applicationCoroutineScope,
                kosmos.testDispatcher,
            )
    }

    @Test
    fun editModeShowingStartsAppWidgetHost() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(false)
                communalInteractor.setEditModeOpen(true)
                verify(appWidgetHost, never()).startListening()

                underTest.start()
                runCurrent()

                verify(appWidgetHost).startListening()
                verify(appWidgetHost, never()).stopListening()

                communalInteractor.setEditModeOpen(false)
                runCurrent()

                verify(appWidgetHost).stopListening()
            }
        }

    @Test
    fun communalShowingStartsAppWidgetHost() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(true)
                communalInteractor.setEditModeOpen(false)
                verify(appWidgetHost, never()).startListening()

                underTest.start()
                runCurrent()

                verify(appWidgetHost).startListening()
                verify(appWidgetHost, never()).stopListening()

                setCommunalAvailable(false)
                runCurrent()

                verify(appWidgetHost).stopListening()
            }
        }

    @Test
    fun communalAndEditModeNotShowingNeverStartListening() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(false)
                communalInteractor.setEditModeOpen(false)

                underTest.start()
                runCurrent()

                verify(appWidgetHost, never()).startListening()
                verify(appWidgetHost, never()).stopListening()
            }
        }

    private suspend fun setCommunalAvailable(available: Boolean) =
        with(kosmos) {
            fakeKeyguardRepository.setIsEncryptedOrLockdown(!available)
            fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)
            fakeKeyguardRepository.setKeyguardShowing(true)
        }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
    }
}
