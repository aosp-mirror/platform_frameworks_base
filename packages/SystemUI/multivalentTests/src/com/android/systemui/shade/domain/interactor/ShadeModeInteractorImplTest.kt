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

package com.android.systemui.shade.domain.interactor

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeModeInteractorImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: ShadeModeInteractor

    @Before
    fun setUp() {
        underTest = kosmos.shadeModeInteractor
    }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun legacyShadeMode_narrowScreen_singleShade() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)
            kosmos.shadeRepository.setShadeLayoutWide(false)

            assertThat(shadeMode).isEqualTo(ShadeMode.Single)
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun legacyShadeMode_wideScreen_splitShade() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)
            kosmos.shadeRepository.setShadeLayoutWide(true)

            assertThat(shadeMode).isEqualTo(ShadeMode.Split)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun shadeMode_wideScreen_isDual() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)
            kosmos.shadeRepository.setShadeLayoutWide(true)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun shadeMode_narrowScreen_isDual() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)
            kosmos.shadeRepository.setShadeLayoutWide(false)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun isDualShade_flagEnabled_true() =
        testScope.runTest {
            // Initiate collection.
            val shadeMode by collectLastValue(underTest.shadeMode)

            assertThat(underTest.isDualShade).isTrue()
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun isDualShade_flagDisabled_false() =
        testScope.runTest {
            // Initiate collection.
            val shadeMode by collectLastValue(underTest.shadeMode)

            assertThat(underTest.isDualShade).isFalse()
        }

    @Test
    fun getTopEdgeSplitFraction_narrowScreen_splitInHalf() =
        testScope.runTest {
            // Ensure isShadeLayoutWide is collected.
            val isShadeLayoutWide by collectLastValue(underTest.isShadeLayoutWide)
            kosmos.shadeRepository.setShadeLayoutWide(false)

            assertThat(underTest.getTopEdgeSplitFraction()).isEqualTo(0.5f)
        }

    @Test
    fun getTopEdgeSplitFraction_wideScreen_leftSideLarger() =
        testScope.runTest {
            // Ensure isShadeLayoutWide is collected.
            val isShadeLayoutWide by collectLastValue(underTest.isShadeLayoutWide)
            kosmos.shadeRepository.setShadeLayoutWide(true)

            assertThat(underTest.getTopEdgeSplitFraction()).isGreaterThan(0.5f)
        }
}
