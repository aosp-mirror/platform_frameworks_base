/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.volume.panel.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.volume.panel.domain.availableCriteria
import com.android.systemui.volume.panel.domain.defaultCriteria
import com.android.systemui.volume.panel.domain.model.ComponentModel
import com.android.systemui.volume.panel.domain.unavailableCriteria
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.composable.enabledComponents
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ComponentsInteractorImplTest : SysuiTestCase() {

    private val kosmos = Kosmos()

    private lateinit var underTest: ComponentsInteractor

    private fun initUnderTest() {
        underTest =
            with(kosmos) {
                ComponentsInteractorImpl(
                    enabledComponents,
                    { defaultCriteria },
                    testScope.backgroundScope,
                    criteriaByKey,
                )
            }
    }

    @Test
    fun componentsAvailability_checked() {
        with(kosmos) {
            testScope.runTest {
                enabledComponents =
                    setOf(
                        BOTTOM_BAR,
                        COMPONENT_1,
                        COMPONENT_2,
                    )
                criteriaByKey =
                    mapOf(
                        BOTTOM_BAR to Provider { availableCriteria },
                        COMPONENT_1 to Provider { unavailableCriteria },
                        COMPONENT_2 to Provider { availableCriteria },
                    )
                initUnderTest()

                val components by collectLastValue(underTest.components)

                assertThat(components)
                    .containsExactly(
                        ComponentModel(BOTTOM_BAR, true),
                        ComponentModel(COMPONENT_1, false),
                        ComponentModel(COMPONENT_2, true),
                    )
            }
        }
    }

    @Test
    fun noCriteria_fallbackToDefaultCriteria() {
        with(kosmos) {
            testScope.runTest {
                enabledComponents =
                    setOf(
                        BOTTOM_BAR,
                        COMPONENT_1,
                        COMPONENT_2,
                    )
                criteriaByKey =
                    mapOf(
                        BOTTOM_BAR to Provider { availableCriteria },
                        COMPONENT_2 to Provider { availableCriteria },
                    )
                defaultCriteria = unavailableCriteria
                initUnderTest()

                val components by collectLastValue(underTest.components)

                assertThat(components)
                    .containsExactly(
                        ComponentModel(BOTTOM_BAR, true),
                        ComponentModel(COMPONENT_1, false),
                        ComponentModel(COMPONENT_2, true),
                    )
            }
        }
    }

    private companion object {
        const val BOTTOM_BAR: VolumePanelComponentKey = "test_bottom_bar"
        const val COMPONENT_1: VolumePanelComponentKey = "test_component:1"
        const val COMPONENT_2: VolumePanelComponentKey = "test_component:2"
    }
}
