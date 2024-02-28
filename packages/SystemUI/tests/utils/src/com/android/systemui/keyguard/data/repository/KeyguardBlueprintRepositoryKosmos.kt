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

package com.android.systemui.keyguard.data.repository

import android.os.fakeExecutorHandler
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor.Companion.WEATHER_CLOCK_BLUEPRINT_ID
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.ThreadAssert
import com.android.systemui.util.mockito.mock

val Kosmos.keyguardBlueprintRepository by
    Kosmos.Fixture {
        KeyguardBlueprintRepository(
            blueprints =
                setOf(
                    defaultBlueprint,
                    splitShadeBlueprint,
                    weatherClockBlueprint,
                    splitShadeWeatherClockBlueprint,
                ),
            handler = fakeExecutorHandler,
            assert = mock<ThreadAssert>(),
        )
    }

private val defaultBlueprint =
    object : KeyguardBlueprint {
        override val id: String
            get() = DEFAULT
        override val sections: List<KeyguardSection>
            get() = listOf()
    }

private val weatherClockBlueprint =
    object : KeyguardBlueprint {
        override val id: String
            get() = WEATHER_CLOCK_BLUEPRINT_ID
        override val sections: List<KeyguardSection>
            get() = listOf()
    }

private val splitShadeWeatherClockBlueprint =
    object : KeyguardBlueprint {
        override val id: String
            get() = SPLIT_SHADE_WEATHER_CLOCK_BLUEPRINT_ID
        override val sections: List<KeyguardSection>
            get() = listOf()
    }

private val splitShadeBlueprint =
    object : KeyguardBlueprint {
        override val id: String
            get() = SplitShadeKeyguardBlueprint.Companion.ID
        override val sections: List<KeyguardSection>
            get() = listOf()
    }
