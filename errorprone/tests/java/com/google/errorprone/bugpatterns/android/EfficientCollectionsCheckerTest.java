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
public class EfficientCollectionsCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                EfficientCollectionsChecker.class, getClass());
    }

    @Test
    public void testMap() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import java.util.HashMap;",
                        "public class Example {",
                        "  public void exampleInteger() {",
                        "    // BUG: Diagnostic contains:",
                        "    HashMap<Integer, Integer> a = new HashMap<>();",
                        "    // BUG: Diagnostic contains:",
                        "    HashMap<Integer, Long> b = new HashMap<>();",
                        "    // BUG: Diagnostic contains:",
                        "    HashMap<Integer, Boolean> c = new HashMap<>();",
                        "    // BUG: Diagnostic contains:",
                        "    HashMap<Integer, String> d = new HashMap<>();",
                        "  }",
                        "  public void exampleLong() {",
                        "    // BUG: Diagnostic contains:",
                        "    HashMap<Long, String> res = new HashMap<>();",
                        "  }",
                        "  public void exampleOther() {",
                        "    HashMap<String, String> res = new HashMap<>();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testList() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import java.util.ArrayList;",
                        "public class Example {",
                        "  public void exampleInteger() {",
                        "    // BUG: Diagnostic contains:",
                        "    ArrayList<Integer> res = new ArrayList<>();",
                        "  }",
                        "  public void exampleLong() {",
                        "    // BUG: Diagnostic contains:",
                        "    ArrayList<Long> res = new ArrayList<>();",
                        "  }",
                        "  public void exampleOther() {",
                        "    ArrayList<String> res = new ArrayList<>();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testErasure() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import java.util.HashMap;",
                        "public class Example {",
                        "  public void example() {",
                        "    HashMap a = new HashMap();",
                        "  }",
                        "}")
                .doTest();
    }
}
