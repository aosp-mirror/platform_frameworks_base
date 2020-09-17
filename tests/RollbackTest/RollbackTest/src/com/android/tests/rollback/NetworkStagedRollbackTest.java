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

package com.android.tests.rollback;

import static com.android.cts.rollback.lib.RollbackInfoSubject.assertThat;
import static com.android.cts.rollback.lib.RollbackUtils.getUniqueRollbackInfoForPackage;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackManager;
import android.os.ParcelFileDescriptor;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.rollback.lib.RollbackUtils;

import libcore.io.IoUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class NetworkStagedRollbackTest {
    private static final String NETWORK_STACK_CONNECTOR_CLASS =
            "android.net.INetworkStackConnector";
    private static final String PROPERTY_WATCHDOG_REQUEST_TIMEOUT_MILLIS =
            "watchdog_request_timeout_millis";

    private static final String[] NETWORK_STACK_APK_NAMES = {
            "NetworkStack", "NetworkStackGoogle", "NetworkStackNext", "NetworkStackNextGoogle"
    };

    private static final TestApp NETWORK_STACK = new TestApp("NetworkStack",
            getNetworkStackPackageName(), -1, false, findNetworkStackApk());

    private static File[] findNetworkStackApk() {
        for (String name : NETWORK_STACK_APK_NAMES) {
            final File apk = new File("/system/priv-app/" + name + "/" + name + ".apk");
            if (apk.isFile()) {
                final File dir = new File("/system/priv-app/" + name);
                return dir.listFiles((d, f) -> f.startsWith(name));
            }
        }
        throw new RuntimeException("Can't find NetworkStackApk");
    }

    /**
     * Adopts common shell permissions needed for rollback tests.
     */
    @Before
    public void adoptShellPermissions() {
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                Manifest.permission.FORCE_STOP_PACKAGES,
                Manifest.permission.WRITE_DEVICE_CONFIG);
    }

    /**
     * Drops shell permissions needed for rollback tests.
     */
    @After
    public void dropShellPermissions() {
        InstallUtils.dropShellPermissionIdentity();
    }

    @Test
    public void cleanUp() {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        rm.getAvailableRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        rm.getRecentlyCommittedRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        assertThat(rm.getAvailableRollbacks()).isEmpty();
        assertThat(rm.getRecentlyCommittedRollbacks()).isEmpty();
        uninstallNetworkStackPackage();
    }

    @Test
    public void testNetworkFailedRollback_Phase1() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        RollbackManager rm = RollbackUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                networkStack)).isNull();

        // Reduce health check deadline
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_WATCHDOG_REQUEST_TIMEOUT_MILLIS,
                Integer.toString(120000), false);
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        installNetworkStackPackage();
    }

    @Test
    public void testNetworkFailedRollback_Phase2() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                getNetworkStackPackageName())).isNotNull();

        // Sleep for < health check deadline
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        // Verify rollback was not executed before health check deadline
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                getNetworkStackPackageName())).isNull();
    }

    @Test
    public void testNetworkFailedRollback_Phase3() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                getNetworkStackPackageName())).isNotNull();
    }

    private static String getNetworkStackPackageName() {
        Intent intent = new Intent(NETWORK_STACK_CONNECTOR_CLASS);
        ComponentName comp = intent.resolveSystemService(
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager(), 0);
        return comp.getPackageName();
    }

    private static void installNetworkStackPackage() throws Exception {
        Install.single(NETWORK_STACK).setStaged().setEnableRollback()
                .addInstallFlags(PackageManager.INSTALL_REPLACE_EXISTING).commit();
    }

    private static void uninstallNetworkStackPackage() {
        // Uninstall the package as a privileged user so we won't fail due to permission.
        runShellCommand("pm uninstall " + getNetworkStackPackageName());
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase1() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        RollbackManager rm = RollbackUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                networkStack)).isNull();

        // Reduce health check deadline, here unlike the network failed case, we use
        // a longer deadline because joining a network can take a much longer time for
        // reasons external to the device than 'not joining'
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_WATCHDOG_REQUEST_TIMEOUT_MILLIS,
                Integer.toString(300000), false);
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        installNetworkStackPackage();
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase2() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                getNetworkStackPackageName())).isNotNull();
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase3() throws Exception {
        // Sleep for > health check deadline. We expect no rollback should happen during sleeping.
        // If the device reboots for rollback, this device test will fail as well as the host test.
        Thread.sleep(TimeUnit.SECONDS.toMillis(310));
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                getNetworkStackPackageName())).isNull();
    }

    private static void runShellCommand(String cmd) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(cmd);
        IoUtils.closeQuietly(pfd);
    }
}
