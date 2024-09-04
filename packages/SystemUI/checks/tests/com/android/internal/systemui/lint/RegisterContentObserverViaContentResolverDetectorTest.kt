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

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class RegisterContentObserverViaContentResolverDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = RegisterContentObserverViaContentResolverDetector()

    override fun getIssues(): List<Issue> =
        listOf(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)

    @Test
    fun testRegisterContentObserver_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void register(Context context) {
                          context.getContentResolver().
                            registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Error: ContentResolver.registerContentObserver() should be replaced with an appropriate interface API call, for eg. <SettingsProxy>/<UserSettingsProxy>.registerContentObserver() [RegisterContentObserverViaContentResolver]
                        registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                        ~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testRegisterContentObserverForUser_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void register(Context context) {
                          context.getContentResolver().
                            registerContentObserverAsUser(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Error: ContentResolver.registerContentObserverAsUser() should be replaced with an appropriate interface API call, for eg. <SettingsProxy>/<UserSettingsProxy>.registerContentObserverAsUser() [RegisterContentObserverViaContentResolver]
        registerContentObserverAsUser(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testSuppressRegisterContentObserver() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        @SuppressWarnings("RegisterContentObserverViaContentResolver")
                        public void register(Context context) {
                          context.getContentResolver().
                            registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expectClean()
    }

    @Test
    fun testRegisterContentObserverInSettingsProxy_allowed() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package com.android.systemui.util.settings;
                    import android.content.Context;

                    public class SettingsProxy {
                        public void register(Context context) {
                          context.getContentResolver().
                            registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expectClean()
    }

    @Test
    fun testNoopIfNoCall() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class SettingsProxy {
                        public void register(Context context) {
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expectClean()
    }

    @Test
    fun testUnRegisterContentObserver_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void register(Context context) {
                          context.getContentResolver().
                            unregisterContentObserver(mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterContentObserverViaContentResolverDetector.CONTENT_RESOLVER_ERROR)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Error: ContentResolver.unregisterContentObserver() should be replaced with an appropriate interface API call, for eg. <SettingsProxy>/<UserSettingsProxy>.unregisterContentObserver() [RegisterContentObserverViaContentResolver]
                        unregisterContentObserver(mSettingObserver);
                        ~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }
}
