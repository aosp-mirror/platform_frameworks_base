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

package android.ext.services.watchdog;

import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_REQUESTED_PACKAGES;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_SUPPORTED_PACKAGES;
import static android.service.watchdog.ExplicitHealthCheckService.PackageConfig;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteCallback;
import android.service.watchdog.ExplicitHealthCheckService;
import android.service.watchdog.IExplicitHealthCheckService;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Contains the base tests that does not rely on the specific algorithm implementation.
 */
public class ExplicitHealthCheckServiceImplTest {
    private static final String NETWORK_STACK_CONNECTOR_CLASS =
            "android.net.INetworkStackConnector";

    private final Context mContext = InstrumentationRegistry.getContext();
    private IExplicitHealthCheckService mService;
    private String mNetworkStackPackageName;

    @Rule
    public ServiceTestRule mServiceTestRule;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE);

        mServiceTestRule = new ServiceTestRule();
        mService = IExplicitHealthCheckService.Stub.asInterface(
                mServiceTestRule.bindService(getExtServiceIntent()));
        mNetworkStackPackageName = getNetworkStackPackage();
        assumeFalse(mNetworkStackPackageName == null);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testHealthCheckSupportedPackage() throws Exception {
        List<PackageConfig> supportedPackages = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        mService.getSupportedPackages(new RemoteCallback(result -> {
            supportedPackages.addAll(result.getParcelableArrayList(EXTRA_SUPPORTED_PACKAGES));
            latch.countDown();
        }));
        latch.await();

        // TODO: Support DeviceConfig changes for the health check timeout
        assertThat(supportedPackages).hasSize(1);
        assertThat(supportedPackages.get(0).getPackageName())
                .isEqualTo(mNetworkStackPackageName);
        assertThat(supportedPackages.get(0).getHealthCheckTimeoutMillis())
                .isEqualTo(ExplicitHealthCheckServiceImpl.DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    @Test
    public void testHealthCheckRequests() throws Exception {
        List<String> requestedPackages = new ArrayList<>();
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        CountDownLatch latch3 = new CountDownLatch(1);

        // Initially, no health checks requested
        mService.getRequestedPackages(new RemoteCallback(result -> {
            requestedPackages.addAll(result.getParcelableArrayList(EXTRA_REQUESTED_PACKAGES));
            latch1.countDown();
        }));

        // Verify that no health checks requested
        latch1.await();
        assertThat(requestedPackages).isEmpty();

        // Then request health check
        mService.request(mNetworkStackPackageName);

        // Verify that health check is requested for network stack
        mService.getRequestedPackages(new RemoteCallback(result -> {
            requestedPackages.addAll(result.getParcelableArrayList(EXTRA_REQUESTED_PACKAGES));
            latch2.countDown();
        }));
        latch2.await();
        assertThat(requestedPackages).hasSize(1);
        assertThat(requestedPackages.get(0)).isEqualTo(mNetworkStackPackageName);

        // Then cancel health check
        requestedPackages.clear();
        mService.cancel(mNetworkStackPackageName);

        // Verify that health check is cancelled for network stack
        mService.getRequestedPackages(new RemoteCallback(result -> {
            requestedPackages.addAll(result.getParcelableArrayList(EXTRA_REQUESTED_PACKAGES));
            latch3.countDown();
        }));
        latch3.await();
        assertThat(requestedPackages).isEmpty();
    }

    private String getNetworkStackPackage() {
        Intent intent = new Intent(NETWORK_STACK_CONNECTOR_CLASS);
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        if (comp != null) {
            return comp.getPackageName();
        } else {
            // On Go devices, or any device that does not ship the network stack module.
            // The network stack will live in system_server process, so no need to monitor.
            return null;
        }
    }

    private Intent getExtServiceIntent() {
        ComponentName component = getExtServiceComponentNameLocked();
        if (component == null) {
            fail("Health check service not found");
        }
        Intent intent = new Intent();
        intent.setComponent(component);
        return intent;
    }

    private ComponentName getExtServiceComponentNameLocked() {
        ServiceInfo serviceInfo = getExtServiceInfoLocked();
        if (serviceInfo == null) {
            return null;
        }

        final ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!Manifest.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE
                .equals(serviceInfo.permission)) {
            return null;
        }
        return name;
    }

    private ServiceInfo getExtServiceInfoLocked() {
        final String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            return null;
        }

        final Intent intent = new Intent(ExplicitHealthCheckService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        }
        return resolveInfo.serviceInfo;
    }
}
