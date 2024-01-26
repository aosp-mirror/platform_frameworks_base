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

package com.android.systemui.keyboard.stickykeys.data.repository

import android.hardware.input.InputManager
import android.hardware.input.InputManager.StickyModifierStateListener
import android.hardware.input.StickyModifierState
import android.provider.Settings
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.stickykeys.StickyKeysLogger
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.ALT
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.ALT_GR
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.CTRL
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.META
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.SHIFT
import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

interface StickyKeysRepository {
    val stickyKeys: Flow<LinkedHashMap<ModifierKey, Locked>>
    val settingEnabled: Flow<Boolean>
}

@SysUISingleton
class StickyKeysRepositoryImpl
@Inject
constructor(
    private val inputManager: InputManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    secureSettingsRepository: UserAwareSecureSettingsRepository,
    private val stickyKeysLogger: StickyKeysLogger,
) : StickyKeysRepository {

    override val stickyKeys: Flow<LinkedHashMap<ModifierKey, Locked>> =
        conflatedCallbackFlow {
                val listener = StickyModifierStateListener { stickyModifierState ->
                    trySendWithFailureLogging(stickyModifierState, TAG)
                }
                // after registering, InputManager calls listener with the current value
                inputManager.registerStickyModifierStateListener(Runnable::run, listener)
                awaitClose { inputManager.unregisterStickyModifierStateListener(listener) }
            }
            .map { toStickyKeysMap(it) }
            .onEach { stickyKeysLogger.logNewStickyKeysReceived(it) }
            .flowOn(backgroundDispatcher)

    override val settingEnabled: Flow<Boolean> =
        secureSettingsRepository
            .boolSettingForActiveUser(SETTING_KEY, defaultValue = false)
            .onEach { stickyKeysLogger.logNewSettingValue(it) }
            .flowOn(backgroundDispatcher)

    private fun toStickyKeysMap(state: StickyModifierState): LinkedHashMap<ModifierKey, Locked> {
        val keys = linkedMapOf<ModifierKey, Locked>()
        state.apply {
            if (isAltGrModifierOn) keys[ALT_GR] = Locked(false)
            if (isAltGrModifierLocked) keys[ALT_GR] = Locked(true)
            if (isAltModifierOn) keys[ALT] = Locked(false)
            if (isAltModifierLocked) keys[ALT] = Locked(true)
            if (isCtrlModifierOn) keys[CTRL] = Locked(false)
            if (isCtrlModifierLocked) keys[CTRL] = Locked(true)
            if (isMetaModifierOn) keys[META] = Locked(false)
            if (isMetaModifierLocked) keys[META] = Locked(true)
            if (isShiftModifierOn) keys[SHIFT] = Locked(false)
            if (isShiftModifierLocked) keys[SHIFT] = Locked(true)
        }
        return keys
    }

    companion object {
        const val TAG = "StickyKeysRepositoryImpl"
        const val SETTING_KEY = Settings.Secure.ACCESSIBILITY_STICKY_KEYS
    }
}
