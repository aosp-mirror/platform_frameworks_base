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
package android.processor

import android.processor.IntDefProcessor.IntDefMapping
import com.google.common.collect.ObjectArrays.concat
import com.google.testing.compile.CompilationSubject.assertThat
import com.google.testing.compile.Compiler.javac
import com.google.testing.compile.JavaFileObjects
import junit.framework.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter
import javax.tools.JavaFileObject
import javax.tools.StandardLocation.CLASS_OUTPUT

/**
 * Tests for [IntDefProcessor]
 */
class IntDefProcessorTest {
    private val mAnnotations = arrayOf<JavaFileObject>(
            JavaFileObjects.forSourceLines("android.annotation.IntDef",
                    "package android.annotation;",
                    "import java.lang.annotation.Retention;",
                    "import java.lang.annotation.Target;",
                    "import static java.lang.annotation.ElementType.ANNOTATION_TYPE;",
                    "import static java.lang.annotation.RetentionPolicy.SOURCE;",
                    "@Retention(SOURCE)",
                    "@Target({ANNOTATION_TYPE})",
                    "public @interface IntDef {",
                    "    String[] prefix() default {};",
                    "    String[] suffix() default {};",
                    "    int[] value() default {};",
                    "    boolean flag() default false;",
                    "}")
    )

    @Test
    public fun annotationProcessorGeneratesMapping() {
        val sources: Array<JavaFileObject> = arrayOf(
                JavaFileObjects.forSourceLines(
                        "com.android.server.accessibility.magnification.MagnificationGestureMatcher",
                        "package com.android.server.accessibility.magnification;",
                        "import android.annotation.IntDef;",
                        "import java.lang.annotation.Retention;",
                        "import java.lang.annotation.RetentionPolicy;",
                        "class MagnificationGestureMatcher {",
                        "    private static final int GESTURE_BASE = 100;",
                        "    public static final int GESTURE_TWO_FINGER_DOWN = GESTURE_BASE + 1;",
                        "    public static final int GESTURE_SWIPE = GESTURE_BASE + 2;",
                        "    @IntDef(prefix = {\"GESTURE_MAGNIFICATION_\"}, value = {",
                        "            GESTURE_TWO_FINGER_DOWN,",
                        "            GESTURE_SWIPE",
                        "    })",
                        "    @Retention(RetentionPolicy.SOURCE)",
                        "    @interface GestureId {}",
                        "}"
                ),
                JavaFileObjects.forSourceLines(
                        "android.service.storage.ExternalStorageService",
                        "package android.service.storage;",
                        "import android.annotation.IntDef;",
                        "import java.lang.annotation.Retention;",
                        "import java.lang.annotation.RetentionPolicy;",
                        "class MagnificationGestureMatcher {",
                        "    public static final int FLAG_SESSION_TYPE_FUSE = 1 << 0;",
                        "    public static final int FLAG_SESSION_ATTRIBUTE_INDEXABLE = 1 << 1;",
                        "    @IntDef(flag = true, prefix = {\"FLAG_SESSION_\"},",
                        "        value = {FLAG_SESSION_TYPE_FUSE, FLAG_SESSION_ATTRIBUTE_INDEXABLE})",
                        "    @Retention(RetentionPolicy.SOURCE)",
                        "    public @interface SessionFlag {}",
                        "}"
                )
        )

        val expectedFile = """
            {
              "com.android.server.accessibility.magnification.MagnificationGestureMatcher.GestureId": {
                "flag": false,
                "values": {
                  "101": "GESTURE_TWO_FINGER_DOWN",
                  "102": "GESTURE_SWIPE"
                }
              },
              "android.service.storage.MagnificationGestureMatcher.SessionFlag": {
                "flag": true,
                "values": {
                  "1": "FLAG_SESSION_TYPE_FUSE",
                  "2": "FLAG_SESSION_ATTRIBUTE_INDEXABLE"
                }
              }
            }

        """.trimIndent()

        val filesToCompile = concat(mAnnotations, sources, JavaFileObject::class.java)

        val compilation = javac()
                .withProcessors(IntDefProcessor())
                .compile(filesToCompile.toMutableList())

        assertThat(compilation).succeeded()
        assertThat(compilation).generatedFile(CLASS_OUTPUT, "com.android.winscope",
                "intDefMapping.json").contentsAsUtf8String().isEqualTo(expectedFile)
    }

    @Test
    public fun serializesMappingCorrectly() {
        val map = linkedMapOf(
            "SimpleIntDef" to IntDefMapping(linkedMapOf(
                0x0001 to "VAL_1",
                0x0002 to "VAL_2",
                0x0003 to "VAL_3"
            ), flag = false),
            "Flags" to IntDefMapping(linkedMapOf(
                0b0001 to "PRIVATE_FLAG_1",
                0b0010 to "PRIVATE_FLAG_2",
                0b0100 to "PRIVATE_FLAG_3"
            ), flag = true)
        )

        val writer = StringWriter()
        IntDefProcessor.serializeTo(map, writer)

        val actualOutput = writer.toString()
        val expectedOutput = """
            {
              "SimpleIntDef": {
                "flag": false,
                "values": {
                  "1": "VAL_1",
                  "2": "VAL_2",
                  "3": "VAL_3"
                }
              },
              "Flags": {
                "flag": true,
                "values": {
                  "1": "PRIVATE_FLAG_1",
                  "2": "PRIVATE_FLAG_2",
                  "4": "PRIVATE_FLAG_3"
                }
              }
            }
            
        """.trimIndent()

        assertEquals(actualOutput, expectedOutput)
    }
}