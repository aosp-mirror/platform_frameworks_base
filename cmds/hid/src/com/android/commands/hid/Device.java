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
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.os.SomeArgs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

public class Device {
    private static final String TAG = "HidDevice";

    private static final int MSG_OPEN_DEVICE = 1;
    private static final int MSG_SEND_REPORT = 2;
    private static final int MSG_SEND_GET_FEATURE_REPORT_REPLY = 3;
    private static final int MSG_CLOSE_DEVICE = 4;

    // Sync with linux uhid_event_type::UHID_OUTPUT
    private static final byte UHID_EVENT_TYPE_UHID_OUTPUT = 6;
    // Sync with linux uhid_event_type::UHID_SET_REPORT
    private static final byte UHID_EVENT_TYPE_SET_REPORT = 13;
    private final int mId;
    private final HandlerThread mThread;
    private final DeviceHandler mHandler;
    // mFeatureReports is limited to 256 entries, because the report number is 8-bit
    private final SparseArray<byte[]> mFeatureReports;
    private final Map<ByteBuffer, byte[]> mOutputs;
    private final OutputStream mOutputStream;
    private long mTimeToSend;

    private final Object mCond = new Object();

    static {
        System.loadLibrary("hidcommand_jni");
    }

    private static native long nativeOpenDevice(String name, int id, int vid, int pid, int bus,
            byte[] descriptor, DeviceCallback callback);
    private static native void nativeSendReport(long ptr, byte[] data);
    private static native void nativeSendGetFeatureReportReply(long ptr, int id, byte[] data);
    private static native void nativeCloseDevice(long ptr);

    public Device(int id, String name, int vid, int pid, int bus, byte[] descriptor,
            byte[] report, SparseArray<byte[]> featureReports, Map<ByteBuffer, byte[]> outputs) {
        mId = id;
        mThread = new HandlerThread("HidDeviceHandler");
        mThread.start();
        mHandler = new DeviceHandler(mThread.getLooper());
        mFeatureReports = featureReports;
        mOutputs = outputs;
        mOutputStream = System.out;
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = id;
        args.argi2 = vid;
        args.argi3 = pid;
        args.argi4 = bus;
        if (name != null) {
            args.arg1 = name;
        } else {
            args.arg1 = id + ":" + vid + ":" + pid;
        }
        args.arg2 = descriptor;
        args.arg3 = report;
        mHandler.obtainMessage(MSG_OPEN_DEVICE, args).sendToTarget();
        mTimeToSend = SystemClock.uptimeMillis();
    }

    public void sendReport(byte[] report) {
        Message msg = mHandler.obtainMessage(MSG_SEND_REPORT, report);
        // if two messages are sent at identical time, they will be processed in order received
        mHandler.sendMessageAtTime(msg, mTimeToSend);
    }

    public void addDelay(int delay) {
        mTimeToSend = Math.max(SystemClock.uptimeMillis(), mTimeToSend) + delay;
    }

    public void close() {
        Message msg = mHandler.obtainMessage(MSG_CLOSE_DEVICE);
        mHandler.sendMessageAtTime(msg, Math.max(SystemClock.uptimeMillis(), mTimeToSend) + 1);
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
                            args.argi4, (byte[]) args.arg2, new DeviceCallback());
                    pauseEvents();
                    break;
                case MSG_SEND_REPORT:
                    if (mPtr != 0) {
                        nativeSendReport(mPtr, (byte[]) msg.obj);
                    } else {
                        Log.e(TAG, "Tried to send report to closed device.");
                    }
                    break;
                case MSG_SEND_GET_FEATURE_REPORT_REPLY:
                    if (mPtr != 0) {
                        nativeSendGetFeatureReportReply(mPtr, msg.arg1, (byte[]) msg.obj);
                    } else {
                        Log.e(TAG, "Tried to send feature report reply to closed device.");
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

        public void onDeviceGetReport(int requestId, int reportId) {
            if (mFeatureReports == null) {
                Log.e(TAG, "Received GET_REPORT request for reportId=" + reportId
                        + ", but 'feature_reports' section is not found");
                return;
            }
            byte[] report = mFeatureReports.get(reportId);

            if (report == null) {
                Log.e(TAG, "Requested feature report " + reportId + " is not specified");
            }

            Message msg;
            msg = mHandler.obtainMessage(MSG_SEND_GET_FEATURE_REPORT_REPLY, requestId, 0, report);

            // Message is set to asynchronous so it won't be blocked by synchronization
            // barrier during UHID_OPEN. This is necessary for drivers that do
            // UHID_GET_REPORT requests during probe.
            msg.setAsynchronous(true);
            mHandler.sendMessageAtTime(msg, mTimeToSend);
        }

        // Send out the report to HID command output
        private void sendReportOutput(byte eventId, byte rtype, byte[] data) {
            JSONObject json = new JSONObject();
            try {
                json.put("eventId", eventId);
                json.put("deviceId", mId);
                json.put("reportType", rtype);
                JSONArray dataArray = new JSONArray();
                for (int i = 0; i < data.length; i++) {
                    dataArray.put(data[i] & 0xFF);
                }
                json.put("reportData", dataArray);
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

        // native callback
        public void onDeviceSetReport(byte rtype, byte[] data) {
            // We don't need to reply for the SET_REPORT but just send it to HID output for test
            // verification.
            sendReportOutput(UHID_EVENT_TYPE_SET_REPORT, rtype, data);
        }

        // native callback
        public void onDeviceOutput(byte rtype, byte[] data) {
            sendReportOutput(UHID_EVENT_TYPE_UHID_OUTPUT, rtype, data);
            if (mOutputs == null) {
                Log.e(TAG, "Received OUTPUT request, but 'outputs' section is not found");
                return;
            }
            byte[] response = mOutputs.get(ByteBuffer.wrap(data));
            if (response == null) {
                Log.i(TAG,
                        "Requested response for output " + Arrays.toString(data) + " is not found");
                return;
            }

            Message msg;
            msg = mHandler.obtainMessage(MSG_SEND_REPORT, response);

            // Message is set to asynchronous so it won't be blocked by synchronization
            // barrier during UHID_OPEN. This is necessary for drivers that do
            // UHID_OUTPUT requests during probe, and expect a response right away.
            msg.setAsynchronous(true);
            mHandler.sendMessageAtTime(msg, mTimeToSend);
        }

        public void onDeviceError() {
            Log.e(TAG, "Device error occurred, closing /dev/uhid");
            Message msg = mHandler.obtainMessage(MSG_CLOSE_DEVICE);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }
}
