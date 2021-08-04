/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags

interface Flag<T> {
    val id: Int
    val default: T
}

data class BooleanFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Boolean = false
) : Flag<Boolean>

data class StringFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: String = ""
) : Flag<String>

data class IntFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Int = 0
) : Flag<Int>

data class LongFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Long = 0
) : Flag<Long>

data class FloatFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Float = 0f
) : Flag<Float>

data class DoubleFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Double = 0.0
) : Flag<Double>