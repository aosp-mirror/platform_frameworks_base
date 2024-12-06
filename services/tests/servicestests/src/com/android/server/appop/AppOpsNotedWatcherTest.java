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

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpNotedListener;
import android.companion.virtual.VirtualDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Process;
import android.virtualdevice.cts.common.VirtualDeviceRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/**
 * Tests watching noted ops.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsNotedWatcherTest {
    @Rule
    public VirtualDeviceRule mVirtualDeviceRule = VirtualDeviceRule.createDefault();
    private static final long NOTIFICATION_TIMEOUT_MILLIS = 5000;

    @Test
    public void testWatchNotedOps() {
        // Create a mock listener
        final OnOpNotedListener listener = mock(OnOpNotedListener.class);

        // Start watching noted ops
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingNoted(new int[]{AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_CAMERA}, listener);

        // Note some ops
        appOpsManager.noteOp(AppOpsManager.OP_FINE_LOCATION, Process.myUid(),
                getContext().getPackageName());
        appOpsManager.noteOp(AppOpsManager.OP_CAMERA, Process.myUid(),
                getContext().getPackageName());

        // Verify that we got called for the ops being noted
        final InOrder inOrder = inOrder(listener);
        inOrder.verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpNoted(eq(AppOpsManager.OPSTR_FINE_LOCATION),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(getContext().getAttributionTag()), eq(Context.DEVICE_ID_DEFAULT),
                eq(AppOpsManager.OP_FLAG_SELF),
                eq(AppOpsManager.MODE_ALLOWED));
        inOrder.verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpNoted(eq(AppOpsManager.OPSTR_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(getContext().getAttributionTag()), eq(Context.DEVICE_ID_DEFAULT),
                eq(AppOpsManager.OP_FLAG_SELF),
                eq(AppOpsManager.MODE_ALLOWED));

        // Stop watching
        appOpsManager.stopWatchingNoted(listener);

        // This should be the only two callbacks we got
        verifyNoMoreInteractions(listener);

        // Note the op again and verify it isn't being watched
        appOpsManager.noteOp(AppOpsManager.OP_FINE_LOCATION);
        verifyNoMoreInteractions(listener);

        // Start watching again
        appOpsManager.startWatchingNoted(new int[]{AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_CAMERA}, listener);

        // Note the op again
        appOpsManager.noteOp(AppOpsManager.OP_FINE_LOCATION, Process.myUid(),
                getContext().getPackageName());

        // Verify it's watched again
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(2)).onOpNoted(eq(AppOpsManager.OPSTR_FINE_LOCATION),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(getContext().getAttributionTag()), eq(Context.DEVICE_ID_DEFAULT),
                eq(AppOpsManager.OP_FLAG_SELF),
                eq(AppOpsManager.MODE_ALLOWED));

        // Finish up
        appOpsManager.stopWatchingNoted(listener);
    }

    @Test
    public void testWatchNotedOpsForExternalDevice() {
        final AppOpsManager.OnOpNotedListener listener = mock(
                AppOpsManager.OnOpNotedListener.class);
        final VirtualDeviceManager.VirtualDevice virtualDevice =
                mVirtualDeviceRule.createManagedVirtualDevice();
        final int virtualDeviceId = virtualDevice.getDeviceId();
        AttributionSource attributionSource = new AttributionSource(Process.myUid(),
                getContext().getOpPackageName(), getContext().getAttributionTag(),
                virtualDeviceId);

        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingNoted(new int[]{AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_CAMERA}, listener);

        appOpsManager.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION, attributionSource, "message");

        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpNoted(eq(AppOpsManager.OPSTR_FINE_LOCATION),
                eq(Process.myUid()), eq(getContext().getOpPackageName()),
                eq(getContext().getAttributionTag()), eq(virtualDeviceId),
                eq(AppOpsManager.OP_FLAG_SELF), eq(AppOpsManager.MODE_ALLOWED));

        appOpsManager.finishOp(getContext().getAttributionSource().getToken(),
                AppOpsManager.OP_FINE_LOCATION, attributionSource);

        verifyNoMoreInteractions(listener);

        appOpsManager.stopWatchingNoted(listener);

        verifyNoMoreInteractions(listener);
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }
}