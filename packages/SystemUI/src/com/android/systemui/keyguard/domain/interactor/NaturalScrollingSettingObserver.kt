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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject

@SysUISingleton
class NaturalScrollingSettingObserver
@Inject
constructor(
    @Main private val handler: Handler,
    private val context: Context,
) {
    var isNaturalScrollingEnabled = true
        get() {
            if (!isInitialized) {
                isInitialized = true
                update()
            }
            return field
        }

    private var isInitialized = false

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }

    init {
        context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TOUCHPAD_NATURAL_SCROLLING), false,
                contentObserver)
    }

    private fun update() {
        isNaturalScrollingEnabled = try {
            Settings.System.getIntForUser(context.contentResolver,
                    Settings.System.TOUCHPAD_NATURAL_SCROLLING, UserHandle.USER_CURRENT) == 1
        } catch (e: SettingNotFoundException) {
            true
        }
    }
}
