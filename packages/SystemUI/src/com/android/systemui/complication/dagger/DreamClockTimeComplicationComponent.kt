/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.complication.dagger

import android.view.LayoutInflater
import android.view.View
import android.widget.TextClock
import com.android.internal.util.Preconditions
import com.android.systemui.res.R
import com.android.systemui.complication.DreamClockTimeComplication
import com.android.systemui.complication.DreamClockTimeComplication.DreamClockTimeViewHolder
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import javax.inject.Named
import javax.inject.Scope

/** Responsible for generating dependencies for the [DreamClockTimeComplication]. */
@Subcomponent(
    modules = [DreamClockTimeComplicationComponent.DreamClockTimeComplicationModule::class]
)
@DreamClockTimeComplicationComponent.DreamClockTimeComplicationScope
interface DreamClockTimeComplicationComponent {
    /** Scope of the clock complication. */
    @MustBeDocumented
    @Retention(AnnotationRetention.RUNTIME)
    @Scope
    annotation class DreamClockTimeComplicationScope

    /** Factory that generates a component for the clock complication. */
    @Subcomponent.Factory
    interface Factory {
        fun create(): DreamClockTimeComplicationComponent
    }

    /** Creates a view holder for the clock complication. */
    fun getViewHolder(): DreamClockTimeViewHolder

    /** Module for providing injected values within the clock complication scope. */
    @Module
    interface DreamClockTimeComplicationModule {
        companion object {
            const val DREAM_CLOCK_TIME_COMPLICATION_VIEW = "clock_time_complication_view"
            private const val TAG_WEIGHT = "'wght' "
            private const val WEIGHT = 400

            /** Provides the complication view. */
            @Provides
            @DreamClockTimeComplicationScope
            @Named(DREAM_CLOCK_TIME_COMPLICATION_VIEW)
            fun provideComplicationView(layoutInflater: LayoutInflater): View {
                val view =
                    Preconditions.checkNotNull(
                        layoutInflater.inflate(
                            R.layout.dream_overlay_complication_clock_time,
                            /* root = */ null,
                            /* attachToRoot = */ false,
                        ) as TextClock,
                        "R.layout.dream_overlay_complication_clock_time did not properly inflate"
                    )
                view.setFontVariationSettings(TAG_WEIGHT + WEIGHT)
                return view
            }
        }
    }
}
