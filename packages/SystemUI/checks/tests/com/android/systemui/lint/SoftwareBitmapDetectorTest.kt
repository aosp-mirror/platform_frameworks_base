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

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

@Suppress("UnstableApiUsage")
class SoftwareBitmapDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = SoftwareBitmapDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> = listOf(SoftwareBitmapDetector.ISSUE)

    private val explanation = "Usage of Config.HARDWARE is highly encouraged."

    @Test
    fun testSoftwareBitmap() {
        lint().files(
                TestFiles.java(
                        """
                    import android.graphics.Bitmap;

                    public class TestClass1 {
                        public void test() {
                          Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
                          Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(SoftwareBitmapDetector.ISSUE)
                .run()
                .expectWarningCount(2)
                .expectContains(explanation)
    }

    @Test
    fun testHardwareBitmap() {
        lint().files(
                TestFiles.java(
                        """
                    import android.graphics.Bitmap;

                    public class TestClass1 {
                        public void test() {
                          Bitmap.createBitmap(300, 300, Bitmap.Config.HARDWARE);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(SoftwareBitmapDetector.ISSUE)
                .run()
                .expectWarningCount(0)
    }

    private val bitmapStub: TestFile = java(
            """
        package android.graphics;

        public class Bitmap {
            public enum Config {
                ARGB_8888,
                RGB_565,
                HARDWARE
            }
            public static Bitmap createBitmap(int width, int height, Config config) {
                return null;
            }
        }
        """
    )

    private val stubs = arrayOf(bitmapStub)
}
