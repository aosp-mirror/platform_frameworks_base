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

package com.google.android.lint.aidl

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationOrigin
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.google.android.lint.findCallExpression
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.skipParenthesizedExprDown

import java.util.EnumSet

/**
 * Lint Detector that ensures consistency when using the @EnforcePermission
 * annotation. Multiple verifications are implemented:
 *
 *  1. Visit any annotation usage, to ensure that any derived class will have
 *     the correct annotation on each methods. Even if the subclass does not
 *     have the annotation, visitAnnotationUsage will be called which allows us
 *     to capture the issue.
 *  2. Visit any method, to ensure that if a method is annotated, it has
 *     its ancestor also annotated. This is to avoid having an annotation on a
 *     Java method without the corresponding annotation on the AIDL interface.
 *  3. When annotated, ensures that the first instruction is to call the helper
 *     method (or the parent helper).
 */
class EnforcePermissionDetector : Detector(), SourceCodeScanner {

    override fun applicableAnnotations(): List<String> {
        return listOf(ANNOTATION_ENFORCE_PERMISSION)
    }

    override fun getApplicableUastTypes(): List<Class<out UElement?>> =
            listOf(UMethod::class.java)

    private fun annotationValueGetChildren(elem: PsiElement): Array<PsiElement> {
        if (elem is PsiArrayInitializerMemberValue)
            return elem.getInitializers().map { it as PsiElement }.toTypedArray()
        return elem.getChildren()
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
            val value1 = attr1[i].value ?: return false
            val value2 = attr2[i].value ?: return false
            // Try to compare values directly with each other.
            val v1 = ConstantEvaluator.evaluate(context, value1)
            val v2 = ConstantEvaluator.evaluate(context, value2)
            if (v1 != null && v2 != null) {
                if (v1 != v2 && !isOneShortPermissionOfOther(v1, v2)) {
                    return false
                }
            } else {
                val children1 = annotationValueGetChildren(value1)
                val children2 = annotationValueGetChildren(value2)
                if (children1.size != children2.size) {
                    return false
                }
                for (j in children1.indices) {
                    val c1 = ConstantEvaluator.evaluate(context, children1[j])
                    val c2 = ConstantEvaluator.evaluate(context, children2[j])
                    if (c1 != c2 && !isOneShortPermissionOfOther(c1, c2)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun isOneShortPermissionOfOther(
        permission1: Any?,
        permission2: Any?
    ): Boolean = permission1 == (permission2 as? String)?.removePrefix(PERMISSION_PREFIX_LITERAL) ||
            permission2 == (permission1 as? String)?.removePrefix(PERMISSION_PREFIX_LITERAL)

    private fun compareMethods(
        context: JavaContext,
        element: UElement,
        overridingMethod: PsiMethod,
        overriddenMethod: PsiMethod,
        checkEquivalence: Boolean = true
    ) {
        val overridingAnnotation = overridingMethod.getAnnotation(ANNOTATION_ENFORCE_PERMISSION)
        val overriddenAnnotation = overriddenMethod.getAnnotation(ANNOTATION_ENFORCE_PERMISSION)
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

    override fun visitAnnotationUsage(
        context: JavaContext,
        element: UElement,
        annotationInfo: AnnotationInfo,
        usageInfo: AnnotationUsageInfo
    ) {
        if (usageInfo.type == AnnotationUsageType.METHOD_OVERRIDE &&
            annotationInfo.origin == AnnotationOrigin.METHOD) {
            /* Ignore implementations that are not a sub-class of Stub (i.e., Proxy). */
            val uMethod = element as? UMethod ?: return
            if (!isContainedInSubclassOfStub(context, uMethod)) {
                return
            }
            val overridingMethod = element.sourcePsi as PsiMethod
            val overriddenMethod = usageInfo.referenced as PsiMethod
            compareMethods(context, element, overridingMethod, overriddenMethod)
        }
    }

    override fun createUastHandler(context: JavaContext): UElementHandler = AidlStubHandler(context)

    private inner class AidlStubHandler(val context: JavaContext) : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (context.evaluator.isAbstract(node)) return
            if (!node.hasAnnotation(ANNOTATION_ENFORCE_PERMISSION)) return

            if (!isContainedInSubclassOfStub(context, node)) {
                context.report(
                    ISSUE_MISUSING_ENFORCE_PERMISSION,
                    node,
                    context.getLocation(node),
                    "The class of ${node.name} does not inherit from an AIDL generated Stub class"
                )
                return
            }

            /* Check that we are connected to the super class */
            val overridingMethod = node as PsiMethod
            val parents = overridingMethod.findSuperMethods()
            if (parents.isEmpty()) {
                context.report(
                    ISSUE_MISUSING_ENFORCE_PERMISSION,
                    node,
                    context.getLocation(node),
                    "The method ${node.name} does not override an AIDL generated method"
                )
                return
            }
            for (overriddenMethod in parents) {
                // The equivalence check can be skipped, if both methods are
                // annotated, it will be verified by visitAnnotationUsage.
                compareMethods(context, node, overridingMethod,
                    overriddenMethod, checkEquivalence = false)
            }

            /* Check that the helper is called as a first instruction */
            val targetExpression = getHelperMethodCallSourceString(node)
            val message =
                "Method must start with $targetExpression or super.${node.name}(), if applicable"

            val firstExpression = (node.uastBody as? UBlockExpression)
                    ?.expressions?.firstOrNull()

            if (firstExpression == null) {
                context.report(
                    ISSUE_ENFORCE_PERMISSION_HELPER,
                    context.getLocation(node),
                    message,
                )
                return
            }

            val firstExpressionSource = firstExpression.skipParenthesizedExprDown()
              .asSourceString()
              .filterNot(Char::isWhitespace)

            if (firstExpressionSource != targetExpression &&
                  firstExpressionSource != "super.$targetExpression") {
                // calling super.<methodName>() is also legal
                val directSuper = context.evaluator.getSuperMethod(node)
                val firstCall = findCallExpression(firstExpression)?.resolve()
                if (directSuper != null && firstCall == directSuper) return

                val locationTarget = getLocationTarget(firstExpression)
                val expressionLocation = context.getLocation(locationTarget)

                context.report(
                    ISSUE_ENFORCE_PERMISSION_HELPER,
                    context.getLocation(node),
                    message,
                    getHelperMethodFix(node, expressionLocation),
                )
            }
        }
    }

    companion object {

        private const val HELPER_SUFFIX = "_enforcePermission"

        val EXPLANATION = """
            The @EnforcePermission annotation is used to delegate the verification of the caller's
            permissions to a generated AIDL method.

            In order to surface that information to platform developers, the same annotation must be
            used on the implementation class or methods.

            The @EnforcePermission annotation can only be used on methods whose class extends from
            the Stub class generated by the AIDL compiler. When @EnforcePermission is applied, the
            AIDL compiler generates a Stub method to do the permission check called yourMethodName$HELPER_SUFFIX.

            yourMethodName$HELPER_SUFFIX must be executed before any other operation. To do that, you can
            either call it directly, or call it indirectly via super.yourMethodName().
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
                    EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
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
                    EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
            )
        )

        val ISSUE_ENFORCE_PERMISSION_HELPER: Issue = Issue.create(
            id = "MissingEnforcePermissionHelper",
            briefDescription = """Missing permission-enforcing method call in AIDL method
                |annotated with @EnforcePermission""".trimMargin(),
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                    EnforcePermissionDetector::class.java,
                    EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
            )
        )

        val ISSUE_MISUSING_ENFORCE_PERMISSION: Issue = Issue.create(
            id = "MisusingEnforcePermissionAnnotation",
            briefDescription = "@EnforcePermission cannot be used here",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                    EnforcePermissionDetector::class.java,
                    EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
            )
        )

        /**
         * handles an edge case with UDeclarationsExpression, where sourcePsi is null,
         * resulting in an incorrect Location if used directly
         */
        private fun getLocationTarget(firstExpression: UExpression): PsiElement? {
            if (firstExpression.sourcePsi != null) return firstExpression.sourcePsi
            if (firstExpression is UDeclarationsExpression) {
                return firstExpression.declarations.firstOrNull()?.sourcePsi
            }
            return null
        }
    }
}
