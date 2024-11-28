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

package com.android.systemui.display.data.repository

import com.android.systemui.display.shared.model.DisplayWindowProperties
import com.google.common.collect.HashBasedTable
import org.mockito.kotlin.mock

class FakeDisplayWindowPropertiesRepository : DisplayWindowPropertiesRepository {

    private val properties = HashBasedTable.create<Int, Int, DisplayWindowProperties>()

    override fun get(displayId: Int, windowType: Int): DisplayWindowProperties {
        return properties.get(displayId, windowType)
            ?: DisplayWindowProperties(
                    displayId = displayId,
                    windowType = windowType,
                    context = mock(),
                    windowManager = mock(),
                    layoutInflater = mock(),
                )
                .also { properties.put(displayId, windowType, it) }
    }

    /** Sets an instance, just for testing purposes. */
    fun insert(instance: DisplayWindowProperties) {
        properties.put(instance.displayId, instance.windowType, instance)
    }
}
