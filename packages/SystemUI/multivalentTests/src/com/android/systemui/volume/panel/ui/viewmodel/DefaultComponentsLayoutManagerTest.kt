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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.testKosmos
import com.android.systemui.volume.panel.mockVolumePanelUiComponent
import com.android.systemui.volume.panel.shared.model.VolumePanelComponentKey
import com.android.systemui.volume.panel.ui.layout.ComponentsLayoutManager
import com.android.systemui.volume.panel.ui.layout.DefaultComponentsLayoutManager
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DefaultComponentsLayoutManagerTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val underTest: ComponentsLayoutManager =
        DefaultComponentsLayoutManager(
            BOTTOM_BAR,
            headerComponents = listOf(COMPONENT_1),
            footerComponents = listOf(COMPONENT_5, COMPONENT_2),
        )

    @Test
    fun correspondingComponents_areSet() {
        val bottomBarComponentState =
            ComponentState(BOTTOM_BAR, kosmos.mockVolumePanelUiComponent, false)
        val component1 = ComponentState(COMPONENT_1, kosmos.mockVolumePanelUiComponent, false)
        val component2 = ComponentState(COMPONENT_2, kosmos.mockVolumePanelUiComponent, false)
        val component3 = ComponentState(COMPONENT_3, kosmos.mockVolumePanelUiComponent, false)
        val component4 = ComponentState(COMPONENT_4, kosmos.mockVolumePanelUiComponent, false)
        val component5 = ComponentState(COMPONENT_5, kosmos.mockVolumePanelUiComponent, false)
        val layout =
            underTest.layout(
                VolumePanelState(0, false, false),
                setOf(
                    bottomBarComponentState,
                    component1,
                    component2,
                    component3,
                    component4,
                    component5,
                )
            )

        Truth.assertThat(layout.bottomBarComponent).isEqualTo(bottomBarComponentState)
        Truth.assertThat(layout.headerComponents)
            .containsExactlyElementsIn(listOf(component1))
            .inOrder()
        Truth.assertThat(layout.footerComponents)
            .containsExactlyElementsIn(listOf(component5, component2))
            .inOrder()
        Truth.assertThat(layout.contentComponents)
            .containsExactlyElementsIn(listOf(component3, component4))
            .inOrder()
    }

    @Test(expected = IllegalStateException::class)
    fun bottomBarAbsence_throwsException() {
        val component1State = ComponentState(COMPONENT_1, kosmos.mockVolumePanelUiComponent, false)
        val component2State = ComponentState(COMPONENT_2, kosmos.mockVolumePanelUiComponent, false)
        underTest.layout(
            VolumePanelState(0, false, false),
            setOf(
                component1State,
                component2State,
            )
        )
    }

    private companion object {
        const val BOTTOM_BAR: VolumePanelComponentKey = "bottom_bar"
        const val COMPONENT_1: VolumePanelComponentKey = "test_component:1"
        const val COMPONENT_2: VolumePanelComponentKey = "test_component:2"
        const val COMPONENT_3: VolumePanelComponentKey = "test_component:3"
        const val COMPONENT_4: VolumePanelComponentKey = "test_component:4"
        const val COMPONENT_5: VolumePanelComponentKey = "test_component:5"
    }
}
