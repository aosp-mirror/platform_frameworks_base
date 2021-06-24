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

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class CallingIdentityTokenDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = CallingIdentityTokenDetector()

    override fun getIssues(): List<Issue> = listOf(
            CallingIdentityTokenIssueRegistry.ISSUE_UNUSED_TOKEN,
            CallingIdentityTokenIssueRegistry.ISSUE_NON_FINAL_TOKEN,
            CallingIdentityTokenIssueRegistry.ISSUE_NESTED_CLEAR_IDENTITY_CALLS,
            CallingIdentityTokenIssueRegistry.ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK,
            CallingIdentityTokenIssueRegistry
                    .ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY,
            CallingIdentityTokenIssueRegistry.ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY
    )

    /** No issue scenario */

    fun testDoesNotDetectIssuesInCorrectScenario() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethod() {
                            final long token1 = Binder.clearCallingIdentity();
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token1);
                            }
                            final long token2 = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                                android.os.Binder.restoreCallingIdentity(token2);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    /** Unused token issue tests */

    fun testDetectsUnusedTokens() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodImported() {
                            final long token1 = Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                        }
                        private void testMethodFullClass() {
                            final long token2 = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Warning: token1 has not been used to \
                        restore the calling identity. Introduce a try-finally after the \
                        declaration and call Binder.restoreCallingIdentity(token1) in finally or \
                        remove token1. [UnusedTokenOfOriginalCallingIdentity]
                                final long token1 = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:11: Warning: token2 has not been used to \
                        restore the calling identity. Introduce a try-finally after the \
                        declaration and call Binder.restoreCallingIdentity(token2) in finally or \
                        remove token2. [UnusedTokenOfOriginalCallingIdentity]
                                final long token2 = android.os.Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 2 warnings
                        """.addLineContinuation()
                )
    }

    fun testDetectsUnusedTokensInScopes() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodTokenFromClearIdentity() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                        }
                        private void testMethodTokenNotFromClearIdentity() {
                            long token = 0;
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Warning: token has not been used to \
                        restore the calling identity. Introduce a try-finally after the \
                        declaration and call Binder.restoreCallingIdentity(token) in finally or \
                        remove token. [UnusedTokenOfOriginalCallingIdentity]
                                final long token = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
                )
    }

    fun testDoesNotDetectUsedTokensInScopes() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodTokenFromClearIdentity() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                        private void testMethodTokenNotFromClearIdentity() {
                            long token = 0;
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testDetectsUnusedTokensWithSimilarNamesInScopes() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethod1() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                        }
                        private void testMethod2() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Warning: token has not been used to \
                        restore the calling identity. Introduce a try-finally after the \
                        declaration and call Binder.restoreCallingIdentity(token) in finally or \
                        remove token. [UnusedTokenOfOriginalCallingIdentity]
                                final long token = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:11: Warning: token has not been used to \
                        restore the calling identity. Introduce a try-finally after the \
                        declaration and call Binder.restoreCallingIdentity(token) in finally or \
                        remove token. [UnusedTokenOfOriginalCallingIdentity]
                                final long token = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 2 warnings
                        """.addLineContinuation()
                )
    }

    /** Non-final token issue tests */

    fun testDetectsNonFinalTokens() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethod() {
                            long token1 = Binder.clearCallingIdentity();
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token1);
                            }
                            long token2 = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                                android.os.Binder.restoreCallingIdentity(token2);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Warning: token1 is a non-final token from \
                        Binder.clearCallingIdentity(). Add final keyword to token1. \
                        [NonFinalTokenOfOriginalCallingIdentity]
                                long token1 = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:10: Warning: token2 is a non-final token from \
                        Binder.clearCallingIdentity(). Add final keyword to token2. \
                        [NonFinalTokenOfOriginalCallingIdentity]
                                long token2 = android.os.Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 2 warnings
                        """.addLineContinuation()
                )
    }

    /** Nested clearCallingIdentity() calls issue tests */

    fun testDetectsNestedClearCallingIdentityCalls() {
        // Pattern: clear - clear - clear - restore - restore - restore
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethod() {
                            final long token1 = Binder.clearCallingIdentity();
                            try {
                                final long token2 = android.os.Binder.clearCallingIdentity();
                                try {
                                    final long token3 = Binder.clearCallingIdentity();
                                    try {
                                    } finally {
                                        Binder.restoreCallingIdentity(token3);
                                    }
                                } finally {
                                    android.os.Binder.restoreCallingIdentity(token2);
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token1);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:7: Warning: The calling identity has already \
                        been cleared and returned into token1. Move token2 declaration after \
                        restoring the calling identity with Binder.restoreCallingIdentity(token1). \
                        [NestedClearCallingIdentityCalls]
                                    final long token2 = android.os.Binder.clearCallingIdentity();
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                            src/test/pkg/TestClass1.java:5: Location of the token1 declaration.
                        src/test/pkg/TestClass1.java:9: Warning: The calling identity has already \
                        been cleared and returned into token1. Move token3 declaration after \
                        restoring the calling identity with Binder.restoreCallingIdentity(token1). \
                        [NestedClearCallingIdentityCalls]
                                        final long token3 = Binder.clearCallingIdentity();
                                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                            src/test/pkg/TestClass1.java:5: Location of the token1 declaration.
                        0 errors, 2 warnings
                        """.addLineContinuation()
                )
    }

    /** clearCallingIdentity() not followed by try-finally issue tests */

    fun testDetectsClearIdentityCallNotFollowedByTryFinally() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodNoTry() {
                            final long token = Binder.clearCallingIdentity();
                            Binder.restoreCallingIdentity(token);
                        }
                        private void testMethodSomethingBetweenClearAndTry() {
                            final long token = Binder.clearCallingIdentity();
                            int pid = 0;
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                        private void testMethodLocalVariableBetweenClearAndTry() {
                            final long token = Binder.clearCallingIdentity(), num = 0;
                            try {
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                        private void testMethodTryCatch() {
                            final long token = android.os.Binder.clearCallingIdentity();
                            try {
                            } catch (Exception e) {
                            }
                            Binder.restoreCallingIdentity(token);
                        }
                        private void testMethodTryCatchInScopes() {
                            final long token = android.os.Binder.clearCallingIdentity();
                            {
                                try {
                                } catch (Exception e) {
                                }
                            }
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Warning: You cleared the calling identity \
                        and returned the result into token, but the next statement is not a \
                        try-finally statement. Define a try-finally block after token declaration \
                        to ensure a safe restore of the calling identity by calling \
                        Binder.restoreCallingIdentity(token) and making it an immediate child of \
                        the finally block. [ClearIdentityCallNotFollowedByTryFinally]
                                final long token = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:9: Warning: You cleared the calling identity \
                        and returned the result into token, but the next statement is not a \
                        try-finally statement. Define a try-finally block after token declaration \
                        to ensure a safe restore of the calling identity by calling \
                        Binder.restoreCallingIdentity(token) and making it an immediate child of \
                        the finally block. [ClearIdentityCallNotFollowedByTryFinally]
                                final long token = Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:17: Warning: You cleared the calling identity \
                        and returned the result into token, but the next statement is not a \
                        try-finally statement. Define a try-finally block after token declaration \
                        to ensure a safe restore of the calling identity by calling \
                        Binder.restoreCallingIdentity(token) and making it an immediate child of \
                        the finally block. [ClearIdentityCallNotFollowedByTryFinally]
                                final long token = Binder.clearCallingIdentity(), num = 0;
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:24: Warning: You cleared the calling identity \
                        and returned the result into token, but the next statement is not a \
                        try-finally statement. Define a try-finally block after token declaration \
                        to ensure a safe restore of the calling identity by calling \
                        Binder.restoreCallingIdentity(token) and making it an immediate child of \
                        the finally block. [ClearIdentityCallNotFollowedByTryFinally]
                                final long token = android.os.Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:31: Warning: You cleared the calling identity \
                        and returned the result into token, but the next statement is not a \
                        try-finally statement. Define a try-finally block after token declaration \
                        to ensure a safe restore of the calling identity by calling \
                        Binder.restoreCallingIdentity(token) and making it an immediate child of \
                        the finally block. [ClearIdentityCallNotFollowedByTryFinally]
                                final long token = android.os.Binder.clearCallingIdentity();
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 5 warnings
                        """.addLineContinuation()
                )
    }

    /** restoreCallingIdentity() call not in finally block issue tests */

    fun testDetectsRestoreCallingIdentityCallNotInFinally() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodImported() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } catch (Exception e) {
                            } finally {
                            }
                            Binder.restoreCallingIdentity(token);
                        }
                        private void testMethodFullClass() {
                            final long token = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                            android.os.Binder.restoreCallingIdentity(token);
                        }
                        private void testMethodRestoreInCatch() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                            } catch (Exception e) {
                                Binder.restoreCallingIdentity(token);
                            } finally {
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:10: Warning: \
                        Binder.restoreCallingIdentity(token) is not an immediate child of the \
                        finally block of the try statement after token declaration. Surround the c\
                        all with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                Binder.restoreCallingIdentity(token);
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:17: Warning: \
                        Binder.restoreCallingIdentity(token) is not an immediate child of the \
                        finally block of the try statement after token declaration. Surround the c\
                        all with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                android.os.Binder.restoreCallingIdentity(token);
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:23: Warning: \
                        Binder.restoreCallingIdentity(token) is not an immediate child of the \
                        finally block of the try statement after token declaration. Surround the c\
                        all with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                    Binder.restoreCallingIdentity(token);
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 3 warnings
                        """.addLineContinuation()
                )
    }

    fun testDetectsRestoreCallingIdentityCallNotInFinallyInScopes() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    public class TestClass1 {
                        private void testMethodOutsideFinally() {
                            final long token1 = Binder.clearCallingIdentity();
                            try {
                            } catch (Exception e) {
                            } finally {
                            }
                            {
                                Binder.restoreCallingIdentity(token1);
                            }
                            final long token2 = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                            }
                            {
                                {
                                    {
                                        android.os.Binder.restoreCallingIdentity(token2);
                                    }
                                }
                            }
                        }
                        private void testMethodInsideFinallyInScopes() {
                            final long token1 = Binder.clearCallingIdentity();
                            try {
                            } finally {
                                {
                                    {
                                        Binder.restoreCallingIdentity(token1);
                                    }
                                }
                            }
                            final long token2 = android.os.Binder.clearCallingIdentity();
                            try {
                            } finally {
                                {
                                    {
                                        android.os.Binder.restoreCallingIdentity(token2);
                                    }
                                }
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:11: Warning: \
                        Binder.restoreCallingIdentity(token1) is not an immediate child of the \
                        finally block of the try statement after token1 declaration. Surround the \
                        call with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                    Binder.restoreCallingIdentity(token1);
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:20: Warning: \
                        Binder.restoreCallingIdentity(token2) is not an immediate child of the \
                        finally block of the try statement after token2 declaration. Surround the \
                        call with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                            android.os.Binder.restoreCallingIdentity(token2);
                                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:31: Warning: \
                        Binder.restoreCallingIdentity(token1) is not an immediate child of the \
                        finally block of the try statement after token1 declaration. Surround the \
                        call with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                            Binder.restoreCallingIdentity(token1);
                                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:40: Warning: \
                        Binder.restoreCallingIdentity(token2) is not an immediate child of the \
                        finally block of the try statement after token2 declaration. Surround the \
                        call with finally block and call it unconditionally. \
                        [RestoreIdentityCallNotInFinallyBlock]
                                            android.os.Binder.restoreCallingIdentity(token2);
                                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 4 warnings
                        """.addLineContinuation()
                )
    }

    /** Use of caller-aware methods after clearCallingIdentity() issue tests */

    fun testDetectsUseOfCallerAwareMethodsWithClearedIdentityIssuesInScopes() {
        lint().files(
                java(
                    """
                    package test.pkg;
                    import android.os.Binder;
                    import android.os.UserHandle;
                    public class TestClass1 {
                        private void testMethod() {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                int pid1 = Binder.getCallingPid();
                                int pid2 = android.os.Binder.getCallingPid();
                                int uid1 = Binder.getCallingUid();
                                int uid2 = android.os.Binder.getCallingUid();
                                int uid3 = Binder.getCallingUidOrThrow();
                                int uid4 = android.os.Binder.getCallingUidOrThrow();
                                UserHandle uh1 = Binder.getCallingUserHandle();
                                UserHandle uh2 = android.os.Binder.getCallingUserHandle();
                                {
                                    int appId1 = UserHandle.getCallingAppId();
                                    int appId2 = android.os.UserHandle.getCallingAppId();
                                    int userId1 = UserHandle.getCallingUserId();
                                    int userId2 = android.os.UserHandle.getCallingUserId();
                                }
                            } finally {
                            Binder.restoreCallingIdentity(token);
                            }
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:8: Warning: You cleared the original identity \
                        with Binder.clearCallingIdentity() and returned into token, so \
                        Binder.getCallingPid() will be using your own identity instead of the \
                        caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int pid1 = Binder.getCallingPid();
                                               ~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:9: Warning: You cleared the original identity \
                        with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.Binder.getCallingPid() will be using your own identity instead \
                        of the caller's. Either explicitly query your own identity or move it \
                        after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int pid2 = android.os.Binder.getCallingPid();
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:10: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        Binder.getCallingUid() will be using your own identity instead of the \
                        caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int uid1 = Binder.getCallingUid();
                                               ~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:11: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.Binder.getCallingUid() will be using your own identity instead \
                        of the caller's. Either explicitly query your own identity or move it \
                        after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int uid2 = android.os.Binder.getCallingUid();
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:12: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        Binder.getCallingUidOrThrow() will be using your own identity instead of \
                        the caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int uid3 = Binder.getCallingUidOrThrow();
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:13: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.Binder.getCallingUidOrThrow() will be using your own identity \
                        instead of the caller's. Either explicitly query your own identity or move \
                        it after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    int uid4 = android.os.Binder.getCallingUidOrThrow();
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:14: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        Binder.getCallingUserHandle() will be using your own identity instead of \
                        the caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    UserHandle uh1 = Binder.getCallingUserHandle();
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:15: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.Binder.getCallingUserHandle() will be using your own identity \
                        instead of the caller's. Either explicitly query your own identity or move \
                        it after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                    UserHandle uh2 = android.os.Binder.getCallingUserHandle();
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:17: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        UserHandle.getCallingAppId() will be using your own identity instead of \
                        the caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                        int appId1 = UserHandle.getCallingAppId();
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:18: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.UserHandle.getCallingAppId() will be using your own identity \
                        instead of the caller's. Either explicitly query your own identity or move \
                        it after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                        int appId2 = android.os.UserHandle.getCallingAppId();
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:19: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        UserHandle.getCallingUserId() will be using your own identity instead of \
                        the caller's. Either explicitly query your own identity or move it after \
                        restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                        int userId1 = UserHandle.getCallingUserId();
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        src/test/pkg/TestClass1.java:20: Warning: You cleared the original \
                        identity with Binder.clearCallingIdentity() and returned into token, so \
                        android.os.UserHandle.getCallingUserId() will be using your own identity \
                        instead of the caller's. Either explicitly query your own identity or move \
                        it after restoring the identity with Binder.restoreCallingIdentity(token). \
                        [UseOfCallerAwareMethodsWithClearedIdentity]
                                        int userId2 = android.os.UserHandle.getCallingUserId();
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 12 warnings
                        """.addLineContinuation()
                )
    }

    /** Stubs for classes used for testing */

    private val binderStub: TestFile = java(
            """
            package android.os;
            public class Binder {
                public static final native long clearCallingIdentity() {
                    return 0;
                }
                public static final native void restoreCallingIdentity(long token) {
                }
                public static final native int getCallingPid() {
                    return 0;
                }
                public static final native int getCallingUid() {
                    return 0;
                }
                public static final int getCallingUidOrThrow() {
                    return 0;
                }
                public static final @NonNull UserHandle getCallingUserHandle() {
                    return UserHandle.of(UserHandle.getUserId(getCallingUid()));
                }
            }
            """
    ).indented()

    private val userHandleStub: TestFile = java(
            """
            package android.os;
            import android.annotation.AppIdInt;
            import android.annotation.UserIdInt;
            public class UserHandle {
                public static @AppIdInt int getCallingAppId() {
                    return getAppId(Binder.getCallingUid());
                }
                public static @UserIdInt int getCallingUserId() {
                    return getUserId(Binder.getCallingUid());
                }
                public static @UserIdInt int getUserId(int uid) {
                    return 0;
                }
                public static @AppIdInt int getAppId(int uid) {
                    return 0;
                }
                public static UserHandle of(@UserIdInt int userId) {
                    return new UserHandle();
                }
            }
            """
    ).indented()

    private val userIdIntStub: TestFile = java(
            """
            package android.annotation;
            public @interface UserIdInt {
            }
            """
    ).indented()

    private val appIdIntStub: TestFile = java(
            """
            package android.annotation;
            public @interface AppIdInt {
            }
            """
    ).indented()

    private val stubs = arrayOf(binderStub, userHandleStub, userIdIntStub, appIdIntStub)

    // Substitutes "backslash + new line" with an empty string to imitate line continuation
    private fun String.addLineContinuation(): String = this.trimIndent().replace("\\\n", "")
}
