/*
 * Copyright (C) 2021 The Android Open Source Project
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
public class UnattributedNoteOpCallCheckerTest {
    private CompilationTestHelper mCompilationHelper;

    @Before
    public void setUp() {
        mCompilationHelper = CompilationTestHelper.newInstance(
                UnattributedNoteOpCallChecker.class, getClass());
    }

    @Test
    public void testNoteOp() {
        mCompilationHelper
                .addSourceFile("/android/app/AppOpsManager.java")
                .addSourceLines("Example.java",
                        "import android.app.AppOpsManager;",
                        "public class Example {",
                        "  void example() {",
                        "    AppOpsManager mAppOps = new AppOpsManager();",
                        "    mAppOps.noteOp(\"foo\", 0, \"bar\", \"baz\", \"qux\");",
                        "    mAppOps.noteOp(0, 0, \"bar\", \"baz\", \"qux\");",
                        "    // BUG: Diagnostic contains:",
                        "    mAppOps.noteOp(1, 2, \"foo\");",
                        "    // BUG: Diagnostic contains:",
                        "    mAppOps.noteOp(\"foo\", 1, \"bar\");",
                        "    // BUG: Diagnostic contains:",
                        "    mAppOps.noteOp(1);",
                        "  }",
                        "}")
                .doTest();
    }
}
