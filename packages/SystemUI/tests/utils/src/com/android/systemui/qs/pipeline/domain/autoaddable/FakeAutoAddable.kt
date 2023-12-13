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

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeAutoAddable(
    private val spec: TileSpec,
    override val autoAddTracking: AutoAddTracking,
) : AutoAddable {

    private val signalsPerUser = mutableMapOf<Int, MutableStateFlow<AutoAddSignal?>>()
    private fun getFlow(userId: Int): MutableStateFlow<AutoAddSignal?> =
        signalsPerUser.getOrPut(userId) { MutableStateFlow(null) }

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return getFlow(userId).asStateFlow().filterNotNull()
    }

    fun sendRemoveSignal(userId: Int) {
        getFlow(userId).value = AutoAddSignal.Remove(spec)
    }

    fun sendAddSignal(userId: Int, position: Int = POSITION_AT_END) {
        getFlow(userId).value = AutoAddSignal.Add(spec, position)
    }

    fun sendRemoveTrackingSignal(userId: Int) {
        getFlow(userId).value = AutoAddSignal.RemoveTracking(spec)
    }

    override val description: String
        get() = "FakeAutoAddable($spec)"
}
