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
        assertTrue(EfficientStringsChecker.isSimple("%04d"));
        assertTrue(EfficientStringsChecker.isSimple("%02x:%02x:%02x"));
        assertTrue(EfficientStringsChecker.isSimple("%10d"));

        assertFalse(EfficientStringsChecker.isSimple("%0.4f"));
        assertFalse(EfficientStringsChecker.isSimple("%t"));
        assertFalse(EfficientStringsChecker.isSimple("%1$s"));
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
                        "    // BUG: Diagnostic contains:",
                        "    String.format(\"foo %04d bar\", 42);",
                        "  }",
                        "  public void exampleLocale(String str) {",
                        "    String.format(Locale.US, str, str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(Locale.US, \"foo %s bar\", str);",
                        "    // BUG: Diagnostic contains:",
                        "    String.format(Locale.US, \"foo %d bar\", 42);",
                        "    // BUG: Diagnostic contains:",
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

    @Test
    public void testPreconditions_Complex() {
        compilationHelper
                .addSourceFile("/android/util/Preconditions.java")
                .addSourceLines("Example.java",
                        "import android.util.Preconditions;",
                        "public class Example {",
                        "  String[] classArray = new String[] { null };",
                        "  String classVar;",
                        "  static final String CONST_VAR = \"baz\";",
                        "  public String classMethod() { return \"baz\"; }",
                        "  public static final String CONST_METHOD() { return \"baz\"; }",
                        "  public void checkNotNull(Example example, Object val) {",
                        "    String methodVar = \"baz\";",
                        "    Preconditions.checkNotNull(val, \"foo\");",
                        "    Preconditions.checkNotNull(val, (\"foo\"));",
                        "    Preconditions.checkNotNull(val, classArray[0]);",
                        "    Preconditions.checkNotNull(val, classVar);",
                        "    Preconditions.checkNotNull(val, CONST_VAR);",
                        "    Preconditions.checkNotNull(val, example.classVar);",
                        "    Preconditions.checkNotNull(val, Example.CONST_VAR);",
                        "    Preconditions.checkNotNull(val, methodVar);",
                        "    Preconditions.checkNotNull(val, classMethod());",
                        "    Preconditions.checkNotNull(val, CONST_METHOD());",
                        "    Preconditions.checkNotNull(val, \"foo\" + \"bar\");",
                        "    Preconditions.checkNotNull(val, (\"foo\" + \"bar\"));",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo\" + classArray[0]);",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo\" + classVar);",
                        "    Preconditions.checkNotNull(val, \"foo\" + CONST_VAR);",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo\" + methodVar);",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo\" + classMethod());",
                        "    // BUG: Diagnostic contains:",
                        "    Preconditions.checkNotNull(val, \"foo\" + CONST_METHOD());",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testStringBuffer() {
        compilationHelper
                .addSourceLines("Example.java",
                        "public class Example {",
                        "  public void example() {",
                        "    // BUG: Diagnostic contains:",
                        "    StringBuffer sb = new StringBuffer();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testStringBuilder() {
        compilationHelper
                .addSourceLines("Example.java",
                        "public class Example {",
                        "  StringBuilder sb = new StringBuilder();",
                        "  String[] classArray = new String[] { null };",
                        "  String classVar;",
                        "  static final String CONST_VAR = \"baz\";",
                        "  public String classMethod() { return \"baz\"; }",
                        "  public static final String CONST_METHOD() { return \"baz\"; }",
                        "  public void generic(Example example) {",
                        "    sb.append(\"foo\");",
                        "    sb.append(\"foo\" + \"bar\");",
                        "    sb.append(classArray[0]);",
                        "    sb.append(example.classArray[0]);",
                        "    sb.append(classVar);",
                        "    sb.append(CONST_VAR);",
                        "    sb.append(example.classVar);",
                        "    sb.append(Example.CONST_VAR);",
                        "    sb.append(classMethod());",
                        "    sb.append(CONST_METHOD());",
                        "  }",
                        "  public void string(String val) {",
                        "    sb.append(\"foo\").append(val);",
                        "    sb.append(\"foo\").append(val != null ? \"bar\" : \"baz\");",
                        "    // BUG: Diagnostic contains:",
                        "    sb.append(\"foo\" + val);",
                        "  }",
                        "  public void number(int val) {",
                        "    sb.append(\"foo\").append(val);",
                        "    sb.append(\"foo\").append(val + val);",
                        "    sb.append(\"foo\").append(val > 0 ? \"bar\" : \"baz\");",
                        "    // BUG: Diagnostic contains:",
                        "    sb.append(\"foo\" + val);",
                        "    // BUG: Diagnostic contains:",
                        "    sb.append(\"foo\" + String.valueOf(val));",
                        "    // BUG: Diagnostic contains:",
                        "    sb.append(\"foo\" + Integer.toString(val));",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testPlusAssignment() {
        compilationHelper
                .addSourceLines("Example.java",
                        "public class Example {",
                        "  public void string(String val) {",
                        "    String s = \"foo\";",
                        "    // BUG: Diagnostic contains:",
                        "    s += \"bar\";",
                        "    // BUG: Diagnostic contains:",
                        "    s += val;",
                        "    // BUG: Diagnostic contains:",
                        "    s += (\"bar\" + \"baz\");",
                        "  }",
                        "  public void number(int val) {",
                        "    int other = 42;",
                        "    other += 24;",
                        "    other += val;",
                        "  }",
                        "}")
                .doTest();
    }
}
