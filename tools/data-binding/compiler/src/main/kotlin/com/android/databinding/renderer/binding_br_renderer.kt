/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding.renderer

import java.util.TreeMap
import java.util.LinkedHashMap

class BrRenderer(val pkg : String, val className : String, val vbrs : List<ViewExprBinderRenderer>) {
    val keyToInt = LinkedHashMap<String,Int>();
    {
        addKey("__")
        vbrs.forEach {
            it.lb.variables.values().forEach {
                addKey(it.name)
            }
        }
    }
    var counter = 0
    fun addKey(name : String) {
        if (!keyToInt.contains(name)) {
            keyToInt.put(name, counter ++)
        }
    }

    public fun toInt(key : String) : Int = if (key == "") keyToInt.get("__") else keyToInt.get(key) ?: -1
    public fun toIntS(key : String) : String = "android.binding.BR.${if (key == "") "_all" else key}"
    public fun render() : String {
        return """
package $pkg;

public class $className {
    ${keyToInt.map({ "public static final int ${it.key} = ${it.value};"}).joinToString("\n    ")}
}
"""
    }
}
