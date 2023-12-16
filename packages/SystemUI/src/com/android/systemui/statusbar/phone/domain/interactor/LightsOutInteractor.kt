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
package com.android.systemui.statusbar.phone.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Apps can request a low profile mode [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE] where status
 * bar and navigation icons dim. In this mode, a notification dot appears where the notification
 * icons would appear if they would be shown outside of this mode.
 *
 * This interactor knows whether the device is in [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE].
 */
@SysUISingleton
class LightsOutInteractor
@Inject
constructor(private val repository: StatusBarModeRepositoryStore) {

    fun isLowProfile(displayId: Int): Flow<Boolean> =
        repository.forDisplay(displayId).statusBarMode.map {
            when (it) {
                StatusBarMode.LIGHTS_OUT,
                StatusBarMode.LIGHTS_OUT_TRANSPARENT -> true
                else -> false
            }
        }
}
