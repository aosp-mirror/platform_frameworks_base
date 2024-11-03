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

package com.android.systemui.bouncer.data.repository

import android.content.Context
import android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE
import com.android.systemui.authentication.shared.model.BouncerInputSide
import com.android.systemui.authentication.shared.model.toBouncerInputSide
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** Provides access to bouncer-related application state. */
@SysUISingleton
class BouncerRepository
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val globalSettings: GlobalSettings,
    private val flags: FeatureFlagsClassic,
) {
    val scale: MutableStateFlow<Float> = MutableStateFlow(1.0f)

    /** Whether the user switcher should be displayed within the bouncer UI on large screens. */
    val isUserSwitcherEnabledInConfig: Boolean
        get() =
            applicationContext.resources.getBoolean(R.bool.config_enableBouncerUserSwitcher) &&
                flags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)

    /** Whether the one handed bouncer is supported for this device. */
    val isOneHandedBouncerSupportedInConfig: Boolean
        get() = applicationContext.resources.getBoolean(R.bool.can_use_one_handed_bouncer)

    /**
     * Preferred side of the screen where the input area on the bouncer should be. This is
     * applicable for large screen devices (foldables and tablets).
     */
    val preferredBouncerInputSide: MutableStateFlow<BouncerInputSide?> =
        MutableStateFlow(getPreferredInputSideSetting())

    /** X coordinate of the last recorded touch position on the lockscreen. */
    val lastRecordedLockscreenTouchPosition = MutableStateFlow<Float?>(null)

    /** Save the preferred bouncer input side. */
    fun setPreferredBouncerInputSide(inputSide: BouncerInputSide) {
        globalSettings.putInt(ONE_HANDED_KEYGUARD_SIDE, inputSide.settingValue)
        // used to only trigger another emission on the flow.
        preferredBouncerInputSide.value = inputSide
    }

    /**
     * Record the x coordinate of the last touch position on the lockscreen. This will be used to
     * determine which side of the bouncer the input area should be shown.
     */
    fun recordLockscreenTouchPosition(x: Float) {
        lastRecordedLockscreenTouchPosition.value = x
    }

    fun getPreferredInputSideSetting(): BouncerInputSide? {
        return globalSettings.getInt(ONE_HANDED_KEYGUARD_SIDE, -1).toBouncerInputSide()
    }
}
