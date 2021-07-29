/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.content.ComponentName
import android.support.test.launcherhelper.ILauncherStrategy
import android.support.test.launcherhelper.LauncherStrategyFactory
import com.android.server.wm.flicker.testapp.ActivityOptions

class NonResizeableAppHelper @JvmOverloads constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.NON_RESIZEABLE_ACTIVITY_LAUNCHER_NAME,
    component: ComponentName = ActivityOptions.NON_RESIZEABLE_ACTIVITY_COMPONENT_NAME,
    launcherStrategy: ILauncherStrategy = LauncherStrategyFactory
        .getInstance(instr)
        .launcherStrategy
) : StandardAppHelper(instr, launcherName, component, launcherStrategy)