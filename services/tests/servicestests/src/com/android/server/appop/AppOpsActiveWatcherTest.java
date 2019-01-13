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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpActiveChangedListener;
import android.content.Context;
import android.os.Process;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tests app ops version upgrades
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsActiveWatcherTest {

    private static final long NOTIFICATION_TIMEOUT_MILLIS = 5000;

    @Test
    public void testWatchActiveOps() {
        // Create a mock listener
        final OnOpActiveChangedListener listener = mock(OnOpActiveChangedListener.class);

        // Start watching active ops
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingActive(new int[] {AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO}, listener);

        // Start the op
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);

        // Verify that we got called for the op being active
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OP_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()), eq(true));

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
                .times(1)).onOpActiveChanged(eq(AppOpsManager.OP_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()), eq(false));

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