/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.flags

import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Named

/** Module containing shared code for all FeatureFlag implementations. */
@Module
interface FlagsCommonModule {
    @Binds fun bindsRestarter(impl: ConditionalRestarter): Restarter

    companion object {
        const val ALL_FLAGS = "all_flags"

        @JvmStatic
        @Provides
        @Named(ALL_FLAGS)
        fun providesAllFlags(): Map<String, Flag<*>> {
            return FlagsFactory.knownFlags
        }
    }
}
