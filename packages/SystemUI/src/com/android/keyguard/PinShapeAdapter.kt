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

package com.android.keyguard

import android.content.Context
import com.android.systemui.res.R
import kotlin.random.Random

class PinShapeAdapter {
    var shapes: MutableList<Int> = ArrayList()
    val random = Random(System.currentTimeMillis())

    constructor(context: Context) {
        val availableShapes = context.resources.obtainTypedArray(R.array.bouncer_pin_shapes)

        for (i in 0 until availableShapes.length()) {
            val shape = availableShapes.getResourceId(i, 0)
            shapes.add(shape)
        }

        shapes.shuffle()
        availableShapes.recycle()
    }

    fun getShape(pos: Int): Int {
        return shapes[pos.mod(shapes.size)]
    }
}
