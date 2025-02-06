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

package com.android.systemui.qs.flags

import com.android.systemui.flags.RefactorFlagUtils
import com.android.systemui.scene.shared.flag.SceneContainerFlag

/**
 * Object to help check if the new QS ui should be used. This is true if either [QSComposeFragment]
 * or [SceneContainerFlag] are enabled.
 */
object QsInCompose {

    /**
     * This is not a real flag name, but a representation of the allowed flag names. Should not be
     * used with test annotations.
     */
    private val flagName =
        "${QSComposeFragment.FLAG_NAME}|${SceneContainerFlag.getMainAconfigFlag().name}"

    @JvmStatic
    inline val isEnabled: Boolean
        get() = QSComposeFragment.isEnabled || SceneContainerFlag.isEnabled

    @JvmStatic
    fun isUnexpectedlyInLegacyMode() =
        RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, flagName)

    @JvmStatic fun assertInLegacyMode() = RefactorFlagUtils.assertInLegacyMode(isEnabled, flagName)
}
