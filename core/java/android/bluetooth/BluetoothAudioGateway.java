package android.bluetooth;

import java.lang.Thread;

import android.os.Message;
import android.os.Handler;
import android.util.Log;

/**
 * Listen's for incoming RFCOMM connection for the headset / handsfree service.
 *
 * This class is planned for deletion, in favor of a generic Rfcomm class.
 *
 * @hide
 */
public class BluetoothAudioGateway {
    private static final String TAG = "BT Audio Gateway";
    private static final boolean DBG = false;

    private int mNativeData;
    static { classInitNative(); }

    private BluetoothDevice mBluetooth;

    /* in */
    private int mHandsfreeAgRfcommChannel = -1;
    private int mHeadsetAgRfcommChannel   = -1;

    /* out */
    private String mConnectingHeadsetAddress;
    private int mConnectingHeadsetRfcommChannel; /* -1 when not connected */
    private int mConnectingHeadsetSocketFd;
    private String mConnectingHandsfreeAddress;
    private int mConnectingHandsfreeRfcommChannel; /* -1 when not connected */
    private int mConnectingHandsfreeSocketFd;
    private int mTimeoutRemainingMs; /* in/out */

    public static final int DEFAULT_HF_AG_CHANNEL = 10;
    public static final int DEFAULT_HS_AG_CHANNEL = 11;

    public BluetoothAudioGateway(BluetoothDevice bluetooth) {
        this(bluetooth, DEFAULT_HF_AG_CHANNEL, DEFAULT_HS_AG_CHANNEL);
    }

    public BluetoothAudioGateway(BluetoothDevice bluetooth,
                                 int handsfreeAgRfcommChannel,
                                 int headsetAgRfcommChannel) {
        mBluetooth = bluetooth;
        mHandsfreeAgRfcommChannel = handsfreeAgRfcommChannel;
        mHeadsetAgRfcommChannel = headsetAgRfcommChannel;
        initializeNativeDataNative();
    }

    private Thread mConnectThead;
    private volatile boolean mInterrupted;
    private static final int SELECT_WAIT_TIMEOUT = 1000;

    private Handler mCallback;

    public class IncomingConnectionInfo {
        IncomingConnectionInfo(BluetoothDevice bluetooth, String address, int socketFd,
                               int rfcommChan) {
            mBluetooth = bluetooth;
            mAddress = address;
            mSocketFd = socketFd;
            mRfcommChan = rfcommChan;
        }

        public BluetoothDevice mBluetooth;
        public String mAddress;
        public int mSocketFd;
        public int mRfcommChan;
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
                                    msg.obj = 
                                        new IncomingConnectionInfo(
                                            mBluetooth, 
                                            mConnectingHeadsetAddress,
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
                                    msg.obj = 
                                        new IncomingConnectionInfo(
                                            mBluetooth,
                                            mConnectingHandsfreeAddress,
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
