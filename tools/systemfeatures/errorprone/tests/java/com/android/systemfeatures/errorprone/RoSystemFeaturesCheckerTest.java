/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemfeatures.errorprone;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RoSystemFeaturesCheckerTest {
    private BugCheckerRefactoringTestHelper mRefactoringHelper;
    private CompilationTestHelper mCompilationHelper;

    @Before
    public void setUp() {
        mCompilationHelper =
                CompilationTestHelper.newInstance(RoSystemFeaturesChecker.class, getClass());
        mRefactoringHelper =
                BugCheckerRefactoringTestHelper.newInstance(
                        RoSystemFeaturesChecker.class, getClass());
    }

    @Test
    public void testNoDiagnostic() {
        mCompilationHelper
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/pm/PackageManager.java")
                .addSourceLines("Example.java",
                        """
                        import android.content.Context;
                        import android.content.pm.PackageManager;
                        public class Example {
                          void test(Context context) {
                            boolean hasCustomFeature = context.getPackageManager()
                                .hasSystemFeature("my.custom.feature");
                            boolean hasNonAnnotatedFeature = context.getPackageManager()
                                .hasSystemFeature(PackageManager.FEATURE_NOT_ANNOTATED);
                            boolean hasNonRoApiFeature = context.getPackageManager()
                                .hasSystemFeature(PackageManager.FEATURE_NOT_IN_RO_FEATURE_API);
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    public void testDiagnostic() {
        mCompilationHelper
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/pm/PackageManager.java")
                .addSourceLines("Example.java",
                        """
                        import android.content.Context;
                        import android.content.pm.PackageManager;
                        public class Example {
                          void test(Context context) {
                            boolean hasFeature = context.getPackageManager()
                            // BUG: Diagnostic contains:
                                .hasSystemFeature(PackageManager.FEATURE_PC);
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    public void testFix() {
        mRefactoringHelper
                .addInputLines("Example.java",
                        """
                        import static android.content.pm.PackageManager.FEATURE_WATCH;

                        import android.content.Context;
                        import android.content.pm.PackageManager;
                        public class Example {
                          static class CustomContext extends Context {};
                          private CustomContext mContext;
                          void test(Context context) {
                            boolean hasPc = mContext.getPackageManager()
                                .hasSystemFeature(PackageManager.FEATURE_PC);
                            boolean hasWatch = context.getPackageManager()
                                .hasSystemFeature(FEATURE_WATCH);
                          }
                        }
                        """)
                .addOutputLines("Example.java",
                        """
                        import android.content.Context;
                        import android.content.pm.PackageManager;
                        import com.android.internal.pm.RoSystemFeatures;
                        public class Example {
                          static class CustomContext extends Context {};
                          private CustomContext mContext;
                          void test(Context context) {
                            boolean hasPc = RoSystemFeatures.hasFeaturePc(mContext);
                            boolean hasWatch = RoSystemFeatures.hasFeatureWatch(context);
                          }
                        }
                        """)
                // Don't try compiling the output, as it requires pulling in the full set of code
                // dependencies.
                .allowBreakingChanges()
                .doTest();
    }
}
