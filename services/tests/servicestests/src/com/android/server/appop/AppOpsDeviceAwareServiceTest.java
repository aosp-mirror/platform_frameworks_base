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

package com.android.server.appop;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CAMERA;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.app.AppOpsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.AttributionSource;
import android.os.Process;
import android.permission.PermissionManager;
import android.permission.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.testing.TestableContext;
import android.virtualdevice.cts.common.VirtualDeviceRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
public class AppOpsDeviceAwareServiceTest {

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getInstrumentation().getTargetContext());

    @Rule
    public VirtualDeviceRule virtualDeviceRule =
            VirtualDeviceRule.withAdditionalPermissions(
                    Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                    Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                    Manifest.permission.CREATE_VIRTUAL_DEVICE,
                    Manifest.permission.GET_APP_OPS_STATS);

    private static final String ATTRIBUTION_TAG_1 = "attributionTag1";
    private static final String ATTRIBUTION_TAG_2 = "attributionTag2";
    private final AppOpsManager mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
    private final PermissionManager mPermissionManager =
            mContext.getSystemService(PermissionManager.class);

    private VirtualDeviceManager.VirtualDevice mVirtualDevice;

    @Before
    public void setUp() {
        mVirtualDevice =
                virtualDeviceRule.createManagedVirtualDevice(
                        new VirtualDeviceParams.Builder()
                                .setDevicePolicy(POLICY_TYPE_CAMERA, DEVICE_POLICY_CUSTOM)
                                .build());

        mPermissionManager.grantRuntimePermission(
                mContext.getOpPackageName(),
                Manifest.permission.CAMERA,
                mVirtualDevice.getPersistentDeviceId());

        mPermissionManager.grantRuntimePermission(
                mContext.getOpPackageName(),
                Manifest.permission.CAMERA,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);
    }

    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_ID_IN_OP_PROXY_INFO_ENABLED)
    @Test
    public void noteProxyOp_proxyAppOnDefaultDevice() {
        AppOpsManager.OpEventProxyInfo proxyInfo =
                noteProxyOpWithDeviceId(TestableContext.DEVICE_ID_DEFAULT);
        assertThat(proxyInfo.getDeviceId())
                .isEqualTo(VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);
    }

    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_ID_IN_OP_PROXY_INFO_ENABLED)
    @Test
    public void noteProxyOp_proxyAppOnRemoteDevice() {
        AppOpsManager.OpEventProxyInfo proxyInfo =
                noteProxyOpWithDeviceId(mVirtualDevice.getDeviceId());
        assertThat(proxyInfo.getDeviceId()).isEqualTo(mVirtualDevice.getPersistentDeviceId());
    }

    private AppOpsManager.OpEventProxyInfo noteProxyOpWithDeviceId(int proxyAppDeviceId) {
        AttributionSource proxiedAttributionSource =
                new AttributionSource.Builder(Process.myUid())
                        .setPackageName(mContext.getOpPackageName())
                        .setAttributionTag(ATTRIBUTION_TAG_2)
                        .setDeviceId(mVirtualDevice.getDeviceId())
                        .build();

        AttributionSource proxyAttributionSource =
                new AttributionSource.Builder(Process.myUid())
                        .setPackageName(mContext.getOpPackageName())
                        .setAttributionTag(ATTRIBUTION_TAG_1)
                        .setDeviceId(proxyAppDeviceId)
                        .setNextAttributionSource(proxiedAttributionSource)
                        .build();

        int mode = mAppOpsManager.noteProxyOp(OP_CAMERA, proxyAttributionSource, null, false);
        assertThat(mode).isEqualTo(AppOpsManager.MODE_ALLOWED);

        List<AppOpsManager.PackageOps> packagesOps =
                mAppOpsManager.getPackagesForOps(
                        new String[] {AppOpsManager.OPSTR_CAMERA},
                        mVirtualDevice.getPersistentDeviceId());

        AppOpsManager.PackageOps packageOps =
                packagesOps.stream()
                        .filter(
                                pkg ->
                                        Objects.equals(
                                                pkg.getPackageName(), mContext.getOpPackageName()))
                        .findFirst()
                        .orElseThrow();

        AppOpsManager.OpEntry opEntry =
                packageOps.getOps().stream()
                        .filter(op -> op.getOp() == OP_CAMERA)
                        .findFirst()
                        .orElseThrow();

        return opEntry.getLastProxyInfo(OP_FLAGS_ALL_TRUSTED);
    }
}
