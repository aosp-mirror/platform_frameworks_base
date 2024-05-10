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
package com.android.systemui.data

import com.android.systemui.accessibility.data.FakeAccessibilityDataLayerModule
import com.android.systemui.authentication.data.FakeAuthenticationDataLayerModule
import com.android.systemui.bouncer.data.repository.FakeBouncerDataLayerModule
import com.android.systemui.common.ui.data.FakeCommonDataLayerModule
import com.android.systemui.deviceentry.data.FakeDeviceEntryDataLayerModule
import com.android.systemui.keyevent.data.repository.FakeKeyEventDataLayerModule
import com.android.systemui.keyguard.data.FakeKeyguardDataLayerModule
import com.android.systemui.power.data.FakePowerDataLayerModule
import com.android.systemui.shade.data.repository.FakeShadeDataLayerModule
import com.android.systemui.statusbar.data.FakeStatusBarDataLayerModule
import com.android.systemui.telephony.data.FakeTelephonyDataLayerModule
import com.android.systemui.user.data.FakeUserDataLayerModule
import com.android.systemui.util.animation.data.FakeAnimationUtilDataLayerModule
import dagger.Module

@Module(
    includes =
        [
            FakeAccessibilityDataLayerModule::class,
            FakeAnimationUtilDataLayerModule::class,
            FakeAuthenticationDataLayerModule::class,
            FakeBouncerDataLayerModule::class,
            FakeCommonDataLayerModule::class,
            FakeDeviceEntryDataLayerModule::class,
            FakeKeyguardDataLayerModule::class,
            FakePowerDataLayerModule::class,
            FakeShadeDataLayerModule::class,
            FakeStatusBarDataLayerModule::class,
            FakeTelephonyDataLayerModule::class,
            FakeUserDataLayerModule::class,
            FakeKeyEventDataLayerModule::class,
        ]
)
object FakeSystemUiDataLayerModule
