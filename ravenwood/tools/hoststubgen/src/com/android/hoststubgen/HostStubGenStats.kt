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
package com.android.hoststubgen

import com.android.hoststubgen.asm.getOuterClassNameFromFullClassName
import com.android.hoststubgen.asm.getPackageNameFromFullClassName
import com.android.hoststubgen.filters.FilterPolicyWithReason
import org.objectweb.asm.Opcodes
import java.io.PrintWriter

open class HostStubGenStats {
    data class Stats(
            var supported: Int = 0,
            var total: Int = 0,
            val children: MutableMap<String, Stats> = mutableMapOf<String, Stats>(),
    )

    private val stats = mutableMapOf<String, Stats>()

    data class Api(
        val fullClassName: String,
        val methodName: String,
        val methodDesc: String,
    )

    private val apis = mutableListOf<Api>()

    fun onVisitPolicyForMethod(
        fullClassName: String,
        methodName: String,
        descriptor: String,
        policy: FilterPolicyWithReason,
        access: Int
    ) {
        if (policy.policy.isSupported) {
            apis.add(Api(fullClassName, methodName, descriptor))
        }

        // Ignore methods that aren't public
        if ((access and Opcodes.ACC_PUBLIC) == 0) return
        // Ignore methods that are abstract
        if ((access and Opcodes.ACC_ABSTRACT) != 0) return
        // Ignore methods where policy isn't relevant
        if (policy.isIgnoredForStats) return

        val packageName = getPackageNameFromFullClassName(fullClassName)
        val className = getOuterClassNameFromFullClassName(fullClassName)

        // Ignore methods for certain generated code
        if (className.endsWith("Proto")
                or className.endsWith("ProtoEnums")
                or className.endsWith("LogTags")
                or className.endsWith("StatsLog")) {
            return
        }

        val packageStats = stats.getOrPut(packageName) { Stats() }
        val classStats = packageStats.children.getOrPut(className) { Stats() }

        if (policy.policy.isSupported) {
            packageStats.supported += 1
            classStats.supported += 1
        }
        packageStats.total += 1
        classStats.total += 1
    }

    fun dumpOverview(pw: PrintWriter) {
        pw.printf("PackageName,ClassName,SupportedMethods,TotalMethods\n")
        stats.toSortedMap().forEach { (packageName, packageStats) ->
            if (packageStats.supported > 0) {
                packageStats.children.toSortedMap().forEach { (className, classStats) ->
                    pw.printf("%s,%s,%d,%d\n", packageName, className,
                            classStats.supported, classStats.total)
                }
            }
        }
    }
}
