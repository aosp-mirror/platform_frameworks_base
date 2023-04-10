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
 *
 */

package com.android.systemui.multishade.data.remoteproxy

import com.android.systemui.multishade.shared.model.ProxiedInputModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Acts as a hub for routing proxied user input into the multi shade system.
 *
 * "Proxied" user input is coming through a proxy; typically from an external app or different UI.
 * In other words: it's not user input that's occurring directly on the shade UI itself. This class
 * is that proxy.
 */
@Singleton
class MultiShadeInputProxy @Inject constructor() {
    private val _proxiedTouch =
        MutableSharedFlow<ProxiedInputModel>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val proxiedInput: Flow<ProxiedInputModel> = _proxiedTouch.asSharedFlow()

    fun onProxiedInput(proxiedInput: ProxiedInputModel) {
        _proxiedTouch.tryEmit(proxiedInput)
    }
}
