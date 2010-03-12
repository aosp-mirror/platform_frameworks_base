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

import java.lang.Thread;

import android.os.Message;
import android.os.Handler;
import android.util.Log;

/**
 * Listen's for incoming RFCOMM connection for the headset / handsfree service.
 *
 * TODO: Use the new generic BluetoothSocket class instead of this legacy code
 *
 * @hide
 */
public final class BluetoothAudioGateway {
    private static final String TAG = "BT Audio Gateway";
    private static final boolean DBG = false;

    private int mNativeData;
    static { classInitNative(); }

    /* in */
    private int mHandsfreeAgRfcommChannel = -1;
    private int mHeadsetAgRfcommChannel   = -1;

    /* out - written by native code */
    private String mConnectingHeadsetAddress;
    private int mConnectingHeadsetRfcommChannel; /* -1 when not connected */
    private int mConnectingHeadsetSocketFd;
    private String mConnectingHandsfreeAddress;
    private int mConnectingHandsfreeRfcommChannel; /* -1 when not connected */
    private int mConnectingHandsfreeSocketFd;
    private int mTimeoutRemainingMs; /* in/out */

    private final BluetoothAdapter mAdapter;

    public static final int DEFAULT_HF_AG_CHANNEL = 10;
    public static final int DEFAULT_HS_AG_CHANNEL = 11;

    public BluetoothAudioGateway(BluetoothAdapter adapter) {
        this(adapter, DEFAULT_HF_AG_CHANNEL, DEFAULT_HS_AG_CHANNEL);
    }

    public BluetoothAudioGateway(BluetoothAdapter adapter, int handsfreeAgRfcommChannel,
                int headsetAgRfcommChannel) {
        mAdapter = adapter;
        mHandsfreeAgRfcommChannel = handsfreeAgRfcommChannel;
        mHeadsetAgRfcommChannel = headsetAgRfcommChannel;
        initializeNativeDataNative();
    }

    private Thread mConnectThead;
    private volatile boolean mInterrupted;
    private static final int SELECT_WAIT_TIMEOUT = 1000;

    private Handler mCallback;

    public class IncomingConnectionInfo {
        public BluetoothAdapter mAdapter;
        public BluetoothDevice mRemoteDevice;
        public int mSocketFd;
        public int mRfcommChan;
        IncomingConnectionInfo(BluetoothAdapter adapter, BluetoothDevice remoteDevice,
                int socketFd, int rfcommChan) {
            mAdapter = adapter;
            mRemoteDevice = remoteDevice;
            mSocketFd = socketFd;
            mRfcommChan = rfcommChan;
        }
    }

    public static final int MSG_INCOMING_HEADSET_CONNECTION   = 100;
    public static final int MSG_INCOMING_HANDSFREE_CONNECTION = 101;

    public synchronized boolean start(Handler callback) {

        if (mConnectThead == null) {
            mCallback = callback;
            mConnectThead = new Thread(TAG) {
                    public void run() {
                        if (DBG) log("Connect Thread starting");
                        while (!mInterrupted) {
                            //Log.i(TAG, "waiting for connect");
                            mConnectingHeadsetRfcommChannel = -1;
                            mConnectingHandsfreeRfcommChannel = -1;
                            if (waitForHandsfreeConnectNative(SELECT_WAIT_TIMEOUT) == false) {
                                if (mTimeoutRemainingMs > 0) {
                                    try {
                                        Log.i(TAG, "select thread timed out, but " + 
                                              mTimeoutRemainingMs + "ms of waiting remain.");
                                        Thread.sleep(mTimeoutRemainingMs);
                                    } catch (InterruptedException e) {
                                        Log.i(TAG, "select thread was interrupted (2), exiting");
                                        mInterrupted = true;
                                    }
                                }
                            }
                            else {
                                Log.i(TAG, "connect notification!");
                                /* A device connected (most likely just one, but 
                                   it is possible for two separate devices, one 
                                   a headset and one a handsfree, to connect
                                   simultaneously. 
                                */
                                if (mConnectingHeadsetRfcommChannel >= 0) {
                                    Log.i(TAG, "Incoming connection from headset " + 
                                          mConnectingHeadsetAddress + " on channel " + 
                                          mConnectingHeadsetRfcommChannel);
                                    Message msg = Message.obtain(mCallback);
                                    msg.what = MSG_INCOMING_HEADSET_CONNECTION;
                                    msg.obj = new IncomingConnectionInfo(
                                        mAdapter,
                                        mAdapter.getRemoteDevice(mConnectingHeadsetAddress),
                                        mConnectingHeadsetSocketFd,
                                        mConnectingHeadsetRfcommChannel);
                                    msg.sendToTarget();
                                }
                                if (mConnectingHandsfreeRfcommChannel >= 0) {
                                    Log.i(TAG, "Incoming connection from handsfree " + 
                                          mConnectingHandsfreeAddress + " on channel " + 
                                          mConnectingHandsfreeRfcommChannel);
                                    Message msg = Message.obtain();
                                    msg.setTarget(mCallback);
                                    msg.what = MSG_INCOMING_HANDSFREE_CONNECTION;
                                    msg.obj = new IncomingConnectionInfo(
                                        mAdapter,
                                        mAdapter.getRemoteDevice(mConnectingHandsfreeAddress),
                                        mConnectingHandsfreeSocketFd,
                                        mConnectingHandsfreeRfcommChannel);
                                    msg.sendToTarget();
                                }
                            }
                        }
                        if (DBG) log("Connect Thread finished");
                    }
                };

            if (setUpListeningSocketsNative() == false) {
                Log.e(TAG, "Could not set up listening socket, exiting");
                return false;
            }

            mInterrupted = false;
            mConnectThead.start();
        }

        return true;
    }

    public synchronized void stop() {
        if (mConnectThead != null) {
            if (DBG) log("stopping Connect Thread");
            mInterrupted = true;
            try {
                mConnectThead.interrupt();
                if (DBG) log("waiting for thread to terminate");
                mConnectThead.join();
                mConnectThead = null;
                mCallback = null;
                tearDownListeningSocketsNative();
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted waiting for Connect Thread to join");
            }
        }
    }

    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }

    private static native void classInitNative();
    private native void initializeNativeDataNative();
    private native void cleanupNativeDataNative();
    private native boolean waitForHandsfreeConnectNative(int timeoutMs);
    private native boolean setUpListeningSocketsNative();
    private native void tearDownListeningSocketsNative();

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
