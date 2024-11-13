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

/** Test class for [RegisterContentObserverSyncViaSettingsProxyDetector]. */
class RegisterContentObserverSyncViaSettingsProxyDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = RegisterContentObserverSyncViaSettingsProxyDetector()

    override fun getIssues(): List<Issue> =
        listOf(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)

    @Test
    fun testRegisterContentObserverSync_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import com.android.systemui.util.settings.SecureSettings;
                    public class TestClass {
                        public void register(SecureSettings secureSettings) {
                          secureSettings.
                            registerContentObserverSync(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:6: Warning: Avoid using registerContentObserverSync() if calling the API is not required on the main thread. Instead use an appropriate async interface API call for eg. registerContentObserver() or registerContentObserverAsync(). [RegisterContentObserverSyncWarning]
        registerContentObserverSync(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
0 errors, 1 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testRegisterContentObserverForUserSync_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import com.android.systemui.util.settings.SecureSettings;
                    public class TestClass {
                        public void register(SecureSettings secureSettings) {
                          secureSettings.
                            registerContentObserverForUserSync(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:6: Warning: Avoid using registerContentObserverForUserSync() if calling the API is not required on the main thread. Instead use an appropriate async interface API call for eg. registerContentObserver() or registerContentObserverAsync(). [RegisterContentObserverSyncWarning]
        registerContentObserverForUserSync(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0 errors, 1 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testSuppressRegisterContentObserverSync() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import com.android.systemui.util.settings.SecureSettings;
                    public class TestClass {
                        @SuppressWarnings("RegisterContentObserverSyncWarning")
                        public void register(SecureSettings secureSettings) {
                          secureSettings.
                            registerContentObserverForUserSync(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                                false, mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)
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
                    import com.android.systemui.util.settings.SecureSettings;
                    public class TestClass {
                        public void register(SecureSettings secureSettings) {
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)
            .run()
            .expectClean()
    }

    @Test
    fun testUnRegisterContentObserverSync_throwError() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import com.android.systemui.util.settings.SecureSettings;
                    public class TestClass {
                        public void register(SecureSettings secureSettings) {
                          secureSettings.
                            unregisterContentObserverSync(mSettingObserver);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(RegisterContentObserverSyncViaSettingsProxyDetector.SYNC_WARNING)
            .run()
            .expect(
                """
        src/test/pkg/TestClass.java:6: Warning: Avoid using unregisterContentObserverSync() if calling the API is not required on the main thread. Instead use an appropriate async interface API call for eg. registerContentObserver() or registerContentObserverAsync(). [RegisterContentObserverSyncWarning]
        unregisterContentObserverSync(mSettingObserver);
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0 errors, 1 warnings
            """
                    .trimIndent()
            )
    }

    private companion object {
        private val SETTINGS_PROXY_STUB =
            kotlin(
                    """
            package com.android.systemui.util.settings
            interface SettingsProxy {
                fun registerContentObserverSync() {}
                fun unregisterContentObserverSync() {}
            }
            """
                )
                .indented()

        private val USER_SETTINGS_PROXY_STUB =
            kotlin(
                    """
            package com.android.systemui.util.settings
            interface UserSettingsProxy : SettingsProxy {
                fun registerContentObserverForUserSync() {}
            }
            """
                )
                .indented()

        private val SECURE_SETTINGS_STUB =
            kotlin(
                    """
            package com.android.systemui.util.settings
            interface SecureSettings : UserSettingsProxy {}
            """
                )
                .indented()
    }

    private val stubs = arrayOf(SETTINGS_PROXY_STUB, USER_SETTINGS_PROXY_STUB, SECURE_SETTINGS_STUB)
}
