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

package com.android.systemui.plugins

// TODO(b/265360975): Evaluate this plugin approach.
/** Plugin to provide BC smartspace configuration */
interface BcSmartspaceConfigPlugin {
    /** Gets default date/weather disabled status. */
    val isDefaultDateWeatherDisabled: Boolean
    /** Gets if Smartspace should use ViewPager2 */
    val isViewPager2Enabled: Boolean
}
