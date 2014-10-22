package android.net.wifi;

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.concurrent.CountDownLatch;

/** @hide */
@SystemApi
public class RttManager {

    private static final boolean DBG = true;
    private static final String TAG = "RttManager";

    public static final int RTT_TYPE_UNSPECIFIED    = 0;
    public static final int RTT_TYPE_ONE_SIDED      = 1;
    public static final int RTT_TYPE_11_V           = 2;
    public static final int RTT_TYPE_11_MC          = 4;

    public static final int RTT_PEER_TYPE_UNSPECIFIED    = 0;
    public static final int RTT_PEER_TYPE_AP             = 1;
    public static final int RTT_PEER_TYPE_STA            = 2;       /* requires NAN */

    public static final int RTT_CHANNEL_WIDTH_20      = 0;
    public static final int RTT_CHANNEL_WIDTH_40      = 1;
    public static final int RTT_CHANNEL_WIDTH_80      = 2;
    public static final int RTT_CHANNEL_WIDTH_160     = 3;
    public static final int RTT_CHANNEL_WIDTH_80P80   = 4;
    public static final int RTT_CHANNEL_WIDTH_5       = 5;
    public static final int RTT_CHANNEL_WIDTH_10      = 6;
    public static final int RTT_CHANNEL_WIDTH_UNSPECIFIED = -1;

    public static final int RTT_STATUS_SUCCESS                  = 0;
    public static final int RTT_STATUS_FAILURE                  = 1;
    public static final int RTT_STATUS_FAIL_NO_RSP              = 2;
    public static final int RTT_STATUS_FAIL_REJECTED            = 3;
    public static final int RTT_STATUS_FAIL_NOT_SCHEDULED_YET   = 4;
    public static final int RTT_STATUS_FAIL_TM_TIMEOUT          = 5;
    public static final int RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL  = 6;
    public static final int RTT_STATUS_FAIL_NO_CAPABILITY       = 7;
    public static final int RTT_STATUS_ABORTED                  = 8;

    public static final int REASON_UNSPECIFIED              = -1;
    public static final int REASON_NOT_AVAILABLE            = -2;
    public static final int REASON_INVALID_LISTENER         = -3;
    public static final int REASON_INVALID_REQUEST          = -4;

    public static final String DESCRIPTION_KEY  = "android.net.wifi.RttManager.Description";

    public class Capabilities {
        public int supportedType;
        public int supportedPeerType;
    }

    public Capabilities getCapabilities() {
        return new Capabilities();
    }

    /** specifies parameters for RTT request */
    public static class RttParams {

        /** type of device being ranged; one of RTT_PEER_TYPE_AP or RTT_PEER_TYPE_STA */
        public int deviceType;

        /** type of RTT being sought; one of RTT_TYPE_ONE_SIDED
         *  RTT_TYPE_11_V or RTT_TYPE_11_MC or RTT_TYPE_UNSPECIFIED */
        public int requestType;

        /** mac address of the device being ranged */
        public String bssid;

        /** channel frequency that the device is on; optional */
        public int frequency;

        /** optional channel width. wider channels result in better accuracy,
         *  but they take longer time, and even get aborted may times; use
         *  RTT_CHANNEL_WIDTH_UNSPECIFIED if not specifying */
        public int channelWidth;

        /** number of samples to be taken */
        public int num_samples;

        /** number of retries if a sample fails */
        public int num_retries;
    }

    /** pseudo-private class used to parcel arguments */
    public static class ParcelableRttParams implements Parcelable {

        public RttParams mParams[];

