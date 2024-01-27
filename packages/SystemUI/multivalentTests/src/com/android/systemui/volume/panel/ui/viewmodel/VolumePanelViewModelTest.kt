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

package com.android.systemui.volume.panel.ui.viewmodel

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.fakeConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.volume.panel.componentByKey
import com.android.systemui.volume.panel.componentsLayoutManager
import com.android.systemui.volume.panel.criteriaByKey
import com.android.systemui.volume.panel.dagger.factory.KosmosVolumePanelComponentFactory
import com.android.systemui.volume.panel.mockVolumePanelUiComponentProvider
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.layout.FakeComponentsLayoutManager
import com.android.systemui.volume.panel.unavailableCriteria
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VolumePanelViewModelTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            componentsLayoutManager = FakeComponentsLayoutManager { it.key == BOTTOM_BAR }
        }

    private val testableResources = context.orCreateTestableResources

    private lateinit var underTest: VolumePanelViewModel

    private fun initUnderTest() {
        underTest =
            VolumePanelViewModel(
                testableResources.resources,
                KosmosVolumePanelComponentFactory(kosmos),
                kosmos.fakeConfigurationController,
            )
    }

    @Test
    fun dismissingPanel_changesVisibility() {
        with(kosmos) {
            testScope.runTest {
                initUnderTest()
                assertThat(underTest.volumePanelState.value.isVisible).isTrue()

                underTest.dismissPanel()
                runCurrent()

                assertThat(underTest.volumePanelState.value.isVisible).isFalse()
            }
        }
    }

    @Test
    fun orientationChanges_panelOrientationChanges() {
        with(kosmos) {
            testScope.runTest {
                initUnderTest()
                val volumePanelState by collectLastValue(underTest.volumePanelState)
                testableResources.overrideConfiguration(
                    Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
                )
                assertThat(volumePanelState!!.orientation)
                    .isEqualTo(Configuration.ORIENTATION_PORTRAIT)

                fakeConfigurationController.onConfigurationChanged(
                    Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
                )
                runCurrent()

                assertThat(volumePanelState!!.orientation)
                    .isEqualTo(Configuration.ORIENTATION_LANDSCAPE)
            }
        }
    }

    @Test
    fun components_areReturned() {
        with(kosmos) {
            testScope.runTest {
                componentByKey =
                    mapOf(
                        COMPONENT_1 to mockVolumePanelUiComponentProvider,
                        COMPONENT_2 to mockVolumePanelUiComponentProvider,
                        BOTTOM_BAR to mockVolumePanelUiComponentProvider,
                    )
                criteriaByKey = mapOf(COMPONENT_2 to unavailableCriteria)
                initUnderTest()

                val componentsLayout by collectLastValue(underTest.componentsLayout)
                runCurrent()

                assertThat(componentsLayout!!.contentComponents).hasSize(2)
                assertThat(componentsLayout!!.contentComponents[0].key).isEqualTo(COMPONENT_1)
                assertThat(componentsLayout!!.contentComponents[0].isVisible).isTrue()
                assertThat(componentsLayout!!.contentComponents[1].key).isEqualTo(COMPONENT_2)
                assertThat(componentsLayout!!.contentComponents[1].isVisible).isFalse()
                assertThat(componentsLayout!!.bottomBarComponent.key).isEqualTo(BOTTOM_BAR)
                assertThat(componentsLayout!!.bottomBarComponent.isVisible).isTrue()
            }
        }
    }

    private companion object {
        const val BOTTOM_BAR: VolumePanelComponentKey = "test_bottom_bar"
        const val COMPONENT_1: VolumePanelComponentKey = "test_component:1"
        const val COMPONENT_2: VolumePanelComponentKey = "test_component:2"
    }
}
