/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.bluetooth;

import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 *
 * The base RFCOMM (service) connection for a headset or handsfree device.
 *
 * In the future this class will be removed.
 *
 * @hide
 */
public final class HeadsetBase {
    private static final String TAG = "Bluetooth HeadsetBase";
    private static final boolean DBG = false;

    public static final int RFCOMM_DISCONNECTED = 1;

    public static final int DIRECTION_INCOMING = 1;
    public static final int DIRECTION_OUTGOING = 2;

    private static int sAtInputCount = 0;  /* TODO: Consider not using a static variable */

    private final BluetoothAdapter mAdapter;
    private final BluetoothDevice mRemoteDevice;
    private final String mAddress;  // for native code
    private final int mRfcommChannel;
    private int mNativeData;
    private Thread mEventThread;
    private volatile boolean mEventThreadInterrupted;
    private Handler mEventThreadHandler;
    private int mTimeoutRemainingMs;
    private final int mDirection;
    private final long mConnectTimestamp;

    protected AtParser mAtParser;

    private WakeLock mWakeLock;  // held while processing an AT command

    private native static void classInitNative();
    static {
        classInitNative();
    }

    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
            releaseWakeLock();
        } finally {
            super.finalize();
        }
    }

    private native void cleanupNativeDataNative();

    public HeadsetBase(PowerManager pm, BluetoothAdapter adapter,
                       BluetoothDevice device, int rfcommChannel) {
        mDirection = DIRECTION_OUTGOING;
        mConnectTimestamp = System.currentTimeMillis();
        mAdapter = adapter;
        mRemoteDevice = device;
        mAddress = device.getAddress();
        mRfcommChannel = rfcommChannel;
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadsetBase");
        mWakeLock.setReferenceCounted(false);
        initializeAtParser();
        // Must be called after this.mAddress is set.
        initializeNativeDataNative(-1);
    }

    /* Create from an existing rfcomm connection */
    public HeadsetBase(PowerManager pm, BluetoothAdapter adapter,
                       BluetoothDevice device,
                       int socketFd, int rfcommChannel, Handler handler) {
        mDirection = DIRECTION_INCOMING;
        mConnectTimestamp = System.currentTimeMillis();
        mAdapter = adapter;
        mRemoteDevice = device;
        mAddress = device.getAddress();
        mRfcommChannel = rfcommChannel;
        mEventThreadHandler = handler;
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadsetBase");
        mWakeLock.setReferenceCounted(false);
        initializeAtParser();
        // Must be called after this.mAddress is set.
        initializeNativeDataNative(socketFd);
    }

    private native void initializeNativeDataNative(int socketFd);

    /* Process an incoming AT command line
     */
    protected void handleInput(String input) {
        acquireWakeLock();
        long timestamp;

        synchronized(HeadsetBase.class) {
            if (sAtInputCount == Integer.MAX_VALUE) {
                sAtInputCount = 0;
            } else {
                sAtInputCount++;
            }
        }

        if (DBG) timestamp = System.currentTimeMillis();
        AtCommandResult result = mAtParser.process(input);
        if (DBG) Log.d(TAG, "Processing " + input + " took " +
                       (System.currentTimeMillis() - timestamp) + " ms");

        if (result.getResultCode() == AtCommandResult.ERROR) {
            Log.i(TAG, "Error processing <" + input + ">");
        }

        sendURC(result.toString());

        releaseWakeLock();
    }

    /**
     * Register AT commands that are common to all Headset / Handsets. This
     * function is called by the HeadsetBase constructor.
     */
    protected void initializeAtParser() {
        mAtParser = new AtParser();

        //TODO(): Get rid of this as there are no parsers registered. But because of dependencies
        // it needs to be done as part of refactoring HeadsetBase and BluetoothHandsfree
    }

    public AtParser getAtParser() {
        return mAtParser;
    }

    public void startEventThread() {
        mEventThread =
            new Thread("HeadsetBase Event Thread") {
                public void run() {
                    int last_read_error;
                    while (!mEventThreadInterrupted) {
                        String input = readNative(500);
                        if (input != null) {
                            handleInput(input);
                        } else {
                            last_read_error = getLastReadStatusNative();
                            if (last_read_error != 0) {
                                Log.i(TAG, "headset read error " + last_read_error);
                                if (mEventThreadHandler != null) {
                                    mEventThreadHandler.obtainMessage(RFCOMM_DISCONNECTED)
                                            .sendToTarget();
                                }
                                disconnectNative();
                                break;
                            }
                        }
                    }
                }
            };
        mEventThreadInterrupted = false;
        mEventThread.start();
    }

    private native String readNative(int timeout_ms);
    private native int getLastReadStatusNative();

    private void stopEventThread() {
        mEventThreadInterrupted = true;
        mEventThread.interrupt();
        try {
            mEventThread.join();
        } catch (java.lang.InterruptedException e) {
            // FIXME: handle this,
        }
        mEventThread = null;
    }

    public boolean connect(Handler handler) {
        if (mEventThread == null) {
            if (!connectNative()) return false;
            mEventThreadHandler = handler;
        }
        return true;
    }
    private native boolean connectNative();

    /*
     * Returns true when either the asynchronous connect is in progress, or
     * the connect is complete.  Call waitForAsyncConnect() to find out whether
     * the connect is actually complete, or disconnect() to cancel.
     */

    public boolean connectAsync() {
        int ret = connectAsyncNative();
        return (ret == 0) ? true : false;
    }
    private native int connectAsyncNative();

    public int getRemainingAsyncConnectWaitingTimeMs() {
        return mTimeoutRemainingMs;
    }

    /*
     * Returns 1 when an async connect is complete, 0 on timeout, and -1 on
     * error.  On error, handler will be called, and you need to re-initiate
     * the async connect.
     */
    public int waitForAsyncConnect(int timeout_ms, Handler handler) {
        int res = waitForAsyncConnectNative(timeout_ms);
        if (res > 0) {
            mEventThreadHandler = handler;
        }
        return res;
    }
    private native int waitForAsyncConnectNative(int timeout_ms);

    public void disconnect() {
        if (mEventThread != null) {
            stopEventThread();
        }
        disconnectNative();
    }
    private native void disconnectNative();


    /*
     * Note that if a remote side disconnects, this method will still return
     * true until disconnect() is called.  You know when a remote side
     * disconnects because you will receive the intent
     * IBluetoothService.REMOTE_DEVICE_DISCONNECTED_ACTION.  If, when you get
     * this intent, method isConnected() returns true, you know that the
     * disconnect was initiated by the remote device.
     */

    public boolean isConnected() {
        return mEventThread != null;
    }

    public BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }

    public int getDirection() {
        return mDirection;
    }

    public long getConnectTimestamp() {
        return mConnectTimestamp;
    }

    public synchronized boolean sendURC(String urc) {
        if (urc.length() > 0) {
            boolean ret = sendURCNative(urc);
            return ret;
        }
        return true;
    }
    private native boolean sendURCNative(String urc);

    private synchronized void acquireWakeLock() {
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    private synchronized void releaseWakeLock() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    public static int getAtInputCount() {
        return sAtInputCount;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
