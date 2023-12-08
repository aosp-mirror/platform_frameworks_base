/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Objects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * It tracks a specific `Secure` int [setting] and when its value changes to non-zero, it will emit
 * a [AutoAddSignal.Add] for [spec].
 */
class AutoAddableSetting
@AssistedInject
constructor(
    private val secureSettings: SecureSettings,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Assisted private val setting: String,
    @Assisted private val spec: TileSpec,
) : AutoAddable {

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return secureSettings
            .observerFlow(userId, setting)
            .onStart { emit(Unit) }
            .map { secureSettings.getIntForUser(setting, 0, userId) != 0 }
            .distinctUntilChanged()
            .filter { it }
            .map { AutoAddSignal.Add(spec) }
            .flowOn(bgDispatcher)
    }

    override val autoAddTracking = AutoAddTracking.IfNotAdded(spec)

    override val description = "AutoAddableSetting: $setting:$spec ($autoAddTracking)"

    override fun equals(other: Any?): Boolean {
        return other is AutoAddableSetting && spec == other.spec && setting == other.setting
    }

    override fun hashCode(): Int {
        return Objects.hash(spec, setting)
    }

    override fun toString(): String {
        return description
    }

    @AssistedFactory
    interface Factory {
        fun create(setting: String, spec: TileSpec): AutoAddableSetting
    }
}
