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

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class SimpleManualPermissionEnforcementDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = SimpleManualPermissionEnforcementDetector()
    override fun getIssues(): List<Issue> = listOf(
            SimpleManualPermissionEnforcementDetector
            .ISSUE_SIMPLE_MANUAL_PERMISSION_ENFORCEMENT
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    fun testClass() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    public void test() throws android.os.RemoteException {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:7: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                @@ -5 +5
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -7 +8
                -         mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                +         test_enforcePermission();
                """
            )
    }

    fun testClass_orSelfFalse_warning() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingPermission("android.permission.READ_CONTACTS", "foo");
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                    """
                    src/Foo.java:7: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.enforceCallingPermission("android.permission.READ_CONTACTS", "foo");
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                    """
                )
                .expectFixDiffs(
                    """
                    Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                    @@ -5 +5
                    +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                    @@ -7 +8
                    -         mContext.enforceCallingPermission("android.permission.READ_CONTACTS", "foo");
                    +         test_enforcePermission();
                    """
                )
    }

    fun testClass_enforcesFalse_warning() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                    """
                    src/Foo.java:7: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                    """
                )
                .expectFixDiffs(
                    """
                    Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                    @@ -5 +5
                    +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                    @@ -7 +8
                    -         mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    +         test_enforcePermission();
                    """
                )
    }

    fun testAnonClass() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo {
                    private Context mContext;
                    private ITest itest = new ITest.Stub() {
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission(
                                "android.permission.READ_CONTACTS", "foo");
                        }
                    };
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:8: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 8: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                +             test_enforcePermission();
                """
            )
    }

    fun testConstantEvaluation() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    public void test() throws android.os.RemoteException {
                        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                    }
                }
                """
            ).indented(),
            *stubs,
            manifestStub
        )
            .run()
            .expect(
                """
                src/Foo.java:8: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 8: Annotate with @EnforcePermission:
                @@ -6 +6
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -8 +9
                -         mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                +         test_enforcePermission();
                """
            )
    }

    fun testAllOf() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo {
                    private Context mContext;
                    private ITest itest = new ITest.Stub() {
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission(
                                "android.permission.READ_CONTACTS", "foo");
                            mContext.enforceCallingOrSelfPermission(
                                "android.permission.WRITE_CONTACTS", "foo");
                        }
                    };
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:10: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 10: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"})
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.WRITE_CONTACTS", "foo");
                +             test_enforcePermission();
                """
            )
    }

    fun testAllOf_mixedOrSelf_warning() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo {
                    private Context mContext;
                    private ITest itest = new ITest.Stub() {
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission(
                                "android.permission.READ_CONTACTS", "foo");
                            mContext.enforceCallingPermission(
                                "android.permission.WRITE_CONTACTS", "foo");
                        }
                    };
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:10: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.enforceCallingPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 10: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"})
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                -             mContext.enforceCallingPermission(
                -                 "android.permission.WRITE_CONTACTS", "foo");
                +             test_enforcePermission();
                """
            )
    }

    fun testAllOf_mixedEnforces_warning() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo {
                    private Context mContext;
                    private ITest itest = new ITest.Stub() {
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission(
                                "android.permission.READ_CONTACTS", "foo");
                            mContext.checkCallingOrSelfPermission(
                                "android.permission.WRITE_CONTACTS", "foo");
                        }
                    };
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:10: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            mContext.checkCallingOrSelfPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 10: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"})
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                -             mContext.checkCallingOrSelfPermission(
                -                 "android.permission.WRITE_CONTACTS", "foo");
                +             test_enforcePermission();
                """
            )
    }

    fun testPrecedingExpressions() {
        lint().files(
            java(
                """
                import android.os.Binder;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private mContext Context;
                    @Override
                    public void test() throws android.os.RemoteException {
                        long uid = Binder.getCallingUid();
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testPermissionHelper() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;

                    @android.annotation.PermissionMethod(orSelf = true)
                    private void helper() {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helper();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:14: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        helper();
                        ~~~~~~~~~
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 14: Annotate with @EnforcePermission:
                @@ -12 +12
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -14 +15
                -         helper();
                +         test_enforcePermission();
                """
            )
    }

    fun testPermissionHelper_orSelfNotBubbledUp_warning() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;

                        @android.annotation.PermissionMethod
                    private void helper() {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helper();
                    }
                }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                    """
                    src/Foo.java:14: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            helper();
                            ~~~~~~~~~
                    0 errors, 1 warnings
                    """
                )
                .expectFixDiffs(
                    """
                    Fix for src/Foo.java line 14: Annotate with @EnforcePermission:
                    @@ -12 +12
                    +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                    @@ -14 +15
                    -         helper();
                    +         test_enforcePermission();
                    """
                )
    }

    fun testPermissionHelperAllOf() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;

                    @android.annotation.PermissionMethod(orSelf = true)
                    private void helper() {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        mContext.enforceCallingOrSelfPermission("android.permission.WRITE_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helper();
                        mContext.enforceCallingOrSelfPermission("FOO", "foo");
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:16: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        mContext.enforceCallingOrSelfPermission("FOO", "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 16: Annotate with @EnforcePermission:
                @@ -13 +13
                +     @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "FOO"})
                @@ -15 +16
                -         helper();
                -         mContext.enforceCallingOrSelfPermission("FOO", "foo");
                +         test_enforcePermission();
                """
            )
    }


    fun testPermissionHelperNested() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;

                    @android.annotation.PermissionMethod(orSelf = true)
                    private void helperHelper() {
                        helper("android.permission.WRITE_CONTACTS");
                    }

                    @android.annotation.PermissionMethod(orSelf = true)
                    private void helper(@android.annotation.PermissionName String extraPermission) {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helperHelper();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:19: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        helperHelper();
                        ~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 19: Annotate with @EnforcePermission:
                @@ -17 +17
                +     @android.annotation.EnforcePermission(allOf={"android.permission.WRITE_CONTACTS", "android.permission.READ_CONTACTS"})
                @@ -19 +20
                -         helperHelper();
                +         test_enforcePermission();
                """
            )
    }

    fun testIfExpression() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        public void test() throws android.os.RemoteException {
                            if (mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo")
                                    != PackageManager.PERMISSION_GRANTED) {
                                throw new SecurityException("yikes!");
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
                    src/Foo.java:7: Error: ITest permission check should be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            if (mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo")
                            ^
                    1 errors, 0 warnings
                    """
                )
                .expectFixDiffs(
                    """
                    Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                    @@ -5 +5
                    +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                    @@ -7 +8
                    -         if (mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo")
                    -                 != PackageManager.PERMISSION_GRANTED) {
                    -             throw new SecurityException("yikes!");
                    -         }
                    +         test_enforcePermission();
                    """
                )
    }

    fun testIfExpression_orSelfFalse_warning() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    public void test() throws android.os.RemoteException {
                        if (mContext.checkCallingPermission("android.permission.READ_CONTACTS", "foo")
                                != PackageManager.PERMISSION_GRANTED) {
                            throw new SecurityException("yikes!");
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
                src/Foo.java:7: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                        if (mContext.checkCallingPermission("android.permission.READ_CONTACTS", "foo")
                        ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                @@ -5 +5
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -7 +8
                -         if (mContext.checkCallingPermission("android.permission.READ_CONTACTS", "foo")
                -                 != PackageManager.PERMISSION_GRANTED) {
                -             throw new SecurityException("yikes!");
                -         }
                +         test_enforcePermission();
                """
            )
    }

    fun testIfExpression_otherSideEffect_ignored() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    public void test() throws android.os.RemoteException {
                        if (mContext.checkCallingPermission("android.permission.READ_CONTACTS", "foo")
                                != PackageManager.PERMISSION_GRANTED) {
                            doSomethingElse();
                            throw new SecurityException("yikes!");
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

    fun testIfExpression_inlinedWithSideEffect_ignored() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    public void test() throws android.os.RemoteException {
                        if (somethingElse() && mContext.checkCallingPermission("android.permission.READ_CONTACTS", "foo")
                                != PackageManager.PERMISSION_GRANTED) {
                            throw new SecurityException("yikes!");
                        }
                    }

                    private boolean somethingElse() {
                        return true;
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testAnyOf_hardCodedAndVarArgs() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;

                        @android.annotation.PermissionMethod(anyOf = true)
                        private void helperHelper() {
                            helper("FOO", "BAR");
                        }

                        @android.annotation.PermissionMethod(anyOf = true, value = {"BAZ", "BUZZ"})
                        private void helper(@android.annotation.PermissionName String... extraPermissions) {}

                        @Override
                        public void test() throws android.os.RemoteException {
                            helperHelper();
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                    """
                    src/Foo.java:17: Warning: ITest permission check can be converted to @EnforcePermission annotation [SimpleManualPermissionEnforcement]
                            helperHelper();
                            ~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                    """
                )
                .expectFixDiffs(
                    """
                    Fix for src/Foo.java line 17: Annotate with @EnforcePermission:
                    @@ -15 +15
                    +     @android.annotation.EnforcePermission(anyOf={"BAZ", "BUZZ", "FOO", "BAR"})
                    @@ -17 +18
                    -         helperHelper();
                    +         test_enforcePermission();
                    """
                )
    }


    fun testAnyOfAllOf_mixedConsecutiveCalls_ignored() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;

                        @android.annotation.PermissionMethod
                        private void allOfhelper() {
                            mContext.enforceCallingOrSelfPermission("FOO");
                            mContext.enforceCallingOrSelfPermission("BAR");
                        }

                        @android.annotation.PermissionMethod(anyOf = true, permissions = {"BAZ", "BUZZ"})
                        private void anyOfHelper() {}

                        @Override
                        public void test() throws android.os.RemoteException {
                            allOfhelper();
                            anyOfHelper();
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testAnyOfAllOf_mixedNestedCalls_ignored() {
        lint().files(
                java(
                    """
                    import android.content.Context;
                    import android.annotation.PermissionName;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;

                        @android.annotation.PermissionMethod(anyOf = true)
                        private void anyOfCheck(@PermissionName String... permissions) {
                            allOfCheck("BAZ", "BUZZ");
                        }

                        @android.annotation.PermissionMethod
                        private void allOfCheck(@PermissionName String... permissions) {}

                        @Override
                        public void test() throws android.os.RemoteException {
                            anyOfCheck("FOO", "BAR");
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    companion object {
        val stubs = arrayOf(
            aidlStub,
            contextStub,
            binderStub,
            permissionMethodStub,
            permissionNameStub
        )
    }
}
