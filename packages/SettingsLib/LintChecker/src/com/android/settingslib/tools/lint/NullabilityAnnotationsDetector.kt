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

package com.android.settingslib.tools.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod

class NullabilityAnnotationsDetector : Detector(), Detector.UastScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (!context.isJavaFile()) return null

        return object : UElementHandler() {
            override fun visitMethod(node: UMethod) {
                if (node.isPublic() && node.name != ANONYMOUS_CONSTRUCTOR) {
                    node.verifyMethod()
                    node.verifyMethodParameters()
                }
            }

            private fun UMethod.isPublic() = modifierList.hasModifierProperty(PsiModifier.PUBLIC)

            private fun UMethod.verifyMethod() {
                if (isConstructor) return
                if (returnType.isPrimitive()) return
                checkAnnotation(METHOD_MSG)
            }

            private fun UMethod.verifyMethodParameters() {
                for (parameter in uastParameters) {
                    if (parameter.type.isPrimitive()) continue
                    parameter.checkAnnotation(PARAMETER_MSG)
                }
            }

            private fun PsiType?.isPrimitive() = this is PsiPrimitiveType

            private fun UAnnotated.checkAnnotation(message: String) {
                val oldAnnotation = findOldNullabilityAnnotation()
                val oldAnnotationName = oldAnnotation?.qualifiedName?.substringAfterLast('.')

                if (oldAnnotationName != null) {
                    val annotation = "androidx.annotation.$oldAnnotationName"
                    reportIssue(
                        REQUIRE_NULLABILITY_ISSUE,
                        "Prefer $annotation",
                        LintFix.create()
                                .replace()
                                .range(context.getLocation(oldAnnotation))
                                .with("@$annotation")
                                .autoFix()
                                .build()
                    )
                } else if (!hasNullabilityAnnotation()) {
                    reportIssue(REQUIRE_NULLABILITY_ISSUE, message)
                }
            }

            private fun UElement.reportIssue(
                issue: Issue,
                message: String,
                quickfixData: LintFix? = null,
            ) {
                context.report(
                    issue = issue,
                    scope = this,
                    location = context.getNameLocation(this),
                    message = message,
                    quickfixData = quickfixData,
                )
            }

            private fun UAnnotated.findOldNullabilityAnnotation() =
                uAnnotations.find { it.qualifiedName in oldAnnotations }

            private fun UAnnotated.hasNullabilityAnnotation() =
                uAnnotations.any { it.qualifiedName in validAnnotations }
        }
    }

    private fun JavaContext.isJavaFile() = psiFile?.fileElementType.toString().startsWith("java")

    companion object {
        private val validAnnotations = arrayOf("androidx.annotation.NonNull",
            "androidx.annotation.Nullable")

        private val oldAnnotations = arrayOf("android.annotation.NonNull",
            "android.annotation.Nullable",
        )

        private const val ANONYMOUS_CONSTRUCTOR = "<anon-init>"

        private const val METHOD_MSG =
                "Java public method return with non-primitive type must add androidx annotation. " +
                        "Example: @NonNull | @Nullable Object functionName() {}"

        private const val PARAMETER_MSG =
                "Java public method parameter with non-primitive type must add androidx " +
                        "annotation. Example: functionName(@NonNull Context context, " +
                        "@Nullable Object obj) {}"

        internal val REQUIRE_NULLABILITY_ISSUE = Issue
            .create(
                id = "RequiresNullabilityAnnotation",
                briefDescription = "Requires nullability annotation for function",
                explanation = "All public java APIs should specify nullability annotations for " +
                        "methods and parameters.",
                category = Category.CUSTOM_LINT_CHECKS,
                priority = 3,
                severity = Severity.WARNING,
                androidSpecific = true,
                implementation = Implementation(
                  NullabilityAnnotationsDetector::class.java,
                  Scope.JAVA_FILE_SCOPE,
                ),
            )
    }
}