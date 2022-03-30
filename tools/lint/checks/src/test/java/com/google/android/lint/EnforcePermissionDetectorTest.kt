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

package com.google.android.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class EnforcePermissionDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = EnforcePermissionDetector()

    override fun getIssues(): List<Issue> = listOf(
            EnforcePermissionDetector.ISSUE_MISSING_ENFORCE_PERMISSION,
            EnforcePermissionDetector.ISSUE_MISMATCHING_ENFORCE_PERMISSION
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun testDoesNotDetectIssuesCorrectAnnotationOnClass() {
        lint().files(java(
            """
            package test.pkg;
            @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
            public class TestClass1 extends IFoo.Stub {
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expectClean()
    }

    fun testDoesNotDetectIssuesCorrectAnnotationOnMethod() {
        lint().files(java(
            """
            package test.pkg;
            import android.annotation.EnforcePermission;
            public class TestClass2 extends IFooMethod.Stub {
                @Override
                @EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expectClean()
    }

    fun testDetectIssuesMismatchingAnnotationOnClass() {
        lint().files(java(
            """
            package test.pkg;
            @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET)
            public class TestClass3 extends IFoo.Stub {
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass3.java:3: Error: The class test.pkg.TestClass3 is \
annotated with @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET) \
which differs from the parent class IFoo.Stub: \
@android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE). The \
same annotation must be used for both classes. [MismatchingEnforcePermissionAnnotation]
public class TestClass3 extends IFoo.Stub {
                                ~~~~~~~~~
1 errors, 0 warnings""".addLineContinuation())
    }

    fun testDetectIssuesMismatchingAnnotationOnMethod() {
        lint().files(java(
            """
            package test.pkg;
            public class TestClass4 extends IFooMethod.Stub {
                @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET)
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass4.java:4: Error: The method TestClass4.testMethod is \
annotated with @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET) \
which differs from the overridden method Stub.testMethod: \
@android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE). The same \
annotation must be used for both methods. [MismatchingEnforcePermissionAnnotation]
    public void testMethod() {}
                ~~~~~~~~~~
1 errors, 0 warnings""".addLineContinuation())
    }

    fun testDetectIssuesMissingAnnotationOnClass() {
        lint().files(java(
            """
            package test.pkg;
            public class TestClass5 extends IFoo.Stub {
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass5.java:2: Error: The class test.pkg.TestClass5 extends \
the class IFoo.Stub which is annotated with @EnforcePermission. The same annotation must be \
used on test.pkg.TestClass5. [MissingEnforcePermissionAnnotation]
public class TestClass5 extends IFoo.Stub {
                                ~~~~~~~~~
1 errors, 0 warnings""".addLineContinuation())
    }

    fun testDetectIssuesMissingAnnotationOnMethod() {
        lint().files(java(
            """
            package test.pkg;
            public class TestClass6 extends IFooMethod.Stub {
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass6.java:3: Error: The method TestClass6.testMethod \
overrides the method Stub.testMethod which is annotated with @EnforcePermission. The same \
annotation must be used on TestClass6.testMethod [MissingEnforcePermissionAnnotation]
    public void testMethod() {}
                ~~~~~~~~~~
1 errors, 0 warnings""".addLineContinuation())
    }

    fun testDetectIssuesExtraAnnotationMethod() {
        lint().files(java(
            """
            package test.pkg;
            public class TestClass7 extends IBar.Stub {
                @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET)
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass7.java:4: Error: The method TestClass7.testMethod \
overrides the method Stub.testMethod which is not annotated with @EnforcePermission. The same \
annotation must be used on Stub.testMethod. Did you forget to annotate the AIDL definition? \
[MissingEnforcePermissionAnnotation]
    public void testMethod() {}
                ~~~~~~~~~~
1 errors, 0 warnings""".addLineContinuation())
    }

    fun testDetectIssuesExtraAnnotationInterface() {
        lint().files(java(
            """
            package test.pkg;
            @android.annotation.EnforcePermission(android.Manifest.permission.INTERNET)
            public class TestClass8 extends IBar.Stub {
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""src/test/pkg/TestClass8.java:2: Error: The class test.pkg.TestClass8 \
extends the class IBar.Stub which is not annotated with @EnforcePermission. The same annotation \
must be used on IBar.Stub. Did you forget to annotate the AIDL definition? \
[MissingEnforcePermissionAnnotation]
@android.annotation.EnforcePermission(android.Manifest.permission.INTERNET)
^
1 errors, 0 warnings""".addLineContinuation())
    }

    /* Stubs */

    // A service with permission annotation on the class.
    private val interfaceIFooStub: TestFile = java(
        """
        @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
        public interface IFoo {
         @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
         public static abstract class Stub extends android.os.Binder implements IFoo {
           @Override
           public void testMethod() {}
         }
         public void testMethod();
        }
        """
    ).indented()

    // A service with permission annotation on the method.
    private val interfaceIFooMethodStub: TestFile = java(
        """
        public interface IFooMethod {
         public static abstract class Stub extends android.os.Binder implements IFooMethod {
            @Override
            @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
            public void testMethod() {}
          }
          @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
          public void testMethod();
        }
        """
    ).indented()

    // A service without any permission annotation.
    private val interfaceIBarStub: TestFile = java(
        """
        public interface IBar {
         public static abstract class Stub extends android.os.Binder implements IBar {
            @Override
            public void testMethod() {}
          }
          public void testMethod();
        }
        """
    ).indented()

    private val manifestPermissionStub: TestFile = java(
        """
        package android.Manifest;
        class permission {
          public static final String READ_PHONE_STATE = "android.permission.READ_PHONE_STATE";
          public static final String INTERNET = "android.permission.INTERNET";
        }
        """
    ).indented()

    private val enforcePermissionAnnotationStub: TestFile = java(
        """
        package android.annotation;
        public @interface EnforcePermission {}
        """
    ).indented()

    private val stubs = arrayOf(interfaceIFooStub, interfaceIFooMethodStub, interfaceIBarStub,
            manifestPermissionStub, enforcePermissionAnnotationStub)

    // Substitutes "backslash + new line" with an empty string to imitate line continuation
    private fun String.addLineContinuation(): String = this.trimIndent().replace("\\\n", "")
}
