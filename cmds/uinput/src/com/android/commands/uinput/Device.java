/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.commands.uinput;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.os.SomeArgs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;

import src.com.android.commands.uinput.InputAbsInfo;

/**
 * Device class defines uinput device interfaces of device operations, for device open, close,
 * configuration, events injection.
 */
public class Device {
    private static final String TAG = "UinputDevice";

    private static final int MSG_OPEN_UINPUT_DEVICE = 1;
    private static final int MSG_CLOSE_UINPUT_DEVICE = 2;
    private static final int MSG_INJECT_EVENT = 3;
    private static final int MSG_SYNC_EVENT = 4;

    private final int mId;
    private final HandlerThread mThread;
    private final DeviceHandler mHandler;
    // mConfiguration is sparse array of ioctl code and array of values.
    private final SparseArray<int[]> mConfiguration;
    private final SparseArray<InputAbsInfo> mAbsInfo;
    private final OutputStream mOutputStream;
    private final Object mCond = new Object();
    private long mTimeToSendNanos;

    static {
        System.loadLibrary("uinputcommand_jni");
    }

    private static native long nativeOpenUinputDevice(String name, int id, int vendorId,
            int productId, int versionId, int bus, int ffEffectsMax, String port,
            DeviceCallback callback);
    private static native void nativeCloseUinputDevice(long ptr);
    private static native void nativeInjectEvent(long ptr, long timestampMicros, int type, int code,
                                                 int value);
    private static native void nativeConfigure(int handle, int code, int[] configs);
    private static native void nativeSetAbsInfo(int handle, int axisCode, Parcel axisParcel);
    private static native int nativeGetEvdevEventTypeByLabel(String label);
    private static native int nativeGetEvdevEventCodeByLabel(int type, String label);
    private static native int nativeGetEvdevInputPropByLabel(String label);

    public Device(int id, String name, int vendorId, int productId, int versionId, int bus,
            SparseArray<int[]> configuration, int ffEffectsMax,
            SparseArray<InputAbsInfo> absInfo, String port) {
        mId = id;
        mThread = new HandlerThread("UinputDeviceHandler");
        mThread.start();
        mHandler = new DeviceHandler(mThread.getLooper());
        mConfiguration = configuration;
        mAbsInfo = absInfo;
        mOutputStream = System.out;
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = id;
        args.argi2 = vendorId;
        args.argi3 = productId;
        args.argi4 = versionId;
        args.argi5 = bus;
        args.argi6 = ffEffectsMax;
        if (name != null) {
            args.arg1 = name;
        } else {
            args.arg1 = id + ":" + vendorId + ":" + productId;
        }
        if (port != null) {
            args.arg2 = port;
        } else {
            args.arg2 = "uinput:" + id + ":" + vendorId + ":" + productId;
        }

        mHandler.obtainMessage(MSG_OPEN_UINPUT_DEVICE, args).sendToTarget();
        mTimeToSendNanos = SystemClock.uptimeNanos();
    }

    private long getTimeToSendMillis() {
        // Since we can only specify delays in milliseconds but evemu timestamps are in
        // microseconds, we have to round up the delays to avoid setting event timestamps
        // which are in the future (which the kernel would silently reject and replace with
        // the current time).
        //
        // This should be the same as (long) Math.ceil(mTimeToSendNanos / 1_000_000.0), except
        // without the precision loss that comes from converting from long to double and back.
        return mTimeToSendNanos / 1_000_000 + ((mTimeToSendNanos % 1_000_000 > 0) ? 1 : 0);
    }

    /**
     * Inject uinput events to device
     *
     * @param events  Array of raw uinput events.
     * @param offsetMicros The difference in microseconds between the timestamps of the previous
     *                     batch of events injected and this batch. If set to -1, the current
     *                     timestamp will be used.
     */
    public void injectEvent(int[] events, long offsetMicros) {
        // if two messages are sent at identical time, they will be processed in order received
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = events;
        args.argl1 = offsetMicros;
        args.argl2 = mTimeToSendNanos;
        Message msg = mHandler.obtainMessage(MSG_INJECT_EVENT, args);
        mHandler.sendMessageAtTime(msg, getTimeToSendMillis());
    }

