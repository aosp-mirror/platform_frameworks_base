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

package com.android.systemui.shade.data.repository

import android.content.Intent
import android.safetycenter.SafetyCenterManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.privacy.PrivacyApplication
import com.android.systemui.privacy.PrivacyConfig
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PrivacyChipRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val broadcastDispatcher = kosmos.broadcastDispatcher

    @Mock private lateinit var privacyConfig: PrivacyConfig
    @Mock private lateinit var privacyItemController: PrivacyItemController
    @Mock private lateinit var safetyCenterManager: SafetyCenterManager

    lateinit var underTest: PrivacyChipRepositoryImpl

    @Before
    fun setUp() {
        initMocks(this)
        setUpUnderTest()
    }

    @Test
    fun isSafetyCenterEnabled_startEnabled() =
        testScope.runTest {
            setUpUnderTest(true)

            val actual by collectLastValue(underTest.isSafetyCenterEnabled)
            runCurrent()

            assertThat(actual).isTrue()
        }

    @Test
    fun isSafetyCenterEnabled_startDisabled() =
        testScope.runTest {
            setUpUnderTest(false)

            val actual by collectLastValue(underTest.isSafetyCenterEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isSafetyCenterEnabled_updates() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isSafetyCenterEnabled)
            runCurrent()

            assertThat(actual).isFalse()

            whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(true)

            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(SafetyCenterManager.ACTION_SAFETY_CENTER_ENABLED_CHANGED),
            )

            runCurrent()

            assertThat(actual).isTrue()
        }

    @Test
    fun privacyItems_updates() =
        testScope.runTest {
            val actual by collectLastValue(underTest.privacyItems)
            runCurrent()

            val callback =
                withArgCaptor<PrivacyItemController.Callback> {
                    verify(privacyItemController).addCallback(capture())
                }

            callback.onPrivacyItemsChanged(emptyList())
            assertThat(actual).isEmpty()

            val privacyItems =
                listOf(
                    PrivacyItem(
                        privacyType = PrivacyType.TYPE_CAMERA,
                        application = PrivacyApplication("", 0)
                    ),
                )
            callback.onPrivacyItemsChanged(privacyItems)
            assertThat(actual).isEqualTo(privacyItems)
        }

    @Test
    fun isMicCameraIndicationEnabled_updates() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isMicCameraIndicationEnabled)
            runCurrent()

            val captor = kotlinArgumentCaptor<PrivacyConfig.Callback>()
            verify(privacyConfig, times(2)).addCallback(captor.capture())
            val callback = captor.allValues[0]

            callback.onFlagMicCameraChanged(false)
            assertThat(actual).isFalse()

            callback.onFlagMicCameraChanged(true)
            assertThat(actual).isTrue()
        }

    @Test
    fun isLocationIndicationEnabled_updates() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLocationIndicationEnabled)
            runCurrent()

            val captor = kotlinArgumentCaptor<PrivacyConfig.Callback>()
            verify(privacyConfig, times(2)).addCallback(captor.capture())
            val callback = captor.allValues[1]

            callback.onFlagLocationChanged(false)
            assertThat(actual).isFalse()

            callback.onFlagLocationChanged(true)
            assertThat(actual).isTrue()
        }

    private fun setUpUnderTest(isSafetyCenterEnabled: Boolean = false) {
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(isSafetyCenterEnabled)

        underTest =
            PrivacyChipRepositoryImpl(
                applicationScope = kosmos.applicationCoroutineScope,
                privacyConfig = privacyConfig,
                privacyItemController = privacyItemController,
                backgroundDispatcher = kosmos.testDispatcher,
                broadcastDispatcher = broadcastDispatcher,
                safetyCenterManager = safetyCenterManager,
            )
    }
}
