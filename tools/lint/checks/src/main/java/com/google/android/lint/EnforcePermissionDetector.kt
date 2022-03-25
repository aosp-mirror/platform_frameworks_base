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

import com.android.tools.lint.client.api.UElementHandler
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
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod

/**
 * Lint Detector that ensures that any method overriding a method annotated
 * with @EnforcePermission is also annotated with the exact same annotation.
 * The intent is to surface the effective permission checks to the service
 * implementations.
 *
 * This is done with 2 mechanisms:
 *  1. Visit any annotation usage, to ensure that any derived class will have
 *     the correct annotation on each methods. This is for the top to bottom
 *     propagation.
 *  2. Visit any annotation, to ensure that if a method is annotated, it has
 *     its ancestor also annotated. This is to avoid having an annotation on a
 *     Java method without the corresponding annotation on the AIDL interface.
 */
class EnforcePermissionDetector : Detector(), SourceCodeScanner {

    val ENFORCE_PERMISSION = "android.annotation.EnforcePermission"
    val BINDER_CLASS = "android.os.Binder"
    val JAVA_OBJECT = "java.lang.Object"

    override fun applicableAnnotations(): List<String> {
        return listOf(ENFORCE_PERMISSION)
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UAnnotation::class.java)
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
            val v1 = ConstantEvaluator.evaluate(context, attr1[i].value)
            val v2 = ConstantEvaluator.evaluate(context, attr2[i].value)
            if (v1 != v2) {
                return false
            }
        }
        return true
    }

    private fun compareMethods(
        context: JavaContext,
        element: UElement,
        overridingMethod: PsiMethod,
        overriddenMethod: PsiMethod,
        checkEquivalence: Boolean = true
    ) {
        val overridingAnnotation = overridingMethod.getAnnotation(ENFORCE_PERMISSION)
        val overriddenAnnotation = overriddenMethod.getAnnotation(ENFORCE_PERMISSION)
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
        } else if (overriddenAnnotation == null) {
            val msg = "The method $overridingName overrides the method $overriddenName which " +
                "is not annotated with @EnforcePermission. The same annotation must be " +
                "used on $overriddenName. Did you forget to annotate the AIDL definition?"
            context.report(ISSUE_MISSING_ENFORCE_PERMISSION, element, location, msg)
        } else if (checkEquivalence && !areAnnotationsEquivalent(
                    context, overridingAnnotation, overriddenAnnotation)) {
            val msg = "The method $overridingName is annotated with " +
                "${overridingAnnotation.text} which differs from the overridden " +
                "method $overriddenName: ${overriddenAnnotation.text}. The same " +
                "annotation must be used for both methods."
            context.report(ISSUE_MISMATCHING_ENFORCE_PERMISSION, element, location, msg)
        }
    }

    private fun compareClasses(
        context: JavaContext,
        element: UElement,
        newClass: PsiClass,
        extendedClass: PsiClass,
        checkEquivalence: Boolean = true
    ) {
        val newAnnotation = newClass.getAnnotation(ENFORCE_PERMISSION)
        val extendedAnnotation = extendedClass.getAnnotation(ENFORCE_PERMISSION)

        val location = context.getLocation(element)
        val newClassName = newClass.qualifiedName
        val extendedClassName = extendedClass.qualifiedName
        if (newAnnotation == null) {
            val msg = "The class $newClassName extends the class $extendedClassName which " +
                "is annotated with @EnforcePermission. The same annotation must be used " +
                "on $newClassName."
            context.report(ISSUE_MISSING_ENFORCE_PERMISSION, element, location, msg)
        } else if (extendedAnnotation == null) {
            val msg = "The class $newClassName extends the class $extendedClassName which " +
                "is not annotated with @EnforcePermission. The same annotation must be used " +
                "on $extendedClassName. Did you forget to annotate the AIDL definition?"
            context.report(ISSUE_MISSING_ENFORCE_PERMISSION, element, location, msg)
        } else if (checkEquivalence && !areAnnotationsEquivalent(
            context, newAnnotation, extendedAnnotation)) {
            val msg = "The class $newClassName is annotated with ${newAnnotation.text} " +
                "which differs from the parent class $extendedClassName: " +
                "${extendedAnnotation.text}. The same annotation must be used for " +
                "both classes."
            context.report(ISSUE_MISMATCHING_ENFORCE_PERMISSION, element, location, msg)
        }
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
            compareClasses(context, element, newClass, extendedClass)
        } else if (usageInfo.type == AnnotationUsageType.METHOD_OVERRIDE &&
            annotationInfo.origin == AnnotationOrigin.METHOD) {
            val overridingMethod = element.sourcePsi as PsiMethod
            val overriddenMethod = usageInfo.referenced as PsiMethod
            compareMethods(context, element, overridingMethod, overriddenMethod)
        }
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitAnnotation(node: UAnnotation) {
                if (node.qualifiedName != ENFORCE_PERMISSION) {
                    return
                }
                val method = node.uastParent as? UMethod
                val klass = node.uastParent as? UClass
                if (klass != null) {
                    val newClass = klass as PsiClass
                    val extendedClass = newClass.getSuperClass()
                    if (extendedClass != null && extendedClass.qualifiedName != JAVA_OBJECT) {
                        // The equivalence check can be skipped, if both classes are
                        // annotated, it will be verified by visitAnnotationUsage.
                        compareClasses(context, klass, newClass,
                            extendedClass, checkEquivalence = false)
                    }
                } else if (method != null) {
                    val overridingMethod = method as PsiMethod
                    val parents = overridingMethod.findSuperMethods()
                    for (overriddenMethod in parents) {
                        // The equivalence check can be skipped, if both methods are
                        // annotated, it will be verified by visitAnnotationUsage.
                        compareMethods(context, method, overridingMethod,
                            overriddenMethod, checkEquivalence = false)
                    }
                }
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
