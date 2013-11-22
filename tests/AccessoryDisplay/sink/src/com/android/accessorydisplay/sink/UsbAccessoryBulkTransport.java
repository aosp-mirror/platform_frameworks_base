/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.sink;

import com.android.accessorydisplay.common.Logger;
import com.android.accessorydisplay.common.Transport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;

/**
 * Sends or receives messages using bulk endpoints associated with a {@link UsbDevice}
 * that represents a USB accessory.
 */
public class UsbAccessoryBulkTransport extends Transport {
    private static final int TIMEOUT_MILLIS = 1000;

    private UsbDeviceConnection mConnection;
    private UsbEndpoint mBulkInEndpoint;
    private UsbEndpoint mBulkOutEndpoint;

    public UsbAccessoryBulkTransport(Logger logger, UsbDeviceConnection connection,
            UsbEndpoint bulkInEndpoint, UsbEndpoint bulkOutEndpoint) {
        super(logger, 16384);
        mConnection = connection;
        mBulkInEndpoint = bulkInEndpoint;
        mBulkOutEndpoint = bulkOutEndpoint;
    }

    @Override
    protected void ioClose() {
        mConnection = null;
        mBulkInEndpoint = null;
        mBulkOutEndpoint = null;
    }

    @Override
    protected int ioRead(byte[] buffer, int offset, int count) throws IOException {
        if (mConnection == null) {
            throw new IOException("Connection was closed.");
        }
        return mConnection.bulkTransfer(mBulkInEndpoint, buffer, offset, count, -1);
    }

    @Override
    protected void ioWrite(byte[] buffer, int offset, int count) throws IOException {
        if (mConnection == null) {
            throw new IOException("Connection was closed.");
        }
        int result = mConnection.bulkTransfer(mBulkOutEndpoint,
                buffer, offset, count, TIMEOUT_MILLIS);
        if (result < 0) {
            throw new IOException("Bulk transfer failed.");
        }
    }
}
