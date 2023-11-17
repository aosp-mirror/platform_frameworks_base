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

package android.platform.coverage

import com.android.tools.metalava.model.text.ApiFile
import java.io.File
import java.io.FileWriter

/** Usage: extract-flagged-apis <api text file> <output .pb file> */
fun main(args: Array<String>) {
    var cb = ApiFile.parseApi(listOf(File(args[0])))
    val flagToApi = mutableMapOf<String, MutableList<String>>()
    cb.getPackages()
        .allTopLevelClasses()
        .filter { it.methods().size > 0 }
        .forEach {
            for (method in it.methods()) {
                val flagValue =
                    method.modifiers
                        .findAnnotation("android.annotation.FlaggedApi")
                        ?.findAttribute("value")
                        ?.value
                        ?.value()
                if (flagValue != null && flagValue is String) {
                    val methodQualifiedName = "${it.qualifiedName()}.${method.name()}"
                    if (flagToApi.containsKey(flagValue)) {
                        flagToApi.get(flagValue)?.add(methodQualifiedName)
                    } else {
                        flagToApi.put(flagValue, mutableListOf(methodQualifiedName))
                    }
                }
            }
        }
    var builder = FlagApiMap.newBuilder()
    for (flag in flagToApi.keys) {
        var flaggedApis = FlaggedApis.newBuilder()
        for (method in flagToApi.get(flag).orEmpty()) {
            flaggedApis.addFlaggedApi(FlaggedApi.newBuilder().setQualifiedName(method))
        }
        builder.putFlagToApi(flag, flaggedApis.build())
    }
    val flagApiMap = builder.build()
    FileWriter(args[1]).use { it.write(flagApiMap.toString()) }
}
