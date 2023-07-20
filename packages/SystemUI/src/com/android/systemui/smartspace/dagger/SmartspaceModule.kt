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
import com.android.systemui.smartspace.filters.LockscreenAndDreamTargetFilter
import com.android.systemui.smartspace.preconditions.LockscreenPrecondition
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import javax.inject.Named

@Module(subcomponents = [SmartspaceViewComponent::class])
abstract class SmartspaceModule {
    @Module
    companion object {
        /**
         * The BcSmartspaceDataProvider for dreams.
         */
        const val DREAM_SMARTSPACE_DATA_PLUGIN = "dreams_smartspace_data_plugin"

        /**
         * The lockscreen smartspace target filter.
         */
        const val LOCKSCREEN_SMARTSPACE_TARGET_FILTER = "lockscreen_smartspace_target_filter"

        /**
         * The dream smartspace target filter.
         */
        const val DREAM_SMARTSPACE_TARGET_FILTER = "dream_smartspace_target_filter"

        /**
         * The precondition for dream smartspace
         */
        const val DREAM_SMARTSPACE_PRECONDITION = "dream_smartspace_precondition"
    }

    @BindsOptionalOf
    @Named(DREAM_SMARTSPACE_TARGET_FILTER)
    abstract fun optionalDreamSmartspaceTargetFilter(): SmartspaceTargetFilter?

    @BindsOptionalOf
    @Named(DREAM_SMARTSPACE_DATA_PLUGIN)
    abstract fun optionalDreamsBcSmartspaceDataPlugin(): BcSmartspaceDataPlugin?

    @Binds
    @Named(LOCKSCREEN_SMARTSPACE_TARGET_FILTER)
    abstract fun provideLockscreenSmartspaceTargetFilter(
        filter: LockscreenAndDreamTargetFilter?
    ): SmartspaceTargetFilter?

    @Binds
    @Named(DREAM_SMARTSPACE_PRECONDITION)
    abstract fun bindSmartspacePrecondition(
        lockscreenPrecondition: LockscreenPrecondition?
    ): SmartspacePrecondition?
}
