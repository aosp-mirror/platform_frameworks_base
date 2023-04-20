/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class CallingSettingsNonUserGetterMethodsIssueDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = CallingSettingsNonUserGetterMethodsDetector()

    override fun getIssues(): List<Issue> = listOf(
            CallingSettingsNonUserGetterMethodsDetector.ISSUE_NON_USER_GETTER_CALLED
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun testDoesNotDetectIssues() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.provider.Settings.Secure;
                    public class TestClass1 {
                        private void testMethod(Context context) {
                            final int value = Secure.getIntForUser(context.getContentResolver(),
                                Settings.Secure.KEY1, 0, 0);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testDetectsNonUserGetterCalledFromSecure() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.provider.Settings.Secure;
                    public class TestClass1 {
                        private void testMethod(Context context) {
                            final int value = Secure.getInt(context.getContentResolver(),
                                Settings.Secure.KEY1);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Error: \
                        android.provider.Settings.Secure#getInt() called from system process. \
                        Please call android.provider.Settings.Secure#getIntForUser() instead.  \
                        [NonUserGetterCalled]
                                final int value = Secure.getInt(context.getContentResolver(),
                                                         ~~~~~~
                        1 errors, 0 warnings
                        """.addLineContinuation()
                )
    }
    fun testDetectsNonUserGetterCalledFromSystem() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.provider.Settings.System;
                    public class TestClass1 {
                        private void testMethod(Context context) {
                            final float value = System.getFloat(context.getContentResolver(),
                                Settings.System.KEY1);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Error: \
                        android.provider.Settings.System#getFloat() called from system process. \
                        Please call android.provider.Settings.System#getFloatForUser() instead.  \
                        [NonUserGetterCalled]
                                final float value = System.getFloat(context.getContentResolver(),
                                                           ~~~~~~~~
                        1 errors, 0 warnings
                        """.addLineContinuation()
                )
    }

    fun testDetectsNonUserGetterCalledFromSettings() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.provider.Settings;
                    public class TestClass1 {
                        private void testMethod(Context context) {
                            float value = Settings.System.getFloat(context.getContentResolver(),
                                Settings.System.KEY1);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:5: Error: \
                        android.provider.Settings.System#getFloat() called from system process. \
                        Please call android.provider.Settings.System#getFloatForUser() instead.  \
                        [NonUserGetterCalled]
                                float value = Settings.System.getFloat(context.getContentResolver(),
                                                              ~~~~~~~~
                        1 errors, 0 warnings
                        """.addLineContinuation()
                )
    }

    fun testDetectsNonUserGettersCalledFromSystemAndSecure() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.provider.Settings.Secure;
                    import android.provider.Settings.System;
                    public class TestClass1 {
                        private void testMethod(Context context) {
                            final long value1 = Secure.getLong(context.getContentResolver(),
                                Settings.Secure.KEY1, 0);
                            final String value2 = System.getString(context.getContentResolver(),
                                Settings.System.KEY2);
                        }
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expect(
                        """
                        src/test/pkg/TestClass1.java:6: Error: \
                        android.provider.Settings.Secure#getLong() called from system process. \
                        Please call android.provider.Settings.Secure#getLongForUser() instead.  \
                        [NonUserGetterCalled]
                                final long value1 = Secure.getLong(context.getContentResolver(),
                                                           ~~~~~~~
                        src/test/pkg/TestClass1.java:8: Error: \
                        android.provider.Settings.System#getString() called from system process. \
                        Please call android.provider.Settings.System#getStringForUser() instead.  \
                        [NonUserGetterCalled]
                                final String value2 = System.getString(context.getContentResolver(),
                                                             ~~~~~~~~~
                        2 errors, 0 warnings
                        """.addLineContinuation()
                )
    }

    private val SettingsStub: TestFile = java(
            """
            package android.provider;
            public class Settings {
                public class Secure {
                    float getFloat(ContentResolver cr, String key) {
                        return 0.0f;
                    }
                    long getLong(ContentResolver cr, String key) {
                        return 0l;
                    }
                    int getInt(ContentResolver cr, String key) {
                        return 0;
                    }
                }
                public class System {
                    float getFloat(ContentResolver cr, String key) {
                        return 0.0f;
                    }
                    long getLong(ContentResolver cr, String key) {
                        return 0l;
                    }
                    String getString(ContentResolver cr, String key) {
                        return null;
                    }
                }
            }
            """
    ).indented()

    private val stubs = arrayOf(SettingsStub)

    // Substitutes "backslash + new line" with an empty string to imitate line continuation
    private fun String.addLineContinuation(): String = this.trimIndent().replace("\\\n", "")
}