        ParcelableRttParams(RttParams[] params) {
            mParams = params;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            if (mParams != null) {
                dest.writeInt(mParams.length);

                for (RttParams params : mParams) {
                    dest.writeInt(params.deviceType);
                    dest.writeInt(params.requestType);
                    dest.writeString(params.bssid);
                    dest.writeInt(params.frequency);
                    dest.writeInt(params.channelWidth);
                    dest.writeInt(params.num_samples);
                    dest.writeInt(params.num_retries);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ParcelableRttParams> CREATOR =
                new Creator<ParcelableRttParams>() {
                    public ParcelableRttParams createFromParcel(Parcel in) {

                        int num = in.readInt();

                        if (num == 0) {
                            return new ParcelableRttParams(null);
                        }

                        RttParams params[] = new RttParams[num];
                        for (int i = 0; i < num; i++) {
                            params[i] = new RttParams();
                            params[i].deviceType = in.readInt();
                            params[i].requestType = in.readInt();
                            params[i].bssid = in.readString();
                            params[i].frequency = in.readInt();
                            params[i].channelWidth = in.readInt();
                            params[i].num_samples = in.readInt();
                            params[i].num_retries = in.readInt();

                        }

                        ParcelableRttParams parcelableParams = new ParcelableRttParams(params);
                        return parcelableParams;
                    }

                    public ParcelableRttParams[] newArray(int size) {
                        return new ParcelableRttParams[size];
                    }
                };
    }

    /** specifies RTT results */
    public static class RttResult {
        /** mac address of the device being ranged */
        public String bssid;

        /** status of the request */
        public int status;

        /** type of the request used */
        public int requestType;

        /** timestamp of completion, in microsecond since boot */
        public long ts;

        /** average RSSI observed */
        public int rssi;

        /** RSSI spread (i.e. max - min) */
        public int rssi_spread;

        /** average transmit rate */
        public int tx_rate;

        /** average round trip time in nano second */
        public long rtt_ns;

        /** standard deviation observed in round trip time */
        public long rtt_sd_ns;

        /** spread (i.e. max - min) round trip time */
        public long rtt_spread_ns;

        /** average distance in centimeter, computed based on rtt_ns */
        public int distance_cm;

        /** standard deviation observed in distance */
        public int distance_sd_cm;

        /** spread (i.e. max - min) distance */
        public int distance_spread_cm;
    }


    /** pseudo-private class used to parcel results */
    public static class ParcelableRttResults implements Parcelable {

        public RttResult mResults[];

        public ParcelableRttResults(RttResult[] results) {
            mResults = results;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            if (mResults != null) {
                dest.writeInt(mResults.length);
                for (RttResult result : mResults) {
                    dest.writeString(result.bssid);
                    dest.writeInt(result.status);
                    dest.writeInt(result.requestType);
                    dest.writeLong(result.ts);
                    dest.writeInt(result.rssi);
                    dest.writeInt(result.rssi_spread);
                    dest.writeInt(result.tx_rate);
                    dest.writeLong(result.rtt_ns);
                    dest.writeLong(result.rtt_sd_ns);
                    dest.writeLong(result.rtt_spread_ns);
                    dest.writeInt(result.distance_cm);
                    dest.writeInt(result.distance_sd_cm);
                    dest.writeInt(result.distance_spread_cm);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ParcelableRttResults> CREATOR =
                new Creator<ParcelableRttResults>() {
                    public ParcelableRttResults createFromParcel(Parcel in) {

                        int num = in.readInt();

                        if (num == 0) {
                            return new ParcelableRttResults(null);
                        }

                        RttResult results[] = new RttResult[num];
                        for (int i = 0; i < num; i++) {
                            results[i] = new RttResult();
                            results[i].bssid = in.readString();
                            results[i].status = in.readInt();
                            results[i].requestType = in.readInt();
                            results[i].ts = in.readLong();
                            results[i].rssi = in.readInt();
                            results[i].rssi_spread = in.readInt();
                            results[i].tx_rate = in.readInt();
                            results[i].rtt_ns = in.readLong();
                            results[i].rtt_sd_ns = in.readLong();
                            results[i].rtt_spread_ns = in.readLong();
                            results[i].distance_cm = in.readInt();
                            results[i].distance_sd_cm = in.readInt();
                            results[i].distance_spread_cm = in.readInt();
                        }

                        ParcelableRttResults parcelableResults = new ParcelableRttResults(results);
                        return parcelableResults;
                    }

                    public ParcelableRttResults[] newArray(int size) {
                        return new ParcelableRttResults[size];
                    }
                };
    }


    public static interface RttListener {
        public void onSuccess(RttResult[] results);
        public void onFailure(int reason, String description);
        public void onAborted();
    }

    public void startRanging(RttParams[] params, RttListener listener) {
        validateChannel();
        ParcelableRttParams parcelableParams = new ParcelableRttParams(params);
        sAsyncChannel.sendMessage(CMD_OP_START_RANGING,
                0, putListener(listener), parcelableParams);
    }

    public void stopRanging(RttListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_OP_STOP_RANGING, 0, removeListener(listener));
    }

    /* private methods */
    public static final int BASE = Protocol.BASE_WIFI_RTT_MANAGER;

    public static final int CMD_OP_START_RANGING        = BASE + 0;
    public static final int CMD_OP_STOP_RANGING         = BASE + 1;
    public static final int CMD_OP_FAILED               = BASE + 2;
    public static final int CMD_OP_SUCCEEDED            = BASE + 3;
    public static final int CMD_OP_ABORTED              = BASE + 4;

    private Context mContext;
    private IRttManager mService;

    private static final int INVALID_KEY = 0;
    private static int sListenerKey = 1;

    private static final SparseArray sListenerMap = new SparseArray();
    private static final Object sListenerMapLock = new Object();

    private static AsyncChannel sAsyncChannel;
    private static CountDownLatch sConnected;

    private static final Object sThreadRefLock = new Object();
    private static int sThreadRefCount;
    private static HandlerThread sHandlerThread;

    /**
     * Create a new WifiScanner instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_RTT_SERVICE Context.WIFI_RTT_SERVICE}.
     * @param context the application context
     * @param service the Binder interface
     * @hide
     */

    public RttManager(Context context, IRttManager service) {
        mContext = context;
        mService = service;
        init();
    }

    private void init() {
        synchronized (sThreadRefLock) {
            if (++sThreadRefCount == 1) {
                Messenger messenger = null;
                try {
                    Log.d(TAG, "Get the messenger from " + mService);
                    messenger = mService.getMessenger();
                } catch (RemoteException e) {
                    /* do nothing */
                } catch (SecurityException e) {
                    /* do nothing */
                }

                if (messenger == null) {
                    sAsyncChannel = null;
                    return;
                }

                sHandlerThread = new HandlerThread("WifiScanner");
                sAsyncChannel = new AsyncChannel();
                sConnected = new CountDownLatch(1);

                sHandlerThread.start();
                Handler handler = new ServiceHandler(sHandlerThread.getLooper());
                sAsyncChannel.connect(mContext, handler, messenger);
                try {
                    sConnected.await();
                } catch (InterruptedException e) {
                    Log.e(TAG, "interrupted wait at init");
                }
            }
        }
    }

    private void validateChannel() {
        if (sAsyncChannel == null) throw new IllegalStateException(
                "No permission to access and change wifi or a bad initialization");
    }

    private static int putListener(Object listener) {
        if (listener == null) return INVALID_KEY;
        int key;
        synchronized (sListenerMapLock) {
            do {
                key = sListenerKey++;
            } while (key == INVALID_KEY);
            sListenerMap.put(key, listener);
        }
        return key;
    }

    private static Object getListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (sListenerMapLock) {
            Object listener = sListenerMap.get(key);
            return listener;
        }
    }

    private static int getListenerKey(Object listener) {
        if (listener == null) return INVALID_KEY;
        synchronized (sListenerMapLock) {
            int index = sListenerMap.indexOfValue(listener);
            if (index == -1) {
                return INVALID_KEY;
            } else {
                return sListenerMap.keyAt(index);
            }
        }
    }

    private static Object removeListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (sListenerMapLock) {
            Object listener = sListenerMap.get(key);
            sListenerMap.remove(key);
            return listener;
        }
    }

    private static int removeListener(Object listener) {
        int key = getListenerKey(listener);
        if (key == INVALID_KEY) return key;
        synchronized (sListenerMapLock) {
            sListenerMap.remove(key);
            return key;
        }
    }

    private static class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        sAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        Log.e(TAG, "Failed to set up channel connection");
                        // This will cause all further async API calls on the WifiManager
                        // to fail and throw an exception
                        sAsyncChannel = null;
                    }
                    sConnected.countDown();
                    return;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection lost");
                    // This will cause all further async API calls on the WifiManager
                    // to fail and throw an exception
                    sAsyncChannel = null;
                    getLooper().quit();
                    return;
            }

