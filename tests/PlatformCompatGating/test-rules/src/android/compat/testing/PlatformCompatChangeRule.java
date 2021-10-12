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

package android.compat.testing;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.compat.Compatibility;
import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.test.platform.app.InstrumentationRegistry;


import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;

import libcore.junit.util.compat.CoreCompatChangeRule;

import org.junit.runners.model.Statement;

/**
 * Allows tests to specify the which change to disable.
 *
 * <p>To use add the following to the test class. It will only change the behavior of a test method
 * if it is annotated with
 * {@link libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges} and/or
 * {@link libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges}.
 * </p>
 * <pre>
 * @Rule
 * public TestRule compatChangeRule = new PlatformCompatChangeRule();
 * </pre>
 *
 * <p>Each test method that needs to disable a specific change needs to be annotated
 * with {@link libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges} and/or
 * {@link libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges} specifying the change
 * id. e.g.:
 * </p>
 * <pre>
 *   @Test
 *   @DisableCompatChanges({42})
 *   public void testAsIfChange42Disabled() {
 *     // check behavior
 *   }
 *
 *   @Test
 *   @EnableCompatChanges({42})
 *   public void testAsIfChange42Enabled() {
 *     // check behavior
 *
 * </pre>
 */
public class PlatformCompatChangeRule extends CoreCompatChangeRule {

    @Override
    protected Statement createStatementForConfig(final Statement statement, ChangeConfig config) {
        return new CompatChangeStatement(statement, config);
    }


    private static class CompatChangeStatement extends Statement {
        private final Statement mTestStatement;
        private final ChangeConfig mConfig;

        private CompatChangeStatement(Statement testStatement, ChangeConfig config) {
            this.mTestStatement = testStatement;
            this.mConfig = config;
        }

        @Override
        public void evaluate() throws Throwable {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            UiAutomation uiAutomation = instrumentation.getUiAutomation();
            String packageName = instrumentation.getTargetContext().getPackageName();
            IPlatformCompat platformCompat = IPlatformCompat.Stub
                    .asInterface(ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
            if (platformCompat == null) {
                throw new IllegalStateException("Could not get IPlatformCompat service!");
            }
            adoptShellPermissions(uiAutomation);
            Compatibility.setOverrides(mConfig);
            try {
                platformCompat.setOverridesForTest(new CompatibilityChangeConfig(mConfig),
                        packageName);
                try {
                    mTestStatement.evaluate();
                } finally {
                    adoptShellPermissions(uiAutomation);
                    platformCompat.clearOverridesForTest(packageName);
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Could not call IPlatformCompat binder method!", e);
            } finally {
                uiAutomation.dropShellPermissionIdentity();
                Compatibility.clearOverrides();
            }
        }

        private static void adoptShellPermissions(UiAutomation uiAutomation) {
            uiAutomation.adoptShellPermissionIdentity(
                    Manifest.permission.LOG_COMPAT_CHANGE,
                    Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG,
                    Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD,
                    Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        }
    }
}
