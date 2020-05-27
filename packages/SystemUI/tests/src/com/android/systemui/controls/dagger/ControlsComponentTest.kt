/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.dagger

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import dagger.Lazy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsComponentTest : SysuiTestCase() {

    @Mock
    private lateinit var controller: ControlsController
    @Mock
    private lateinit var uiController: ControlsUiController
    @Mock
    private lateinit var listingController: ControlsListingController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testFeatureEnabled() {
        val component = ControlsComponent(
                true,
                Lazy { controller },
                Lazy { uiController },
                Lazy { listingController }
        )

        assertTrue(component.getControlsController().isPresent)
        assertEquals(controller, component.getControlsController().get())
        assertTrue(component.getControlsUiController().isPresent)
        assertEquals(uiController, component.getControlsUiController().get())
        assertTrue(component.getControlsListingController().isPresent)
        assertEquals(listingController, component.getControlsListingController().get())
    }

    @Test
    fun testFeatureDisabled() {
        val component = ControlsComponent(
                false,
                Lazy { controller },
                Lazy { uiController },
                Lazy { listingController }
        )

        assertFalse(component.getControlsController().isPresent)
        assertFalse(component.getControlsUiController().isPresent)
        assertFalse(component.getControlsListingController().isPresent)
    }
}