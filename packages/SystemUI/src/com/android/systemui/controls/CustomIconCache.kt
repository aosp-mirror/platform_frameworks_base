/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls

import android.content.ComponentName
import android.graphics.drawable.Icon
import androidx.annotation.GuardedBy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Icon cache for custom icons sent with controls.
 *
 * It assumes that only one component can be current at the time, to minimize the number of icons
 * stored at a given time.
 */
@Singleton
class CustomIconCache @Inject constructor() {

    private var currentComponent: ComponentName? = null
    @GuardedBy("cache")
    private val cache: MutableMap<String, Icon> = LinkedHashMap()

    /**
     * Store an icon in the cache.
     *
     * If the icons currently stored do not correspond to the component to be stored, the cache is
     * cleared first.
     */
    fun store(component: ComponentName, controlId: String, icon: Icon?) {
        if (component != currentComponent) {
            clear()
            currentComponent = component
        }
        synchronized(cache) {
            if (icon != null) {
                cache.put(controlId, icon)
            } else {
                cache.remove(controlId)
            }
        }
    }

    /**
     * Retrieves a custom icon stored in the cache.
     *
     * It will return null if the component requested is not the one whose icons are stored, or if
     * there is no icon cached for that id.
     */
    fun retrieve(component: ComponentName, controlId: String): Icon? {
        if (component != currentComponent) return null
        return synchronized(cache) {
            cache.get(controlId)
        }
    }

    private fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }
}