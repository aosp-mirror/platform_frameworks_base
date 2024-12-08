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

package com.android.systemui.communal.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import android.service.dream.dreamManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@EnableFlags(FLAG_GLANCEABLE_HUB_V2)
@RunWith(AndroidJUnit4::class)
class CommunalToDreamButtonViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest: CommunalToDreamButtonViewModel by lazy {
        kosmos.communalToDreamButtonViewModel
    }

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        underTest.activateIn(testScope)
    }

    @Test
    fun shouldShowDreamButtonOnHub_trueWhenCanDream() =
        with(kosmos) {
            runTest {
                whenever(dreamManager.canStartDreaming(any())).thenReturn(true)
                whenever(batteryController.isPluggedIn()).thenReturn(true)

                val shouldShowButton by collectLastValue(underTest.shouldShowDreamButtonOnHub)
                assertThat(shouldShowButton).isTrue()
            }
        }

    @Test
    fun shouldShowDreamButtonOnHub_falseWhenCannotDream() =
        with(kosmos) {
            runTest {
                whenever(dreamManager.canStartDreaming(any())).thenReturn(false)
                whenever(batteryController.isPluggedIn()).thenReturn(true)

                val shouldShowButton by collectLastValue(underTest.shouldShowDreamButtonOnHub)
                assertThat(shouldShowButton).isFalse()
            }
        }

    @Test
    fun shouldShowDreamButtonOnHub_falseWhenNotPluggedIn() =
        with(kosmos) {
            runTest {
                whenever(dreamManager.canStartDreaming(any())).thenReturn(true)
                whenever(batteryController.isPluggedIn()).thenReturn(false)

                val shouldShowButton by collectLastValue(underTest.shouldShowDreamButtonOnHub)
                assertThat(shouldShowButton).isFalse()
            }
        }

    @Test
    fun onShowDreamButtonTap_startsDream() =
        with(kosmos) {
            runTest {
                underTest.onShowDreamButtonTap()
                runCurrent()

                verify(dreamManager).startDream()
            }
        }
}
