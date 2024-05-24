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

package com.android.systemui.shade.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyApplication
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.privacy.privacyDialogController
import com.android.systemui.privacy.privacyDialogControllerV2
import com.android.systemui.shade.data.repository.fakePrivacyChipRepository
import com.android.systemui.shade.data.repository.privacyChipRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PrivacyChipInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val privacyChipRepository = kosmos.fakePrivacyChipRepository
    private val privacyDialogController = kosmos.privacyDialogController
    private val privacyDialogControllerV2 = kosmos.privacyDialogControllerV2
    @Mock private lateinit var privacyChip: OngoingPrivacyChip

    val underTest = kosmos.privacyChipInteractor

    @Before
    fun setUp() {
        initMocks(this)
        whenever(privacyChip.context).thenReturn(this.context)
    }

    @Test
    fun isChipVisible_updates() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isChipVisible)

            privacyChipRepository.setPrivacyItems(emptyList())
            runCurrent()

            assertThat(actual).isFalse()

            val privacyItems =
                listOf(
                    PrivacyItem(
                        privacyType = PrivacyType.TYPE_CAMERA,
                        application = PrivacyApplication("", 0)
                    ),
                )
            privacyChipRepository.setPrivacyItems(privacyItems)
            runCurrent()

            assertThat(actual).isTrue()
        }

    @Test
    fun isChipEnabled_noIndicationEnabled() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isChipEnabled)

            privacyChipRepository.setIsMicCameraIndicationEnabled(false)
            privacyChipRepository.setIsLocationIndicationEnabled(false)

            assertThat(actual).isFalse()
        }

    @Test
    fun isChipEnabled_micCameraIndicationEnabled() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isChipEnabled)

            privacyChipRepository.setIsMicCameraIndicationEnabled(true)
            privacyChipRepository.setIsLocationIndicationEnabled(false)

            assertThat(actual).isTrue()
        }

    @Test
    fun isChipEnabled_locationIndicationEnabled() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isChipEnabled)

            privacyChipRepository.setIsMicCameraIndicationEnabled(false)
            privacyChipRepository.setIsLocationIndicationEnabled(true)

            assertThat(actual).isTrue()
        }

    @Test
    fun isChipEnabled_allIndicationEnabled() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isChipEnabled)

            privacyChipRepository.setIsMicCameraIndicationEnabled(true)
            privacyChipRepository.setIsLocationIndicationEnabled(true)

            assertThat(actual).isTrue()
        }

    @Test
    fun onPrivacyChipClicked_safetyCenterEnabled() =
        testScope.runTest {
            privacyChipRepository.setIsSafetyCenterEnabled(true)

            underTest.onPrivacyChipClicked(privacyChip)

            verify(privacyDialogControllerV2).showDialog(any(), any())
            verify(privacyDialogController, never()).showDialog(any())
        }

    @Test
    fun onPrivacyChipClicked_safetyCenterDisabled() =
        testScope.runTest {
            privacyChipRepository.setIsSafetyCenterEnabled(false)

            underTest.onPrivacyChipClicked(privacyChip)

            verify(privacyDialogController).showDialog(any())
            verify(privacyDialogControllerV2, never()).showDialog(any(), any())
        }
}
