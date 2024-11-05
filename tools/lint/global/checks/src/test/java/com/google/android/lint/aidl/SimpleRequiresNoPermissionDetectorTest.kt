/*
 * Copyright (C) 2024 The Android Open Source Project
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

class SimpleRequiresNoPermissionDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = SimpleRequiresNoPermissionDetector()
    override fun getIssues(): List<Issue> = listOf(
        SimpleRequiresNoPermissionDetector
            .ISSUE_SIMPLE_REQUIRES_NO_PERMISSION
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    fun testRequiresNoPermissionUsedCorrectly_shouldNotWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Foo.java"),
                    """
                        package com.android.server;
                        public class Foo extends IFoo.Stub {
                            private int memberInt;

                            @Override
                            @android.annotation.RequiresNoPermission
                            public void testMethodNoPermission(int parameter1, int parameter2) {
                                if (parameter1 < parameter2) {
                                    memberInt = parameter1;
                                } else {
                                    memberInt = parameter2;
                                }
                            }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testMissingRequiresNoPermission_shouldWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            private int memberInt;

                            @Override
                            public void testMethod(int parameter1, int parameter2) {
                                if (parameter1 < parameter2) {
                                    memberInt = parameter1;
                                } else {
                                    memberInt = parameter2;
                                }
                            }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expect(
                """
                src/frameworks/base/services/java/com/android/server/Bar.java:5: Error: Method testMethod doesn't perform any permission checks, meaning it should be annotated with @RequiresNoPermission. [SimpleRequiresNoPermission]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testMethodOnlyPerformsConstructorCall_shouldWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            private IntPair memberIntPair;

                            @Override
                            public void testMethod(int parameter1, int parameter2) {
                                memberIntPair = new IntPair(parameter1, parameter2);
                            }

                            private static class IntPair {
                                public int first;
                                public int second;

                                public IntPair(int first, int second) {
                                    this.first = first;
                                    this.second = second;
                                }
                            }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expect(
                """
                src/frameworks/base/services/java/com/android/server/Bar.java:5: Error: Method testMethod doesn't perform any permission checks, meaning it should be annotated with @RequiresNoPermission. [SimpleRequiresNoPermission]
                    @Override
                    ^
                1 errors, 0 warnings
                """
            )
    }

    fun testMissingRequiresNoPermissionInIgnoredDirectory_shouldNotWarn() {
        lint()
            .files(
                java(
                    ignoredPath,
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            @Override
                            public void testMethod(int parameter1, int parameter2) {}
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testMissingRequiresNoPermissionAbstractMethod_shouldNotWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public abstract class Bar extends IBar.Stub {
                            private int memberInt;

                            @Override
                            public abstract void testMethodNoPermission(int parameter1, int parameter2);
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
    fun testMissingRequiresNoPermissionAidlInterfaceExempted_shouldNotWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends android.accessibilityservice.IBrailleDisplayConnection.Stub {
                            public void testMethod(int parameter1, int parameter2) {}
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testMethodMakesAnotherMethodCall_shouldNotWarn() {
        lint()
            .files(
                java(
                    createVisitedPath("Bar.java"),
                    """
                        package com.android.server;
                        public class Bar extends IBar.Stub {
                            private int memberInt;

                            @Override
                            public void testMethod(int parameter1, int parameter2) {
                                if (!hasPermission()) return;

                                if (parameter1 < parameter2) {
                                    memberInt = parameter1;
                                } else {
                                    memberInt = parameter2;
                                }
                            }

                            private bool hasPermission() {
                                // Perform a permission check.
                                return true;
                            }
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    private val stubs = arrayOf(interfaceIFoo, interfaceIBar, interfaceIExempted)

    private fun createVisitedPath(filename: String) =
        "src/frameworks/base/services/java/com/android/server/$filename"

    private val ignoredPath = "src/test/pkg/TestClass.java"
}
