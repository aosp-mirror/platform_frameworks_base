/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util.kotlin

import android.annotation.UserHandleAware
import android.annotation.WorkerThread
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import com.android.systemui.util.Assert

/**
 * Determines whether a component is actually enabled (not just its default value).
 *
 * @throws IllegalArgumentException if the component is not found
 */
@WorkerThread
@UserHandleAware
fun PackageManager.isComponentActuallyEnabled(componentInfo: ComponentInfo): Boolean {
    Assert.isNotMainThread()
    return when (getComponentEnabledSetting(componentInfo.componentName)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> componentInfo.isEnabled
        else -> false
    }
}
