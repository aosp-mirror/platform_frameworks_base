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
import com.android.systemui.keyguard.ui.view.layout.sections.AlignShortcutsToUdfpsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultAmbientIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultLockIconSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SplitShadeGuidelines
import javax.inject.Inject

/** Vertically aligns the shortcuts with the udfps. */
@SysUISingleton
class ShortcutsBesideUdfpsKeyguardBlueprint
@Inject
constructor(
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultLockIconSection: DefaultLockIconSection,
    defaultAmbientIndicationAreaSection: DefaultAmbientIndicationAreaSection,
    defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection,
    alignShortcutsToUdfpsSection: AlignShortcutsToUdfpsSection,
    defaultStatusViewSection: DefaultStatusViewSection,
    splitShadeGuidelines: SplitShadeGuidelines,
    defaultNotificationStackScrollLayoutSection: DefaultNotificationStackScrollLayoutSection,
) : KeyguardBlueprint {
    override val id: String = SHORTCUTS_BESIDE_UDFPS

    override val sections =
        arrayOf(
            defaultIndicationAreaSection,
            defaultLockIconSection,
            defaultAmbientIndicationAreaSection,
            defaultSettingsPopupMenuSection,
            alignShortcutsToUdfpsSection,
            defaultStatusViewSection,
            defaultNotificationStackScrollLayoutSection,
            splitShadeGuidelines,
        )

    companion object {
        const val SHORTCUTS_BESIDE_UDFPS = "shortcutsBesideUdfps"
    }
}
