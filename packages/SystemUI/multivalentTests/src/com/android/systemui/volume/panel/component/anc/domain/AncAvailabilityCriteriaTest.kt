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

package com.android.systemui.volume.panel.component.anc.domain

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.panel.component.anc.FakeSliceFactory
import com.android.systemui.volume.panel.component.anc.ancSliceInteractor
import com.android.systemui.volume.panel.component.anc.ancSliceRepository
import com.android.systemui.volume.panel.component.anc.sliceViewManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AncAvailabilityCriteriaTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: AncAvailabilityCriteria

    @Before
    fun setup() {
        with(kosmos) {
            whenever(sliceViewManager.bindSlice(any<Uri>())).thenReturn(mock {})

            underTest = AncAvailabilityCriteria(ancSliceInteractor)
        }
    }

    @Test
    fun noSlice_unavailable() {
        with(kosmos) {
            testScope.runTest {
                ancSliceRepository.putSlice(1, null)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isFalse()
            }
        }
    }

    @Test
    fun hasSlice_available() {
        with(kosmos) {
            testScope.runTest {
                ancSliceRepository.putSlice(
                    1,
                    FakeSliceFactory.createSlice(hasError = false, hasSliceItem = true)
                )

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isTrue()
            }
        }
    }
}
