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

package com.android.systemui.shade.domain.interactor

import android.content.testableContext
import android.provider.Settings
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shared.settings.data.repository.fakeSecureSettingsRepository

val Kosmos.shadeModeInteractor by Fixture {
    ShadeModeInteractorImpl(
        applicationScope = applicationCoroutineScope,
        repository = shadeRepository,
        secureSettingsRepository = fakeSecureSettingsRepository,
        tableLogBuffer = logcatTableLogBuffer(this, "sceneFrameworkTableLogBuffer"),
    )
}

// TODO(b/391578667): Make this user-aware once supported by FakeSecureSettingsRepository.
/**
 * Enables the Dual Shade setting, and (optionally) sets the shade layout to be wide (`true`) or
 * narrow (`false`).
 *
 * In a wide layout, notifications and quick settings shades each take up only half the screen
 * width. In a narrow layout, they each take up the entire screen width.
 */
fun Kosmos.enableDualShade(wideLayout: Boolean? = null) {
    fakeSecureSettingsRepository.setBool(Settings.Secure.DUAL_SHADE, true)

    if (wideLayout != null) {
        overrideLargeScreenResources(isLargeScreen = wideLayout)
        fakeShadeRepository.setShadeLayoutWide(wideLayout)
    }
}

// TODO(b/391578667): Make this user-aware once supported by FakeSecureSettingsRepository.
fun Kosmos.disableDualShade() {
    fakeSecureSettingsRepository.setBool(Settings.Secure.DUAL_SHADE, false)
}

fun Kosmos.enableSingleShade() {
    disableDualShade()
    overrideLargeScreenResources(isLargeScreen = false)
    fakeShadeRepository.setShadeLayoutWide(false)
}

fun Kosmos.enableSplitShade() {
    disableDualShade()
    overrideLargeScreenResources(isLargeScreen = true)
    fakeShadeRepository.setShadeLayoutWide(true)
}

private fun Kosmos.overrideLargeScreenResources(isLargeScreen: Boolean) {
    with(testableContext.orCreateTestableResources) {
        addOverride(R.bool.config_use_split_notification_shade, isLargeScreen)
        addOverride(R.bool.config_use_large_screen_shade_header, isLargeScreen)
    }
}
