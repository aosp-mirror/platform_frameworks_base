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
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ExemptAidlInterfacesGeneratorTest : LintDetectorTest() {
    override fun getDetector(): Detector = ExemptAidlInterfacesGenerator()

    override fun getIssues(): List<Issue> = listOf(
        ExemptAidlInterfacesGenerator.ISSUE_PERMISSION_ANNOTATION_EXEMPT_AIDL_INTERFACES,
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun testMultipleAidlInterfacesImplemented() {
        lint()
            .files(
                java(
                    createVisitedPath("TestClass1.java"),
                    """
                        package com.android.server;
                        public class TestClass1 extends IFoo.Stub {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                java(
                    createVisitedPath("TestClass2.java"),
                    """
                        package com.android.server;
                        public class TestClass2 extends IBar.Stub {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                    app: Information: "IFoo",
                    "IBar", [PermissionAnnotationExemptAidlInterfaces]
                    0 errors, 0 warnings
                """
            )
    }

    fun testSingleAidlInterfaceRepeated() {
        lint()
            .files(
                java(
                    createVisitedPath("TestClass1.java"),
                    """
                        package com.android.server;
                        public class TestClass1 extends IFoo.Stub {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                java(
                    createVisitedPath("TestClass2.java"),
                    """
                        package com.android.server;
                        public class TestClass2 extends IFoo.Stub {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                    app: Information: "IFoo", [PermissionAnnotationExemptAidlInterfaces]
                    0 errors, 0 warnings
                """
            )
    }

    fun testAnonymousClassExtendsAidlStub() {
        lint()
            .files(
                java(
                    createVisitedPath("TestClass.java"),
                    """
                        package com.android.server;
                        public class TestClass {
                            private IBinder aidlImpl = new IFoo.Stub() {
                                public void testMethod() {}
                            };
                        }
                        """
                )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                    app: Information: "IFoo", [PermissionAnnotationExemptAidlInterfaces]
                    0 errors, 0 warnings
                """
            )
    }

    fun testNoAidlInterfacesImplemented() {
        lint()
            .files(
                java(
                    createVisitedPath("TestClass.java"),
                    """
                        package com.android.server;
                        public class TestClass {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    fun testAidlInterfaceImplementedInIgnoredDirectory() {
        lint()
            .files(
                java(
                    ignoredPath,
                    """
                        package com.android.server;
                        public class TestClass1 extends IFoo.Stub {
                            public void testMethod() {}
                        }
                    """
                )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    private val interfaceIFoo: TestFile = java(
        """
            public interface IFoo extends android.os.IInterface {
                public static abstract class Stub extends android.os.Binder implements IFoo {}
                public void testMethod();
            }
        """
    ).indented()

    private val interfaceIBar: TestFile = java(
        """
            public interface IBar extends android.os.IInterface {
                public static abstract class Stub extends android.os.Binder implements IBar {}
                public void testMethod();
            }
        """
    ).indented()

    private val stubs = arrayOf(interfaceIFoo, interfaceIBar)

    private fun createVisitedPath(filename: String) =
        "src/frameworks/base/services/java/com/android/server/$filename"

    private val ignoredPath = "src/test/pkg/TestClass.java"
}
