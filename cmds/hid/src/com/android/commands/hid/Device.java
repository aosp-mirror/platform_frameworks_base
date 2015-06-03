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

package com.android.commands.hid;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.os.SomeArgs;

public class Device {
    private static final String TAG = "HidDevice";

    // Minimum amount of time to wait before sending input events to a device. Even though we're
    // guaranteed that the device has been created and opened by the input system, there's still a
    // window in which the system hasn't started reading events out of it. If a stream of events
    // begins in during this window (like a button down event) and *then* we start reading, we're
    // liable to ignore the whole stream.
    private static final int MIN_WAIT_FOR_FIRST_EVENT = 150;

    private static final int MSG_OPEN_DEVICE = 1;
    private static final int MSG_SEND_REPORT = 2;
    private static final int MSG_CLOSE_DEVICE = 3;


    private final int mId;
    private final HandlerThread mThread;
    private final DeviceHandler mHandler;
    private long mEventTime;

    private final Object mCond = new Object();

    static {
        System.loadLibrary("hidcommand_jni");
    }

    private static native long nativeOpenDevice(String name, int id, int vid, int pid,
            byte[] descriptor, MessageQueue queue, DeviceCallback callback);
    private static native void nativeSendReport(long ptr, byte[] data);
    private static native void nativeCloseDevice(long ptr);

    public Device(int id, String name, int vid, int pid, byte[] descriptor, byte[] report) {
        mId = id;
        mThread = new HandlerThread("HidDeviceHandler");
        mThread.start();
        mHandler = new DeviceHandler(mThread.getLooper());
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = id;
        args.argi2 = vid;
        args.argi3 = pid;
        if (name != null) {
            args.arg1 = name;
        } else {
            args.arg1 = id + ":" + vid + ":" + pid;
        }
        args.arg2 = descriptor;
        args.arg3 = report;
        mHandler.obtainMessage(MSG_OPEN_DEVICE, args).sendToTarget();
        mEventTime = SystemClock.uptimeMillis() + MIN_WAIT_FOR_FIRST_EVENT;
    }

    public void sendReport(byte[] report) {
        Message msg = mHandler.obtainMessage(MSG_SEND_REPORT, report);
        mHandler.sendMessageAtTime(msg, mEventTime);
    }

    public void addDelay(int delay) {
        mEventTime += delay;
    }

    public void close() {
        Message msg = mHandler.obtainMessage(MSG_CLOSE_DEVICE);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, mEventTime + 1);
        try {
            synchronized (mCond) {
                mCond.wait();
            }
        } catch (InterruptedException ignore) {}
    }

    private class DeviceHandler extends Handler {
        private long mPtr;
        private int mBarrierToken;

        public DeviceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OPEN_DEVICE:
                    SomeArgs args = (SomeArgs) msg.obj;
                    mPtr = nativeOpenDevice((String) args.arg1, args.argi1, args.argi2, args.argi3,
                            (byte[]) args.arg2, getLooper().myQueue(), new DeviceCallback());
                    nativeSendReport(mPtr, (byte[]) args.arg3);
                    pauseEvents();
                    break;
                case MSG_SEND_REPORT:
                    if (mPtr != 0) {
                        nativeSendReport(mPtr, (byte[]) msg.obj);
                    } else {
                        Log.e(TAG, "Tried to send report to closed device.");
                    }
                    break;
                case MSG_CLOSE_DEVICE:
                    if (mPtr != 0) {
                        nativeCloseDevice(mPtr);
                        getLooper().quitSafely();
                        mPtr = 0;
                    } else {
                        Log.e(TAG, "Tried to close already closed device.");
                    }
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

        public void onDeviceError() {
            Message msg = mHandler.obtainMessage(MSG_CLOSE_DEVICE);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }
}
