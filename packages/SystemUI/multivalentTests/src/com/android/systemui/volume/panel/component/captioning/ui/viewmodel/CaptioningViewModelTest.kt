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

package com.android.systemui.volume.panel.component.captioning.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.view.accessibility.data.repository.captioningInteractor
import com.android.systemui.view.accessibility.data.repository.captioningRepository
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
class CaptioningViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: CaptioningViewModel

    @Before
    fun setup() {
        underTest =
            with(kosmos) {
                CaptioningViewModel(
                    context,
                    captioningInteractor,
                    testScope.backgroundScope,
                    uiEventLogger,
                )
            }
    }

    @Test
    fun captioningDisabled_buttonViewModel_notChecked() {
        with(kosmos) {
            testScope.runTest {
                captioningRepository.setIsSystemAudioCaptioningEnabled(false)

                val buttonViewModel by collectLastValue(underTest.buttonViewModel)
                runCurrent()

                assertThat(buttonViewModel!!.isActive).isFalse()
            }
        }
    }

    @Test
    fun captioningDisabled_buttonViewModel_checked() {
        with(kosmos) {
            testScope.runTest {
                captioningRepository.setIsSystemAudioCaptioningEnabled(true)

                val buttonViewModel by collectLastValue(underTest.buttonViewModel)
                runCurrent()

                assertThat(buttonViewModel!!.isActive).isTrue()
            }
        }
    }
}
