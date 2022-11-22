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

import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.getUMethod
import com.google.android.lint.hasPermissionNameAnnotation
import com.google.android.lint.isPermissionMethodCall
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Helper class that facilitates the creation of lint auto fixes
 *
 * Handles "Single" permission checks that should be migrated to @EnforcePermission(...), as well as consecutive checks
 * that should be migrated to @EnforcePermission(allOf={...})
 *
 * TODO: handle anyOf style annotations
 */
data class EnforcePermissionFix(
    val locations: List<Location>,
    val permissionNames: List<String>
) {
    val annotation: String
        get() {
            val quotedPermissions = permissionNames.joinToString(", ") { """"$it"""" }
            val annotationParameter =
                if (permissionNames.size > 1) "allOf={$quotedPermissions}" else quotedPermissions
            return "@$ANNOTATION_ENFORCE_PERMISSION($annotationParameter)"
        }

    companion object {
        /**
         * conditionally constructs EnforcePermissionFix from a UCallExpression
         * @return EnforcePermissionFix if the called method is annotated with @PermissionMethod, else null
         */
        fun fromCallExpression(
            context: JavaContext,
            callExpression: UCallExpression
        ): EnforcePermissionFix? =
            if (isPermissionMethodCall(callExpression)) {
                EnforcePermissionFix(
                    listOf(getPermissionCheckLocation(context, callExpression)),
                    getPermissionCheckValues(callExpression)
                )
            } else null


        fun compose(individuals: List<EnforcePermissionFix>): EnforcePermissionFix =
            EnforcePermissionFix(
                individuals.flatMap { it.locations },
                individuals.flatMap { it.permissionNames }
            )

        /**
         * Given a permission check, get its proper location
         * so that a lint fix can remove the entire expression
         */
        private fun getPermissionCheckLocation(
            context: JavaContext,
            callExpression: UCallExpression
        ):
                Location {
            val javaPsi = callExpression.javaPsi!!
            return Location.create(
                context.file,
                javaPsi.containingFile?.text,
                javaPsi.textRange.startOffset,
                // unfortunately the element doesn't include the ending semicolon
                javaPsi.textRange.endOffset + 1
            )
        }

        /**
         * Given a @PermissionMethod, find arguments annotated with @PermissionName
         * and pull out the permission value(s) being used.  Also evaluates nested calls
         * to @PermissionMethod(s) in the given method's body.
         */
        private fun getPermissionCheckValues(
            callExpression: UCallExpression
        ): List<String> {
            if (!isPermissionMethodCall(callExpression)) return emptyList()

            val result = mutableSetOf<String>() // protect against duplicate permission values
            val visitedCalls = mutableSetOf<UCallExpression>() // don't visit the same call twice
            val bfsQueue = ArrayDeque(listOf(callExpression))

            // Breadth First Search - evalutaing nested @PermissionMethod(s) in the available
            // source code for @PermissionName(s).
            while (bfsQueue.isNotEmpty()) {
                val current = bfsQueue.removeFirst()
                visitedCalls.add(current)
                result.addAll(findPermissions(current))

                current.resolve()?.getUMethod()?.accept(object : AbstractUastVisitor() {
                    override fun visitCallExpression(node: UCallExpression): Boolean {
                        if (isPermissionMethodCall(node) && node !in visitedCalls) {
                            bfsQueue.add(node)
                        }
                        return false
                    }
                })
            }

            return result.toList()
        }

        private fun findPermissions(
            callExpression: UCallExpression,
        ): List<String> {
            val indices = callExpression.resolve()?.getUMethod()
                ?.uastParameters
                ?.filter(::hasPermissionNameAnnotation)
                ?.mapNotNull { it.sourcePsi?.parameterIndex() }
                ?: emptyList()

            return indices.mapNotNull {
                callExpression.getArgumentForParameter(it)?.evaluateString()
            }
        }
    }
}
