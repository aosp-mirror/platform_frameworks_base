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
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EfficientXmlCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                EfficientXmlChecker.class, getClass());
    }

    @Test
    public void testCtor() {
        compilationHelper
                .addSourceFile("/android/util/Xml.java")
                .addSourceFile("/com/android/internal/util/FastXmlSerializer.java")
                .addSourceLines("Example.java",
                        "import android.util.Xml;",
                        "import com.android.internal.util.FastXmlSerializer;",
                        "public class Example {",
                        "  public void writer() throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    Xml.newSerializer();",
                        "    // BUG: Diagnostic contains:",
                        "    new FastXmlSerializer();",
                        "  }",
                        "  public void reader() throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    Xml.newPullParser();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testWrite() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlSerializer;",
                        "public class Example {",
                        "  public void typical(XmlSerializer out) throws Exception {",
                        "    out.attribute(null, null, null);",
                        "    out.attribute(null, null, \"foo\");",
                        "    out.attribute(null, null, String.valueOf(null));",
                        "    out.attribute(null, null, String.valueOf(\"foo\"));",
                        "  }",
                        "  public void rawBoolean(XmlSerializer out) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, \"true\");",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, \"false\");",
                        "  }",
                        "  public void toString(XmlSerializer out) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Integer.toString(42));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Integer.toString(42, 10));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Integer.toString(42, 16));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Integer.toHexString(42));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Long.toString(42L));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Long.toString(42L, 10));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Long.toString(42L, 16));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Long.toHexString(42L));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Float.toString(42f));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Double.toString(42d));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Boolean.toString(true));",
                        "  }",
                        "  public void toStringBoxed(XmlSerializer out) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Integer.valueOf(42).toString());",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Long.valueOf(42L).toString());",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Float.valueOf(42f).toString());",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Double.valueOf(42d).toString());",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Boolean.valueOf(true).toString());",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, Boolean.TRUE.toString());",
                        "  }",
                        "  public void valueOf(XmlSerializer out) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(42));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(42L));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(42f));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(42d));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(true));",
                        "  }",
                        "  public void valueOfBoxed(XmlSerializer out) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Integer.valueOf(42)));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Long.valueOf(42L)));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Float.valueOf(42f)));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Double.valueOf(42d)));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Boolean.valueOf(true)));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null, String.valueOf(Boolean.TRUE));",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testWrite_Indirect() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlSerializer;",
                        "public class Example {",
                        "  XmlSerializer out;",
                        "  public void argUnknown(String arg) throws Exception {",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "  public void argNull(String arg) throws Exception {",
                        "    arg = null;",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "  public void argValueOfNull(String arg) throws Exception {",
                        "    arg = String.valueOf(null);",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "  public void argToString(String arg) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    arg = Integer.toString(42);",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "  public void argValueOf(String arg) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    arg = String.valueOf(42);",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "  public void directToString() throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    String arg = Integer.toString(42);",
                        "    out.attribute(null, null, arg);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testWrite_Bytes() {
        compilationHelper
                .addSourceFile("/android/util/Base64.java")
                .addSourceFile("/libcore/util/HexEncoding.java")
                .addSourceFile("/com/android/internal/util/HexDump.java")
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlSerializer;",
                        "public class Example {",
                        "  XmlSerializer out;",
                        "  public void bytes(byte[] arg) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null,",
                        "        android.util.Base64.encodeToString(arg, 0));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null,",
                        "        java.util.Base64.getEncoder().encodeToString(arg));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null,",
                        "        libcore.util.HexEncoding.encodeToString(arg));",
                        "    // BUG: Diagnostic contains:",
                        "    out.attribute(null, null,",
                        "        com.android.internal.util.HexDump.toHexString(arg));",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testRead() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlPullParser;",
                        "public class Example {",
                        "  public void typical(XmlPullParser in) throws Exception {",
                        "    in.getAttributeValue(null, null);",
                        "  }",
                        "  public void parse(XmlPullParser in) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    Integer.parseInt(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Integer.parseInt(in.getAttributeValue(null, null), 10);",
                        "    // BUG: Diagnostic contains:",
                        "    Integer.parseInt(in.getAttributeValue(null, null), 16);",
                        "    // BUG: Diagnostic contains:",
                        "    Long.parseLong(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Long.parseLong(in.getAttributeValue(null, null), 10);",
                        "    // BUG: Diagnostic contains:",
                        "    Long.parseLong(in.getAttributeValue(null, null), 16);",
                        "    // BUG: Diagnostic contains:",
                        "    Float.parseFloat(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Double.parseDouble(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Boolean.parseBoolean(in.getAttributeValue(null, null));",
                        "  }",
                        "  public void valueOf(XmlPullParser in) throws Exception {",
                        "    // BUG: Diagnostic contains:",
                        "    Integer.valueOf(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Long.valueOf(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Float.valueOf(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Double.valueOf(in.getAttributeValue(null, null));",
                        "    // BUG: Diagnostic contains:",
                        "    Boolean.valueOf(in.getAttributeValue(null, null));",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testRead_Indirect() {
        compilationHelper
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlPullParser;",
                        "public class Example {",
                        "  public int direct(XmlPullParser in) throws Exception {",
                        "    String arg = in.getAttributeValue(null, null);",
                        "    if (arg != null) {",
                        "      // BUG: Diagnostic contains:",
                        "      return Integer.parseInt(arg);",
                        "    } else {",
                        "      return -1;",
                        "    }",
                        "  }",
                        "  public int indirect(XmlPullParser in, String arg) throws Exception {",
                        "    arg = in.getAttributeValue(null, null);",
                        "    if (arg != null) {",
                        "      // BUG: Diagnostic contains:",
                        "      return Integer.parseInt(arg);",
                        "    } else {",
                        "      return -1;",
                        "    }",
                        "  }",
                        "  public int tryCatch(XmlPullParser in) throws Exception {",
                        "    String arg = in.getAttributeValue(null, null);",
                        "    try {",
                        "      // BUG: Diagnostic contains:",
                        "      return Integer.parseInt(arg);",
                        "    } catch (NumberFormatException e) {",
                        "      return -1;",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testRead_Bytes() {
        compilationHelper
                .addSourceFile("/android/util/Base64.java")
                .addSourceFile("/libcore/util/HexEncoding.java")
                .addSourceFile("/com/android/internal/util/HexDump.java")
                .addSourceLines("Example.java",
                        "import org.xmlpull.v1.XmlPullParser;",
                        "public class Example {",
                        "  XmlPullParser in;",
                        "  public void bytes() throws Exception {",
                        "    android.util.Base64.decode(",
                        "        // BUG: Diagnostic contains:",
                        "        in.getAttributeValue(null, null), 0);",
                        "    java.util.Base64.getDecoder().decode(",
                        "        // BUG: Diagnostic contains:",
                        "        in.getAttributeValue(null, null));",
                        "    libcore.util.HexEncoding.decode(",
                        "        // BUG: Diagnostic contains:",
                        "        in.getAttributeValue(null, null));",
                        "    com.android.internal.util.HexDump.hexStringToByteArray(",
                        "        // BUG: Diagnostic contains:",
                        "        in.getAttributeValue(null, null));",
                        "  }",
                        "}")
                .doTest();
    }
}
