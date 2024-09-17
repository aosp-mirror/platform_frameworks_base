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

package com.google.android.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

@Suppress("UnstableApiUsage")
class FeatureAutomotiveDetectorTest : LintDetectorTest() {
    val explanation =
        FeatureAutomotiveDetector.EXPLANATION.replace("\\", "").replace("\n            ", "") +
            " [UsingFeatureAutomotiveInCTS]"

    override fun getDetector(): Detector = FeatureAutomotiveDetector()
    override fun getIssues(): List<Issue> = listOf(FeatureAutomotiveDetector.ISSUE)
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    @Test
    fun testWarning1() {
        lint()
            .files(
                java(
                        """
                import android.content.pm.PackageManager;

                public class Foo {

                    private void fun() {
                        PackageManager.getInstance().hasSystemFeature(
                            "android.hardware.type.automotive");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                            src/android/content/pm/PackageManager.java:13: Warning: $explanation
                public boolean hasSystemFeature(String feature) {
                               ~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning2() {
        lint()
            .files(
                java(
                        """
                import android.content.pm.PackageManager;

                public class Foo {

                    private void fun() {
                        String featureName = "android.hardware.type.automotive";
                        PackageManager.getInstance().hasSystemFeature(featureName);
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                            src/android/content/pm/PackageManager.java:13: Warning: $explanation
                public boolean hasSystemFeature(String feature) {
                               ~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning3() {
        lint()
            .files(
                java(
                        """
                import android.content.pm.PackageManager;

                public class Foo {

                    private void fun() {
                        PackageManager.getInstance().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                            src/android/content/pm/PackageManager.java:13: Warning: $explanation
                public boolean hasSystemFeature(String feature) {
                               ~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning4() {
        lint()
            .files(
                java(
                        """
                import android.content.pm.PackageManager;

                public class Foo {

                    private void fun() {
                        String featureName = PackageManager.FEATURE_AUTOMOTIVE;
                        PackageManager.getInstance().hasSystemFeature(featureName);
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                            src/android/content/pm/PackageManager.java:13: Warning: $explanation
                public boolean hasSystemFeature(String feature) {
                               ~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning5() {
        lint()
            .files(
                java(
                        """
                import com.android.example.Utils;

                public class Foo {

                    private void fun() {
                        Utils.hasFeature("android.hardware.type.automotive");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                    src/com/android/example/Utils.java:7: Warning: $explanation
                public static boolean hasFeature(String feature) {
                                      ~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning6() {
        lint()
            .files(
                java(
                        """
                import com.android.example.Utils;

                public class Foo {

                    private void fun() {
                        Utils.hasDeviceFeature("android.hardware.type.automotive");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                    src/com/android/example/Utils.java:11: Warning: $explanation
                public static boolean hasDeviceFeature(String feature) {
                                      ~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning7() {
        lint()
            .files(
                java(
                        """
                import com.android.example.Utils;

                public class Foo {

                    private void fun() {
                        Utils.hasFeature(new Object(), "android.hardware.type.automotive");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                    src/com/android/example/Utils.java:15: Warning: $explanation
                public static boolean hasFeature(Object object, String feature) {
                                      ~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testWarning8() {
        lint()
            .files(
                java(
                        """
                import com.android.example.Utils;

                public class Foo {

                    private void fun() {
                        Utils.bypassTestForFeatures("android.hardware.type.automotive");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expect(
                """
                    src/com/android/example/Utils.java:19: Warning: $explanation
                public static boolean bypassTestForFeatures(String feature) {
                                      ~~~~~~~~~~~~~~~~~~~~~
    0 errors, 1 warnings
                            """
            )
    }

    @Test
    fun testNoWarning() {
        lint()
            .files(
                java(
                        """
                import android.content.pm.PackageManager;

                public class Foo {
                    private void fun() {
                        String featureName1 = "android.hardware.type.automotive";
                        String featureName2 = PackageManager.FEATURE_AUTOMOTIVE;
                        String notFeatureName = "FEATURE_AUTOMOTIVE";
                        PackageManager.getInstance().hasSystemFeature(notFeatureName);
                        /*
                        PackageManager.getInstance().hasSystemFeature(
                            "android.hardware.type.automotive");
                         */
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(FeatureAutomotiveDetector.ISSUE)
            .run()
            .expectClean()
    }

    private val pmStub: TestFile =
        java(
            """
        package android.content.pm;

        import java.lang.String;

        public class PackageManager {
            public static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

            public static PackageManager getInstance() {
                return new PackageManager();
            }

            public boolean hasSystemFeature(String feature) {
                return true;
            }
        }
        """
        )

    private val exampleStub: TestFile =
        java(
            """
        package com.android.example;

        import java.lang.String;

        public class Utils {
            public static boolean hasFeature(String feature) {
                return true;
            }

            public static boolean hasDeviceFeature(String feature) {
                return true;
            }

            public static boolean hasFeature(Object object, String feature) {
                return true;
            }

            public static boolean bypassTestForFeatures(String feature) {
                return true;
            }
        }
        """
        )

    private val stubs = arrayOf(pmStub, exampleStub)
}
