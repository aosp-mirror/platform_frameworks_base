/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class BouncerPanelExpansionCalculatorTest : SysuiTestCase() {
    @Test
    fun testGetHostViewScaledExpansion() {
        assertThat(BouncerPanelExpansionCalculator.showBouncerProgress(1f))
                .isEqualTo(1f)
        assertThat(BouncerPanelExpansionCalculator.showBouncerProgress(0.9f))
                .isEqualTo(1f)
        assertThat(BouncerPanelExpansionCalculator.showBouncerProgress(0.59f))
                .isEqualTo(0f)
        assertThat(BouncerPanelExpansionCalculator.showBouncerProgress(0f))
                .isEqualTo(0f)
        assertEquals(BouncerPanelExpansionCalculator
                .showBouncerProgress(0.8f), 2f / 3f, 0.01f)
    }

    @Test
    fun testGetBackScrimScaledExpansion() {
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(1f))
                .isEqualTo(1f)
        assertEquals(BouncerPanelExpansionCalculator
                .aboutToShowBouncerProgress(0.95f), 1f / 2f, 0.01f)
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(0.9f))
                .isEqualTo(0f)
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(0.5f))
                .isEqualTo(0f)
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(0f))
                .isEqualTo(0f)
    }

    @Test
    fun testGetKeyguardClockScaledExpansion() {
        assertThat(BouncerPanelExpansionCalculator.getKeyguardClockScaledExpansion(1f))
                .isEqualTo(1f)
        assertEquals(BouncerPanelExpansionCalculator
                .getKeyguardClockScaledExpansion(0.8f), 1f / 3f, 0.01f)
        assertThat(BouncerPanelExpansionCalculator.getKeyguardClockScaledExpansion(0.7f))
                .isEqualTo(0f)
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(0.5f))
                .isEqualTo(0f)
        assertThat(BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(0f))
                .isEqualTo(0f)
    }
}
