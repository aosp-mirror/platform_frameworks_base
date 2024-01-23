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
    private val underTest: ComponentsLayoutManager = DefaultComponentsLayoutManager()

    @Test
    fun bottomBar_isSet() {
        val bottomBarComponentState =
            ComponentState(BOTTOM_BAR, kosmos.mockVolumePanelUiComponent, false)
        val layout =
            underTest.layout(
                VolumePanelState(0, false),
                setOf(
                    bottomBarComponentState,
                    ComponentState(COMPONENT_1, kosmos.mockVolumePanelUiComponent, false),
                    ComponentState(COMPONENT_2, kosmos.mockVolumePanelUiComponent, false),
                )
            )

        Truth.assertThat(layout.bottomBarComponent).isEqualTo(bottomBarComponentState)
    }

    @Test
    fun componentsAreInOrder() {
        val bottomBarComponentState =
            ComponentState(BOTTOM_BAR, kosmos.mockVolumePanelUiComponent, false)
        val component1State = ComponentState(COMPONENT_1, kosmos.mockVolumePanelUiComponent, false)
        val component2State = ComponentState(COMPONENT_2, kosmos.mockVolumePanelUiComponent, false)
        val layout =
            underTest.layout(
                VolumePanelState(0, false),
                setOf(
                    bottomBarComponentState,
                    component1State,
                    component2State,
                )
            )

        Truth.assertThat(layout.contentComponents[0]).isEqualTo(component1State)
        Truth.assertThat(layout.contentComponents[1]).isEqualTo(component2State)
    }

    @Test(expected = IllegalStateException::class)
    fun bottomBarAbsence_throwsException() {
        val component1State = ComponentState(COMPONENT_1, kosmos.mockVolumePanelUiComponent, false)
        val component2State = ComponentState(COMPONENT_2, kosmos.mockVolumePanelUiComponent, false)
        underTest.layout(
            VolumePanelState(0, false),
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
    }
}
