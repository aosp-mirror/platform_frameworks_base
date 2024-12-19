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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import android.content.res.Resources
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A place to define the blocklist/allowlist for home status bar icons */
@SysUISingleton
class HomeStatusBarIconBlockListInteractor
@Inject
constructor(@Main res: Resources, secureSettingsRepository: SecureSettingsRepository) {
    private val defaultBlockedIcons =
        res.getStringArray(R.array.config_collapsed_statusbar_icon_blocklist)

    private val vibrateIconSlot = res.getString(com.android.internal.R.string.status_bar_volume)

    /** Tracks the user setting [Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON] */
    private val shouldShowVibrateIcon: Flow<Boolean> =
        secureSettingsRepository.boolSetting(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, false)

    val iconBlockList: Flow<List<String>> =
        shouldShowVibrateIcon.map {
            val defaultSet = defaultBlockedIcons.toMutableSet()
            // It's possible that the vibrate icon was in the default blocklist, so we manually
            // merge the setting and list
            if (it) {
                defaultSet.remove(vibrateIconSlot)
            } else {
                defaultSet.add(vibrateIconSlot)
            }

            defaultSet.toList()
        }
}
