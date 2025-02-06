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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.sections.AccessibilityActionsSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodNotificationIconsSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodPromotedNotificationSection
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultUdfpsAccessibilityOverlaySection
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSectionsModule.Companion.KEYGUARD_AMBIENT_INDICATION_AREA_SECTION
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSliceViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
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
    accessibilityActionsSection: AccessibilityActionsSection,
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultDeviceEntrySection: DefaultDeviceEntrySection,
    defaultShortcutsSection: DefaultShortcutsSection,
    @Named(KEYGUARD_AMBIENT_INDICATION_AREA_SECTION)
    defaultAmbientIndicationAreaSection: Optional<KeyguardSection>,
    defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection,
    defaultStatusBarSection: DefaultStatusBarSection,
    defaultNotificationStackScrollLayoutSection: DefaultNotificationStackScrollLayoutSection,
    aodPromotedNotificationSection: AodPromotedNotificationSection,
    aodNotificationIconsSection: AodNotificationIconsSection,
    aodBurnInSection: AodBurnInSection,
    clockSection: ClockSection,
    smartspaceSection: SmartspaceSection,
    keyguardSliceViewSection: KeyguardSliceViewSection,
    udfpsAccessibilityOverlaySection: DefaultUdfpsAccessibilityOverlaySection,
) : KeyguardBlueprint {
    override val id: String = DEFAULT

    override val sections =
        listOfNotNull(
            accessibilityActionsSection,
            defaultIndicationAreaSection,
            defaultShortcutsSection,
            defaultAmbientIndicationAreaSection.getOrNull(),
            defaultSettingsPopupMenuSection,
            defaultStatusBarSection,
            defaultNotificationStackScrollLayoutSection,
            aodNotificationIconsSection,
            aodPromotedNotificationSection,
            smartspaceSection,
            aodBurnInSection,
            clockSection,
            keyguardSliceViewSection,
            defaultDeviceEntrySection,
            udfpsAccessibilityOverlaySection, // Add LAST: Intentionally has z-order above others
        )

    companion object {
        const val DEFAULT = "default"
    }
}
