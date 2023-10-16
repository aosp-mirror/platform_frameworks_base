/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.errorprone.BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HideInCommentsCheckerTest {
    private static final String REFACTORING_FILE = "Test.java";

    private BugCheckerRefactoringTestHelper mRefactoringHelper;
    private CompilationTestHelper mCompilationHelper;

    @Before
    public void setUp() {
        mRefactoringHelper = BugCheckerRefactoringTestHelper.newInstance(
                HideInCommentsChecker.class, HideInCommentsCheckerTest.class);
        mCompilationHelper = CompilationTestHelper.newInstance(
                HideInCommentsChecker.class, HideInCommentsCheckerTest.class);
    }


    @Test
    public void refactorSingleLineComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  // Foo @hide",
                        "  void foo() {}",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /** Foo @hide */",
                        "  void foo() {}",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorSingleLineComment_doesntAddUnnecessarySpace() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  // Foo @hide ",
                        "  void foo() {}",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /** Foo @hide */",
                        "  void foo() {}",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorSingleLineBlockComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /* Foo @hide */",
                        "  void foo() {}",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /** Foo @hide */",
                        "  void foo() {}",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorMultiLineBlockComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /*",
                        "   * Foo.",
                        "   *",
                        "   * @hide",
                        "   */",
                        "  void foo(int foo) {}",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /**",
                        "   * Foo.",
                        "   *",
                        "   * @hide",
                        "   */",
                        "  void foo(int foo) {}",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorFieldComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /* Foo @hide */",
                        "  public int foo = 0;",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /** Foo @hide */",
                        "  public int foo = 0;",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorClassComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "/* Foo @hide */",
                        "public class Test {}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "/** Foo @hide */",
                        "public class Test {}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void refactorEnumComment() {
        mRefactoringHelper
                .addInputLines(
                        REFACTORING_FILE,
                        "public enum Test {",
                        "  /* Foo @hide */",
                        "  FOO",
                        "}")
                .addOutputLines(
                        REFACTORING_FILE,
                        "public enum Test {",
                        "  /** Foo @hide */",
                        "  FOO",
                        "}")
                .doTest(TEXT_MATCH);
    }

    @Test
    public void canBeSuppressed() {
        mCompilationHelper
                .addSourceLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /* Foo @hide */",
                        "  @SuppressWarnings(\"AndroidHideInComments\")",
                        "  void foo() {}",
                        "}")
                .doTest();
    }

    @Test
    public void isInJavadoc() {
        mCompilationHelper
                .addSourceLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /** Foo @hide */",
                        "  void foo() {}",
                        "}")
                .doTest();
    }

    @Test
    public void isInMultilineJavadoc() {
        mCompilationHelper
                .addSourceLines(
                        REFACTORING_FILE,
                        "public class Test {",
                        "  /**",
                        "   * Foo.",
                        "   *",
                        "   * @hide",
                        "   */",
                        "  void foo(int foo) {}",
                        "}")
                .doTest();
    }

    @Test
    public void noHidePresent() {
        mCompilationHelper
                .addSourceLines(
                        "test/" + REFACTORING_FILE,
                        "package test;",
                        "// Foo.",
                        "public class Test {",
                        "  // Foo.",
                        "  public int a;",
                        "  /*",
                        "   * Foo.",
                        "   *",
                        "   */",
                        "  void foo(int foo) {}",
                        "}")
                .doTest();
    }

}
