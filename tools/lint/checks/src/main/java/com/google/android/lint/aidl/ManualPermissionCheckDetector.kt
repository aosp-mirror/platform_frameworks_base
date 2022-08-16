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
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.google.android.lint.CLASS_STUB
import com.google.android.lint.ENFORCE_PERMISSION_METHODS
import com.intellij.psi.PsiAnonymousClass
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression

/**
 * Looks for methods implementing generated AIDL interface stubs
 * that can have simple permission checks migrated to
 * @EnforcePermission annotations
 *
 * TODO: b/242564870 (enable parse and autoFix of .aidl files)
 */
@Suppress("UnstableApiUsage")
class ManualPermissionCheckDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement?>> =
        listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = AidlStubHandler(context)

    private inner class AidlStubHandler(val context: JavaContext) : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            val interfaceName = getContainingAidlInterface(node)
                .takeUnless(EXCLUDED_CPP_INTERFACES::contains) ?: return
            val body = (node.uastBody as? UBlockExpression) ?: return
            val fix = accumulateSimplePermissionCheckFixes(body) ?: return

            val javaRemoveFixes = fix.locations().map {
                fix()
                    .replace()
                    .reformat(true)
                    .range(it)
                    .with("")
                    .autoFix()
                    .build()
            }

            val javaAnnotateFix = fix()
                .annotate(fix.javaAnnotation())
                .range(context.getLocation(node))
                .autoFix()
                .build()

            val message =
                "$interfaceName permission check can be converted to @EnforcePermission annotation"

            context.report(
                ISSUE_USE_ENFORCE_PERMISSION_ANNOTATION,
                fix.locations().last(),
                message,
                fix().composite(*javaRemoveFixes.toTypedArray(), javaAnnotateFix)
            )
        }

        /**
         * Walk the expressions in the method, looking for simple permission checks.
         *
         * If a single permission check is found at the beginning of the method,
         * this should be migrated to @EnforcePermission(value).
         *
         * If multiple consecutive permission checks are found,
         * these should be migrated to @EnforcePermission(allOf={value1, value2, ...})
         *
         * As soon as something other than a permission check is encountered, stop looking,
         * as some other business logic is happening that prevents an automated fix.
         */
        private fun accumulateSimplePermissionCheckFixes(methodBody: UBlockExpression):
                EnforcePermissionFix? {
            val singleFixes = mutableListOf<SingleFix>()
            for (expression in methodBody.expressions) {
                singleFixes.add(getPermissionCheckFix(expression) ?: break)
            }
            return when (singleFixes.size) {
                0 -> null
                1 -> singleFixes[0]
                else -> AllOfFix(singleFixes)
            }
        }

        /**
         * If an expression boils down to a permission check, return
         * the helper for creating a lint auto fix to @EnforcePermission
         */
        private fun getPermissionCheckFix(startingExpression: UElement?):
                SingleFix? {
            return when (startingExpression) {
                is UQualifiedReferenceExpression -> getPermissionCheckFix(
                    startingExpression.selector
                )

                is UIfExpression -> getPermissionCheckFix(startingExpression.condition)

                is UCallExpression -> {
                    return if (isPermissionCheck(startingExpression))
                        EnforcePermissionFix.fromCallExpression(startingExpression, context)
                    else null
                }

                else -> null
            }
        }
    }

    companion object {

        private val EXPLANATION = """
            Whenever possible, method implementations of AIDL interfaces should use the @EnforcePermission
            annotation to declare the permissions to be enforced.  The verification code is then
            generated by the AIDL compiler, which also takes care of annotating the generated java
            code.

            This reduces the risk of bugs around these permission checks (that often become vulnerabilities).
            It also enables easier auditing and review.

            Please migrate to an @EnforcePermission annotation. (See: go/aidl-enforce-howto)
        """.trimIndent()

        @JvmField
        val ISSUE_USE_ENFORCE_PERMISSION_ANNOTATION = Issue.create(
            id = "UseEnforcePermissionAnnotation",
            briefDescription = "Manual permission check can be @EnforcePermission annotation",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.WARNING,
            implementation = Implementation(
                ManualPermissionCheckDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            ),
            enabledByDefault = false, // TODO: enable once b/241171714 is resolved
        )

        private fun isPermissionCheck(callExpression: UCallExpression): Boolean {
            val method = callExpression.resolve() ?: return false
            val className = method.containingClass?.qualifiedName
            return ENFORCE_PERMISSION_METHODS.any {
                it.clazz == className && it.name == method.name
            }
        }

        /**
         * given a UMethod, determine if this method is
         * an entrypoint to an interface generated by AIDL,
         * returning the interface name if so
         */
        fun getContainingAidlInterface(node: UMethod): String? {
            if (!isInClassCalledStub(node)) return null
            for (superMethod in node.findSuperMethods()) {
                for (extendsInterface in superMethod.containingClass?.extendsList?.referenceElements
                    ?: continue) {
                    if (extendsInterface.qualifiedName == IINTERFACE_INTERFACE) {
                        return superMethod.containingClass?.name
                    }
                }
            }
            return null
        }

        private fun isInClassCalledStub(node: UMethod): Boolean {
            (node.containingClass as? PsiAnonymousClass)?.let {
                return it.baseClassReference.referenceName == CLASS_STUB
            }
            return node.containingClass?.extendsList?.referenceElements?.any {
                it.referenceName == CLASS_STUB
            } ?: false
        }
    }
}
