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

package com.google.android.lint.aidl

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UMethod

import java.util.TreeMap

/**
 *  Count the number of AIDL interfaces. Reports the number of annotated and
 *  non-annotated methods.
 */
@Suppress("UnstableApiUsage")
class AnnotatedAidlCounter : AidlImplementationDetector() {

    private data class Stat(
        var unannotated: Int = 0,
        var enforced: Int = 0,
        var notRequired: Int = 0,
    )

    private var packagesStats: TreeMap<String, Stat> = TreeMap<String, Stat>()

    override fun visitAidlMethod(
            context: JavaContext,
            node: UMethod,
            interfaceName: String,
            body: UBlockExpression
    ) {
        val packageName = context.uastFile?.packageName ?: "<unknown>"
        var packageStat = packagesStats.getOrDefault(packageName, Stat())
        when {
            node.hasAnnotation(ANNOTATION_ENFORCE_PERMISSION) -> packageStat.enforced += 1
            node.hasAnnotation(ANNOTATION_REQUIRES_NO_PERMISSION) -> packageStat.notRequired += 1
            else -> packageStat.unannotated += 1
        }
        packagesStats.put(packageName, packageStat)
        // context.driver.client.log(null, "%s.%s#%s".format(packageName, interfaceName, node.name))
    }

    override fun afterCheckRootProject(context: Context) {
        var total = Stat()
        for ((packageName, stat) in packagesStats) {
            context.client.log(null, "package $packageName => $stat")
            total.unannotated += stat.unannotated
            total.enforced += stat.enforced
            total.notRequired += stat.notRequired
        }
        val location = Location.create(context.project.dir)
        context.report(
            ISSUE_ANNOTATED_AIDL_COUNTER,
            location,
            "module ${context.project.name} => $total"
        )
    }

    companion object {

        @JvmField
        val ISSUE_ANNOTATED_AIDL_COUNTER = Issue.create(
                id = "AnnotatedAidlCounter",
                briefDescription = "Statistics on the number of annotated AIDL methods.",
                explanation = "",
                category = Category.SECURITY,
                priority = 5,
                severity = Severity.INFORMATIONAL,
                implementation = Implementation(
                        AnnotatedAidlCounter::class.java,
                        Scope.JAVA_FILE_SCOPE
                ),
        )
    }
}
