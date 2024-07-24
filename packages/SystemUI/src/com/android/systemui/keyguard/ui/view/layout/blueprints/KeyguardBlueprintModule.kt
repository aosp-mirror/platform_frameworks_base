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

import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet

@Module
abstract class KeyguardBlueprintModule {
    @Binds
    @IntoSet
    abstract fun bindDefaultBlueprint(
        defaultLockscreenBlueprint: DefaultKeyguardBlueprint
    ): KeyguardBlueprint

    @Binds
    @IntoSet
    abstract fun bindSplitShadeBlueprint(
        splitShadeBlueprint: SplitShadeKeyguardBlueprint
    ): KeyguardBlueprint

    @Binds
    @IntoSet
    abstract fun bindShortcutsBesideUdfpsLockscreenBlueprint(
        shortcutsBesideUdfpsLockscreenBlueprint: ShortcutsBesideUdfpsKeyguardBlueprint
    ): KeyguardBlueprint

    companion object {
        /** This is a place holder for weather clock in compose. */
        @Provides
        @IntoSet
        fun bindWeatherClockBlueprintPlaceHolder(): KeyguardBlueprint {
            return object : KeyguardBlueprint {
                override val id: String = WEATHER_CLOCK_BLUEPRINT_ID
                override val sections: List<KeyguardSection> = listOf()
            }
        }

        /** This is a place holder for weather clock in compose. */
        @Provides
        @IntoSet
        fun bindSplitShadeWeatherClockBlueprintPlaceHolder(): KeyguardBlueprint {
            return object : KeyguardBlueprint {
                override val id: String = SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID
                override val sections: List<KeyguardSection> = listOf()
            }
        }
    }
}
