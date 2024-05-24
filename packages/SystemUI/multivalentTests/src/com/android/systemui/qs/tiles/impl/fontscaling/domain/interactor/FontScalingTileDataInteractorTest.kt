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

package com.android.systemui.qs.tiles.impl.fontscaling.domain.interactor

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FontScalingTileDataInteractorTest : SysuiTestCase() {
    private val underTest: FontScalingTileDataInteractor = FontScalingTileDataInteractor()
    private val testUser = UserHandle.of(1)

    @Test
    fun collectsExactlyOneValue() = runTest {
        val flowValues by
            collectValues(underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()

        Truth.assertThat(flowValues.size).isEqualTo(1)
    }

    @Test
    fun lastValueIsNotEmpty() = runTest {
        val flowValue by
            collectLastValue(underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()

        Truth.assertThat(flowValue).isNotNull()
    }

    @Test
    fun isAvailable() = runTest {
        val availability by collectLastValue(underTest.availability(testUser))

        Truth.assertThat(availability).isTrue()
    }
}
