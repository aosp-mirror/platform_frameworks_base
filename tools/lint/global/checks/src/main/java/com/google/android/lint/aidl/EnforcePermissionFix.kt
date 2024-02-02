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
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.UastLintUtils.Companion.getAnnotationBooleanValue
import com.android.tools.lint.detector.api.UastLintUtils.Companion.getAnnotationStringValues
import com.android.tools.lint.detector.api.findSelector
import com.android.tools.lint.detector.api.getUMethod
import com.google.android.lint.findCallExpression
import com.google.android.lint.getPermissionMethodAnnotation
import com.google.android.lint.hasPermissionNameAnnotation
import com.google.android.lint.isPermissionMethodCall
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Helper class that facilitates the creation of lint auto fixes
 */
data class EnforcePermissionFix(
    val manualCheckLocations: List<Location>,
    val permissionNames: List<String>,
    val errorLevel: Boolean,
    val anyOf: Boolean,
) {
    fun toLintFix(context: JavaContext, node: UMethod): LintFix {
        val methodLocation = context.getLocation(node)
        val replaceOrRemoveFixes = manualCheckLocations.mapIndexed { index, manualCheckLocation ->
            if (index == 0) {
                // Replace the first manual check with a call to the helper method
                getHelperMethodFix(node, manualCheckLocation, false)
            } else {
                // Remove all subsequent manual checks
                LintFix.create()
                    .replace()
                    .reformat(true)
                    .range(manualCheckLocation)
                    .with("")
                    .autoFix()
                    .build()
            }
        }

        // Annotate the method with @EnforcePermission(...)
        val annotateFix = LintFix.create()
            .annotate(annotation)
            .range(methodLocation)
            .autoFix()
            .build()

        return LintFix.create()
            .name(annotateFix.getDisplayName())
            .composite(annotateFix, *replaceOrRemoveFixes.toTypedArray())
    }

    private val annotation: String
        get() {
            val quotedPermissions = permissionNames.joinToString(", ") { """"$it"""" }

            val attributeName =
                if (permissionNames.size > 1) {
                    if (anyOf) "anyOf" else "allOf"
                } else null

            val annotationParameter =
                if (attributeName != null) "$attributeName={$quotedPermissions}"
                else quotedPermissions

            return "@$ANNOTATION_ENFORCE_PERMISSION($annotationParameter)"
        }

    companion object {
        /**
         * Walks the expressions in a block, looking for simple permission checks.
         *
         * As soon as something other than a permission check is encountered, stop looking,
         * as some other business logic is happening that prevents an automated fix.
         */
        fun fromBlockExpression(
            context: JavaContext,
            blockExpression: UBlockExpression
        ): EnforcePermissionFix? {
            try {
                val singleFixes = mutableListOf<EnforcePermissionFix>()
                for (expression in blockExpression.expressions) {
                    val fix = fromExpression(context, expression) ?: break
                    singleFixes.add(fix)
                }
                return compose(singleFixes)
            } catch (e: AnyOfAllOfException) {
                return null
            }
        }

        /**
         * Conditionally constructs EnforcePermissionFix from any UExpression
         *
         * @return EnforcePermissionFix if the expression boils down to a permission check,
         * else null
         */
        fun fromExpression(
            context: JavaContext,
            expression: UExpression
        ): EnforcePermissionFix? {
            val trimmedExpression = expression.skipParenthesizedExprDown()
            if (trimmedExpression is UIfExpression) {
                return fromIfExpression(context, trimmedExpression)
            }
            findCallExpression(trimmedExpression)?.let {
                return fromCallExpression(context, it)
            }
            return null
        }

        /**
         * Conditionally constructs EnforcePermissionFix from a UCallExpression
         *
         * @return EnforcePermissionFix if the called method is annotated with @PermissionMethod, else null
         */
        fun fromCallExpression(
            context: JavaContext,
            callExpression: UCallExpression
        ): EnforcePermissionFix? {
            val method = callExpression.resolve()?.getUMethod() ?: return null
            val annotation = getPermissionMethodAnnotation(method) ?: return null
            val returnsVoid = method.returnType == PsiType.VOID
            val orSelf = getAnnotationBooleanValue(annotation, "orSelf") ?: false
            val anyOf = getAnnotationBooleanValue(annotation, "anyOf") ?: false
            return EnforcePermissionFix(
                    listOf(getPermissionCheckLocation(context, callExpression)),
                    getPermissionCheckValues(callExpression),
                    errorLevel = isErrorLevel(throws = returnsVoid, orSelf = orSelf),
                    anyOf,
            )
        }

        /**
         * Conditionally constructs EnforcePermissionFix from a UCallExpression
         *
         * @return EnforcePermissionFix IF AND ONLY IF:
         * * The condition of the if statement compares the return value of a
         *   PermissionMethod to one of the PackageManager.PermissionResult values
         * * The expression inside the if statement does nothing but throw SecurityException
         */
        fun fromIfExpression(
            context: JavaContext,
            ifExpression: UIfExpression
        ): EnforcePermissionFix? {
            val condition = ifExpression.condition.skipParenthesizedExprDown()
            if (condition !is UBinaryExpression) return null

            val maybeLeftCall = findCallExpression(condition.leftOperand)
            val maybeRightCall = findCallExpression(condition.rightOperand)

            val (callExpression, comparison) =
                    if (maybeLeftCall is UCallExpression) {
                        Pair(maybeLeftCall, condition.rightOperand)
                    } else if (maybeRightCall is UCallExpression) {
                        Pair(maybeRightCall, condition.leftOperand)
                    } else return null

            val permissionMethodAnnotation = getPermissionMethodAnnotation(
                    callExpression.resolve()?.getUMethod()) ?: return null

            val equalityCheck =
                    when (comparison.findSelector().asSourceString()
                            .filterNot(Char::isWhitespace)) {
                        "PERMISSION_GRANTED" -> UastBinaryOperator.IDENTITY_NOT_EQUALS
                        "PERMISSION_DENIED" -> UastBinaryOperator.IDENTITY_EQUALS
                        else -> return null
                    }

            if (condition.operator != equalityCheck) return null

            val throwExpression: UThrowExpression? =
                    ifExpression.thenExpression as? UThrowExpression
                            ?: (ifExpression.thenExpression as? UBlockExpression)
                                    ?.expressions?.firstOrNull()
                                    as? UThrowExpression


            val thrownClass = (throwExpression?.thrownExpression?.getExpressionType()
                    as? PsiClassType)?.resolve() ?: return null
            if (!context.evaluator.inheritsFrom(
                            thrownClass, "java.lang.SecurityException")){
                return null
            }

            val orSelf = getAnnotationBooleanValue(permissionMethodAnnotation, "orSelf") ?: false
            val anyOf = getAnnotationBooleanValue(permissionMethodAnnotation, "anyOf") ?: false

            return EnforcePermissionFix(
                    listOf(context.getLocation(ifExpression)),
                    getPermissionCheckValues(callExpression),
                    errorLevel = isErrorLevel(throws = true, orSelf = orSelf),
                    anyOf = anyOf
            )
        }


        fun compose(individuals: List<EnforcePermissionFix>): EnforcePermissionFix? {
            if (individuals.isEmpty()) return null
            val anyOfs = individuals.filter(EnforcePermissionFix::anyOf)
            // anyOf/allOf should be consistent.  If we encounter some @PermissionMethods that are anyOf
            // and others that aren't, we don't know what to do.
            if (anyOfs.isNotEmpty() && anyOfs.size < individuals.size) {
                throw AnyOfAllOfException()
            }
            return EnforcePermissionFix(
                    individuals.flatMap(EnforcePermissionFix::manualCheckLocations),
                    individuals.flatMap(EnforcePermissionFix::permissionNames),
                    errorLevel = individuals.all(EnforcePermissionFix::errorLevel),
                    anyOf = anyOfs.isNotEmpty()
            )
        }

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
        @Throws(AnyOfAllOfException::class)
        private fun getPermissionCheckValues(
            callExpression: UCallExpression
        ): List<String> {
            if (!isPermissionMethodCall(callExpression)) return emptyList()

            val result = mutableSetOf<String>() // protect against duplicate permission values
            val visitedCalls = mutableSetOf<UCallExpression>() // don't visit the same call twice
            val bfsQueue = ArrayDeque(listOf(callExpression))

            var anyOfAllOfState: AnyOfAllOfState = AnyOfAllOfState.INITIAL

            // Bread First Search - evaluating nested @PermissionMethod(s) in the available
            // source code for @PermissionName(s).
            while (bfsQueue.isNotEmpty()) {
                val currentCallExpression = bfsQueue.removeFirst()
                visitedCalls.add(currentCallExpression)
                val currentPermissions = findPermissions(currentCallExpression)
                result.addAll(currentPermissions)

                val currentAnnotation = getPermissionMethodAnnotation(
                        currentCallExpression.resolve()?.getUMethod())
                val currentAnyOf = getAnnotationBooleanValue(currentAnnotation, "anyOf") ?: false

                // anyOf/allOf should be consistent.  If we encounter a nesting of @PermissionMethods
                // where we start in an anyOf state and switch to allOf, or vice versa,
                // we don't know what to do.
                if (anyOfAllOfState == AnyOfAllOfState.INITIAL) {
                    if (currentAnyOf) anyOfAllOfState = AnyOfAllOfState.ANY_OF
                    else if (result.isNotEmpty()) anyOfAllOfState = AnyOfAllOfState.ALL_OF
                }

                if (anyOfAllOfState == AnyOfAllOfState.ALL_OF && currentAnyOf) {
                    throw AnyOfAllOfException()
                }

                if (anyOfAllOfState == AnyOfAllOfState.ANY_OF &&
                        !currentAnyOf && currentPermissions.size > 1) {
                    throw AnyOfAllOfException()
                }

                currentCallExpression.resolve()?.getUMethod()
                        ?.accept(PermissionCheckValuesVisitor(visitedCalls, bfsQueue))
            }

            return result.toList()
        }

        private enum class AnyOfAllOfState {
            INITIAL,
            ANY_OF,
            ALL_OF
        }

        /**
         * Adds visited permission method calls to the provided
         * queue in support of the BFS traversal happening while
         * this is used
         */
        private class PermissionCheckValuesVisitor(
                val visitedCalls: Set<UCallExpression>,
                val bfsQueue: ArrayDeque<UCallExpression>
        ) : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (isPermissionMethodCall(node) && node !in visitedCalls) {
                    bfsQueue.add(node)
                }
                return false
            }
        }

        private fun findPermissions(
            callExpression: UCallExpression,
        ): List<String> {
            val annotation = getPermissionMethodAnnotation(callExpression.resolve()?.getUMethod())

            val hardCodedPermissions = (getAnnotationStringValues(annotation, "value")
                    ?: emptyArray())
                    .toList()

            val indices = callExpression.resolve()?.getUMethod()
                    ?.uastParameters
                    ?.filter(::hasPermissionNameAnnotation)
                    ?.mapNotNull { it.sourcePsi?.parameterIndex() }
                    ?: emptyList()

            val argPermissions = indices
                    .flatMap { i ->
                        when (val argument = callExpression.getArgumentForParameter(i)) {
                            null -> listOf(null)
                            is UExpressionList -> // varargs e.g. someMethod(String...)
                                argument.expressions.map(UExpression::evaluateString)
                            else -> listOf(argument.evaluateString())
                        }
                    }
                    .filterNotNull()

            return hardCodedPermissions + argPermissions
        }

        /**
         * If we detect that the PermissionMethod enforces that permission is granted,
         * AND is of the "orSelf" variety, we are very confident that this is a behavior
         * preserving migration to @EnforcePermission.  Thus, the incident should be ERROR
         * level.
         */
        private fun isErrorLevel(throws: Boolean, orSelf: Boolean): Boolean = throws && orSelf
    }
}
/**
 * anyOf/allOf @PermissionMethods must be consistent to apply @EnforcePermission -
 * meaning if we encounter some @PermissionMethods that are anyOf, and others are allOf,
 * we don't know which to apply.
 */
class AnyOfAllOfException : Exception() {
    override val message: String = "anyOf/allOf permission methods cannot be mixed"
}
