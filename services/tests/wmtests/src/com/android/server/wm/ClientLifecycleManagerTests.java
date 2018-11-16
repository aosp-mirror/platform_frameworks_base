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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import android.app.IApplicationThread;
import android.app.servertransaction.ClientTransaction;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:ClientLifecycleManagerTests
 */
@SmallTest
@Presubmit
public class ClientLifecycleManagerTests {

    @Test
    public void testScheduleAndRecycleBinderClientTransaction() throws Exception {
        ClientTransaction item = spy(ClientTransaction.obtain(mock(IApplicationThread.class),
                new Binder()));

        ClientLifecycleManager clientLifecycleManager = new ClientLifecycleManager();
        clientLifecycleManager.scheduleTransaction(item);

        verify(item, times(1)).recycle();
    }

    @Test
    public void testScheduleNoRecycleNonBinderClientTransaction() throws Exception {
        ClientTransaction item = spy(ClientTransaction.obtain(mock(IApplicationThread.Stub.class),
                new Binder()));

        ClientLifecycleManager clientLifecycleManager = new ClientLifecycleManager();
        clientLifecycleManager.scheduleTransaction(item);

        verify(item, times(0)).recycle();
    }
}
