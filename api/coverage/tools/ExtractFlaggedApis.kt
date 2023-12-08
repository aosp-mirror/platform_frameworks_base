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
    var builder = FlagApiMap.newBuilder()
    for (pkg in cb.getPackages().packages) {
        var packageName = pkg.qualifiedName()
        pkg.allClasses()
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
                        var api =
                            JavaMethod.newBuilder()
                                .setPackageName(packageName)
                                .setClassName(it.fullName())
                                .setMethodName(method.name())
                        for (param in method.parameters()) {
                            api.addParameterTypes(param.type().toTypeString())
                        }
                        if (builder.containsFlagToApi(flagValue)) {
                            var updatedApis =
                                builder
                                    .getFlagToApiOrThrow(flagValue)
                                    .toBuilder()
                                    .addJavaMethods(api)
                                    .build()
                            builder.putFlagToApi(flagValue, updatedApis)
                        } else {
                            var apis = FlaggedApis.newBuilder().addJavaMethods(api).build()
                            builder.putFlagToApi(flagValue, apis)
                        }
                    }
                }
            }
    }
    val flagApiMap = builder.build()
    FileWriter(args[1]).use { it.write(flagApiMap.toString()) }
}
