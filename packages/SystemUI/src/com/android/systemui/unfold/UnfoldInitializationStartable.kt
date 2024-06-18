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

package com.android.systemui.unfold

import com.android.systemui.CoreStartable
import com.android.systemui.Flags
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder
import java.util.Optional
import javax.inject.Inject

class UnfoldInitializationStartable
@Inject
constructor(
    private val unfoldComponentOptional: Optional<SysUIUnfoldComponent>,
    private val foldStateLoggingProviderOptional: Optional<FoldStateLoggingProvider>,
    private val foldStateLoggerOptional: Optional<FoldStateLogger>,
    @UnfoldBg
    private val unfoldBgTransitionProgressProviderOptional:
        Optional<UnfoldTransitionProgressProvider>,
    private val unfoldTransitionProgressProviderOptional:
        Optional<UnfoldTransitionProgressProvider>,
    private val unfoldTransitionProgressForwarder: Optional<UnfoldTransitionProgressForwarder>
) : CoreStartable {
    override fun start() {
        unfoldComponentOptional.ifPresent { c: SysUIUnfoldComponent ->
            c.getFullScreenLightRevealAnimations().forEach { it: FullscreenLightRevealAnimation ->
                it.init()
            }
            c.getUnfoldTransitionWallpaperController().init()
            c.getUnfoldHapticsPlayer()
            c.getNaturalRotationUnfoldProgressProvider().init()
            c.getUnfoldLatencyTracker().init()
        }

        foldStateLoggingProviderOptional.ifPresent { obj: FoldStateLoggingProvider -> obj.init() }
        foldStateLoggerOptional.ifPresent { obj: FoldStateLogger -> obj.init() }

        val unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider> =
            if (Flags.unfoldAnimationBackgroundProgress()) {
                unfoldBgTransitionProgressProviderOptional
            } else {
                unfoldTransitionProgressProviderOptional
            }
        unfoldTransitionProgressProvider.ifPresent {
            progressProvider: UnfoldTransitionProgressProvider ->
            unfoldTransitionProgressForwarder.ifPresent {
                listener: UnfoldTransitionProgressForwarder ->
                progressProvider.addCallback(listener)
            }
        }
    }
}
