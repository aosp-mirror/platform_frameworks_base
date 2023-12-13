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

/** Defines a producer of [SliderEvent] to be consumed as a [Flow] */
interface SliderEventProducer {

    /**
     * Produce a stream of [SliderEvent]
     *
     * @return A [Flow] of [SliderEvent] produced from the operation of a slider.
     */
    fun produceEvents(): Flow<SliderEvent>
}
