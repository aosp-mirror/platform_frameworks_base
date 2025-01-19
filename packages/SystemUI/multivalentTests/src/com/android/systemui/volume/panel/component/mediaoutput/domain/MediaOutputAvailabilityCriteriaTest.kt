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

package com.android.systemui.volume.panel.component.mediaoutput.domain

import android.content.mockedContext
import android.content.packageManager
import android.content.pm.PackageManager.FEATURE_PC
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MediaOutputAvailabilityCriteriaTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val scope = kosmos.testScope

    private lateinit var underTest: MediaOutputAvailabilityCriteria

    @Before
    fun setup() {
        with(kosmos) {
            underTest = MediaOutputAvailabilityCriteria(kosmos.mockedContext, scope.backgroundScope)
        }
    }

    @Test
    fun isDesktop_unavailable() =
        kosmos.runTest {
            whenever(mockedContext.getPackageManager()).thenReturn(packageManager)
            whenever(packageManager.hasSystemFeature(FEATURE_PC)).thenReturn(true)

            val isAvailable by collectLastValue(underTest.isAvailable())

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun notIsDesktop_available() =
        kosmos.runTest {
            whenever(mockedContext.getPackageManager()).thenReturn(packageManager)
            whenever(packageManager.hasSystemFeature(FEATURE_PC)).thenReturn(false)

            val isAvailable by collectLastValue(underTest.isAvailable())

            assertThat(isAvailable).isTrue()
        }
}
