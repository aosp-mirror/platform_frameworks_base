/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.lint.parcel

data class Method(
    val params: List<String>,
    val clazz: String,
    val name: String,
    val parameters: List<String>
) {
    constructor(
        clazz: String,
        name: String,
        parameters: List<String>
    ) : this(
            listOf(), clazz, name, parameters
    )

    val signature: String
        get() {
            val prefix = if (params.isEmpty()) "" else "${params.joinToString(", ", "<", ">")} "
            return "$prefix$clazz.$name(${parameters.joinToString()})"
        }

    val className: String by lazy {
        clazz.split(".").last()
    }
}
