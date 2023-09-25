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
package com.android.systemui

import com.android.systemui.classifier.FakeClassifierModule
import com.android.systemui.data.FakeSystemUiDataLayerModule
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.log.FakeUiEventLoggerModule
import com.android.systemui.scene.FakeSceneModule
import com.android.systemui.settings.FakeSettingsModule
import com.android.systemui.statusbar.policy.FakeConfigurationControllerModule
import com.android.systemui.statusbar.policy.FakeSplitShadeStateControllerModule
import com.android.systemui.util.concurrency.FakeExecutorModule
import com.android.systemui.util.time.FakeSystemClockModule
import dagger.Module

@Module(
    includes =
        [
            FakeClassifierModule::class,
            FakeConfigurationControllerModule::class,
            FakeExecutorModule::class,
            FakeFeatureFlagsClassicModule::class,
            FakeSceneModule::class,
            FakeSettingsModule::class,
            FakeSplitShadeStateControllerModule::class,
            FakeSystemClockModule::class,
            FakeSystemUiDataLayerModule::class,
            FakeUiEventLoggerModule::class,
        ]
)
object FakeSystemUiModule
