/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.lint

import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_NESTED_CLEAR_IDENTITY_CALLS
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_NON_FINAL_TOKEN
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_UNUSED_TOKEN
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageClearIdentityCallNotFollowedByTryFinally
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageNestedClearIdentityCallsPrimary
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageNestedClearIdentityCallsSecondary
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageNonFinalToken
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageRestoreIdentityCallNotInFinallyBlock
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageUnusedToken
import com.android.lint.CallingIdentityTokenIssueRegistry.Companion.getIncidentMessageUseOfCallerAwareMethodsWithClearedIdentity
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.getParentOfType

/**
 * Lint Detector that finds issues with improper usages of the token returned by
 * Binder.clearCallingIdentity()
 */
@Suppress("UnstableApiUsage")
class CallingIdentityTokenDetector : Detector(), SourceCodeScanner {
    private companion object {
        const val CLASS_BINDER = "android.os.Binder"
        const val CLASS_USER_HANDLE = "android.os.UserHandle"

        @JvmField
        val callerAwareMethods = listOf(
                Method.BINDER_GET_CALLING_PID,
                Method.BINDER_GET_CALLING_UID,
                Method.BINDER_GET_CALLING_UID_OR_THROW,
                Method.BINDER_GET_CALLING_USER_HANDLE,
                Method.USER_HANDLE_GET_CALLING_APP_ID,
                Method.USER_HANDLE_GET_CALLING_USER_ID
        )
    }

    /** Map of <Token variable name, Token object> */
    private val tokensMap = mutableMapOf<String, Token>()

