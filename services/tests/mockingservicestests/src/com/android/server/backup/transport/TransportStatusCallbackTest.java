/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.backup.transport;

import static com.google.common.truth.Truth.assertThat;

import android.app.backup.BackupTransport;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class TransportStatusCallbackTest {
    private static final int OPERATION_TIMEOUT_MILLIS = 10;
    private static final int OPERATION_COMPLETE_STATUS = 123;

    private TransportStatusCallback mTransportStatusCallback;

    @Before
    public void setUp() {
        mTransportStatusCallback = new TransportStatusCallback();
    }

    @Test
    public void testGetOperationStatus_withPreCompletedOperation_returnsStatus() throws Exception {
        mTransportStatusCallback.onOperationCompleteWithStatus(OPERATION_COMPLETE_STATUS);

        int result = mTransportStatusCallback.getOperationStatus();

        assertThat(result).isEqualTo(OPERATION_COMPLETE_STATUS);
    }

    @Test
    public void testGetOperationStatus_completeOperation_returnsStatus() throws Exception {
        Thread thread = new Thread(() -> {
            int result = mTransportStatusCallback.getOperationStatus();
            assertThat(result).isEqualTo(OPERATION_COMPLETE_STATUS);
        });
        thread.start();

        mTransportStatusCallback.onOperationCompleteWithStatus(OPERATION_COMPLETE_STATUS);

        thread.join();
    }

    @Test
    public void testGetOperationStatus_operationTimesOut_returnsError() throws Exception {
        TransportStatusCallback callback = new TransportStatusCallback(OPERATION_TIMEOUT_MILLIS);

        int result = callback.getOperationStatus();

        assertThat(result).isEqualTo(BackupTransport.TRANSPORT_ERROR);
    }
}
