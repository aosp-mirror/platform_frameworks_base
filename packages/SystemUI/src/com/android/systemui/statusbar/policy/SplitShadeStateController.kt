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
package com.android.systemui.statusbar.policy

import android.content.res.Resources

/** Source of truth for split shade state: should or should not use split shade. */
interface SplitShadeStateController {

    /** Returns true if the device should use the split notification shade. */
    @Deprecated(
        message = "This is deprecated, please use ShadeInteractor#isSplitShade instead",
        replaceWith =
            ReplaceWith(
                "shadeInteractor.isSplitShade",
                "com.android.systemui.shade.domain.interactor.ShadeInteractor",
            ),
    )
    fun shouldUseSplitNotificationShade(resources: Resources): Boolean
}
