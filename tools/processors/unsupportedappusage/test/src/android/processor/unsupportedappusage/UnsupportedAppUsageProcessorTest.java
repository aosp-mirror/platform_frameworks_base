/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.processor.unsupportedappusage;

import static com.google.common.truth.Truth.assertThat;

import com.android.javac.Javac;

import com.google.common.base.Joiner;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

public class UnsupportedAppUsageProcessorTest {

    private Javac mJavac;

    @Before
    public void setup() throws IOException {
        mJavac = new Javac();
        mJavac.addSource("dalvik.annotation.compat.UnsupportedAppUsage", Joiner.on('\n').join(
                "package dalvik.annotation.compat;",
                "public @interface UnsupportedAppUsage {",
                "    String expectedSignature() default \"\";\n",
                "    String someProperty() default \"\";",
                "}"));
    }

    private CsvReader compileAndReadCsv() throws IOException {
        mJavac.compileWithAnnotationProcessor(new UnsupportedAppUsageProcessor());
        return new CsvReader(
                mJavac.getOutputFile("unsupportedappusage/unsupportedappusage_index.csv"));
    }

    @Test
    public void testSignatureFormat() throws Exception {
        mJavac.addSource("a.b.Class", Joiner.on('\n').join(
                "package a.b;",
                "import dalvik.annotation.compat.UnsupportedAppUsage;",
                "public class Class {",
                "  @UnsupportedAppUsage",
                "  public void method() {}",
                "}"));
        assertThat(compileAndReadCsv().getContents().get(0)).containsEntry(
                "signature", "La/b/Class;->method()V"
        );
    }

    @Test
    public void testSourcePosition() throws Exception {
        mJavac.addSource("a.b.Class", Joiner.on('\n').join(
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage", // 4
                "  public void method() {}", // 5
                "}"));
        Map<String, String> row = compileAndReadCsv().getContents().get(0);
        assertThat(row).containsEntry("startline", "4");
        assertThat(row).containsEntry("startcol", "3");
        assertThat(row).containsEntry("endline", "4");
        assertThat(row).containsEntry("endcol", "23");
    }

    @Test
    public void testAnnotationProperties() throws Exception {
        mJavac.addSource("a.b.Class", Joiner.on('\n').join(
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage(someProperty=\"value\")", // 4
                "  public void method() {}", // 5
                "}"));
        assertThat(compileAndReadCsv().getContents().get(0)).containsEntry(
                "properties", "someProperty=%22value%22");
    }


}
