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

    private final int mId;
    private final HandlerThread mThread;
    private final DeviceHandler mHandler;
    // mConfiguration is sparse array of ioctl code and array of values.
    private final SparseArray<int[]> mConfiguration;
    private final SparseArray<InputAbsInfo> mAbsInfo;
    private final OutputStream mOutputStream;
    private final Object mCond = new Object();
    private long mTimeToSend;

    static {
        System.loadLibrary("uinputcommand_jni");
    }

    private static native long nativeOpenUinputDevice(String name, int id, int vid, int pid,
            int bus, int ffEffectsMax, String port, DeviceCallback callback);
    private static native void nativeCloseUinputDevice(long ptr);
    private static native void nativeInjectEvent(long ptr, int type, int code, int value);
    private static native void nativeConfigure(int handle, int code, int[] configs);
    private static native void nativeSetAbsInfo(int handle, int axisCode, Parcel axisParcel);

    public Device(int id, String name, int vid, int pid, int bus,
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
        args.argi2 = vid;
        args.argi3 = pid;
        args.argi4 = bus;
        args.argi5 = ffEffectsMax;
        if (name != null) {
            args.arg1 = name;
        } else {
            args.arg1 = id + ":" + vid + ":" + pid;
        }
        if (port != null) {
            args.arg2 = port;
        } else {
            args.arg2 = "uinput:" + id + ":" + vid + ":" + pid;
        }

        mHandler.obtainMessage(MSG_OPEN_UINPUT_DEVICE, args).sendToTarget();
        mTimeToSend = SystemClock.uptimeMillis();
    }

    /**
     * Inject uinput events to device
     *
     * @param events  Array of raw uinput events.
     */
    public void injectEvent(int[] events) {
        // if two messages are sent at identical time, they will be processed in order received
        Message msg = mHandler.obtainMessage(MSG_INJECT_EVENT, events);
        mHandler.sendMessageAtTime(msg, mTimeToSend);
    }

    /**
     * Impose a delay to the device for execution.
     *
     * @param delay  Time to delay in unit of milliseconds.
     */
    public void addDelay(int delay) {
        mTimeToSend = Math.max(SystemClock.uptimeMillis(), mTimeToSend) + delay;
    }

    /**
     * Close an uinput device.
     *
     */
    public void close() {
        Message msg = mHandler.obtainMessage(MSG_CLOSE_UINPUT_DEVICE);
        mHandler.sendMessageAtTime(msg, Math.max(SystemClock.uptimeMillis(), mTimeToSend) + 1);
        try {
            synchronized (mCond) {
                mCond.wait();
            }
        } catch (InterruptedException ignore) {
        }
    }

    private class DeviceHandler extends Handler {
        private long mPtr;
        private int mBarrierToken;

        DeviceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OPEN_UINPUT_DEVICE:
                    SomeArgs args = (SomeArgs) msg.obj;
                    mPtr = nativeOpenUinputDevice((String) args.arg1, args.argi1, args.argi2,
                            args.argi3, args.argi4, args.argi5, (String) args.arg2,
                            new DeviceCallback());
                    break;
                case MSG_INJECT_EVENT:
                    if (mPtr != 0) {
                        int[] events = (int[]) msg.obj;
                        for (int pos = 0; pos + 2 < events.length; pos += 3) {
                            nativeInjectEvent(mPtr, events[pos], events[pos + 1], events[pos + 2]);
                        }
                    }
                    break;
                case MSG_CLOSE_UINPUT_DEVICE:
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
                default:
                    throw new IllegalArgumentException("Unknown device message");
            }
        }

        public void pauseEvents() {
            mBarrierToken = getLooper().myQueue().postSyncBarrier();
        }

        public void resumeEvents() {
            getLooper().myQueue().removeSyncBarrier(mBarrierToken);
            mBarrierToken = 0;
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
            JSONObject json = new JSONObject();
            try {
                json.put("reason", "vibrating");
                json.put("id", mId);
                json.put("status", value);
            } catch (JSONException e) {
                throw new RuntimeException("Could not create JSON object ", e);
            }
            try {
                mOutputStream.write(json.toString().getBytes());
                mOutputStream.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public void onDeviceError() {
            Log.e(TAG, "Device error occurred, closing /dev/uinput");
            Message msg = mHandler.obtainMessage(MSG_CLOSE_UINPUT_DEVICE);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }
}
