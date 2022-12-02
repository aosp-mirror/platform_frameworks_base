/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.app.StatusBarManager
import android.content.Context
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.CameraGestureHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class CameraQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var cameraGestureHelper: CameraGestureHelper
    @Mock private lateinit var context: Context

    private lateinit var underTest: CameraQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CameraQuickAffordanceConfig(
                context,
            ) {
                cameraGestureHelper
            }
    }

    @Test
    fun `affordance triggered -- camera launch called`() {
        // When
        val result = underTest.onTriggered(null)

        // Then
        verify(cameraGestureHelper)
            .launchCamera(StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE)
        assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
    }
}
