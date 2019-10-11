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

package com.android.server.appops;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpNotedListener;
import android.content.Context;
import android.os.Process;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;


import static org.junit.Assert.fail;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests watching noted ops.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsNotedWatcherTest {

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
                .times(1)).onOpNoted(eq(AppOpsManager.OP_FINE_LOCATION),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(AppOpsManager.MODE_ALLOWED));
        inOrder.verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpNoted(eq(AppOpsManager.OP_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(AppOpsManager.MODE_ALLOWED));

        // Stop watching
        appOpsManager.stopWatchingNoted(listener);

        // This should be the only two callbacks we got
        verifyNoMoreInteractions(listener);
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }
}