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

package com.android.systemui.statusbar.domain.interactor

import com.android.systemui.keyguard.domain.interactor.keyguardSurfaceBehindInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.windowManagerLockscreenVisibilityInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.power.domain.interactor.powerInteractor

val Kosmos.statusBarKeyguardViewManagerInteractor by
    Kosmos.Fixture {
        StatusBarKeyguardViewManagerInteractor(
            keyguardTransitionInteractor = this.keyguardTransitionInteractor,
            keyguardOcclusionInteractor = this.keyguardOcclusionInteractor,
            powerInteractor = this.powerInteractor,
            wmLockscreenVisibilityInteractor = windowManagerLockscreenVisibilityInteractor,
            surfaceBehindInteractor = keyguardSurfaceBehindInteractor,
        )
    }
