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
import android.mtp.MtpConstants;
import android.mtp.MtpEvent;
import android.mtp.MtpObjectInfo;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RealDeviceTest
public class MtpManagerTest extends InstrumentationTestCase {
    private static final int TIMEOUT_MS = 1000;
    UsbManager mUsbManager;
    MtpManager mManager;
    UsbDevice mUsbDevice;

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
        SystemClock.sleep(TIMEOUT_MS);
        signal.cancel();
        assertTrue(future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    public void testOperationsSupported() {
        final MtpDeviceRecord[] records = mManager.getDevices();
        assertEquals(1, records.length);
        assertNotNull(records[0].operationsSupported);
        getInstrumentation().show(Arrays.toString(records[0].operationsSupported));
    }

    public void testEventsSupported() {
        final MtpDeviceRecord[] records = mManager.getDevices();
        assertEquals(1, records.length);
        assertNotNull(records[0].eventsSupported);
        getInstrumentation().show(Arrays.toString(records[0].eventsSupported));
    }

    public void testDeviceKey() {
        final MtpDeviceRecord[] records = mManager.getDevices();
        assertEquals(1, records.length);
        assertNotNull(records[0].deviceKey);
        getInstrumentation().show("deviceKey: " + records[0].deviceKey);
    }

    public void testEventObjectAdded() throws Exception {
        while (true) {
            getInstrumentation().show("Please take a photo by using connected MTP device.");
            final CancellationSignal signal = new CancellationSignal();
            MtpEvent event = mManager.readEvent(mUsbDevice.getDeviceId(), signal);
            if (event.getEventCode() != MtpEvent.EVENT_OBJECT_ADDED) {
                continue;
            }
            assertTrue(event.getObjectHandle() != 0);
            break;
        }
    }

    public void testCreateDocumentAndGetPartialObject() throws Exception {
        int storageId = 0;
        for (final MtpDeviceRecord record : mManager.getDevices()) {
            if (record.deviceId == mUsbDevice.getDeviceId()) {
                storageId = record.roots[0].mStorageId;
                break;
            }
        }
        assertTrue("Valid storage not found.", storageId != 0);

        final String testFileName = "MtpManagerTest_testFile.txt";
        for (final int handle : mManager.getObjectHandles(
                mUsbDevice.getDeviceId(), storageId, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN)) {
            if (mManager.getObjectInfo(mUsbDevice.getDeviceId(), handle)
                    .getName().equals(testFileName)) {
                mManager.deleteDocument(mUsbDevice.getDeviceId(), handle);
                break;
            }
        }

        final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
        final byte[] expectedBytes = "Hello Android!".getBytes("ascii");
        try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                new ParcelFileDescriptor.AutoCloseOutputStream(fds[1])) {
            stream.write(expectedBytes);
        }
        final int objectHandle = mManager.createDocument(
                mUsbDevice.getDeviceId(),
                new MtpObjectInfo.Builder()
                        .setStorageId(storageId)
                        .setName(testFileName)
                        .setCompressedSize(expectedBytes.length)
                        .setFormat(MtpConstants.FORMAT_TEXT)
                        .build(),
                fds[0]);
        final byte[] bytes = new byte[100];
        assertEquals(5, mManager.getPartialObject(
                mUsbDevice.getDeviceId(), objectHandle, 0, 5, bytes));
        assertEquals("Hello", new String(bytes, 0, 5, "ascii"));
        assertEquals(8, mManager.getPartialObject(
                mUsbDevice.getDeviceId(), objectHandle, 6, 100, bytes));
        assertEquals("Android!", new String(bytes, 0, 8, "ascii"));
    }

    private Context getContext() {
        return getInstrumentation().getContext();
    }
}
