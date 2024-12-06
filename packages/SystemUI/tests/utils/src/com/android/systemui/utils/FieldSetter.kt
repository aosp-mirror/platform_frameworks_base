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

package com.android.systemui.utils

import java.lang.reflect.Field

object FieldSetter {
    @JvmStatic
    fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field[obj] = value
        } catch (e: NoSuchFieldException) {
            throw RuntimeException("Failed to set $fieldName of obj", e)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("Failed to set $fieldName of obj", e)
        }
    }

    @JvmStatic
    fun setField(obj: Any?, fld: Field, value: Any?) {
        try {
            fld.setAccessible(true)
            fld.set(obj, value)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("Failed to set ${fld.getName()} of obj", e)
        }
    }
}
