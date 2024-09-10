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

package com.android.systemui.communal

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalSmartspaceRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.interactor.setCommunalEnabled
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnableFlags(FLAG_COMMUNAL_HUB)
@RunWith(AndroidJUnit4::class)
class CommunalOngoingContentStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaRepository = kosmos.fakeCommunalMediaRepository
    private val smartspaceRepository = kosmos.fakeCommunalSmartspaceRepository

    private lateinit var underTest: CommunalOngoingContentStartable

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        underTest =
            CommunalOngoingContentStartable(
                bgScope = kosmos.applicationCoroutineScope,
                communalInteractor = kosmos.communalInteractor,
                communalMediaRepository = mediaRepository,
                communalSettingsInteractor = kosmos.communalSettingsInteractor,
                communalSmartspaceRepository = smartspaceRepository,
            )
    }

    @Test
    fun testListenForOngoingContentWhenCommunalIsEnabled() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            assertThat(mediaRepository.isListening()).isFalse()
            assertThat(smartspaceRepository.isListening()).isFalse()

            kosmos.setCommunalEnabled(true)
            runCurrent()

            assertThat(mediaRepository.isListening()).isTrue()
            assertThat(smartspaceRepository.isListening()).isTrue()

            kosmos.setCommunalEnabled(false)
            runCurrent()

            assertThat(mediaRepository.isListening()).isFalse()
            assertThat(smartspaceRepository.isListening()).isFalse()
        }
}
