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

@Suppress("UnstableApiUsage")
class StaticSettingsProviderDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = StaticSettingsProviderDetector()
    override fun getIssues(): List<Issue> = listOf(StaticSettingsProviderDetector.ISSUE)

    @Test
    fun testSuppressGetServiceWithString() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;

                        import android.provider.Settings;
                        import android.provider.Settings.Global;
                        import android.provider.Settings.Secure;

                        public class TestClass {
                            public void getSystemServiceWithoutDagger(Context context) {
                                final ContentResolver cr = mContext.getContentResolver();
                                Global.getFloat(cr, Settings.Global.UNLOCK_SOUND);
                                Global.getInt(cr, Settings.Global.UNLOCK_SOUND);
                                Global.getLong(cr, Settings.Global.UNLOCK_SOUND);
                                Global.getString(cr, Settings.Global.UNLOCK_SOUND);
                                Global.getFloat(cr, Settings.Global.UNLOCK_SOUND, 1f);
                                Global.getInt(cr, Settings.Global.UNLOCK_SOUND, 1);
                                Global.getLong(cr, Settings.Global.UNLOCK_SOUND, 1L);
                                Global.getString(cr, Settings.Global.UNLOCK_SOUND, "1");
                                Global.putFloat(cr, Settings.Global.UNLOCK_SOUND, 1f);
                                Global.putInt(cr, Settings.Global.UNLOCK_SOUND, 1);
                                Global.putLong(cr, Settings.Global.UNLOCK_SOUND, 1L);
                                Global.putString(cr, Settings.Global.UNLOCK_SOUND, "1");

                                Secure.getFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                                Secure.getInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                                Secure.getLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                                Secure.getString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                                Secure.getFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1f);
                                Secure.getInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1);
                                Secure.getLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1L);
                                Secure.getString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, "1");
                                Secure.putFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1f);
                                Secure.putInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1);
                                Secure.putLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1L);
                                Secure.putString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, "1");

                                Settings.System.getFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                Settings.System.getString(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                Settings.System.getFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1f);
                                Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1);
                                Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1L);
                                Settings.System.getString(cr, Settings.System.SCREEN_OFF_TIMEOUT, "1");
                                Settings.System.putFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1f);
                                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1);
                                Settings.System.putLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1L);
                                Settings.System.putString(cr, Settings.Global.UNLOCK_SOUND, "1");
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(StaticSettingsProviderDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:10: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getFloat(cr, Settings.Global.UNLOCK_SOUND);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:11: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getInt(cr, Settings.Global.UNLOCK_SOUND);
                               ~~~~~~
                src/test/pkg/TestClass.java:12: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getLong(cr, Settings.Global.UNLOCK_SOUND);
                               ~~~~~~~
                src/test/pkg/TestClass.java:13: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getString(cr, Settings.Global.UNLOCK_SOUND);
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:14: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getFloat(cr, Settings.Global.UNLOCK_SOUND, 1f);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:15: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getInt(cr, Settings.Global.UNLOCK_SOUND, 1);
                               ~~~~~~
                src/test/pkg/TestClass.java:16: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getLong(cr, Settings.Global.UNLOCK_SOUND, 1L);
                               ~~~~~~~
                src/test/pkg/TestClass.java:17: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.getString(cr, Settings.Global.UNLOCK_SOUND, "1");
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:18: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.putFloat(cr, Settings.Global.UNLOCK_SOUND, 1f);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:19: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.putInt(cr, Settings.Global.UNLOCK_SOUND, 1);
                               ~~~~~~
                src/test/pkg/TestClass.java:20: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.putLong(cr, Settings.Global.UNLOCK_SOUND, 1L);
                               ~~~~~~~
                src/test/pkg/TestClass.java:21: Warning: @Inject a GlobalSettings instead [StaticSettingsProvider]
                        Global.putString(cr, Settings.Global.UNLOCK_SOUND, "1");
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:23: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:24: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                               ~~~~~~
                src/test/pkg/TestClass.java:25: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                               ~~~~~~~
                src/test/pkg/TestClass.java:26: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED);
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:27: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1f);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:28: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1);
                               ~~~~~~
                src/test/pkg/TestClass.java:29: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1L);
                               ~~~~~~~
                src/test/pkg/TestClass.java:30: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.getString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, "1");
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:31: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.putFloat(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1f);
                               ~~~~~~~~
                src/test/pkg/TestClass.java:32: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.putInt(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1);
                               ~~~~~~
                src/test/pkg/TestClass.java:33: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.putLong(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, 1L);
                               ~~~~~~~
                src/test/pkg/TestClass.java:34: Warning: @Inject a SecureSettings instead [StaticSettingsProvider]
                        Secure.putString(cr, Settings.Secure.ASSIST_GESTURE_ENABLED, "1");
                               ~~~~~~~~~
                src/test/pkg/TestClass.java:36: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                        ~~~~~~~~
                src/test/pkg/TestClass.java:37: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                        ~~~~~~
                src/test/pkg/TestClass.java:38: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                        ~~~~~~~
                src/test/pkg/TestClass.java:39: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getString(cr, Settings.System.SCREEN_OFF_TIMEOUT);
                                        ~~~~~~~~~
                src/test/pkg/TestClass.java:40: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1f);
                                        ~~~~~~~~
                src/test/pkg/TestClass.java:41: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1);
                                        ~~~~~~
                src/test/pkg/TestClass.java:42: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1L);
                                        ~~~~~~~
                src/test/pkg/TestClass.java:43: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.getString(cr, Settings.System.SCREEN_OFF_TIMEOUT, "1");
                                        ~~~~~~~~~
                src/test/pkg/TestClass.java:44: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.putFloat(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1f);
                                        ~~~~~~~~
                src/test/pkg/TestClass.java:45: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1);
                                        ~~~~~~
                src/test/pkg/TestClass.java:46: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.putLong(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1L);
                                        ~~~~~~~
                src/test/pkg/TestClass.java:47: Warning: @Inject a SystemSettings instead [StaticSettingsProvider]
                        Settings.System.putString(cr, Settings.Global.UNLOCK_SOUND, "1");
                                        ~~~~~~~~~
                0 errors, 36 warnings
                """
            )
    }

    @Test
    fun testGetServiceWithString() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;

                        import android.provider.Settings;
                        import android.provider.Settings.Global;
                        import android.provider.Settings.Secure;

                        public class TestClass {
                            @SuppressWarnings("StaticSettingsProvider")
                            public void getSystemServiceWithoutDagger(Context context) {
                                final ContentResolver cr = mContext.getContentResolver();
                                Global.getFloat(cr, Settings.Global.UNLOCK_SOUND);
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(StaticSettingsProviderDetector.ISSUE)
            .run()
            .expectClean()
    }

    private val stubs = androidStubs
}
