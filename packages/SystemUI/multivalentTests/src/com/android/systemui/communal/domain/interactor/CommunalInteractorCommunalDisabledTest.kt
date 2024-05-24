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
 *
 */

package com.android.systemui.communal.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This class is a variation of the [CommunalInteractorTest] for cases where communal is disabled.
 */
@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalInteractorCommunalDisabledTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var communalRepository: FakeCommunalSceneRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        communalRepository = kosmos.fakeCommunalRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        keyguardRepository = kosmos.fakeKeyguardRepository

        mSetFlagsRule.disableFlags(FLAG_COMMUNAL_HUB)

        underTest = kosmos.communalInteractor
    }

    @Test
    fun isCommunalEnabled_false() =
        testScope.runTest { assertThat(underTest.isCommunalEnabled.value).isFalse() }

    @Test
    fun isCommunalAvailable_whenStorageUnlock_false() =
        testScope.runTest {
            val isCommunalAvailable by collectLastValue(underTest.isCommunalAvailable)

            assertThat(isCommunalAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            runCurrent()

            assertThat(isCommunalAvailable).isFalse()
        }
}
