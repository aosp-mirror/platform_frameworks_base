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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

/*
 * We need this class for the splitShadeBlueprint so `addViews` and `removeViews` will be called
 * when switching to and from splitShade.
 */
class SplitShadeSmartspaceSection
@Inject
constructor(
    keyguardClockViewModel: KeyguardClockViewModel,
    keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    context: Context,
    smartspaceController: LockscreenSmartspaceController,
    keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
) :
    SmartspaceSection(
        keyguardClockViewModel,
        keyguardSmartspaceViewModel,
        context,
        smartspaceController,
        keyguardUnlockAnimationController,
    )
