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

import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.android.app.tracing.coroutines.launchTraced as launch

/** Runs actions on keyguard dismissal. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardDismissActionBinder
@Inject
constructor(
    private val interactorLazy: Lazy<KeyguardDismissActionInteractor>,
    @Application private val scope: CoroutineScope,
) : CoreStartable {

    override fun start() {
        if (!ComposeBouncerFlags.isEnabled) {
            return
        }

        scope.launch { interactorLazy.get().activate() }
    }
}
