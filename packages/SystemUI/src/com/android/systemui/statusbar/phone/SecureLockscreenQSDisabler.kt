/*
 * Copyright (C) 2022 FlamingoOS Project
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

import android.app.StatusBarManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.SECURE_LOCKSCREEN_QS_DISABLED

import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.SecureSettings

import javax.inject.Inject

@SysUISingleton
class SecureLockscreenQSDisabler @Inject constructor(
    private val context: Context,
    private val commandQueue: CommandQueue,
    private val secureSettings: SecureSettings,
    private val keyguardStateController: KeyguardStateController,
    @Main handler: Handler,
) {

    private var disableQSOnSecureLockscreen: Boolean = shouldDisableQS()

    init {
        val settingsObserver = object: ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                disableQSOnSecureLockscreen = shouldDisableQS()
                recomputeDisableFlags()
            }
        }
        secureSettings.registerContentObserverForUser(SECURE_LOCKSCREEN_QS_DISABLED,
            settingsObserver, UserHandle.USER_ALL)
    }

    fun adjustDisableFlags(state2: Int): Int {
        return if (disableQSOnSecureLockscreen &&
                !keyguardStateController.isUnlocked()) {
            state2 or StatusBarManager.DISABLE2_QUICK_SETTINGS
        } else {
            state2
        }
    }

    private fun shouldDisableQS(): Boolean =
        secureSettings.getIntForUser(SECURE_LOCKSCREEN_QS_DISABLED,
            0, UserHandle.USER_CURRENT) == 1

    private fun recomputeDisableFlags() {
        commandQueue.recomputeDisableFlags(context.displayId, true /** animate */)
    }
}
