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

package com.android.systemui.controls.controller

import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Default Instance for ControlsTileResourceConfiguration. */
@SysUISingleton
class ControlsTileResourceConfigurationImpl @Inject constructor() :
    ControlsTileResourceConfiguration {
    override fun getPackageName(): String? {
        return null
    }

    override fun getTileTitleId(): Int {
        return R.string.quick_controls_title
    }

    override fun getTileImageId(): Int {
        return R.drawable.controls_icon
    }
}
