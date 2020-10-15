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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EfficientStringsCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                EfficientStringsChecker.class, getClass());
    }

    @Test
    public void testSimple() {
        assertTrue(EfficientStringsChecker.isSimple(""));
        assertTrue(EfficientStringsChecker.isSimple("%s"));
        assertTrue(EfficientStringsChecker.isSimple("String %s%s and %%%% number %d%d together"));

        assertFalse(EfficientStringsChecker.isSimple("%04d"));
        assertFalse(EfficientStringsChecker.isSimple("%02x:%02x:%02x"));
    }

    @Test
    public void testFormat() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import java.util.Locale;",
                        "public class Example {",
                        "  public void example(String str) {",
                        "    String.format(str, str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(\"foo %s bar\", str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(\"foo %d bar\", 42);",
                        "    String.format(\"foo %04d bar\", 42);",
                        "  }",
                        "  public void exampleLocale(String str) {",
                        "    String.format(Locale.US, str, str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(Locale.US, \"foo %s bar\", str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(Locale.US, \"foo %d bar\", 42);",
                        "    String.format(Locale.US, \"foo %04d bar\", 42);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testPreconditions() {
        compilationHelper
                .addSourceFile("/android/util/Preconditions.java")
                .addSourceLines("Example.java",
                        "import android.util.Preconditions;",
                        "import java.util.Objects;",
                        "public class Example {",
                        "  String str;",
                        "  public void checkState(boolean val) {",
                        "    Preconditions.checkState(val);",
                        "    Preconditions.checkState(val, str);",
                        "    Preconditions.checkState(val, \"foo\");",
                        "    Preconditions.checkState(val, \"foo\" + \"bar\");",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkState(val, \"foo \" + val);",
                        "  }",
                        "  public void checkArgument(boolean val) {",
                        "    Preconditions.checkArgument(val);",
                        "    Preconditions.checkArgument(val, str);",
                        "    Preconditions.checkArgument(val, \"foo\");",
                        "    Preconditions.checkArgument(val, \"foo\" + \"bar\");",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkArgument(val, \"foo \" + val);",
                        "  }",
                        "  public void checkNotNull(Object val) {",
                        "    Preconditions.checkNotNull(val);",
                        "    Preconditions.checkNotNull(val, str);",
                        "    Preconditions.checkNotNull(val, \"foo\");",
                        "    Preconditions.checkNotNull(val, \"foo\" + \"bar\");",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo \" + val);",
                        "  }",
                        "  public void requireNonNull(Object val) {",
                        "    Objects.requireNonNull(val);",
                        "    Objects.requireNonNull(val, str);",
                        "    Objects.requireNonNull(val, \"foo\");",
                        "    Objects.requireNonNull(val, \"foo\" + \"bar\");",
                        "    // BUG: Diagnostic contains:",
                        "    Objects.requireNonNull(val, \"foo \" + val);",
                        "  }",
                        "}")
                .doTest();
    }
}
