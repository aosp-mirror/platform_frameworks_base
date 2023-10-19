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

package com.android.systemui.flags

import com.android.systemui.power.domain.interactor.PowerInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Returns true when the device is "asleep" as defined by the [WakefullnessLifecycle]. */
class ScreenIdleCondition
@Inject
constructor(private val powerInteractorLazy: Lazy<PowerInteractor>) :
    ConditionalRestarter.Condition {

    override val canRestartNow: Flow<Boolean>
        get() {
            return powerInteractorLazy.get().isAsleep
        }
}
