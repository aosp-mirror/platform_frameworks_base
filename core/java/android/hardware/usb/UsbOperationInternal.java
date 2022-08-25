/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbPort;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
/**
 * UsbOperationInternal allows UsbPort to support both synchronous and
 * asynchronous function irrespective of whether the underlying hal
 * method is synchronous or asynchronous.
 *
 * @hide
 */
public final class UsbOperationInternal extends IUsbOperationInternal.Stub {
    private static final String TAG = "UsbPortStatus";
    private final int mOperationID;
    // Cached portId.
    private final String mId;
    // True implies operation did not timeout.
    private boolean mOperationComplete;
    private boolean mAsynchronous = false;
    private Executor mExecutor;
    private Consumer<Integer> mConsumer;
    private int mResult = 0;
    private @UsbOperationStatus int mStatus;
    final ReentrantLock mLock = new ReentrantLock();
    final Condition mOperationWait  = mLock.newCondition();
    // Maximum time the caller has to wait for onOperationComplete to be called.
    private static final int USB_OPERATION_TIMEOUT_MSECS = 5000;

    /**
     * The requested operation was successfully completed.
     * Returned in {@link onOperationComplete} and {@link getStatus}.
     */
    public static final int USB_OPERATION_SUCCESS = 0;

    /**
     * The requested operation failed due to internal error.
     * Returned in {@link onOperationComplete} and {@link getStatus}.
     */
    public static final int USB_OPERATION_ERROR_INTERNAL = 1;

    /**
     * The requested operation failed as it's not supported.
     * Returned in {@link onOperationComplete} and {@link getStatus}.
     */
    public static final int USB_OPERATION_ERROR_NOT_SUPPORTED = 2;

    /**
     * The requested operation failed as it's not supported.
     * Returned in {@link onOperationComplete} and {@link getStatus}.
     */
    public static final int USB_OPERATION_ERROR_PORT_MISMATCH = 3;

    @IntDef(prefix = { "USB_OPERATION_" }, value = {
            USB_OPERATION_SUCCESS,
            USB_OPERATION_ERROR_INTERNAL,
            USB_OPERATION_ERROR_NOT_SUPPORTED,
            USB_OPERATION_ERROR_PORT_MISMATCH
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbOperationStatus{}

    UsbOperationInternal(int operationID, String id,
        Executor executor, Consumer<Integer> consumer) {
        this.mOperationID = operationID;
        this.mId = id;
        this.mExecutor = executor;
        this.mConsumer = consumer;
        this.mAsynchronous = true;
    }

    UsbOperationInternal(int operationID, String id) {
        this.mOperationID = operationID;
        this.mId = id;
    }

    /**
     * Hal glue layer would directly call this function when the requested
     * operation is complete.
     */
    @Override
    public void onOperationComplete(@UsbOperationStatus int status) {
        mLock.lock();
        try {
            mOperationComplete = true;
            mStatus = status;
            Log.i(TAG, "Port:" + mId + " opID:" + mOperationID + " status:" + mStatus);
            if (mAsynchronous) {
                switch (mStatus) {
                    case USB_OPERATION_SUCCESS:
                        mResult = UsbPort.RESET_USB_PORT_SUCCESS;
                        break;
                    case USB_OPERATION_ERROR_INTERNAL:
                        mResult = UsbPort.RESET_USB_PORT_ERROR_INTERNAL;
                        break;
                    case USB_OPERATION_ERROR_NOT_SUPPORTED:
                        mResult = UsbPort.RESET_USB_PORT_ERROR_NOT_SUPPORTED;
                        break;
                    case USB_OPERATION_ERROR_PORT_MISMATCH:
                        mResult = UsbPort.RESET_USB_PORT_ERROR_PORT_MISMATCH;
                        break;
                    default:
                        mResult = UsbPort.RESET_USB_PORT_ERROR_OTHER;
                }
                mExecutor.execute(() -> mConsumer.accept(mResult));
            } else {
                mOperationWait.signal();
            }
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Caller invokes this function to wait for the operation to be complete.
     */
    public void waitForOperationComplete() {
        mLock.lock();
        try {
            long now = System.currentTimeMillis();
            long deadline = now + USB_OPERATION_TIMEOUT_MSECS;
            // Wait in loop to overcome spurious wakeups.
            do {
                mOperationWait.await(deadline - System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS);
            } while (!mOperationComplete && System.currentTimeMillis() < deadline);
            if (!mOperationComplete) {
                Log.e(TAG, "Port:" + mId + " opID:" + mOperationID
                        + " operationComplete not received in " + USB_OPERATION_TIMEOUT_MSECS
                        + "msecs");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Port:" + mId + " opID:" + mOperationID + " operationComplete interrupted");
        } finally {
            mLock.unlock();
        }
    }

    public @UsbOperationStatus int getStatus() {
        return mOperationComplete ? mStatus : USB_OPERATION_ERROR_INTERNAL;
    }
}
