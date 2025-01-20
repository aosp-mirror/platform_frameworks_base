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

package com.android.systemui.shade.display

import com.android.systemui.shade.domain.interactor.ShadeExpandedStateInteractor.ShadeElement
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.StateFlow

/** Describes the display the shade should be shown in. */
interface ShadeDisplayPolicy {
    /**
     * String used to identify each policy and used to set policy via adb command. This value must
     * match a value defined in the SettingsLib shade_display_awareness_values string array.
     */
    val name: String

    /** The display id the shade should be at, according to this policy. */
    val displayId: StateFlow<Int>
}

/** Return the latest element the user intended to expand in the shade (notifications or QS). */
interface ShadeExpansionIntent {
    /**
     * Returns the latest element the user intended to expand in the shade (notifications or QS).
     *
     * When the shade moves to a different display (e.g., due to a touch on the status bar of an
     * external display), it's first collapsed and then re-expanded on the target display.
     *
     * If the user was trying to open a specific element (QS or notifications) when the shade was on
     * the original display, that intention might be lost during the collapse/re-expand transition.
     * This is used to preserve the user's intention, ensuring the correct element is expanded on
     * the target display.
     *
     * Note that the expansion intent is kept for a very short amount of time (ideally, just a bit
     * above the time it takes for the shade to collapse)
     */
    fun consumeExpansionIntent(): ShadeElement?
}

@Module
interface ShadeDisplayPolicyModule {

    @Binds fun provideDefaultPolicy(impl: DefaultDisplayShadePolicy): ShadeDisplayPolicy

    @Binds
    fun provideShadeExpansionIntent(impl: StatusBarTouchShadeDisplayPolicy): ShadeExpansionIntent

    @IntoSet
    @Binds
    fun provideDefaultDisplayPolicyToSet(impl: DefaultDisplayShadePolicy): ShadeDisplayPolicy

    @IntoSet
    @Binds
    fun provideAnyExternalShadeDisplayPolicyToSet(
        impl: AnyExternalShadeDisplayPolicy
    ): ShadeDisplayPolicy

    @Binds
    @IntoSet
    fun provideStatusBarTouchShadeDisplayPolicy(
        impl: StatusBarTouchShadeDisplayPolicy
    ): ShadeDisplayPolicy
}