            Object listener = getListener(msg.arg2);
            if (listener == null) {
                if (DBG) Log.d(TAG, "invalid listener key = " + msg.arg2);
                return;
            } else {
                if (DBG) Log.d(TAG, "listener key = " + msg.arg2);
            }

            switch (msg.what) {
                /* ActionListeners grouped together */
                case CMD_OP_SUCCEEDED :
                    reportSuccess(listener, msg);
                    removeListener(msg.arg2);
                    break;
                case CMD_OP_FAILED :
                    reportFailure(listener, msg);
                    removeListener(msg.arg2);
                    break;
                case CMD_OP_ABORTED :
                    ((RttListener) listener).onAborted();
                    removeListener(msg.arg2);
                    break;
                default:
                    if (DBG) Log.d(TAG, "Ignoring message " + msg.what);
                    return;
            }
        }

        void reportSuccess(Object listener, Message msg) {
            RttListener rttListener = (RttListener) listener;
            ParcelableRttResults parcelableResults = (ParcelableRttResults) msg.obj;
            ((RttListener) listener).onSuccess(parcelableResults.mResults);
        }

        void reportFailure(Object listener, Message msg) {
            RttListener rttListener = (RttListener) listener;
            Bundle bundle = (Bundle) msg.obj;
            ((RttListener) listener).onFailure(msg.arg1, bundle.getString(DESCRIPTION_KEY));
        }
    }

}

