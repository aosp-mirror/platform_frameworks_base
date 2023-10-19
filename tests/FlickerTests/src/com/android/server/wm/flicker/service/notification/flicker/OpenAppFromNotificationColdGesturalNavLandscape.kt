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

package com.android.server.wm.flicker.service.notification.flicker

import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.common.flicker.FlickerConfig
import android.tools.common.flicker.annotation.ExpectedScenarios
import android.tools.common.flicker.annotation.FlickerConfigProvider
import android.tools.common.flicker.config.FlickerConfig
import android.tools.common.flicker.config.FlickerServiceConfig
import android.tools.device.flicker.junit.FlickerServiceJUnit4ClassRunner
import com.android.server.wm.flicker.service.notification.scenarios.OpenAppFromNotificationCold
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(FlickerServiceJUnit4ClassRunner::class)
class OpenAppFromNotificationColdGesturalNavLandscape :
    OpenAppFromNotificationCold(NavBar.MODE_GESTURAL, Rotation.ROTATION_90) {
    @ExpectedScenarios(["APP_LAUNCH_FROM_NOTIFICATION"])
    @Test
    override fun openAppFromNotificationCold() = super.openAppFromNotificationCold()

    companion object {
        @JvmStatic
        @FlickerConfigProvider
        fun flickerConfigProvider(): FlickerConfig =
            FlickerConfig().use(FlickerServiceConfig.DEFAULT)
    }
}
