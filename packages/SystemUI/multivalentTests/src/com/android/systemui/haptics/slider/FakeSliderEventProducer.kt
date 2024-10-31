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

package com.android.systemui.haptics.slider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of a slider event producer */
class FakeSliderEventProducer : SliderEventProducer {

    private val _currentEvent = MutableStateFlow(SliderEvent(SliderEventType.NOTHING, 0f))

    fun sendEvent(event: SliderEvent) {
        _currentEvent.value = event
    }
    override fun produceEvents(): Flow<SliderEvent> = _currentEvent.asStateFlow()
}
