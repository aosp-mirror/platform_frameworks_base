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

package com.android.databinding.ext

import com.android.databinding.ext.joinToCamelCase
import com.android.databinding.ext.joinToCamelCaseAsVar
import com.android.databinding.ext.joinIndented
import com.android.databinding.ext.joinIndentedExceptFirst

public fun String.extractAndroidId() : String = this.split("/")[1]

public fun String.times(x : Int) : String = 0.rangeTo(x-1).map { this }.join("")

public fun String.indent(x : Int) : String = split("\n").filterNot{it.trim() == ""}.joinIndented(x)

public fun String.indentExceptFirst(x : Int) : String = split("\n").filterNot{it.trim() == ""}.joinIndentedExceptFirst(x)

public fun String.getIndentation() : Int {
    var count = 0
    while ((count < this.length) && (this[count] <= ' ')) {
        count++
    }
    return count
}

public fun String.toCamelCase() : String {
    val split = this.split("_")
    if (split.size == 0) return ""
    if (split.size == 1) return split[0].capitalize()
    return split.joinToCamelCase()
}

public fun String.toCamelCaseAsVar() : String {
    val split = this.split("_")
    if (split.size == 0) return ""
    if (split.size == 1) return split[0]
    return split.joinToCamelCaseAsVar()
}