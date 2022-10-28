/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import kotlin.reflect.KClass

/** Fake implementation of [FakeKeyguardQuickAffordanceRegistry], for tests. */
class FakeKeyguardQuickAffordanceRegistry(
    private val configsByPosition:
        Map<KeyguardQuickAffordancePosition, List<FakeKeyguardQuickAffordanceConfig>>,
) : KeyguardQuickAffordanceRegistry<FakeKeyguardQuickAffordanceConfig> {

    override fun getAll(
        position: KeyguardQuickAffordancePosition
    ): List<FakeKeyguardQuickAffordanceConfig> {
        return configsByPosition.getValue(position)
    }

    override fun get(
        configClass: KClass<out FakeKeyguardQuickAffordanceConfig>
    ): FakeKeyguardQuickAffordanceConfig {
        return configsByPosition.values
            .flatten()
            .associateBy { config -> config::class }
            .getValue(configClass)
    }
}
