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

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public class UnsupportedAppUsageProcessorTest {

    private static final JavaFileObject ANNOTATION = JavaFileObjects.forSourceLines(
            "dalvik.dalvik.annotation.compat.UnsupportedAppUsage",
            "package dalvik.annotation.compat;",
            "public @interface UnsupportedAppUsage {",
            "    String expectedSignature() default \"\";\n",
            "    String someProperty() default \"\";",
            "    String overrideSourcePosition() default \"\";",
            "}");

    private CsvReader compileAndReadCsv(JavaFileObject source) throws IOException {
        Compilation compilation =
                Compiler.javac().withProcessors(new UnsupportedAppUsageProcessor())
                .compile(ANNOTATION, source);
        CompilationSubject.assertThat(compilation).succeeded();
        Optional<JavaFileObject> csv = compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                "unsupportedappusage/unsupportedappusage_index.csv");
        assertThat(csv.isPresent()).isTrue();

        return new CsvReader(csv.get().openInputStream());
    }

    @Test
    public void testSignatureFormat() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;",
                "import dalvik.annotation.compat.UnsupportedAppUsage;",
                "public class Class {",
                "  @UnsupportedAppUsage",
                "  public void method() {}",
                "}");
        assertThat(compileAndReadCsv(src).getContents().get(0)).containsEntry(
                "signature", "La/b/Class;->method()V"
        );
    }

    @Test
    public void testSourcePosition() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage", // 4
                "  public void method() {}", // 5
                "}");
        Map<String, String> row = compileAndReadCsv(src).getContents().get(0);
        assertThat(row).containsEntry("startline", "4");
        assertThat(row).containsEntry("startcol", "3");
        assertThat(row).containsEntry("endline", "4");
        assertThat(row).containsEntry("endcol", "23");
    }

    @Test
    public void testAnnotationProperties() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage(someProperty=\"value\")", // 4
                "  public void method() {}", // 5
                "}");
        assertThat(compileAndReadCsv(src).getContents().get(0)).containsEntry(
                "properties", "someProperty=%22value%22");
    }

    @Test
    public void testSourcePositionOverride() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage(overrideSourcePosition=\"otherfile.aidl:30:10:31:20\")",
                "  public void method() {}", // 5
                "}");
        Map<String, String> row = compileAndReadCsv(src).getContents().get(0);
        assertThat(row).containsEntry("file", "otherfile.aidl");
        assertThat(row).containsEntry("startline", "30");
        assertThat(row).containsEntry("startcol", "10");
        assertThat(row).containsEntry("endline", "31");
        assertThat(row).containsEntry("endcol", "20");
        assertThat(row).containsEntry("properties", "");
    }

    @Test
    public void testSourcePositionOverrideWrongFormat() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage(overrideSourcePosition=\"invalid\")", // 4
                "  public void method() {}", // 5
                "}");
        Compilation compilation =
                Compiler.javac().withProcessors(new UnsupportedAppUsageProcessor())
                        .compile(ANNOTATION, src);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "Expected overrideSourcePosition to have format "
                + "file:startLine:startCol:endLine:endCol").inFile(src).onLine(4);
    }

    @Test
    public void testSourcePositionOverrideInvalidInt() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("a.b.Class",
                "package a.b;", // 1
                "import dalvik.annotation.compat.UnsupportedAppUsage;", // 2
                "public class Class {", // 3
                "  @UnsupportedAppUsage(overrideSourcePosition=\"otherfile.aidl:a:b:c:d\")", // 4
                "  public void method() {}", // 5
                "}");
        Compilation compilation =
                Compiler.javac().withProcessors(new UnsupportedAppUsageProcessor())
                        .compile(ANNOTATION, src);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "error parsing integer").inFile(src).onLine(4);
    }

}
