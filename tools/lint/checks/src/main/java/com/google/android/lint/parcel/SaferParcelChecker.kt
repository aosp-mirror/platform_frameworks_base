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

import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import org.jetbrains.uast.UCallExpression
import java.util.*

@Suppress("UnstableApiUsage")
class SaferParcelChecker : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> =
            MIGRATORS
                    .map(CallMigrator::method)
                    .map(Method::name)

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!isAtLeastT(context)) return
        val signature = getSignature(method)
        val migrator = MIGRATORS.firstOrNull { it.method.signature == signature } ?: return
        migrator.report(context, node, method)
    }

    private fun getSignature(method: PsiMethod): String {
        val name = UastLintUtils.getQualifiedName(method)
        val signature = method.getSignature(PsiSubstitutor.EMPTY)
        val parameters =
                signature.parameterTypes.joinToString(transform = PsiType::getCanonicalText)
        val types = signature.typeParameters.map(PsiTypeParameter::getName)
        val prefix = if (types.isEmpty()) "" else types.joinToString(", ", "<", ">") + " "
        return "$prefix$name($parameters)"
    }

    /** Taken from androidx-main:core/core/src/main/java/androidx/core/os/BuildCompat.java */
    private fun isAtLeastT(context: Context): Boolean {
        val project = if (context.isGlobalAnalysis()) context.mainProject else context.project
        return project.isAndroidProject
                && project.minSdkVersion.featureLevel >= 32
                && isAtLeastPreReleaseCodename("Tiramisu", project.minSdkVersion.codename)
    }

    /** Taken from androidx-main:core/core/src/main/java/androidx/core/os/BuildCompat.java */
    private fun isAtLeastPreReleaseCodename(min: String, actual: String): Boolean {
        if (actual == "REL") return false
        return actual.uppercase(Locale.ROOT) >= min.uppercase(Locale.ROOT)
    }

    companion object {
        @JvmField
        val ISSUE_UNSAFE_API_USAGE: Issue = Issue.create(
                id = "UnsafeParcelApi",
                briefDescription = "Use of unsafe deserialization API",
                explanation = """
                    You are using a deprecated deserialization API that doesn't accept the expected class as\
                     a parameter. This means that unexpected classes could be instantiated and\
                     unexpected code executed.

                    Please migrate to the safer alternative that takes an extra Class<T> parameter.
                    """,
                category = Category.SECURITY,
                priority = 8,
                severity = Severity.WARNING,

                implementation = Implementation(
                        SaferParcelChecker::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        // Parcel
        private val PARCEL_METHOD_READ_SERIALIZABLE = Method("android.os.Parcel", "readSerializable", listOf())
        private val PARCEL_METHOD_READ_ARRAY_LIST = Method("android.os.Parcel", "readArrayList", listOf("java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_LIST = Method("android.os.Parcel", "readList", listOf("java.util.List", "java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_PARCELABLE = Method(listOf("T"), "android.os.Parcel", "readParcelable", listOf("java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_PARCELABLE_LIST = Method(listOf("T"), "android.os.Parcel", "readParcelableList", listOf("java.util.List<T>", "java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_SPARSE_ARRAY = Method(listOf("T"), "android.os.Parcel", "readSparseArray", listOf("java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_ARRAY = Method("android.os.Parcel", "readArray", listOf("java.lang.ClassLoader"))
        private val PARCEL_METHOD_READ_PARCELABLE_ARRAY = Method("android.os.Parcel", "readParcelableArray", listOf("java.lang.ClassLoader"))

        // Bundle
        private val BUNDLE_METHOD_GET_SERIALIZABLE = Method("android.os.Bundle", "getSerializable", listOf("java.lang.String"))
        private val BUNDLE_METHOD_GET_PARCELABLE = Method(listOf("T"), "android.os.Bundle", "getParcelable", listOf("java.lang.String"))
        private val BUNDLE_METHOD_GET_PARCELABLE_ARRAY_LIST = Method(listOf("T"), "android.os.Bundle", "getParcelableArrayList", listOf("java.lang.String"))
        private val BUNDLE_METHOD_GET_PARCELABLE_ARRAY = Method("android.os.Bundle", "getParcelableArray", listOf("java.lang.String"))
        private val BUNDLE_METHOD_GET_SPARSE_PARCELABLE_ARRAY = Method(listOf("T"), "android.os.Bundle", "getSparseParcelableArray", listOf("java.lang.String"))

        // Intent
        private val INTENT_METHOD_GET_SERIALIZABLE_EXTRA = Method("android.content.Intent", "getSerializableExtra", listOf("java.lang.String"))
        private val INTENT_METHOD_GET_PARCELABLE_EXTRA = Method(listOf("T"), "android.content.Intent", "getParcelableExtra", listOf("java.lang.String"))
        private val INTENT_METHOD_GET_PARCELABLE_ARRAY_EXTRA = Method("android.content.Intent", "getParcelableArrayExtra", listOf("java.lang.String"))
        private val INTENT_METHOD_GET_PARCELABLE_ARRAY_LIST_EXTRA = Method(listOf("T"), "android.content.Intent", "getParcelableArrayListExtra", listOf("java.lang.String"))

        // TODO: Write migrators for methods below
        private val PARCEL_METHOD_READ_PARCELABLE_CREATOR = Method("android.os.Parcel", "readParcelableCreator", listOf("java.lang.ClassLoader"))

        private val MIGRATORS = listOf(
            ReturnMigrator(PARCEL_METHOD_READ_PARCELABLE, setOf("android.os.Parcelable")),
            ContainerArgumentMigrator(PARCEL_METHOD_READ_LIST, 0, "java.util.List"),
            ContainerReturnMigrator(PARCEL_METHOD_READ_ARRAY_LIST, "java.util.Collection"),
            ContainerReturnMigrator(PARCEL_METHOD_READ_SPARSE_ARRAY, "android.util.SparseArray"),
            ContainerArgumentMigrator(PARCEL_METHOD_READ_PARCELABLE_LIST, 0, "java.util.List"),
            ReturnMigratorWithClassLoader(PARCEL_METHOD_READ_SERIALIZABLE),
            ArrayReturnMigrator(PARCEL_METHOD_READ_ARRAY, setOf("java.lang.Object")),
            ArrayReturnMigrator(PARCEL_METHOD_READ_PARCELABLE_ARRAY, setOf("android.os.Parcelable")),

            ReturnMigrator(BUNDLE_METHOD_GET_PARCELABLE, setOf("android.os.Parcelable")),
            ContainerReturnMigrator(BUNDLE_METHOD_GET_PARCELABLE_ARRAY_LIST, "java.util.Collection", setOf("android.os.Parcelable")),
            ArrayReturnMigrator(BUNDLE_METHOD_GET_PARCELABLE_ARRAY, setOf("android.os.Parcelable")),
            ContainerReturnMigrator(BUNDLE_METHOD_GET_SPARSE_PARCELABLE_ARRAY, "android.util.SparseArray", setOf("android.os.Parcelable")),
            ReturnMigrator(BUNDLE_METHOD_GET_SERIALIZABLE, setOf("java.io.Serializable")),

            ReturnMigrator(INTENT_METHOD_GET_PARCELABLE_EXTRA, setOf("android.os.Parcelable")),
            ContainerReturnMigrator(INTENT_METHOD_GET_PARCELABLE_ARRAY_LIST_EXTRA, "java.util.Collection", setOf("android.os.Parcelable")),
            ArrayReturnMigrator(INTENT_METHOD_GET_PARCELABLE_ARRAY_EXTRA, setOf("android.os.Parcelable")),
            ReturnMigrator(INTENT_METHOD_GET_SERIALIZABLE_EXTRA, setOf("java.io.Serializable")),
        )
    }
}
