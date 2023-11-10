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
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodNotificationIconsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultAmbientIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntryIconSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeGuidelines
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeNotificationStackScrollLayoutSection
import javax.inject.Inject

/**
 * Split-shade layout, mostly used for larger devices like foldables and tablets when in landscape
 * orientation.
 */
@SysUISingleton
@JvmSuppressWildcards
class SplitShadeKeyguardBlueprint
@Inject
constructor(
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultDeviceEntryIconSection: DefaultDeviceEntryIconSection,
    defaultShortcutsSection: DefaultShortcutsSection,
    defaultAmbientIndicationAreaSection: DefaultAmbientIndicationAreaSection,
    defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection,
    defaultStatusViewSection: DefaultStatusViewSection,
    defaultStatusBarSection: DefaultStatusBarSection,
    splitShadeNotificationStackScrollLayoutSection: SplitShadeNotificationStackScrollLayoutSection,
    splitShadeGuidelines: SplitShadeGuidelines,
    aodNotificationIconsSection: AodNotificationIconsSection,
    aodBurnInSection: AodBurnInSection,
    communalTutorialIndicatorSection: CommunalTutorialIndicatorSection,
) : KeyguardBlueprint {
    override val id: String = ID

    override val sections =
        listOf(
            defaultIndicationAreaSection,
            defaultDeviceEntryIconSection,
            defaultShortcutsSection,
            defaultAmbientIndicationAreaSection,
            defaultSettingsPopupMenuSection,
            defaultStatusViewSection,
            defaultStatusBarSection,
            splitShadeNotificationStackScrollLayoutSection,
            splitShadeGuidelines,
            aodNotificationIconsSection,
            aodBurnInSection,
            communalTutorialIndicatorSection,
        )

    companion object {
        const val ID = "split-shade"
    }
}
