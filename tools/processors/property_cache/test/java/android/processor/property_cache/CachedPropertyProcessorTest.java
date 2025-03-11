/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.processor.property_cache.test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import android.processor.property_cache.CachedPropertyProcessor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Tests the {@link CachedPropertyProcessor}. */
@RunWith(JUnit4.class)
public class CachedPropertyProcessorTest {
    private final Compiler mCompiler =
            Compiler.javac().withProcessors(new CachedPropertyProcessor());

    @Test
    public void testDefaultValues() {
        JavaFileObject expectedJava = JavaFileObjects.forResource("DefaultCache.java");

        Compilation compilation = mCompiler.compile(JavaFileObjects.forResource("Default.java"));
        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedFile(StandardLocation.SOURCE_OUTPUT,
                        "android/processor/property_cache/test/DefaultCache.java")
                .hasSourceEquivalentTo(expectedJava);
    }

    @Test
    public void testCustomValues() {
        JavaFileObject expectedJava = JavaFileObjects.forResource("CustomCache.java");

        Compilation compilation = mCompiler.compile(JavaFileObjects.forResource("Custom.java"));
        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedFile(StandardLocation.SOURCE_OUTPUT,
                        "android/processor/property_cache/test/CustomCache.java")
                .hasSourceEquivalentTo(expectedJava);
    }
}
