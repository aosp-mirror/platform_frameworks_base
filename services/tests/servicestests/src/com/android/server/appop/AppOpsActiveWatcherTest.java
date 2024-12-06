/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CAMERA;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpActiveChangedListener;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Process;
import android.permission.PermissionManager;
import android.virtualdevice.cts.common.VirtualDeviceRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tests app ops version upgrades
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsActiveWatcherTest {

    @Rule
    public VirtualDeviceRule virtualDeviceRule =
            VirtualDeviceRule.withAdditionalPermissions(
                    Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                    Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                    Manifest.permission.GET_APP_OPS_STATS
            );
    private static final long NOTIFICATION_TIMEOUT_MILLIS = 5000;

    @Test
    public void testWatchActiveOps() {
        // Create a mock listener
        final OnOpActiveChangedListener listener = mock(OnOpActiveChangedListener.class);

        // Start watching active ops
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingActive(new String[] {AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO}, getContext().getMainExecutor(), listener);

        // Start the op
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);

        // Verify that we got called for the op being active
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                isNull(), eq(Context.DEVICE_ID_DEFAULT), eq(true), anyInt(), anyInt());

        // This should be the only callback we got
        verifyNoMoreInteractions(listener);

        // Start with a clean slate
        reset(listener);

        // Verify that the op is active
        assertThat(appOpsManager.isOperationActive(AppOpsManager.OP_CAMERA,
                Process.myUid(), getContext().getPackageName())).isTrue();

        // Finish the op
        appOpsManager.finishOp(AppOpsManager.OP_CAMERA);

        // Verify that we got called for the op being active
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()), isNull(),
                eq(Context.DEVICE_ID_DEFAULT), eq(false), anyInt(), anyInt());

        // Verify that the op is not active
        assertThat(appOpsManager.isOperationActive(AppOpsManager.OP_CAMERA,
                Process.myUid(), getContext().getPackageName())).isFalse();

        // This should be the only callback we got
        verifyNoMoreInteractions(listener);

        // Start with a clean slate
        reset(listener);

        // Stop watching active ops
        appOpsManager.stopWatchingActive(listener);

        // Start the op
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);

        // We should not be getting any callbacks
        verifyNoMoreInteractions(listener);

        // Finish the op
        appOpsManager.finishOp(AppOpsManager.OP_CAMERA);

        // We should not be getting any callbacks
        verifyNoMoreInteractions(listener);

        // Start watching op again
        appOpsManager.startWatchingActive(new String[] {AppOpsManager.OPSTR_CAMERA},
                getContext().getMainExecutor(), listener);

        // Start the op
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);

        // We should get the callback again (and since we reset the listener, we therefore expect 1)
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()), isNull(),
                eq(Context.DEVICE_ID_DEFAULT), eq(true), anyInt(), anyInt());

        // Finish up
        appOpsManager.finishOp(AppOpsManager.OP_CAMERA);
        appOpsManager.stopWatchingActive(listener);
    }

    @Test
    public void testWatchActiveOpsForExternalDevice() {
        VirtualDeviceManager.VirtualDevice virtualDevice =
                virtualDeviceRule.createManagedVirtualDevice(
                        new VirtualDeviceParams.Builder()
                                .setDevicePolicy(POLICY_TYPE_CAMERA, DEVICE_POLICY_CUSTOM)
                                .build()
                );

        PermissionManager permissionManager =
                getContext().getSystemService(PermissionManager.class);

        // Unlike runtime permission being automatically granted to the default device, we need to
        // grant camera permission to the external device first before we can start op.
        permissionManager.grantRuntimePermission(
                getContext().getOpPackageName(),
                Manifest.permission.CAMERA,
                virtualDevice.getPersistentDeviceId()
        );

        final OnOpActiveChangedListener listener = mock(OnOpActiveChangedListener.class);
        AttributionSource attributionSource = new AttributionSource(Process.myUid(),
                getContext().getOpPackageName(), getContext().getAttributionTag(),
                virtualDevice.getDeviceId());

        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingActive(new String[]{AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO}, getContext().getMainExecutor(), listener);

        appOpsManager.startOpNoThrow(getContext().getAttributionSource().getToken(),
                AppOpsManager.OP_CAMERA, attributionSource, false, "",
                AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);

        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getOpPackageName()),
                eq(getContext().getAttributionTag()), eq(virtualDevice.getDeviceId()), eq(true),
                eq(AppOpsManager.ATTRIBUTION_FLAGS_NONE),
                eq(AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE));
        verifyNoMoreInteractions(listener);

        appOpsManager.finishOp(getContext().getAttributionSource().getToken(),
                AppOpsManager.OP_CAMERA, attributionSource);

        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getOpPackageName()),
                eq(getContext().getAttributionTag()), eq(virtualDevice.getDeviceId()), eq(false),
                eq(AppOpsManager.ATTRIBUTION_FLAGS_NONE),
                eq(AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE));
        verifyNoMoreInteractions(listener);

        appOpsManager.stopWatchingActive(listener);

        appOpsManager.startOpNoThrow(getContext().getAttributionSource().getToken(),
                AppOpsManager.OP_CAMERA, attributionSource, false, "",
                AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);

        verifyNoMoreInteractions(listener);

        appOpsManager.finishOp(getContext().getAttributionSource().getToken(),
                AppOpsManager.OP_CAMERA, attributionSource);

        verifyNoMoreInteractions(listener);
    }

    @Test
    public void testIsRunning() throws Exception {
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        // Start the op
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);

        assertTrue("Camera should be running", isCameraOn(appOpsManager));

        // Finish the op
        appOpsManager.finishOp(AppOpsManager.OP_CAMERA);

        assertFalse("Camera should not be running", isCameraOn(appOpsManager));
    }

    private boolean isCameraOn(AppOpsManager appOpsManager) {
        List<AppOpsManager.PackageOps> packages
                = appOpsManager.getPackagesForOps(new int[] {AppOpsManager.OP_CAMERA});
        // AppOpsManager can return null when there is no requested data.
        if (packages != null) {
            final int numPackages = packages.size();
            for (int packageInd = 0; packageInd < numPackages; packageInd++) {
                AppOpsManager.PackageOps packageOp = packages.get(packageInd);
                List<AppOpsManager.OpEntry> opEntries = packageOp.getOps();
                if (opEntries != null) {
                    final int numOps = opEntries.size();
                    for (int opInd = 0; opInd < numOps; opInd++) {
                        AppOpsManager.OpEntry opEntry = opEntries.get(opInd);
                        if (opEntry.getOp() == AppOpsManager.OP_CAMERA) {
                            if (opEntry.isRunning()) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }
}
