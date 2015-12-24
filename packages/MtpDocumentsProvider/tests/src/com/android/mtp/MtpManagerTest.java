/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RealDeviceTest
public class MtpManagerTest extends InstrumentationTestCase {

    private static final int TIMEOUT_MS = 1000;
    UsbManager mUsbManager;
    MtpManager mManager;
    UsbDevice mUsbDevice;
    int mRequest;

    @Override
    public void setUp() throws Exception {
        mUsbManager = getContext().getSystemService(UsbManager.class);
        mManager = new MtpManager(getContext());
        mUsbDevice = TestUtil.setupMtpDevice(getInstrumentation(), mUsbManager, mManager);
    }

    @Override
    public void tearDown() throws IOException {
        mManager.closeDevice(mUsbDevice.getDeviceId());
    }

    @Override
    public TestResultInstrumentation getInstrumentation() {
        return (TestResultInstrumentation) super.getInstrumentation();
    }

    public void testCancelEvent() throws Exception {
        final CancellationSignal signal = new CancellationSignal();
        final FutureTask<Boolean> future = new FutureTask<Boolean>(
                new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws IOException {
                        try {
                            while (true) {
                                mManager.readEvent(mUsbDevice.getDeviceId(), signal);
                            }
                        } catch (OperationCanceledException exception) {
                            return true;
                        }
                    }
                });
        final Thread thread = new Thread(future);
        thread.start();
        Thread.sleep(TIMEOUT_MS);
        signal.cancel();
        assertTrue(future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private Context getContext() {
        return getInstrumentation().getContext();
    }
}
