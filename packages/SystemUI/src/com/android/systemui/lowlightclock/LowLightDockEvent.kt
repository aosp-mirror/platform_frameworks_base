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

package com.android.systemui.lowlightclock

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class LowLightDockEvent(private val id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Ambient light changed from light to dark") AMBIENT_LIGHT_TO_DARK(999),
    @UiEvent(doc = "The low light mode has started") LOW_LIGHT_STARTED(1000),
    @UiEvent(doc = "Ambient light changed from dark to light") AMBIENT_LIGHT_TO_LIGHT(1001),
    @UiEvent(doc = "The low light mode has stopped") LOW_LIGHT_STOPPED(1002);

    override fun getId(): Int {
        return id
    }
}
