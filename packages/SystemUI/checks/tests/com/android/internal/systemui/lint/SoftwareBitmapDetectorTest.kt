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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class SoftwareBitmapDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = SoftwareBitmapDetector()

    override fun getIssues(): List<Issue> = listOf(SoftwareBitmapDetector.ISSUE)

    @Test
    fun testSoftwareBitmap() {
        lint()
            .files(
                TestFiles.java(
                        """
                    import android.graphics.Bitmap;

                    public class TestClass {
                        public void test() {
                          Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
                          Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(SoftwareBitmapDetector.ISSUE)
            .run()
            .expect(
                """
                src/TestClass.java:5: Warning: Replace software bitmap with Config.HARDWARE [SoftwareBitmap]
                      Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
                                                                  ~~~~~~~
                src/TestClass.java:6: Warning: Replace software bitmap with Config.HARDWARE [SoftwareBitmap]
                      Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
                                                                  ~~~~~~~~~
                0 errors, 2 warnings
                """
            )
    }

    @Test
    fun testSuppressSoftwareBitmap() {
        lint()
            .files(
                TestFiles.java(
                        """
                    import android.graphics.Bitmap;

                    @SuppressWarnings("SoftwareBitmap")
                    public class TestClass {
                        public void test() {
                          Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
                          Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(SoftwareBitmapDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testHardwareBitmap() {
        lint()
            .files(
                TestFiles.java(
                    """
                    import android.graphics.Bitmap;

                    public class TestClass {
                        public void test() {
                          Bitmap.createBitmap(300, 300, Bitmap.Config.HARDWARE);
                        }
                    }
                """
                ),
                *androidStubs
            )
            .issues(SoftwareBitmapDetector.ISSUE)
            .run()
            .expectClean()
    }
}
