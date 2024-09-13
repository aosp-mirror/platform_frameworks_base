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

package com.android.server.display.brightness.clamper

import android.os.IBinder
import android.view.Display
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.brightness.clamper.BrightnessClamperController.DisplayDeviceData

@JvmOverloads
fun createDisplayDeviceData(
    displayDeviceConfig: DisplayDeviceConfig,
    displayToken: IBinder,
    uniqueDisplayId: String = "displayId",
    thermalThrottlingDataId: String = "thermalId",
    powerThrottlingDataId: String = "powerId",
    width: Int = 100,
    height: Int = 100,
    displayId: Int = Display.DEFAULT_DISPLAY
): DisplayDeviceData {
    return DisplayDeviceData(
        uniqueDisplayId,
        thermalThrottlingDataId,
        powerThrottlingDataId,
        displayDeviceConfig,
        width,
        height,
        displayToken,
        displayId
    )
}