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

package com.android.systemui.settings.brightness.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.brightness.data.repository.brightnessMirrorShowingRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessMirrorShowingInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest =
        BrightnessMirrorShowingInteractor(kosmos.brightnessMirrorShowingRepository)

    @Test
    fun isShowing_setAndFlow() =
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)

            assertThat(isShowing).isFalse()

            underTest.setMirrorShowing(true)
            assertThat(isShowing).isTrue()

            underTest.setMirrorShowing(false)
            assertThat(isShowing).isFalse()
        }
}