    /**
     * Delay subsequent device activity by the specified amount of time.
     *
     * <p>Note that although the delay is specified in nanoseconds, due to limitations of {@link
     * Handler}'s API, scheduling only occurs with millisecond precision. When scheduling an
     * injection or sync, the time at which it is scheduled will be rounded up to the nearest
     * millisecond. While this means that a particular injection cannot be scheduled precisely,
     * rounding errors will not accumulate over time. For example, if five injections are scheduled
     * with a delay of 1,200,000ns before each one, the total delay will be 6ms, as opposed to the
     * 10ms it would have been if each individual delay had been rounded up (as {@link EvemuParser}
     * would otherwise have to do to avoid sending timestamps that are in the future).
     *
     * @param delayNanos  Time to delay in unit of nanoseconds.
     */
    public void addDelayNanos(long delayNanos) {
        mTimeToSendNanos += delayNanos;
    }

    /**
     * Synchronize the uinput command queue by writing a sync response with the provided syncToken
     * to the output stream when this event is processed.
     *
     * @param syncToken  The token for this sync command.
     */
    public void syncEvent(String syncToken) {
        mHandler.sendMessageAtTime(
                mHandler.obtainMessage(MSG_SYNC_EVENT, syncToken), getTimeToSendMillis());
    }

    /**
     * Close an uinput device.
     *
     */
    public void close() {
        Message msg = mHandler.obtainMessage(MSG_CLOSE_UINPUT_DEVICE);
        mHandler.sendMessageAtTime(
                msg, Math.max(SystemClock.uptimeMillis(), getTimeToSendMillis()) + 1);
        try {
            synchronized (mCond) {
                mCond.wait();
            }
        } catch (InterruptedException ignore) {
        }
    }

    private class DeviceHandler extends Handler {
        private long mPtr;
        private long mLastInjectTimestampMicros = -1;
        private int mBarrierToken;

        DeviceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OPEN_UINPUT_DEVICE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String name = (String) args.arg1;
                    mPtr = nativeOpenUinputDevice(name, args.argi1 /* id */,
                            args.argi2 /* vendorId */, args.argi3 /* productId */,
                            args.argi4 /* versionId */, args.argi5 /* bus */,
                            args.argi6 /* ffEffectsMax */, (String) args.arg2 /* port */,
                            new DeviceCallback());
                    if (mPtr == 0) {
                        RuntimeException ex = new RuntimeException(
                                "Could not create uinput device \"" + name + "\"");
                        Log.e(TAG, "Couldn't create uinput device, exiting.", ex);
                        throw ex;
                    }
                    break;
                }
                case MSG_INJECT_EVENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    if (mPtr == 0) {
                        args.recycle();
                        break;
                    }
                    long offsetMicros = args.argl1;
                    if (mLastInjectTimestampMicros == -1 || offsetMicros == -1) {
                        // There's often a delay of a few milliseconds between the time specified to
                        // Handler.sendMessageAtTime and the handler actually being called, due to
                        // the way threads are scheduled. We don't take this into account when
                        // calling addDelayNanos between the first batch of event injections (when
                        // we set the "base timestamp" from which all others will be offset) and the
                        // second batch, meaning that the actual time between the handler calls for
                        // those batches may be less than the offset between their timestamps. When
                        // that happens, we would pass a timestamp for the second batch that's
                        // actually in the future. The kernel's uinput API rejects timestamps that
                        // are in the future and uses the current time instead, making the reported
                        // timestamps inconsistent with the recording we're replaying.
                        //
                        // To prevent this, we need to use the time we scheduled this first batch
                        // for (in microseconds, to avoid potential rounding up from
                        // getTimeToSendMillis), rather than the actual current time.
                        mLastInjectTimestampMicros = args.argl2 / 1000;
                    } else {
                        mLastInjectTimestampMicros += offsetMicros;
                    }

