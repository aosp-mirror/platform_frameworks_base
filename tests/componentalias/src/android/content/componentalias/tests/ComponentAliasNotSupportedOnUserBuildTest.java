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
package android.content.componentalias.tests;

import static com.google.common.truth.Truth.assertThat;

import android.os.Build;
import android.provider.DeviceConfig;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.compatibility.common.util.ShellUtils;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to make sure component-alias can't be enabled on user builds.
 */
public class ComponentAliasNotSupportedOnUserBuildTest {
    protected static final DeviceConfigStateHelper sDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS);

    @AfterClass
    public static void restoreDeviceConfig() throws Exception {
        sDeviceConfig.close();
    }

    @Test
    public void enableComponentAliasWithCompatFlag() throws Exception {
        Assume.assumeFalse(Build.isDebuggable());

        // Try to enable it by both the device config and compat-id.
        sDeviceConfig.set("enable_experimental_component_alias", "true");
        ShellUtils.runShellCommand(
                "am compat enable --no-kill USE_EXPERIMENTAL_COMPONENT_ALIAS android");

        // Sleep for an arbitrary amount of time, so the config would sink in, if there was
        // no "not on user builds" check.

        Thread.sleep(5000);

        // Make sure the feature is still disabled.
        assertThat(ShellUtils.runShellCommand("dumpsys activity component-alias")
                .indexOf("Enabled: false") > 0).isTrue();
    }
}
