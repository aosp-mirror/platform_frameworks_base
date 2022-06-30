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

import android.os.Build;
import android.provider.DeviceConfig;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.compatibility.common.util.ShellUtils;
import com.android.compatibility.common.util.TestUtils;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Test;

public class ComponentAliasEnableWithDeviceConfigTest {
    protected static final DeviceConfigStateHelper sDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS);

    @AfterClass
    public static void restoreDeviceConfig() throws Exception {
        sDeviceConfig.close();
    }

    @Test
    public void enableComponentAliasWithCompatFlag() throws Exception {
        Assume.assumeTrue(Build.isDebuggable());

        sDeviceConfig.set("component_alias_overrides", "");

        // First, disable with both compat-id and device config.
        ShellUtils.runShellCommand(
                "am compat disable --no-kill USE_EXPERIMENTAL_COMPONENT_ALIAS android");
        sDeviceConfig.set("enable_experimental_component_alias", "");

        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            return ShellUtils.runShellCommand("dumpsys activity component-alias")
                    .indexOf("Enabled: false") > 0;
        });

        // Then, enable by device config.
        sDeviceConfig.set("enable_experimental_component_alias", "true");

        // Make sure the feature is actually enabled.
        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            return ShellUtils.runShellCommand("dumpsys activity component-alias")
                    .indexOf("Enabled: true") > 0;
        });
    }
}
