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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.blueprints

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultAmbientIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultLockIconSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultLongPressHandlingSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeGuidelines
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class DefaultKeyguardBlueprintTest : SysuiTestCase() {
    private lateinit var underTest: DefaultKeyguardBlueprint
    private lateinit var rootView: KeyguardRootView
    @Mock private lateinit var defaultIndicationAreaSection: DefaultIndicationAreaSection
    @Mock private lateinit var defaultLockIconSection: DefaultLockIconSection
    @Mock private lateinit var defaultShortcutsSection: DefaultShortcutsSection
    @Mock
    private lateinit var defaultAmbientIndicationAreaSection: DefaultAmbientIndicationAreaSection
    @Mock private lateinit var defaultLongPressHandlingSection: DefaultLongPressHandlingSection
    @Mock private lateinit var defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection
    @Mock private lateinit var defaultStatusViewSection: DefaultStatusViewSection
    @Mock private lateinit var splitShadeGuidelines: SplitShadeGuidelines

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        rootView = KeyguardRootView(context, null)
        underTest =
            DefaultKeyguardBlueprint(
                defaultIndicationAreaSection,
                defaultLockIconSection,
                defaultShortcutsSection,
                defaultAmbientIndicationAreaSection,
                defaultLongPressHandlingSection,
                defaultSettingsPopupMenuSection,
                defaultStatusViewSection,
                splitShadeGuidelines,
            )
    }

    @Test
    fun apply() {
        val cs = ConstraintSet()
        underTest.apply(cs)
        verify(defaultIndicationAreaSection).apply(cs)
        verify(defaultLockIconSection).apply(cs)
        verify(defaultShortcutsSection).apply(cs)
        verify(defaultAmbientIndicationAreaSection).apply(cs)
        verify(defaultSettingsPopupMenuSection).apply(cs)
        verify(defaultStatusViewSection).apply(cs)
        verify(splitShadeGuidelines).apply(cs)
    }
}
