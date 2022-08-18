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
import com.intellij.psi.PsiVariable
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.asRecursiveLogString

/**
 * Helper ADT class that facilitates the creation of lint auto fixes
 *
 * Handles "Single" permission checks that should be migrated to @EnforcePermission(...), as well as consecutive checks
 * that should be migrated to @EnforcePermission(allOf={...})
 *
 * TODO: handle anyOf style annotations
 */
sealed class EnforcePermissionFix {
    abstract fun locations(): List<Location>
    abstract fun javaAnnotationParameter(): String

    fun javaAnnotation(): String = "@$ANNOTATION_ENFORCE_PERMISSION(${javaAnnotationParameter()})"

    companion object {
        fun fromCallExpression(callExpression: UCallExpression, context: JavaContext): SingleFix =
            SingleFix(
                getPermissionCheckLocation(context, callExpression),
                getPermissionCheckArgumentValue(callExpression)
            )

        fun maybeAddManifestPrefix(permissionName: String): String =
            if (permissionName.contains(".")) permissionName
            else "android.Manifest.permission.$permissionName"

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
         * Given a permission check and an argument,
         * pull out the permission value that is being used
         */
        private fun getPermissionCheckArgumentValue(
            callExpression: UCallExpression,
            argumentPosition: Int = 0
        ): String {

            val identifier = when (
                val argument = callExpression.valueArguments.getOrNull(argumentPosition)
            ) {
                is UQualifiedReferenceExpression -> when (val selector = argument.selector) {
                    is USimpleNameReferenceExpression ->
                        ((selector.resolve() as PsiVariable).computeConstantValue() as String)

                    else -> throw RuntimeException(
                        "Couldn't resolve argument: ${selector.asRecursiveLogString()}"
                    )
                }

                is USimpleNameReferenceExpression -> (
                        (argument.resolve() as PsiVariable).computeConstantValue() as String)

                is ULiteralExpression -> argument.value as String

                else -> throw RuntimeException(
                    "Couldn't resolve argument: ${argument?.asRecursiveLogString()}"
                )
            }

            return identifier.substringAfterLast(".")
        }
    }
}

data class SingleFix(val location: Location, val permissionName: String) : EnforcePermissionFix() {
    override fun locations(): List<Location> = listOf(this.location)
    override fun javaAnnotationParameter(): String = maybeAddManifestPrefix(this.permissionName)
}
data class AllOfFix(val checks: List<SingleFix>) : EnforcePermissionFix() {
    override fun locations(): List<Location> = this.checks.map { it.location }
    override fun javaAnnotationParameter(): String =
        "allOf={${
            this.checks.joinToString(", ") { maybeAddManifestPrefix(it.permissionName) }
        }}"
}
