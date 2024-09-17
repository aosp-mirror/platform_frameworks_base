/*
 * Copyright (C) 2023 The Android Open Source Project
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
class PermissionAnnotationDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector =
        PermissionAnnotationDetector()

    override fun getIssues(): List<Issue> = listOf(
        PermissionAnnotationDetector.ISSUE_MISSING_PERMISSION_ANNOTATION,
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    /** No issue scenario */

    fun testDoesNotDetectIssuesInCorrectScenario() {
        lint()
            .files(
                java(
                    createVisitedPath("Foo.java"),
                    """
                        package com.android.server;
                        public class Foo extends IFoo.Stub {
                            @Override
                            @android.annotation.EnforcePermission("android.Manifest.permission.READ_CONTACTS")
                            public void testMethod() { }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testMissingAnnotation() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            public void testMethod() { }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expect(
                """
                src/frameworks/base/services/java/com/android/server/Bar.java:3: Error: The method testMethod is not permission-annotated. [MissingPermissionAnnotation]
                    public void testMethod() { }
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
    }

    fun testMissingAnnotationInIgnoredDirectory() {
        lint()
            .files(
                java(
                    ignoredPath,
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            public void testMethod() { }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    // If this test fails, consider the following steps:
    //   1. Pick the first entry (interface) from `exemptAidlInterfaces`.
    //   2. Change `interfaceIExempted` to use that interface.
    //   3. Change this test's class to extend the interface's Stub.
    fun testMissingAnnotationAidlInterfaceExempted() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends android.accessibilityservice.IBrailleDisplayConnection.Stub {
                            public void testMethod() { }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testMissingAnnotationAidlInterfaceAbstractMethod() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public abstract class Bar extends IBar.Stub {
                            public abstract void testMethod();
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testNoIssueWhenExtendingWithAnotherSubclass() {
        lint()
            .files(
                java(
                    createVisitedPath("Foo.java"),
                    """
                        package com.android.server;
                        public class Foo extends IFoo.Stub {
                            @Override
                            @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                            public void testMethod() { }
                            // not an AIDL method, just another method
                            public void someRandomMethod() { }
                        }
                    """
                )
                    .indented(),
                java(
                    createVisitedPath("Baz.java"),
                    """
                        package com.android.server;
                        public class Baz extends Bar {
                          @Override
                          public void someRandomMethod() { }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    /* Stubs */

    // A service with permission annotation on the method.
    private val interfaceIFoo: TestFile = java(
        """
        public interface IFoo extends android.os.IInterface {
         public static abstract class Stub extends android.os.Binder implements IFoo {
          }
          @Override
          @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
          public void testMethod();
          @Override
          @android.annotation.RequiresNoPermission
          public void testMethodNoPermission();
          @Override
          @android.annotation.PermissionManuallyEnforced
          public void testMethodManual();
        }
        """
    ).indented()

    // A service with no permission annotation.
    private val interfaceIBar: TestFile = java(
        """
        public interface IBar extends android.os.IInterface {
         public static abstract class Stub extends android.os.Binder implements IBar {
          }
          public void testMethod();
        }
        """
    ).indented()

    // A service whose AIDL Interface is exempted.
    private val interfaceIExempted: TestFile = java(
        """
        package android.accessibilityservice;
        public interface IBrailleDisplayConnection extends android.os.IInterface {
         public static abstract class Stub extends android.os.Binder implements IBrailleDisplayConnection {
          }
          public void testMethod();
        }
        """
    ).indented()

    private val stubs = arrayOf(interfaceIFoo, interfaceIBar, interfaceIExempted)

    private fun createVisitedPath(filename: String) =
        "src/frameworks/base/services/java/com/android/server/$filename"

    private val ignoredPath = "src/test/pkg/TestClass.java"
}
