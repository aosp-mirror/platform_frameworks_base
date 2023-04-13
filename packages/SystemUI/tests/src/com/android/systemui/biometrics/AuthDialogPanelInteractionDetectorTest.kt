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

package com.android.systemui.biometrics

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shade.ShadeExpansionStateManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AuthDialogPanelInteractionDetectorTest : SysuiTestCase() {

    private lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    private lateinit var detector: AuthDialogPanelInteractionDetector

    @Mock private lateinit var action: Runnable

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        shadeExpansionStateManager = ShadeExpansionStateManager()
        detector =
            AuthDialogPanelInteractionDetector(shadeExpansionStateManager, mContext.mainExecutor)
    }

    @Test
    fun testEnableDetector_expandWithTrack_shouldPostRunnable() {
        detector.enable(action)
        shadeExpansionStateManager.onPanelExpansionChanged(1.0f, true, true, 0f)
        verify(action).run()
    }

    @Test
    fun testEnableDetector_trackOnly_shouldPostRunnable() {
        detector.enable(action)
        shadeExpansionStateManager.onPanelExpansionChanged(1.0f, false, true, 0f)
        verify(action).run()
    }

    @Test
    fun testEnableDetector_expandOnly_shouldPostRunnable() {
        detector.enable(action)
        shadeExpansionStateManager.onPanelExpansionChanged(1.0f, true, false, 0f)
        verify(action).run()
    }

    @Test
    fun testEnableDetector_expandWithoutFraction_shouldPostRunnable() {
        detector.enable(action)
        // simulate headsup notification
        shadeExpansionStateManager.onPanelExpansionChanged(0.0f, true, false, 0f)
        verifyZeroInteractions(action)
    }

    @Test
    fun testEnableDetector_shouldNotPostRunnable() {
        detector.enable(action)
        detector.disable()
        shadeExpansionStateManager.onPanelExpansionChanged(1.0f, true, true, 0f)
        verifyZeroInteractions(action)
    }
}
