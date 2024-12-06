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

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.StateFlow

/** Describes the display the shade should be shown in. */
interface ShadeDisplayPolicy {
    val name: String

    /** The display id the shade should be at, according to this policy. */
    val displayId: StateFlow<Int>
}

@Module
interface ShadeDisplayPolicyModule {

    @Binds fun provideDefaultPolicy(impl: StatusBarTouchShadeDisplayPolicy): ShadeDisplayPolicy

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
