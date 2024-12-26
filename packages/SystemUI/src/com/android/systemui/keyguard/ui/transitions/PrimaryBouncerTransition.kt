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

package com.android.systemui.keyguard.ui.transitions

import android.content.res.Resources
import android.util.MathUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToOccludedTransitionViewModel
import com.android.systemui.res.R
import com.android.systemui.window.flag.WindowBlurFlag
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Each PrimaryBouncerTransition is responsible for updating various UI states based on the nature
 * of the transition.
 *
 * MUST list implementing classes in dagger module [PrimaryBouncerTransitionModule].
 */
interface PrimaryBouncerTransition {
    /** Radius of blur applied to the window's root view. */
    val windowBlurRadius: Flow<Float>

    fun transitionProgressToBlurRadius(
        starBlurRadius: Float,
        endBlurRadius: Float,
        transitionProgress: Float,
    ): Float {
        return MathUtils.lerp(starBlurRadius, endBlurRadius, transitionProgress)
    }
}

/**
 * Module that installs all the transitions from different keyguard states to and away from the
 * primary bouncer.
 */
@ExperimentalCoroutinesApi
@Module
interface PrimaryBouncerTransitionModule {
    @Binds
    @IntoSet
    fun fromAod(impl: AodToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromAlternateBouncer(
        impl: AlternateBouncerToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromDozing(impl: DozingToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromLockscreen(
        impl: LockscreenToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromGlanceableHub(
        impl: GlanceableHubToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromOccluded(impl: OccludedToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toAod(impl: PrimaryBouncerToAodTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toLockscreen(impl: PrimaryBouncerToLockscreenTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toDozing(impl: PrimaryBouncerToDozingTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toGlanceableHub(
        impl: PrimaryBouncerToGlanceableHubTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toGone(impl: PrimaryBouncerToGoneTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toOccluded(impl: PrimaryBouncerToOccludedTransitionViewModel): PrimaryBouncerTransition

    companion object {
        @Provides
        @SysUISingleton
        fun provideBlurConfig(@Main resources: Resources): BlurConfig {
            val minBlurRadius = resources.getDimensionPixelSize(R.dimen.min_window_blur_radius)
            val maxBlurRadius =
                if (WindowBlurFlag.isEnabled) {
                    resources.getDimensionPixelSize(R.dimen.max_shade_window_blur_radius)
                } else {
                    resources.getDimensionPixelSize(R.dimen.max_window_blur_radius)
                }
            return BlurConfig(minBlurRadius.toFloat(), maxBlurRadius.toFloat())
        }
    }
}

/** Config that provides the max and min blur radius for the window blurs. */
data class BlurConfig(val minBlurRadiusPx: Float, val maxBlurRadiusPx: Float) {
    // No-op config that will be used by dagger of other SysUI variants which don't blur the
    // background surface.
    @Inject constructor() : this(0.0f, 0.0f)
}
