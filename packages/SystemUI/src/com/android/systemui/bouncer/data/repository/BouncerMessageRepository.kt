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

package com.android.systemui.bouncer.data.repository

import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Provide different sources of messages that needs to be shown on the bouncer. */
interface BouncerMessageRepository {
    val bouncerMessage: Flow<BouncerMessageModel>

    fun setMessage(message: BouncerMessageModel)
}

@SysUISingleton
class BouncerMessageRepositoryImpl @Inject constructor() : BouncerMessageRepository {

    private val _bouncerMessage = MutableStateFlow(BouncerMessageModel())
    override val bouncerMessage: Flow<BouncerMessageModel> = _bouncerMessage

    override fun setMessage(message: BouncerMessageModel) {
        _bouncerMessage.value = message
    }
}
