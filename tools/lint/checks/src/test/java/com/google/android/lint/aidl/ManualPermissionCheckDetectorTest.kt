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
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class ManualPermissionCheckDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = ManualPermissionCheckDetector()
    override fun getIssues(): List<Issue> = listOf(
        ManualPermissionCheckDetector
            .ISSUE_USE_ENFORCE_PERMISSION_ANNOTATION
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
                            mContext.enforceCallingOrSelfPermission("android.Manifest.permission.READ_CONTACTS", "foo");
                        }
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:7: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        mContext.enforceCallingOrSelfPermission("android.Manifest.permission.READ_CONTACTS", "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                @@ -5 +5
                +     @android.annotation.EnforcePermission(android.Manifest.permission.READ_CONTACTS)
                @@ -7 +8
                -         mContext.enforceCallingOrSelfPermission("android.Manifest.permission.READ_CONTACTS", "foo");
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
                                    "android.Manifest.permission.READ_CONTACTS", "foo");
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
                src/Foo.java:8: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 8: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission(android.Manifest.permission.READ_CONTACTS)
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.Manifest.permission.READ_CONTACTS", "foo");
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
                                    "android.Manifest.permission.READ_CONTACTS", "foo");
                                mContext.enforceCallingOrSelfPermission(
                                    "android.Manifest.permission.WRITE_CONTACTS", "foo");
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
                src/Foo.java:10: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 10: Annotate with @EnforcePermission:
                @@ -6 +6                                                                                                                                                                                                       
                +         @android.annotation.EnforcePermission(allOf={android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS})
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.Manifest.permission.READ_CONTACTS", "foo");
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.Manifest.permission.WRITE_CONTACTS", "foo");
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
                            mContext.enforceCallingOrSelfPermission("android.Manifest.permission.READ_CONTACTS", "foo");
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
        private val aidlStub: TestFile = java(
            """
               package android.test;
               public interface ITest extends android.os.IInterface {
                    public static abstract class Stub extends android.os.Binder implements android.test.ITest {}
                    public void test() throws android.os.RemoteException;
               }
            """
        ).indented()

        private val contextStub: TestFile = java(
            """
                package android.content;
                public class Context {
                    public void enforceCallingOrSelfPermission(String permission, String message) {}
                }
            """
        ).indented()

        private val binderStub: TestFile = java(
            """
                package android.os;
                public class Binder {
                    public static int getCallingUid() {}
                }
            """
        ).indented()

        val stubs = arrayOf(aidlStub, contextStub, binderStub)
    }
}
