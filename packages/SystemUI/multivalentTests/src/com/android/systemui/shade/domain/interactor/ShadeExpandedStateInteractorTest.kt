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

package com.android.systemui.shade.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableSceneContainer
class ShadeExpandedStateInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val testScope = kosmos.testScope
    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private val underTest: ShadeExpandedStateInteractor by lazy {
        kosmos.shadeExpandedStateInteractor
    }

    @Test
    fun expandedElement_qsExpanded_returnsQSElement() =
        testScope.runTest {
            shadeTestUtil.setShadeAndQsExpansion(shadeExpansion = 0f, qsExpansion = 1f)
            val currentlyExpandedElement = underTest.currentlyExpandedElement

            val element = currentlyExpandedElement.value

            assertThat(element).isInstanceOf(QSShadeElement::class.java)
        }

    @Test
    fun expandedElement_shadeExpanded_returnsShade() =
        testScope.runTest {
            shadeTestUtil.setShadeAndQsExpansion(shadeExpansion = 1f, qsExpansion = 0f)

            val element = underTest.currentlyExpandedElement.value

            assertThat(element).isInstanceOf(NotificationShadeElement::class.java)
        }

    @Test
    fun expandedElement_noneExpanded_returnsNull() =
        testScope.runTest {
            shadeTestUtil.setShadeAndQsExpansion(shadeExpansion = 0f, qsExpansion = 0f)

            val element = underTest.currentlyExpandedElement.value

            assertThat(element).isNull()
        }
}
