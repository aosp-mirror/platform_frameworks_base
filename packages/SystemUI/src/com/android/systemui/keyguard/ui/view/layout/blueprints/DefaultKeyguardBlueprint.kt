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

import com.android.systemui.communal.ui.view.layout.sections.CommunalTutorialIndicatorSection
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.items.ClockSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodNotificationIconsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntryIconSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSectionsModule.Companion.KEYGUARD_AMBIENT_INDICATION_AREA_SECTION
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeGuidelines
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import kotlin.jvm.optionals.getOrNull

/**
 * Positions elements of the lockscreen to the default position.
 *
 * This will be the most common use case for phones in portrait mode.
 */
@SysUISingleton
@JvmSuppressWildcards
class DefaultKeyguardBlueprint
@Inject
constructor(
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultDeviceEntryIconSection: DefaultDeviceEntryIconSection,
    defaultShortcutsSection: DefaultShortcutsSection,
    @Named(KEYGUARD_AMBIENT_INDICATION_AREA_SECTION)
    defaultAmbientIndicationAreaSection: Optional<KeyguardSection>,
    defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection,
    defaultStatusViewSection: DefaultStatusViewSection,
    defaultStatusBarSection: DefaultStatusBarSection,
    defaultNotificationStackScrollLayoutSection: DefaultNotificationStackScrollLayoutSection,
    aodNotificationIconsSection: AodNotificationIconsSection,
    aodBurnInSection: AodBurnInSection,
    communalTutorialIndicatorSection: CommunalTutorialIndicatorSection,
    clockSection: ClockSection,
    smartspaceSection: SmartspaceSection
) : KeyguardBlueprint {
    override val id: String = DEFAULT

    override val sections =
        listOfNotNull(
            defaultIndicationAreaSection,
            defaultDeviceEntryIconSection,
            defaultShortcutsSection,
            defaultAmbientIndicationAreaSection.getOrNull(),
            defaultSettingsPopupMenuSection,
            defaultStatusViewSection,
            defaultStatusBarSection,
            defaultNotificationStackScrollLayoutSection,
            aodNotificationIconsSection,
            aodBurnInSection,
            communalTutorialIndicatorSection,
            clockSection,
            smartspaceSection
        )

    companion object {
        const val DEFAULT = "default"
    }
}