    override fun getApplicableUastTypes(): List<Class<out UElement?>> =
            listOf(ULocalVariable::class.java, UQualifiedReferenceExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
            TokenUastHandler(context)

    /** File analysis starts with a clear map */
    override fun beforeCheckFile(context: Context) {
        tokensMap.clear()
    }

    /**
     * - If tokensMap has tokens after checking the file -> reports all locations as unused token
     * issue incidents
     * - File analysis ends with a clear map
     */
    override fun afterCheckFile(context: Context) {
        for (token in tokensMap.values) {
            context.report(
                    ISSUE_UNUSED_TOKEN,
                    token.location,
                    getIncidentMessageUnusedToken(token.variableName)
            )
        }
        tokensMap.clear()
    }

    /** UAST handler that analyses elements and reports incidents */
    private inner class TokenUastHandler(val context: JavaContext) : UElementHandler() {
        /**
         * For every variable initialization with Binder.clearCallingIdentity():
         * - Checks for non-final token issue
         * - Checks for unused token issue within different scopes
         * - Checks for nested calls of clearCallingIdentity() issue
         * - Checks for clearCallingIdentity() not followed by try-finally issue
         * - Stores token variable name, scope in the file, location and finally block in tokensMap
         */
        override fun visitLocalVariable(node: ULocalVariable) {
            val rhsExpression = node.uastInitializer as? UQualifiedReferenceExpression ?: return
            if (!isMethodCall(rhsExpression, Method.BINDER_CLEAR_CALLING_IDENTITY)) return
            val location = context.getLocation(node as UElement)
            val variableName = node.getName()
            if (!node.isFinal) {
                context.report(
                        ISSUE_NON_FINAL_TOKEN,
                        location,
                        getIncidentMessageNonFinalToken(variableName)
                )
            }
            // If there exists an unused variable with the same name in the map, we can imply that
            // we left the scope of the previous declaration, so we need to report the unused token
            val oldToken = tokensMap[variableName]
            if (oldToken != null) {
                context.report(
                        ISSUE_UNUSED_TOKEN,
                        oldToken.location,
                        getIncidentMessageUnusedToken(oldToken.variableName)
                )
            }
            // If there exists a token in the same scope as the current new token, it means that
            // clearCallingIdentity() has been called at least twice without immediate restoration
            // of identity, so we need to report the nested call of clearCallingIdentity()
            val firstCallToken = findFirstTokenInScope(node)
            if (firstCallToken != null) {
                context.report(
                        ISSUE_NESTED_CLEAR_IDENTITY_CALLS,
                        createNestedLocation(firstCallToken, location),
                        getIncidentMessageNestedClearIdentityCallsPrimary(
                                firstCallToken.variableName,
                                variableName
                        )
                )
            }
            // If the next statement in the tree is not a try-finally statement, we need to report
            // the "clearCallingIdentity() is not followed by try-finally" issue
            val finallyClause = (getNextStatementOfLocalVariable(node) as? UTryExpression)
                    ?.finallyClause
            if (finallyClause == null) {
                context.report(
                        ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY,
                        location,
                        getIncidentMessageClearIdentityCallNotFollowedByTryFinally(variableName)
                )
            }
            tokensMap[variableName] = Token(
                    variableName,
                    node.sourcePsi?.getUseScope(),
                    location,
                    finallyClause
            )
        }

        /**
         * For every class.method():
         * - Checks use of caller-aware methods issue
         * For every call of Binder.restoreCallingIdentity(token):
         * - Checks for restoreCallingIdentity() not in the finally block issue
         * - Removes token from tokensMap if token is within the scope of the method
         */
        override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression) {
            val token = findFirstTokenInScope(node)
            if (isCallerAwareMethod(node) && token != null) {
                context.report(
                        ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY,
                        context.getLocation(node),
                        getIncidentMessageUseOfCallerAwareMethodsWithClearedIdentity(
                                token.variableName,
                                node.asRenderString()
                        )
                )
                return
            }
            if (!isMethodCall(node, Method.BINDER_RESTORE_CALLING_IDENTITY)) return
            val selector = node.selector as UCallExpression
            val arg = selector.valueArguments[0] as? USimpleNameReferenceExpression ?: return
            val variableName = arg.identifier
            val originalScope = tokensMap[variableName]?.scope ?: return
            val psi = arg.sourcePsi ?: return
            // Checks if Binder.restoreCallingIdentity(token) is called within the scope of the
            // token declaration. If not within the scope, no action is needed because the token is
            // irrelevant i.e. not in the same scope or was not declared with clearCallingIdentity()
            if (!PsiSearchScopeUtil.isInScope(originalScope, psi)) return
            // We do not report "restore identity call not in finally" issue when there is no
            // finally block because that case is already handled by "clear identity call not
            // followed by try-finally" issue
            if (tokensMap[variableName]?.finallyBlock != null &&
                    node.uastParent != tokensMap[variableName]?.finallyBlock) {
                context.report(
                        ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK,
                        context.getLocation(node),
                        getIncidentMessageRestoreIdentityCallNotInFinallyBlock(variableName)
                )
            }
            tokensMap.remove(variableName)
        }

        private fun isCallerAwareMethod(expression: UQualifiedReferenceExpression): Boolean =
                callerAwareMethods.any { method -> isMethodCall(expression, method) }

        private fun isMethodCall(
            expression: UQualifiedReferenceExpression,
            method: Method
        ): Boolean {
            val psiMethod = expression.resolve() as? PsiMethod ?: return false
            return psiMethod.getName() == method.methodName &&
                    context.evaluator.methodMatches(
                            psiMethod,
                            method.className,
                            /* allowInherit */ true,
                            *method.args
                    )
        }

        /**
         * ULocalVariable in the file tree:
         *
         * UBlockExpression
         *     UDeclarationsExpression
         *         ULocalVariable
         *         ULocalVariable
         *     UTryStatement
         *     etc.
         *
         * To get the next statement of ULocalVariable:
         * - If there exists a next sibling in UDeclarationsExpression, return the sibling
         * - If there exists a next sibling of UDeclarationsExpression in UBlockExpression, return
         *   the sibling
         * - Otherwise, return null
         *
         * Example 1 - the next sibling is in UDeclarationsExpression:
         * Code:
         * {
         *     int num1 = 0, num2 = methodThatThrowsException();
         * }
         * Returns: num2 = methodThatThrowsException()
         *
         * Example 2 - the next sibling is in UBlockExpression:
         * Code:
         * {
         *     int num1 = 0;
         *     methodThatThrowsException();
         * }
         * Returns: methodThatThrowsException()
         *
         * Example 3 - no next sibling;
         * Code:
         * {
         *     int num1 = 0;
         * }
         * Returns: null
         */
        private fun getNextStatementOfLocalVariable(node: ULocalVariable): UElement? {
            val declarationsExpression = node.uastParent as? UDeclarationsExpression ?: return null
            val declarations = declarationsExpression.declarations
            val indexInDeclarations = declarations.indexOf(node)
            if (indexInDeclarations != -1 && declarations.size > indexInDeclarations + 1) {
                return declarations[indexInDeclarations + 1]
            }
            val enclosingBlock = node
                    .getParentOfType<UBlockExpression>(strict = true) ?: return null
            val expressions = enclosingBlock.expressions
            val indexInBlock = expressions.indexOf(declarationsExpression as UElement)
            return if (indexInBlock == -1) null else expressions.getOrNull(indexInBlock + 1)
        }
    }

