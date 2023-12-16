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
 * limitations under the License
 */
package com.android.systemui.qs.pipeline.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shade.ShadeController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class PanelInteractorImplTest : SysuiTestCase() {

    @Mock private lateinit var shadeController: ShadeController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun openPanels_callsCentralSurfaces() {
        val underTest = PanelInteractorImpl(shadeController)

        underTest.openPanels()

        verify(shadeController).postAnimateExpandQs()
    }

    @Test
    fun collapsePanels_callsCentralSurfaces() {
        val underTest = PanelInteractorImpl(shadeController)

        underTest.collapsePanels()

        verify(shadeController).postAnimateCollapseShade()
    }

    @Test
    fun forceCollapsePanels_callsCentralSurfaces() {
        val underTest = PanelInteractorImpl(shadeController)

        underTest.forceCollapsePanels()

        verify(shadeController).postAnimateForceCollapseShade()
    }
}
