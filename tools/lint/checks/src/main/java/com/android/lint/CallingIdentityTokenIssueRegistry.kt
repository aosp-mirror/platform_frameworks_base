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

import com.android.tools.lint.client.api.IssueRegistry
// TODO: uncomment when lint API in Soong becomes 30.0+
// import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.google.auto.service.AutoService

@AutoService(IssueRegistry::class)
@Suppress("UnstableApiUsage")
class CallingIdentityTokenIssueRegistry : IssueRegistry() {
    override val issues = listOf(
            ISSUE_UNUSED_TOKEN,
            ISSUE_NON_FINAL_TOKEN,
            ISSUE_NESTED_CLEAR_IDENTITY_CALLS,
            ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK,
            ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY,
            ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY
    )

    override val api: Int
        get() = CURRENT_API

    override val minApi: Int
        get() = 8

//    TODO: uncomment when lint API in Soong becomes 30.0+
//    override val vendor: Vendor = Vendor(
//            vendorName = "Android Open Source Project",
//            feedbackUrl = "http://b/issues/new?component=315013",
//            contact = "brufino@google.com"
//    )

    companion object {
        /** Issue: unused token from Binder.clearCallingIdentity() */
        @JvmField
        val ISSUE_UNUSED_TOKEN: Issue = Issue.create(
                id = "UnusedTokenOfOriginalCallingIdentity",
                briefDescription = "Unused token of Binder.clearCallingIdentity()",
                explanation = """
                    You cleared the original calling identity with \
                    `Binder.clearCallingIdentity()`, but have not used the returned token to \
                    restore the identity.

                    Call `Binder.restoreCallingIdentity(token)` in the `finally` block, at the end \
                    of the method or when you need to restore the identity.

                    `token` is the result of `Binder.clearCallingIdentity()`
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageUnusedToken(variableName: String) = "`$variableName` has not been " +
                "used to restore the calling identity. Introduce a `try`-`finally` after the " +
                "declaration and call `Binder.restoreCallingIdentity($variableName)` in " +
                "`finally` or remove `$variableName`."

        /** Issue: non-final token from Binder.clearCallingIdentity() */
        @JvmField
        val ISSUE_NON_FINAL_TOKEN: Issue = Issue.create(
                id = "NonFinalTokenOfOriginalCallingIdentity",
                briefDescription = "Non-final token of Binder.clearCallingIdentity()",
                explanation = """
                    You cleared the original calling identity with \
                    `Binder.clearCallingIdentity()`, but have not made the returned token `final`.

                    The token should be `final` in order to prevent it from being overwritten, \
                    which can cause problems when restoring the identity with \
                    `Binder.restoreCallingIdentity(token)`.
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageNonFinalToken(variableName: String) = "`$variableName` is a " +
                "non-final token from `Binder.clearCallingIdentity()`. Add `final` keyword to " +
                "`$variableName`."

        /** Issue: nested calls of Binder.clearCallingIdentity() */
        @JvmField
        val ISSUE_NESTED_CLEAR_IDENTITY_CALLS: Issue = Issue.create(
                id = "NestedClearCallingIdentityCalls",
                briefDescription = "Nested calls of Binder.clearCallingIdentity()",
                explanation = """
                    You cleared the original calling identity with \
                    `Binder.clearCallingIdentity()` twice without restoring identity with the \
                    result of the first call.

                    Make sure to restore the identity after each clear identity call.
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageNestedClearIdentityCallsPrimary(
            firstCallVariableName: String,
            secondCallVariableName: String
        ): String = "The calling identity has already been cleared and returned into " +
                "`$firstCallVariableName`. Move `$secondCallVariableName` declaration after " +
                "restoring the calling identity with " +
                "`Binder.restoreCallingIdentity($firstCallVariableName)`."

        fun getIncidentMessageNestedClearIdentityCallsSecondary(
            firstCallVariableName: String
        ): String = "Location of the `$firstCallVariableName` declaration."

        /** Issue: Binder.clearCallingIdentity() is not followed by `try-finally` statement */
        @JvmField
        val ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY: Issue = Issue.create(
                id = "ClearIdentityCallNotFollowedByTryFinally",
                briefDescription = "Binder.clearCallingIdentity() is not followed by try-finally " +
                        "statement",
                explanation = """
                    You cleared the original calling identity with \
                    `Binder.clearCallingIdentity()`, but the next statement is not a `try` \
                    statement.

                    Use the following pattern for running operations with your own identity:

                    ```
                    final long token = Binder.clearCallingIdentity();
                    try {
                        // Code using your own identity
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    ```

                    Any calls/operations between `Binder.clearCallingIdentity()` and `try` \
                    statement risk throwing an exception without doing a safe and unconditional \
                    restore of the identity with `Binder.restoreCallingIdentity()` as an immediate \
                    child of the `finally` block. If you do not follow the pattern, you may run \
                    code with your identity that was originally intended to run with the calling \
                    application's identity.
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageClearIdentityCallNotFollowedByTryFinally(
            variableName: String
        ): String = "You cleared the calling identity and returned the result into " +
                "`$variableName`, but the next statement is not a `try`-`finally` statement. " +
                "Define a `try`-`finally` block after `$variableName` declaration to ensure a " +
                "safe restore of the calling identity by calling " +
                "`Binder.restoreCallingIdentity($variableName)` and making it an immediate child " +
                "of the `finally` block."

        /** Issue: Binder.restoreCallingIdentity() is not in finally block */
        @JvmField
        val ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK: Issue = Issue.create(
                id = "RestoreIdentityCallNotInFinallyBlock",
                briefDescription = "Binder.restoreCallingIdentity() is not in finally block",
                explanation = """
                    You are restoring the original calling identity with \
                    `Binder.restoreCallingIdentity()`, but the call is not an immediate child of \
                    the `finally` block of the `try` statement.

                    Use the following pattern for running operations with your own identity:

                    ```
                    final long token = Binder.clearCallingIdentity();
                    try {
                        // Code using your own identity
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    ```

                    If you do not surround the code using your identity with the `try` statement \
                    and call `Binder.restoreCallingIdentity()` as an immediate child of the \
                    `finally` block, you may run code with your identity that was originally \
                    intended to run with the calling application's identity.
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageRestoreIdentityCallNotInFinallyBlock(variableName: String): String =
                "`Binder.restoreCallingIdentity($variableName)` is not an immediate child of the " +
                        "`finally` block of the try statement after `$variableName` declaration. " +
                        "Surround the call with `finally` block and call it unconditionally."

        /** Issue: Use of caller-aware methods after Binder.clearCallingIdentity() */
        @JvmField
        val ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY: Issue = Issue.create(
                id = "UseOfCallerAwareMethodsWithClearedIdentity",
                briefDescription = "Use of caller-aware methods after " +
                        "Binder.clearCallingIdentity()",
                explanation = """
                    You cleared the original calling identity with \
                    `Binder.clearCallingIdentity()`, but used one of the methods below before \
                    restoring the identity. These methods will use your own identity instead of \
                    the caller's identity, so if this is expected replace them with methods that \
                    explicitly query your own identity such as `Process.myUid()`, \
                    `Process.myPid()` and `UserHandle.myUserId()`, otherwise move those methods \
                    out of the `Binder.clearCallingIdentity()` / `Binder.restoreCallingIdentity()` \
                    section.

                    ```
                    Binder.getCallingPid()
                    Binder.getCallingUid()
                    Binder.getCallingUidOrThrow()
                    Binder.getCallingUserHandle()
                    UserHandle.getCallingAppId()
                    UserHandle.getCallingUserId()
                    ```
                    """,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.WARNING,
                implementation = Implementation(
                        CallingIdentityTokenDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageUseOfCallerAwareMethodsWithClearedIdentity(
            variableName: String,
            methodName: String
        ): String = "You cleared the original identity with `Binder.clearCallingIdentity()` " +
                "and returned into `$variableName`, so `$methodName` will be using your own " +
                "identity instead of the caller's. Either explicitly query your own identity or " +
                "move it after restoring the identity with " +
                "`Binder.restoreCallingIdentity($variableName)`."
    }
}