                    int[] events = (int[]) args.arg1;
                    for (int pos = 0; pos + 2 < events.length; pos += 3) {
                        nativeInjectEvent(mPtr, mLastInjectTimestampMicros, events[pos],
                                events[pos + 1], events[pos + 2]);
                    }
                    args.recycle();
                    break;
                }
                case MSG_CLOSE_UINPUT_DEVICE: {
                    if (mPtr != 0) {
                        nativeCloseUinputDevice(mPtr);
                        getLooper().quitSafely();
                        mPtr = 0;
                    } else {
                        Log.e(TAG, "Tried to close already closed device.");
                    }
                    Log.i(TAG, "Device closed.");
                    synchronized (mCond) {
                        mCond.notify();
                    }
                    break;
                }
                case MSG_SYNC_EVENT: {
                    handleSyncEvent((String) msg.obj);
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown device message");
                }
            }
        }

        public void pauseEvents() {
            mBarrierToken = getLooper().myQueue().postSyncBarrier();
        }

        public void resumeEvents() {
            getLooper().myQueue().removeSyncBarrier(mBarrierToken);
            mBarrierToken = 0;
        }

        private void handleSyncEvent(String syncToken) {
            final JSONObject json = new JSONObject();
            try {
                json.put("reason", "sync");
                json.put("id", mId);
                json.put("syncToken", syncToken);
            } catch (JSONException e) {
                throw new RuntimeException("Could not create JSON object ", e);
            }
            writeOutputObject(json);
        }
    }

    private class DeviceCallback {
        public void onDeviceOpen() {
            mHandler.resumeEvents();
        }

        public void onDeviceConfigure(int handle) {
            for (int i = 0; i < mConfiguration.size(); i++) {
                int key = mConfiguration.keyAt(i);
                int[] data = mConfiguration.get(key);
                nativeConfigure(handle, key, data);
            }

            if (mAbsInfo != null) {
                for (int i = 0; i < mAbsInfo.size(); i++) {
                    int key = mAbsInfo.keyAt(i);
                    InputAbsInfo info = mAbsInfo.get(key);
                    Parcel parcel = Parcel.obtain();
                    info.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    nativeSetAbsInfo(handle, key, parcel);
                }
            }
        }

        public void onDeviceVibrating(int value) {
            final JSONObject json = new JSONObject();
            try {
                json.put("reason", "vibrating");
                json.put("id", mId);
                json.put("status", value);
            } catch (JSONException e) {
                throw new RuntimeException("Could not create JSON object ", e);
            }
            writeOutputObject(json);
        }

        public void onDeviceError() {
            Log.e(TAG, "Device error occurred, closing /dev/uinput");
            Message msg = mHandler.obtainMessage(MSG_CLOSE_UINPUT_DEVICE);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }

    private void writeOutputObject(JSONObject json) {
        try {
            mOutputStream.write(json.toString().getBytes());
            mOutputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static int getEvdevEventTypeByLabel(String label) {
        final var type = nativeGetEvdevEventTypeByLabel(label);
        if (type < 0) {
            throw new IllegalArgumentException(
                    "Failed to get evdev event type from label: " + label);
        }
        return type;
    }

    static int getEvdevEventCodeByLabel(int type, String label) {
        final var code = nativeGetEvdevEventCodeByLabel(type, label);
        if (code < 0) {
            throw new IllegalArgumentException(
                    "Failed to get evdev event code for type " + type + " from label: " + label);
        }
        return code;

    }

    static int getEvdevInputPropByLabel(String label) {
        final var prop = nativeGetEvdevInputPropByLabel(label);
        if (prop < 0) {
            throw new IllegalArgumentException(
                    "Failed to get evdev input prop from label: " + label);
        }
        return prop;
    }
}
