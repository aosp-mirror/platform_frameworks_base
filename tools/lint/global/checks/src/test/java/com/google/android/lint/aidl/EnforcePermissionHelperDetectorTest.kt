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

class EnforcePermissionHelperDetectorTest : LintDetectorTest() {
    override fun getDetector() = EnforcePermissionHelperDetector()
    override fun getIssues() = listOf(
        EnforcePermissionHelperDetector.ISSUE_ENFORCE_PERMISSION_HELPER,
        EnforcePermissionHelperDetector.ISSUE_MISUSING_ENFORCE_PERMISSION
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    fun testFirstExpressionIsFunctionCall() {
        lint().files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                        public void test() throws android.os.RemoteException {
                            Binder.getCallingUid();
                        }
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:5: Error: Method must start with test_enforcePermission() or super.test(), if applicable [MissingEnforcePermissionHelper]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Autofix for src/Foo.java line 5: Replace with test_enforcePermission();...:
                @@ -8 +8
                +         test_enforcePermission();
                +
                """
            )
    }

    fun testFirstExpressionIsVariableDeclaration() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        String foo = "bar";
                        Binder.getCallingUid();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:5: Error: Method must start with test_enforcePermission() or super.test(), if applicable [MissingEnforcePermissionHelper]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
            .expectFixDiffs(
                """
                Autofix for src/Foo.java line 5: Replace with test_enforcePermission();...:
                @@ -8 +8
                +         test_enforcePermission();
                +
                """
            )
    }

    fun testMethodIsEmpty() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {}
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:5: Error: Method must start with test_enforcePermission() or super.test(), if applicable [MissingEnforcePermissionHelper]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testOkay() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testHelperWithoutSuperPrefix_Okay() {
        lint().files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        test_enforcePermission();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testInterfaceDefaultMethod_notStubAncestor_error() {
        lint().files(
            java(
                """
                public interface IProtected extends android.os.IInterface {
                    @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                    default void PermissionProtected() throws android.os.RemoteException {
                        String foo = "bar";
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/IProtected.java:2: Error: The class of PermissionProtected does not inherit from an AIDL generated Stub class [MisusingEnforcePermissionAnnotation]
                    @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testInheritance_callSuper_okay() {
        lint().files(
            java(
                """
                package test;
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Foo;
                public class Bar extends Foo {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Bar;
                public class Baz extends Bar {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testInheritance_callHelper_okay() {
        lint().files(
            java(
                """
                package test;
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Foo;
                public class Bar extends Foo {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Bar;
                public class Baz extends Bar {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testInheritance_missingCallInChain_error() {
        lint().files(
            java(
                """
                package test;
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Foo;
                public class Bar extends Foo {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        doStuff();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Bar;
                public class Baz extends Bar {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/test/Bar.java:4: Error: Method must start with test_enforcePermission() or super.test(), if applicable [MissingEnforcePermissionHelper]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testInheritance_missingCall_error() {
        lint().files(
            java(
                """
                package test;
                import android.content.Context;
                import android.test.ITest;
                public class Foo extends ITest.Stub {
                    private Context mContext;
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test_enforcePermission();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Foo;
                public class Bar extends Foo {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        super.test();
                    }
                }
                """
            ).indented(),
            java(
                """
                package test;
                import test.Bar;
                public class Baz extends Bar {
                    @Override
                    @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                    public void test() throws android.os.RemoteException {
                        doStuff();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/test/Baz.java:4: Error: Method must start with test_enforcePermission() or super.test(), if applicable [MissingEnforcePermissionHelper]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testRandomClass_notStubAncestor_error() {
        lint().files(
            java(
                """
                public class Foo {
                    @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                    void PermissionProtected() throws android.os.RemoteException {
                        String foo = "bar";
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:2: Error: The class of PermissionProtected does not inherit from an AIDL generated Stub class [MisusingEnforcePermissionAnnotation]
                    @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                    ^
                1 errors, 0 warnings
                """
            )
    }

    companion object {
        val stubs = arrayOf(aidlStub, contextStub, binderStub)
    }
}



