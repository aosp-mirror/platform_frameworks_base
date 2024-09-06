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
 * limitations under the License
 */
package com.android.systemui.keyguard.ui.binder

import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor
import com.android.systemui.log.core.LogLevel
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/** Runs actions on keyguard dismissal. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardDismissActionBinder
@Inject
constructor(
    private val interactor: KeyguardDismissActionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardLogger: KeyguardLogger,
) : CoreStartable {

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        scope.launch {
            interactor.executeDismissAction.collect {
                log("executeDismissAction")
                interactor.setKeyguardDone(it())
                interactor.handleDismissAction()
            }
        }

        scope.launch {
            interactor.resetDismissAction.sample(interactor.onCancel).collect {
                log("resetDismissAction")
                it.run()
                interactor.handleDismissAction()
            }
        }

        scope.launch { interactor.dismissAction.collect { log("updatedDismissAction=$it") } }
    }

    private fun log(message: String) {
        keyguardLogger.log(TAG, LogLevel.DEBUG, message)
    }

    companion object {
        private const val TAG = "KeyguardDismissAction"
    }
}
