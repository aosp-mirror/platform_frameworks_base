/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.posturing.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.posturing.data.repository.fake
import com.android.systemui.communal.posturing.data.repository.posturingRepository
import com.android.systemui.communal.posturing.shared.model.PosturedState
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PosturingInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val underTest by lazy { kosmos.posturingInteractor }

    @Test
    fun testNoDebugOverride() =
        kosmos.runTest {
            val postured by collectLastValue(underTest.postured)
            assertThat(postured).isFalse()

            posturingRepository.fake.setPosturedState(PosturedState.Postured(1f))
            assertThat(postured).isTrue()
        }

    @Test
    fun testOverriddenByDebugValue() =
        kosmos.runTest {
            val postured by collectLastValue(underTest.postured)
            assertThat(postured).isFalse()

            underTest.setValueForDebug(PosturedState.NotPostured)
            posturingRepository.fake.setPosturedState(PosturedState.Postured(1f))

            // Repository value is overridden by debug value
            assertThat(postured).isFalse()

            underTest.setValueForDebug(PosturedState.Unknown)
            assertThat(postured).isTrue()
        }
}
