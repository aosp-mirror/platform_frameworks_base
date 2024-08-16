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

import com.android.tools.metalava.model.CallableItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.text.ApiFile
import java.io.File
import java.io.FileWriter

/** Usage: extract-flagged-apis <api text file> <output .pb file> */
fun main(args: Array<String>) {
    val cb = ApiFile.parseApi(listOf(File(args[0])))
    val builder = FlagApiMap.newBuilder()
    for (pkg in cb.getPackages().packages) {
        val packageName = pkg.qualifiedName()
        pkg.allClasses().forEach {
            extractFlaggedApisFromClass(it, it.methods(), packageName, builder)
            extractFlaggedApisFromClass(it, it.constructors(), packageName, builder)
        }
    }
    val flagApiMap = builder.build()
    FileWriter(args[1]).use { it.write(flagApiMap.toString()) }
}

fun extractFlaggedApisFromClass(
    classItem: ClassItem,
    callables: List<CallableItem>,
    packageName: String,
    builder: FlagApiMap.Builder
) {
    if (callables.isEmpty()) return
    val classFlag = getClassFlag(classItem)
    for (callable in callables) {
        val callableFlag = getFlagAnnotation(callable) ?: classFlag
        val api =
            JavaMethod.newBuilder()
                .setPackageName(packageName)
                .setClassName(classItem.fullName())
                .setMethodName(callable.name())
        for (param in callable.parameters()) {
            api.addParameters(param.type().toTypeString())
        }
        if (callableFlag != null) {
            addFlaggedApi(builder, api, callableFlag)
        }
    }
}

fun addFlaggedApi(builder: FlagApiMap.Builder, api: JavaMethod.Builder, flag: String) {
    if (builder.containsFlagToApi(flag)) {
        val updatedApis = builder.getFlagToApiOrThrow(flag).toBuilder().addJavaMethods(api).build()
        builder.putFlagToApi(flag, updatedApis)
    } else {
        val apis = FlaggedApis.newBuilder().addJavaMethods(api).build()
        builder.putFlagToApi(flag, apis)
    }
}

fun getClassFlag(classItem: ClassItem): String? {
    var classFlag = getFlagAnnotation(classItem)
    var cur = classItem
    // If a class is not a nested class, use its @FlaggedApi annotation value.
    // Otherwise, use the flag value of the closest outer class that is annotated by @FlaggedApi.
    while (classFlag == null) {
        cur = cur.containingClass() ?: break
        classFlag = getFlagAnnotation(cur)
    }
    return classFlag
}

fun getFlagAnnotation(item: Item): String? {
    return item.modifiers
        .findAnnotation("android.annotation.FlaggedApi")
        ?.findAttribute("value")
        ?.value
        ?.value() as? String
}
