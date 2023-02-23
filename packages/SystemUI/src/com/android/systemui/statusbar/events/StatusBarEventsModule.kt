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
 */

package com.android.systemui.statusbar.events

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope

@Module
interface StatusBarEventsModule {

    companion object {

        @Provides
        @SysUISingleton
        fun provideSystemStatusAnimationScheduler(
                featureFlags: FeatureFlags,
                coordinator: SystemEventCoordinator,
                chipAnimationController: SystemEventChipAnimationController,
                statusBarWindowController: StatusBarWindowController,
                dumpManager: DumpManager,
                systemClock: SystemClock,
                @Application coroutineScope: CoroutineScope,
                @Main executor: DelayableExecutor
        ): SystemStatusAnimationScheduler {
            return if (featureFlags.isEnabled(Flags.PLUG_IN_STATUS_BAR_CHIP)) {
                SystemStatusAnimationSchedulerImpl(
                        coordinator,
                        chipAnimationController,
                        statusBarWindowController,
                        dumpManager,
                        systemClock,
                        coroutineScope
                )
            } else {
                SystemStatusAnimationSchedulerLegacyImpl(
                        coordinator,
                        chipAnimationController,
                        statusBarWindowController,
                        dumpManager,
                        systemClock,
                        executor
                )
            }
        }
    }
}

