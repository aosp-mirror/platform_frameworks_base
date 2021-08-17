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

package com.google.errorprone.bugpatterns.android;

import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TargetSdkCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                TargetSdkChecker.class, getClass());
    }

    @Test
    public void testValid() {
        compilationHelper
                .addSourceFile("/android/os/Build.java")
                .addSourceLines("Example.java",
                        "import android.os.Build;",
                        "public class Example {",
                        "  void test(int targetSdkVersion) {",
                        "    boolean res = targetSdkVersion >= Build.VERSION_CODES.DONUT;",
                        "    if (targetSdkVersion < Build.VERSION_CODES.DONUT) { }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testInvalid() {
        compilationHelper
                .addSourceFile("/android/os/Build.java")
                .addSourceLines("Example.java",
                        "import android.os.Build;",
                        "public class Example {",
                        "  void test(int targetSdkVersion) {",
                        "    // BUG: Diagnostic contains:",
                        "    boolean res = targetSdkVersion > Build.VERSION_CODES.DONUT;",
                        "    // BUG: Diagnostic contains:",
                        "    if (targetSdkVersion <= Build.VERSION_CODES.DONUT) { }",
                        "  }",
                        "}")
                .doTest();
    }
}
