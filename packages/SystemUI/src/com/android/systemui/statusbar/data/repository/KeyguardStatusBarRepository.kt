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

package com.android.systemui.statusbar.data.repository

import android.content.Context
import com.android.internal.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.user.data.repository.UserSwitcherRepository
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Repository for data that's specific to the status bar **on keyguard**. For data that applies to
 * all status bars, use [StatusBarModeRepositoryStore].
 */
interface KeyguardStatusBarRepository {
    /** True if we can show the user switcher on keyguard and false otherwise. */
    val isKeyguardUserSwitcherEnabled: Flow<Boolean>
}

@SysUISingleton
class KeyguardStatusBarRepositoryImpl
@Inject
constructor(
    context: Context,
    configurationController: ConfigurationController,
    userSwitcherRepository: UserSwitcherRepository,
) : KeyguardStatusBarRepository {
    private val relevantConfigChanges: Flow<Unit> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val callback =
                object : ConfigurationController.ConfigurationListener {
                    override fun onSmallestScreenWidthChanged() {
                        trySend(Unit)
                    }

                    override fun onDensityOrFontScaleChanged() {
                        trySend(Unit)
                    }
                }
            configurationController.addCallback(callback)
            awaitClose { configurationController.removeCallback(callback) }
        }

    private val isKeyguardUserSwitcherConfigEnabled: Flow<Boolean> =
        // The config depends on screen size and user enabled settings, so re-fetch whenever any of
        // those change.
        merge(userSwitcherRepository.isEnabled.map {}, relevantConfigChanges).map {
            context.resources.getBoolean(R.bool.config_keyguardUserSwitcher)
        }

    /** True if we can show the user switcher on keyguard and false otherwise. */
    override val isKeyguardUserSwitcherEnabled: Flow<Boolean> =
        combine(
            userSwitcherRepository.isEnabled,
            isKeyguardUserSwitcherConfigEnabled,
        ) { isEnabled, isKeyguardEnabled ->
            isEnabled && isKeyguardEnabled
        }
}

@Module
interface KeyguardStatusBarRepositoryModule {
    @Binds fun bindImpl(impl: KeyguardStatusBarRepositoryImpl): KeyguardStatusBarRepository
}
