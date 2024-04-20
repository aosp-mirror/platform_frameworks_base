/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.content.applicationContext
import android.os.fakeExecutorHandler
import com.android.systemui.keyguard.domain.interactor.keyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardClockInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.viewmodel.keyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardSmartspaceViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.mockito.mock
import java.util.Optional

val Kosmos.keyguardClockSection: ClockSection by
    Kosmos.Fixture {
        ClockSection(
            clockInteractor = keyguardClockInteractor,
            keyguardClockViewModel = keyguardClockViewModel,
            context = applicationContext,
            smartspaceViewModel = keyguardSmartspaceViewModel,
            blueprintInteractor = { keyguardBlueprintInteractor },
        )
    }

val Kosmos.defaultKeyguardBlueprint by
    Kosmos.Fixture {
        DefaultKeyguardBlueprint(
            defaultIndicationAreaSection = mock(),
            defaultDeviceEntrySection = mock(),
            defaultShortcutsSection = mock(),
            defaultAmbientIndicationAreaSection = Optional.of(mock()),
            defaultSettingsPopupMenuSection = mock(),
            defaultStatusViewSection = mock(),
            defaultStatusBarSection = mock(),
            defaultNotificationStackScrollLayoutSection = mock(),
            aodNotificationIconsSection = mock(),
            aodBurnInSection = mock(),
            communalTutorialIndicatorSection = mock(),
            clockSection = keyguardClockSection,
            smartspaceSection = mock(),
            keyguardSliceViewSection = mock(),
            udfpsAccessibilityOverlaySection = mock(),
            accessibilityActionsSection = mock(),
        )
    }

val Kosmos.splitShadeBlueprint by
    Kosmos.Fixture {
        SplitShadeKeyguardBlueprint(
            defaultIndicationAreaSection = mock(),
            defaultDeviceEntrySection = mock(),
            defaultShortcutsSection = mock(),
            defaultAmbientIndicationAreaSection = Optional.of(mock()),
            defaultSettingsPopupMenuSection = mock(),
            defaultStatusViewSection = mock(),
            defaultStatusBarSection = mock(),
            splitShadeNotificationStackScrollLayoutSection = mock(),
            splitShadeGuidelines = mock(),
            aodNotificationIconsSection = mock(),
            aodBurnInSection = mock(),
            communalTutorialIndicatorSection = mock(),
            clockSection = keyguardClockSection,
            smartspaceSection = mock(),
            mediaSection = mock(),
            accessibilityActionsSection = mock(),
        )
    }

val Kosmos.keyguardBlueprintRepository by
    Kosmos.Fixture {
        KeyguardBlueprintRepository(
            blueprints =
                setOf(
                    defaultKeyguardBlueprint,
                    splitShadeBlueprint,
                ),
            handler = fakeExecutorHandler,
            assert = mock(),
        )
    }
