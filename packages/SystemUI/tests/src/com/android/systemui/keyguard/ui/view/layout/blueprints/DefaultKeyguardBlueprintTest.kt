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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.ui.view.layout.sections.CommunalTutorialIndicatorSection
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodNotificationIconsSection
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeGuidelines
import com.android.systemui.util.mockito.whenever
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class DefaultKeyguardBlueprintTest : SysuiTestCase() {
    private lateinit var underTest: DefaultKeyguardBlueprint
    private lateinit var rootView: KeyguardRootView
    @Mock private lateinit var defaultIndicationAreaSection: DefaultIndicationAreaSection
    @Mock private lateinit var mDefaultDeviceEntrySection: DefaultDeviceEntrySection
    @Mock private lateinit var defaultShortcutsSection: DefaultShortcutsSection
    @Mock private lateinit var defaultAmbientIndicationAreaSection: Optional<KeyguardSection>
    @Mock private lateinit var defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection
    @Mock private lateinit var defaultStatusViewSection: DefaultStatusViewSection
    @Mock private lateinit var defaultStatusBarViewSection: DefaultStatusBarSection
    @Mock private lateinit var defaultNSSLSection: DefaultNotificationStackScrollLayoutSection
    @Mock private lateinit var splitShadeGuidelines: SplitShadeGuidelines
    @Mock private lateinit var aodNotificationIconsSection: AodNotificationIconsSection
    @Mock private lateinit var aodBurnInSection: AodBurnInSection
    @Mock private lateinit var communalTutorialIndicatorSection: CommunalTutorialIndicatorSection
    @Mock private lateinit var clockSection: ClockSection
    @Mock private lateinit var smartspaceSection: SmartspaceSection

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        rootView = KeyguardRootView(context, null)
        underTest =
            DefaultKeyguardBlueprint(
                defaultIndicationAreaSection,
                mDefaultDeviceEntrySection,
                defaultShortcutsSection,
                defaultAmbientIndicationAreaSection,
                defaultSettingsPopupMenuSection,
                defaultStatusViewSection,
                defaultStatusBarViewSection,
                defaultNSSLSection,
                aodNotificationIconsSection,
                aodBurnInSection,
                communalTutorialIndicatorSection,
                clockSection,
                smartspaceSection,
            )
    }

    @Test
    fun replaceViews() {
        val constraintLayout = ConstraintLayout(context, null)
        underTest.replaceViews(null, constraintLayout)
        underTest.sections.forEach { verify(it)?.addViews(constraintLayout) }
    }

    @Test
    fun replaceViews_withPrevBlueprint() {
        val prevBlueprint = mock(KeyguardBlueprint::class.java)
        val someSection = mock(KeyguardSection::class.java)
        whenever(prevBlueprint.sections)
            .thenReturn(underTest.sections.minus(mDefaultDeviceEntrySection).plus(someSection))
        val constraintLayout = ConstraintLayout(context, null)
        underTest.replaceViews(prevBlueprint, constraintLayout)
        underTest.sections.minus(mDefaultDeviceEntrySection).forEach {
            verify(it, never())?.addViews(constraintLayout)
        }

        verify(mDefaultDeviceEntrySection).addViews(constraintLayout)
        verify(someSection).removeViews(constraintLayout)
    }

    @Test
    fun deviceEntryIconIsOnTop() {
        val constraintLayout = ConstraintLayout(context, null)
        underTest.replaceViews(null, constraintLayout)
        underTest.sections.forEach { verify(it)?.addViews(constraintLayout) }
    }

    @Test
    fun applyConstraints() {
        val cs = ConstraintSet()
        underTest.applyConstraints(cs)
        underTest.sections.forEach { verify(it)?.applyConstraints(cs) }
    }
}
