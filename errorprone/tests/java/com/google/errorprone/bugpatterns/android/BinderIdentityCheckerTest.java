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
public class BinderIdentityCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                BinderIdentityChecker.class, getClass());
    }

    @Test
    public void testValid() {
        compilationHelper
                .addSourceFile("/android/os/Binder.java")
                .addSourceLines("FooService.java",
                        "import android.os.Binder;",
                        "public class FooService {",
                        "  void bar() {",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    try {",
                        "      FooService.class.toString();",
                        "    } finally {",
                        "      Binder.restoreCallingIdentity(token);",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testInvalid() {
        compilationHelper
                .addSourceFile("/android/os/Binder.java")
                .addSourceLines("FooService.java",
                        "import android.os.Binder;",
                        "public class FooService {",
                        "  void noRestore() {",
                        "    // BUG: Diagnostic contains:",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    FooService.class.toString();",
                        "  }",
                        "  void noTry() {",
                        "    // BUG: Diagnostic contains:",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    FooService.class.toString();",
                        "    Binder.restoreCallingIdentity(token);",
                        "  }",
                        "  void noImmediateTry() {",
                        "    // BUG: Diagnostic contains:",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    FooService.class.toString();",
                        "    try {",
                        "      FooService.class.toString();",
                        "    } finally {",
                        "      Binder.restoreCallingIdentity(token);",
                        "    }",
                        "  }",
                        "  void noFinally() {",
                        "    // BUG: Diagnostic contains:",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    try {",
                        "      FooService.class.toString();",
                        "    } catch (Exception ignored) { }",
                        "  }",
                        "  void noFinal() {",
                        "    // BUG: Diagnostic contains:",
                        "    long token = Binder.clearCallingIdentity();",
                        "    try {",
                        "      FooService.class.toString();",
                        "    } finally {",
                        "      Binder.restoreCallingIdentity(token);",
                        "    }",
                        "  }",
                        "  void noRecording() {",
                        "    // BUG: Diagnostic contains:",
                        "    Binder.clearCallingIdentity();",
                        "    FooService.class.toString();",
                        "  }",
                        "  void noWork() {",
                        "    // BUG: Diagnostic contains:",
                        "    final long token = Binder.clearCallingIdentity();",
                        "  }",
                        "}")
                .doTest();
    }
}
