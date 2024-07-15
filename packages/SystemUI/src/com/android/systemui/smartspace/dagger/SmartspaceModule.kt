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
package com.android.systemui.smartspace.dagger

import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.smartspace.SmartspacePrecondition
import com.android.systemui.smartspace.SmartspaceTargetFilter
import com.android.systemui.smartspace.preconditions.LockscreenPrecondition
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import javax.inject.Named

@Module(subcomponents = [SmartspaceViewComponent::class])
abstract class SmartspaceModule {
    @Module
    companion object {
        /** The BcSmartspaceDataProvider for dreams. */
        const val DREAM_SMARTSPACE_DATA_PLUGIN = "dreams_smartspace_data_plugin"

        /** The BcSmartspaceDataPlugin for the standalone weather on dream. */
        const val DREAM_WEATHER_SMARTSPACE_DATA_PLUGIN = "dream_weather_smartspace_data_plugin"

        /** The target filter for smartspace over lockscreen. */
        const val LOCKSCREEN_SMARTSPACE_TARGET_FILTER = "lockscreen_smartspace_target_filter"

        /** The precondition for smartspace over lockscreen */
        const val LOCKSCREEN_SMARTSPACE_PRECONDITION = "lockscreen_smartspace_precondition"

        /** The BcSmartspaceDataPlugin for the standalone date (+alarm+dnd). */
        const val DATE_SMARTSPACE_DATA_PLUGIN = "date_smartspace_data_plugin"

        /** The BcSmartspaceDataPlugin for the standalone weather. */
        const val WEATHER_SMARTSPACE_DATA_PLUGIN = "weather_smartspace_data_plugin"

        /** The BcSmartspaceDataProvider for the glanceable hub. */
        const val GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN = "glanceable_hub_smartspace_data_plugin"
    }

    @BindsOptionalOf
    @Named(LOCKSCREEN_SMARTSPACE_TARGET_FILTER)
    abstract fun optionalDreamSmartspaceTargetFilter(): SmartspaceTargetFilter?

    @BindsOptionalOf
    @Named(DREAM_SMARTSPACE_DATA_PLUGIN)
    abstract fun optionalDreamsBcSmartspaceDataPlugin(): BcSmartspaceDataPlugin?

    @BindsOptionalOf
    @Named(DREAM_WEATHER_SMARTSPACE_DATA_PLUGIN)
    abstract fun optionalDreamWeatherSmartspaceDataPlugin(): BcSmartspaceDataPlugin?

    @Binds
    @Named(LOCKSCREEN_SMARTSPACE_PRECONDITION)
    abstract fun bindSmartspacePrecondition(
        lockscreenPrecondition: LockscreenPrecondition?
    ): SmartspacePrecondition?

    @BindsOptionalOf
    @Named(GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN)
    abstract fun optionalBcSmartspaceDataPlugin(): BcSmartspaceDataPlugin?
}