    private fun findFirstTokenInScope(node: UElement): Token? {
        val psi = node.sourcePsi ?: return null
        for (token in tokensMap.values) {
            if (token.scope != null && PsiSearchScopeUtil.isInScope(token.scope, psi)) {
                return token
            }
        }
        return null
    }

    /**
     * Creates a new instance of the primary location with the secondary location
     *
     * Here, secondary location is the helper location that shows where the issue originated
     *
     * The detector reports locations as objects, so when we add a secondary location to a location
     * that has multiple issues, the secondary location gets displayed every time a location is
     * referenced.
     *
     * Example:
     * 1: final long token1 = Binder.clearCallingIdentity();
     * 2: long token2 = Binder.clearCallingIdentity();
     * 3: Binder.restoreCallingIdentity(token1);
     * 4: Binder.restoreCallingIdentity(token2);
     *
     * Explanation:
     * token2 has 2 issues: NonFinal and NestedCalls
     *
     *     Lint report without cloning                        Lint report with cloning
     * line 2: [NonFinalIssue]                            line 2: [NonFinalIssue]
     *     line 1: [NestedCallsIssue]
     * line 2: [NestedCallsIssue]                            line 2: [NestedCallsIssue]
     *     line 1: [NestedCallsIssue]                           line 1: [NestedCallsIssue]
     */
    private fun createNestedLocation(
        firstCallToken: Token,
        secondCallTokenLocation: Location
    ): Location {
        return cloneLocation(secondCallTokenLocation)
                .withSecondary(
                        cloneLocation(firstCallToken.location),
                        getIncidentMessageNestedClearIdentityCallsSecondary(
                                firstCallToken.variableName
                        )
                )
    }

    private fun cloneLocation(location: Location): Location {
        // smart cast of location.start to 'Position' is impossible, because 'location.start' is a
        // public API property declared in different module
        val locationStart = location.start
        return if (locationStart == null) {
            Location.create(location.file)
        } else {
            Location.create(location.file, locationStart, location.end)
        }
    }

    private enum class Method(
        val className: String,
        val methodName: String,
        val args: Array<String>
    ) {
        BINDER_CLEAR_CALLING_IDENTITY(CLASS_BINDER, "clearCallingIdentity", emptyArray()),
        BINDER_RESTORE_CALLING_IDENTITY(CLASS_BINDER, "restoreCallingIdentity", arrayOf("long")),
        BINDER_GET_CALLING_PID(CLASS_BINDER, "getCallingPid", emptyArray()),
        BINDER_GET_CALLING_UID(CLASS_BINDER, "getCallingUid", emptyArray()),
        BINDER_GET_CALLING_UID_OR_THROW(CLASS_BINDER, "getCallingUidOrThrow", emptyArray()),
        BINDER_GET_CALLING_USER_HANDLE(CLASS_BINDER, "getCallingUserHandle", emptyArray()),
        USER_HANDLE_GET_CALLING_APP_ID(CLASS_USER_HANDLE, "getCallingAppId", emptyArray()),
        USER_HANDLE_GET_CALLING_USER_ID(CLASS_USER_HANDLE, "getCallingUserId", emptyArray())
    }

    private data class Token(
        val variableName: String,
        val scope: SearchScope?,
        val location: Location,
        val finallyBlock: UElement?
    )
}
