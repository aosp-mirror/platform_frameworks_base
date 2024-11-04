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

package com.android.systemui.bouncer.domain.startable

import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardMediaKeyInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Starts interactors needed for the compose bouncer to work as expected. */
@SysUISingleton
class BouncerStartable
@Inject
constructor(
    private val keyguardMediaKeyInteractor: dagger.Lazy<KeyguardMediaKeyInteractor>,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    override fun start() {
        if (!ComposeBouncerFlags.isEnabled) return

        scope.launchTraced("KeyguardMediaKeyInteractor#start") {
            keyguardMediaKeyInteractor.get().activate()
        }
    }
}
