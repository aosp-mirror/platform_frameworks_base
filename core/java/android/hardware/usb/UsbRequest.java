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

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.util.Log;

import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.nio.BufferOverflowException;
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

    // From drivers/usb/core/devio.c
    static final int MAX_USBFS_BUFFER_SIZE = 16384;

    // used by the JNI code
    @UnsupportedAppUsage
    private long mNativeContext;

    private UsbEndpoint mEndpoint;

    /** The buffer that is currently being read / written */
    @UnsupportedAppUsage
    private ByteBuffer mBuffer;

    /** The amount of data to read / write when using {@link #queue} */
    @UnsupportedAppUsage
    private int mLength;

    // for client use
    private Object mClientData;

    // Prevent the connection from being finalized
    private UsbDeviceConnection mConnection;

    /**
     * Whether this buffer was {@link #queue(ByteBuffer) queued using the new behavior} or
     * {@link #queue(ByteBuffer, int) queued using the deprecated behavior}.
     */
    private boolean mIsUsingNewQueue;

    /** Temporary buffer than might be used while buffer is enqueued */
    private ByteBuffer mTempBuffer;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Lock for queue, enqueue and dequeue, so a queue operation can be finished by a dequeue
     * operation on a different thread.
     */
    private final Object mLock = new Object();

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
        mConnection = Preconditions.checkNotNull(connection, "connection");

        boolean wasInitialized = native_init(connection, endpoint.getAddress(),
                endpoint.getAttributes(), endpoint.getMaxPacketSize(), endpoint.getInterval());

        if (wasInitialized) {
            mCloseGuard.open("close");
        }

        return wasInitialized;
    }

    /**
     * Releases all resources related to this request.
     */
    public void close() {
        if (mNativeContext != 0) {
            mEndpoint = null;
            mConnection = null;
            native_close();
            mCloseGuard.close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
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
     * <p>For OUT endpoints, the given buffer data will be sent on the endpoint. For IN endpoints,
     * the endpoint will attempt to read the given number of bytes into the specified buffer. If the
     * queueing operation is successful, return true. The result will be returned via
     * {@link UsbDeviceConnection#requestWait}</p>
     *
     * @param buffer the buffer containing the bytes to write, or location to store the results of a
     *               read. Position and array offset will be ignored and assumed to be 0. Limit and
     *               capacity will be ignored. Once the request
     *               {@link UsbDeviceConnection#requestWait() is processed} the position will be set
     *               to the number of bytes read/written.
     * @param length number of bytes to read or write. Before {@value Build.VERSION_CODES#P}, a
     *               value larger than 16384 bytes would be truncated down to 16384. In API
     *               {@value Build.VERSION_CODES#P} and after, any value of length is valid.
     *
     * @return true if the queueing operation succeeded
     *
     * @deprecated Use {@link #queue(ByteBuffer)} instead.
     */
    @Deprecated
    public boolean queue(ByteBuffer buffer, int length) {
        boolean out = (mEndpoint.getDirection() == UsbConstants.USB_DIR_OUT);
        boolean result;

        if (mConnection.getContext().getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.P
                && length > MAX_USBFS_BUFFER_SIZE) {
            length = MAX_USBFS_BUFFER_SIZE;
        }

        synchronized (mLock) {
            // save our buffer for when the request has completed
            mBuffer = buffer;
            mLength = length;

            // Note: On a buffer slice we lost the capacity information about the underlying buffer,
            // hence we cannot check if the access would be a data leak/memory corruption.

            if (buffer.isDirect()) {
                result = native_queue_direct(buffer, length, out);
            } else if (buffer.hasArray()) {
                result = native_queue_array(buffer.array(), length, out);
            } else {
                throw new IllegalArgumentException("buffer is not direct and has no array");
            }
            if (!result) {
                mBuffer = null;
                mLength = 0;
            }
        }

        return result;
    }

    /**
     * Queues the request to send or receive data on its endpoint.
     *
     * <p>For OUT endpoints, the remaining bytes of the buffer will be sent on the endpoint. For IN
     * endpoints, the endpoint will attempt to fill the remaining bytes of the buffer. If the
     * queueing operation is successful, return true. The result will be returned via
     * {@link UsbDeviceConnection#requestWait}</p>
     *
     * @param buffer the buffer containing the bytes to send, or the buffer to fill. The state
     *               of the buffer is undefined until the request is returned by
     *               {@link UsbDeviceConnection#requestWait}. If the request failed the buffer
     *               will be unchanged; if the request succeeded the position of the buffer is
     *               incremented by the number of bytes sent/received. Before
     *               {@value Build.VERSION_CODES#P}, a buffer of length larger than 16384 bytes
     *               would throw IllegalArgumentException. In API {@value Build.VERSION_CODES#P}
     *               and after, any size buffer is valid.
     *
     * @return true if the queueing operation succeeded
     */
    public boolean queue(@Nullable ByteBuffer buffer) {
        // Request need to be initialized
        Preconditions.checkState(mNativeContext != 0, "request is not initialized");

        // Request can not be currently queued
        Preconditions.checkState(!mIsUsingNewQueue, "this request is currently queued");

        boolean isSend = (mEndpoint.getDirection() == UsbConstants.USB_DIR_OUT);
        boolean wasQueued;

        synchronized (mLock) {
            mBuffer = buffer;

            if (buffer == null) {
                // Null buffers enqueue empty USB requests which is supported
                mIsUsingNewQueue = true;
                wasQueued = native_queue(null, 0, 0);
            } else {
                if (mConnection.getContext().getApplicationInfo().targetSdkVersion
                        < Build.VERSION_CODES.P) {
                    // Can only send/receive MAX_USBFS_BUFFER_SIZE bytes at once
                    Preconditions.checkArgumentInRange(buffer.remaining(), 0, MAX_USBFS_BUFFER_SIZE,
                            "number of remaining bytes");
                }

                // Can not receive into read-only buffers.
                Preconditions.checkArgument(!(buffer.isReadOnly() && !isSend), "buffer can not be "
                        + "read-only when receiving data");

                if (!buffer.isDirect()) {
                    mTempBuffer = ByteBuffer.allocateDirect(mBuffer.remaining());

                    if (isSend) {
                        // Copy buffer into temporary buffer
                        mBuffer.mark();
                        mTempBuffer.put(mBuffer);
                        mTempBuffer.flip();
                        mBuffer.reset();
                    }

                    // Send/Receive into the temp buffer instead
                    buffer = mTempBuffer;
                }

                mIsUsingNewQueue = true;
                wasQueued = native_queue(buffer, buffer.position(), buffer.remaining());
            }
        }

        if (!wasQueued) {
            mIsUsingNewQueue = false;
            mTempBuffer = null;
            mBuffer = null;
        }

        return wasQueued;
    }

    /* package */ void dequeue(boolean useBufferOverflowInsteadOfIllegalArg) {
        boolean isSend = (mEndpoint.getDirection() == UsbConstants.USB_DIR_OUT);
        int bytesTransferred;

        synchronized (mLock) {
            if (mIsUsingNewQueue) {
                bytesTransferred = native_dequeue_direct();
                mIsUsingNewQueue = false;

                if (mBuffer == null) {
                    // Nothing to do
                } else if (mTempBuffer == null) {
                    mBuffer.position(mBuffer.position() + bytesTransferred);
                } else {
                    mTempBuffer.limit(bytesTransferred);

                    // The user might have modified mBuffer which might make put/position fail.
                    // Changing the buffer while a request is in flight is not supported. Still,
                    // make sure to free mTempBuffer correctly.
                    try {
                        if (isSend) {
                            mBuffer.position(mBuffer.position() + bytesTransferred);
                        } else {
                            // Copy temp buffer back into original buffer
                            mBuffer.put(mTempBuffer);
                        }
                    } finally {
                        mTempBuffer = null;
                    }
                }
            } else {
                if (mBuffer.isDirect()) {
                    bytesTransferred = native_dequeue_direct();
                } else {
                    bytesTransferred = native_dequeue_array(mBuffer.array(), mLength, isSend);
                }
                if (bytesTransferred >= 0) {
                    int bytesToStore = Math.min(bytesTransferred, mLength);
                    try {
                        mBuffer.position(bytesToStore);
                    } catch (IllegalArgumentException e) {
                        if (useBufferOverflowInsteadOfIllegalArg) {
                            Log.e(TAG, "Buffer " + mBuffer + " does not have enough space to read "
                                    + bytesToStore + " bytes", e);
                            throw new BufferOverflowException();
                        } else {
                            throw e;
                        }
                    }
                }
            }

            mBuffer = null;
            mLength = 0;
        }
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
    private native boolean native_queue(ByteBuffer buffer, int offset, int length);
    private native boolean native_queue_array(byte[] buffer, int length, boolean out);
    private native int native_dequeue_array(byte[] buffer, int length, boolean out);
    private native boolean native_queue_direct(ByteBuffer buffer, int length, boolean out);
    private native int native_dequeue_direct();
    private native boolean native_cancel();
}
