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

package com.google.android.lint

import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationOrigin
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UElement

/**
 * Lint Detector that ensures that any method overriding a method annotated
 * with @EnforcePermission is also annotated with the exact same annotation.
 * The intent is to surface the effective permission checks to the service
 * implementations.
 */
class EnforcePermissionDetector : Detector(), SourceCodeScanner {

    val ENFORCE_PERMISSION = "android.annotation.EnforcePermission"

    override fun applicableAnnotations(): List<String> {
        return listOf(ENFORCE_PERMISSION)
    }

    private fun areAnnotationsEquivalent(
        context: JavaContext,
        anno1: PsiAnnotation,
        anno2: PsiAnnotation
    ): Boolean {
        if (anno1.qualifiedName != anno2.qualifiedName) {
            return false
        }
        val attr1 = anno1.parameterList.attributes
        val attr2 = anno2.parameterList.attributes
        if (attr1.size != attr2.size) {
            return false
        }
        for (i in attr1.indices) {
            if (attr1[i].name != attr2[i].name) {
                return false
            }
            val value1 = attr1[i].value
            val value2 = attr2[i].value
            if (value1 == null && value2 == null) {
                continue
            }
            if (value1 == null || value2 == null) {
                return false
            }
            val v1 = ConstantEvaluator.evaluate(context, value1)
            val v2 = ConstantEvaluator.evaluate(context, value2)
            if (v1 != v2) {
                return false
            }
        }
        return true
    }

    override fun visitAnnotationUsage(
        context: JavaContext,
        element: UElement,
        annotationInfo: AnnotationInfo,
        usageInfo: AnnotationUsageInfo
    ) {
        if (usageInfo.type == AnnotationUsageType.EXTENDS) {
            val newClass = element.sourcePsi?.parent?.parent as PsiClass
            val extendedClass: PsiClass = usageInfo.referenced as PsiClass
            val newAnnotation = newClass.getAnnotation(ENFORCE_PERMISSION)
            val extendedAnnotation = extendedClass.getAnnotation(ENFORCE_PERMISSION)!!

            val location = context.getLocation(element)
            val newClassName = newClass.qualifiedName
            val extendedClassName = extendedClass.qualifiedName
            if (newAnnotation == null) {
                val msg = "The class $newClassName extends the class $extendedClassName which " +
                    "is annotated with @EnforcePermission. The same annotation must be used " +
                    "on $newClassName."
                context.report(ISSUE_MISSING_ENFORCE_PERMISSION, element, location, msg)
            } else if (!areAnnotationsEquivalent(context, newAnnotation, extendedAnnotation)) {
                val msg = "The class $newClassName is annotated with ${newAnnotation.text} " +
                    "which differs from the parent class $extendedClassName: " +
                    "${extendedAnnotation.text}. The same annotation must be used for " +
                    "both classes."
                context.report(ISSUE_MISMATCHING_ENFORCE_PERMISSION, element, location, msg)
            }
        } else if (usageInfo.type == AnnotationUsageType.METHOD_OVERRIDE &&
            annotationInfo.origin == AnnotationOrigin.METHOD) {
            val overridingMethod = element.sourcePsi as PsiMethod
            val overriddenMethod = usageInfo.referenced as PsiMethod
            val overridingAnnotation = overridingMethod.getAnnotation(ENFORCE_PERMISSION)
            val overriddenAnnotation = overriddenMethod.getAnnotation(ENFORCE_PERMISSION)!!

            val location = context.getLocation(element)
            val overridingClass = overridingMethod.parent as PsiClass
            val overriddenClass = overriddenMethod.parent as PsiClass
            val overridingName = "${overridingClass.name}.${overridingMethod.name}"
            val overriddenName = "${overriddenClass.name}.${overriddenMethod.name}"
            if (overridingAnnotation == null) {
                val msg = "The method $overridingName overrides the method $overriddenName which " +
                    "is annotated with @EnforcePermission. The same annotation must be used " +
                    "on $overridingName"
                context.report(ISSUE_MISSING_ENFORCE_PERMISSION, element, location, msg)
            } else if (!areAnnotationsEquivalent(
                        context, overridingAnnotation, overriddenAnnotation)) {
                val msg = "The method $overridingName is annotated with " +
                    "${overridingAnnotation.text} which differs from the overridden " +
                    "method $overriddenName: ${overriddenAnnotation.text}. The same " +
                    "annotation must be used for both methods."
                context.report(ISSUE_MISMATCHING_ENFORCE_PERMISSION, element, location, msg)
            }
        }
    }

    companion object {
        val EXPLANATION = """
            The @EnforcePermission annotation is used to indicate that the underlying binder code
            has already verified the caller's permissions before calling the appropriate method. The
            verification code is usually generated by the AIDL compiler, which also takes care of
            annotating the generated Java code.

            In order to surface that information to platform developers, the same annotation must be
            used on the implementation class or methods.
            """

        val ISSUE_MISSING_ENFORCE_PERMISSION: Issue = Issue.create(
            id = "MissingEnforcePermissionAnnotation",
            briefDescription = "Missing @EnforcePermission annotation on Binder method",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                    EnforcePermissionDetector::class.java,
                    Scope.JAVA_FILE_SCOPE
            )
        )

        val ISSUE_MISMATCHING_ENFORCE_PERMISSION: Issue = Issue.create(
            id = "MismatchingEnforcePermissionAnnotation",
            briefDescription = "Incorrect @EnforcePermission annotation on Binder method",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                    EnforcePermissionDetector::class.java,
                    Scope.JAVA_FILE_SCOPE
            )
        )
    }
}
