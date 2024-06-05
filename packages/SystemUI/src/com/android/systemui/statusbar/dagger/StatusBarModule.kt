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

package com.android.systemui.statusbar.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.statusbar.data.StatusBarDataLayerModule
import com.android.systemui.statusbar.phone.LightBarController
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.ui.SystemBarUtilsProxyImpl
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * A module for **only** classes related to the status bar **UI element**. This module specifically
 * should **not** include:
 * - Classes in the `statusbar` package that are unrelated to the status bar UI.
 * - Status bar classes that are already provided by other modules
 *   ([com.android.systemui.statusbar.pipeline.dagger.StatusBarPipelineModule],
 *   [com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule], etc.).
 */
@Module(includes = [StatusBarDataLayerModule::class, SystemBarUtilsProxyImpl.Module::class])
abstract class StatusBarModule {
    @Binds
    @IntoMap
    @ClassKey(OngoingCallController::class)
    abstract fun bindOngoingCallController(impl: OngoingCallController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(LightBarController::class)
    abstract fun bindLightBarController(impl: LightBarController): CoreStartable
}
