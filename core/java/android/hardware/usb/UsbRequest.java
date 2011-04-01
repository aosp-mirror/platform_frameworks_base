/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.hardware.usb;

import android.util.Log;

import java.nio.ByteBuffer;

/**
 * A class representing USB request packet.
 * This can be used for both reading and writing data to or from a
 * {@link android.hardware.usb.UsbDeviceConnection}.
 * UsbRequests can be used to transfer data on bulk and interrupt endpoints.
 * Requests on bulk endpoints can be sent synchronously via {@link UsbDeviceConnection#bulkTransfer}
 * or asynchronously via {@link #queue} and {@link UsbDeviceConnection#requestWait}.
 * Requests on interrupt endpoints are only send and received asynchronously.
 *
 * <p>Requests on endpoint zero are not supported by this class;
 * use {@link UsbDeviceConnection#controlTransfer} for endpoint zero requests instead.
 */
public class UsbRequest {

    private static final String TAG = "UsbRequest";

    // used by the JNI code
    private int mNativeContext;

    private UsbEndpoint mEndpoint;

    // for temporarily saving current buffer across queue and dequeue
    private ByteBuffer mBuffer;
    private int mLength;

    // for client use
    private Object mClientData;

    public UsbRequest() {
    }

    /**
     * Initializes the request so it can read or write data on the given endpoint.
     * Whether the request allows reading or writing depends on the direction of the endpoint.
     *
     * @param endpoint the endpoint to be used for this request.
     * @return true if the request was successfully opened.
     */
    public boolean initialize(UsbDeviceConnection connection, UsbEndpoint endpoint) {
        mEndpoint = endpoint;
        return native_init(connection, endpoint.getAddress(), endpoint.getAttributes(),
                endpoint.getMaxPacketSize(), endpoint.getInterval());
    }

    /**
     * Releases all resources related to this request.
     */
    public void close() {
        mEndpoint = null;
        native_close();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mEndpoint != null) {
                Log.v(TAG, "endpoint still open in finalize(): " + this);
                close();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Returns the endpoint for the request, or null if the request is not opened.
     *
     * @return the request's endpoint
     */
    public UsbEndpoint getEndpoint() {
        return mEndpoint;
    }

    /**
     * Returns the client data for the request.
     * This can be used in conjunction with {@link #setClientData}
     * to associate another object with this request, which can be useful for
     * maintaining state between calls to {@link #queue} and
     * {@link android.hardware.usb.UsbDeviceConnection#requestWait}
     *
     * @return the client data for the request
     */
    public Object getClientData() {
        return mClientData;
    }

    /**
     * Sets the client data for the request.
     * This can be used in conjunction with {@link #getClientData}
     * to associate another object with this request, which can be useful for
     * maintaining state between calls to {@link #queue} and
     * {@link android.hardware.usb.UsbDeviceConnection#requestWait}
     *
     * @param data the client data for the request
     */
    public void setClientData(Object data) {
        mClientData = data;
    }

    /**
     * Queues the request to send or receive data on its endpoint.
     * For OUT endpoints, the given buffer data will be sent on the endpoint.
     * For IN endpoints, the endpoint will attempt to read the given number of bytes
     * into the specified buffer.
     * If the queueing operation is successful, we return true and the result will be
     * returned via {@link android.hardware.usb.UsbDeviceConnection#requestWait}
     *
     * @param buffer the buffer containing the bytes to write, or location to store
     * the results of a read
     * @param length number of bytes to read or write
     * @return true if the queueing operation succeeded
     */
    public boolean queue(ByteBuffer buffer, int length) {
        boolean out = (mEndpoint.getDirection() == UsbConstants.USB_DIR_OUT);
        boolean result;
        if (buffer.isDirect()) {
            result = native_queue_direct(buffer, length, out);
        } else if (buffer.hasArray()) {
            result = native_queue_array(buffer.array(), length, out);
        } else {
            throw new IllegalArgumentException("buffer is not direct and has no array");
        }
        if (result) {
            // save our buffer for when the request has completed
            mBuffer = buffer;
            mLength = length;
        }
        return result;
    }

    /* package */ void dequeue() {
        boolean out = (mEndpoint.getDirection() == UsbConstants.USB_DIR_OUT);
        if (mBuffer.isDirect()) {
            native_dequeue_direct();
        } else {
            native_dequeue_array(mBuffer.array(), mLength, out);
        }
        mBuffer = null;
        mLength = 0;
    }

    /**
     * Cancels a pending queue operation.
     *
     * @return true if cancelling succeeded
     */
    public boolean cancel() {
        return native_cancel();
    }

    private native boolean native_init(UsbDeviceConnection connection, int ep_address,
            int ep_attributes, int ep_max_packet_size, int ep_interval);
    private native void native_close();
    private native boolean native_queue_array(byte[] buffer, int length, boolean out);
    private native void native_dequeue_array(byte[] buffer, int length, boolean out);
    private native boolean native_queue_direct(ByteBuffer buffer, int length, boolean out);
    private native void native_dequeue_direct();
    private native boolean native_cancel();
}
