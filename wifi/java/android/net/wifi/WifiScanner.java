/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.SystemApi;
import android.content.Context;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;


/**
 * This class provides a way to scan the Wifi universe around the device
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String) Context.getSystemService(Context
 * .WIFI_SCANNING_SERVICE)}.
 * @hide
 */
@SystemApi
public class WifiScanner {

    /** no band specified; use channel list instead */
    public static final int WIFI_BAND_UNSPECIFIED = 0;      /* not specified */

    /** 2.4 GHz band */
    public static final int WIFI_BAND_24_GHZ = 1;           /* 2.4 GHz band */
    /** 5 GHz band excluding DFS channels */
    public static final int WIFI_BAND_5_GHZ = 2;            /* 5 GHz band without DFS channels */
    /** DFS channels from 5 GHz band only */
    public static final int WIFI_BAND_5_GHZ_DFS_ONLY  = 4;  /* 5 GHz band with DFS channels */
    /** 5 GHz band including DFS channels */
    public static final int WIFI_BAND_5_GHZ_WITH_DFS  = 6;  /* 5 GHz band with DFS channels */
    /** Both 2.4 GHz band and 5 GHz band; no DFS channels */
    public static final int WIFI_BAND_BOTH = 3;             /* both bands without DFS channels */
    /** Both 2.4 GHz band and 5 GHz band; with DFS channels */
    public static final int WIFI_BAND_BOTH_WITH_DFS = 7;    /* both bands with DFS channels */

    /** Minimum supported scanning period */
    public static final int MIN_SCAN_PERIOD_MS = 1000;      /* minimum supported period */
    /** Maximum supported scanning period */
    public static final int MAX_SCAN_PERIOD_MS = 1024000;   /* maximum supported period */

    /** No Error */
    public static final int REASON_SUCCEEDED = 0;
    /** Unknown error */
    public static final int REASON_UNSPECIFIED = -1;
    /** Invalid listener */
    public static final int REASON_INVALID_LISTENER = -2;
    /** Invalid request */
    public static final int REASON_INVALID_REQUEST = -3;

    /**
     * Generic action callback invocation interface
     *  @hide
     */
    @SystemApi
    public static interface ActionListener {
        public void onSuccess();
        public void onFailure(int reason, String description);
    }

    /**
     * gives you all the possible channels; channel is specified as an
     * integer with frequency in MHz i.e. channel 1 is 2412
     * @hide
     */
    public List<Integer> getAvailableChannels(int band) {
        return null;
    }

    /**
     * provides channel specification for scanning
     */
    public static class ChannelSpec {
        /**
         * channel frequency in MHz; for example channel 1 is specified as 2412
         */
        public int frequency;
        /**
         * if true, scan this channel in passive fashion.
         * This flag is ignored on DFS channel specification.
         * @hide
         */
        public boolean passive;                                    /* ignored on DFS channels */
        /**
         * how long to dwell on this channel
         * @hide
         */
        public int dwellTimeMS;                                    /* not supported for now */

        /**
         * default constructor for channel spec
         */
        public ChannelSpec(int frequency) {
            this.frequency = frequency;
            passive = false;
            dwellTimeMS = 0;
        }
    }

    /** reports {@link ScanListener#onResults} when underlying buffers are full */
    public static final int REPORT_EVENT_AFTER_BUFFER_FULL = 0;
    /** reports {@link ScanListener#onResults} after each scan */
    public static final int REPORT_EVENT_AFTER_EACH_SCAN = 1;
    /** reports {@link ScanListener#onFullResult} whenever each beacon is discovered */
    public static final int REPORT_EVENT_FULL_SCAN_RESULT = 2;

    /**
     * scan configuration parameters to be sent to {@link #startBackgroundScan}
     */
    public static class ScanSettings implements Parcelable {

        /** one of the WIFI_BAND values */
        public int band;
        /** list of channels; used when band is set to WIFI_BAND_UNSPECIFIED */
        public ChannelSpec[] channels;
        /** period of background scan; in millisecond, 0 => single shot scan */
        public int periodInMs;
        /** must have a valid REPORT_EVENT value */
        public int reportEvents;
        /** defines number of bssids to cache from each scan */
        public int numBssidsPerScan;

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(band);
            dest.writeInt(periodInMs);
            dest.writeInt(reportEvents);
            dest.writeInt(numBssidsPerScan);

            if (channels != null) {
                dest.writeInt(channels.length);

                for (int i = 0; i < channels.length; i++) {
                    dest.writeInt(channels[i].frequency);
                    dest.writeInt(channels[i].dwellTimeMS);
                    dest.writeInt(channels[i].passive ? 1 : 0);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ScanSettings> CREATOR =
                new Creator<ScanSettings>() {
                    public ScanSettings createFromParcel(Parcel in) {

                        ScanSettings settings = new ScanSettings();
                        settings.band = in.readInt();
                        settings.periodInMs = in.readInt();
                        settings.reportEvents = in.readInt();
                        settings.numBssidsPerScan = in.readInt();
                        int num_channels = in.readInt();
                        settings.channels = new ChannelSpec[num_channels];
                        for (int i = 0; i < num_channels; i++) {
                            int frequency = in.readInt();

                            ChannelSpec spec = new ChannelSpec(frequency);
                            spec.dwellTimeMS = in.readInt();
                            spec.passive = in.readInt() == 1;
                            settings.channels[i] = spec;
                        }

                        return settings;
                    }

                    public ScanSettings[] newArray(int size) {
                        return new ScanSettings[size];
                    }
                };

    }

    /** @hide */
    public static class ParcelableScanResults implements Parcelable {
        public ScanResult mResults[];

        public ParcelableScanResults(ScanResult[] results) {
            mResults = results;
        }

        public ScanResult[] getResults() {
            return mResults;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            if (mResults != null) {
                dest.writeInt(mResults.length);
                for (int i = 0; i < mResults.length; i++) {
                    ScanResult result = mResults[i];
                    result.writeToParcel(dest, flags);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ParcelableScanResults> CREATOR =
                new Creator<ParcelableScanResults>() {
                    public ParcelableScanResults createFromParcel(Parcel in) {
                        int n = in.readInt();
                        ScanResult results[] = new ScanResult[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = ScanResult.CREATOR.createFromParcel(in);
                        }
                        return new ParcelableScanResults(results);
                    }

                    public ParcelableScanResults[] newArray(int size) {
                        return new ParcelableScanResults[size];
                    }
                };
    }

    /**
     * interface to get scan events on; specify this on {@link #startBackgroundScan}
     */
    public interface ScanListener extends ActionListener {
        /**
         * Framework co-ordinates scans across multiple apps; so it may not give exactly the
         * same period requested. If period of a scan is changed; it is reported by this event.
         */
        public void onPeriodChanged(int periodInMs);
        /**
         * reports results retrieved from background scan
         */
        public void onResults(ScanResult[] results);
        /**
         * reports full scan result for each access point found in scan
         */
        public void onFullResult(ScanResult fullScanResult);
    }

    /** start wifi scan in background
     * @param settings specifies various parameters for the scan; for more information look at
     * {@link ScanSettings}
     * @param listener specifies the object to report events to. This object is also treated as a
     *                 key for this scan, and must also be specified to cancel the scan. Multiple
     *                 scans should also not share this object.
     */
    public void startBackgroundScan(ScanSettings settings, ScanListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_START_BACKGROUND_SCAN, 0, putListener(listener), settings);
    }
    /**
     * stop an ongoing wifi scan
     * @param listener specifies which scan to cancel; must be same object as passed in {@link
     *  #startBackgroundScan}
     */
    public void stopBackgroundScan(ScanListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_STOP_BACKGROUND_SCAN, 0, removeListener(listener));
    }
    /**
     * retrieves currently available scan results
     */
    public ScanResult[] getScanResults() {
        validateChannel();
        Message reply = sAsyncChannel.sendMessageSynchronously(CMD_GET_SCAN_RESULTS, 0);
        ScanResult[] results = (ScanResult[]) reply.obj;
        return results;
    }

    /** specifies information about an access point of interest */
    public static class BssidInfo {
        /** bssid of the access point; in XX:XX:XX:XX:XX:XX format */
        public String bssid;
        /** low signal strength threshold; more information at {@link ScanResult#level} */
        public int low;                                            /* minimum RSSI */
        /** high signal threshold; more information at {@link ScanResult#level} */
        public int high;                                           /* maximum RSSI */
        /** channel frequency (in KHz) where you may find this BSSID */
        public int frequencyHint;
    }

    /** @hide */
    @SystemApi
    public static class WifiChangeSettings implements Parcelable {
        public int rssiSampleSize;                          /* sample size for RSSI averaging */
        public int lostApSampleSize;                        /* samples to confirm AP's loss */
        public int unchangedSampleSize;                     /* samples to confirm no change */
        public int minApsBreachingThreshold;                /* change threshold to trigger event */
        public int periodInMs;                              /* scan period in millisecond */
        public BssidInfo[] bssidInfos;

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(rssiSampleSize);
            dest.writeInt(lostApSampleSize);
            dest.writeInt(unchangedSampleSize);
            dest.writeInt(minApsBreachingThreshold);
            dest.writeInt(periodInMs);
            if (bssidInfos != null) {
                dest.writeInt(bssidInfos.length);
                for (int i = 0; i < bssidInfos.length; i++) {
                    BssidInfo info = bssidInfos[i];
                    dest.writeString(info.bssid);
                    dest.writeInt(info.low);
                    dest.writeInt(info.high);
                    dest.writeInt(info.frequencyHint);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<WifiChangeSettings> CREATOR =
                new Creator<WifiChangeSettings>() {
                    public WifiChangeSettings createFromParcel(Parcel in) {
                        WifiChangeSettings settings = new WifiChangeSettings();
                        settings.rssiSampleSize = in.readInt();
                        settings.lostApSampleSize = in.readInt();
                        settings.unchangedSampleSize = in.readInt();
                        settings.minApsBreachingThreshold = in.readInt();
                        settings.periodInMs = in.readInt();
                        int len = in.readInt();
                        settings.bssidInfos = new BssidInfo[len];
                        for (int i = 0; i < len; i++) {
                            BssidInfo info = new BssidInfo();
                            info.bssid = in.readString();
                            info.low = in.readInt();
                            info.high = in.readInt();
                            info.frequencyHint = in.readInt();
                            settings.bssidInfos[i] = info;
                        }
                        return settings;
                    }

                    public WifiChangeSettings[] newArray(int size) {
                        return new WifiChangeSettings[size];
                    }
                };

    }

    /** configure WifiChange detection
     * @param rssiSampleSize number of samples used for RSSI averaging
     * @param lostApSampleSize number of samples to confirm an access point's loss
     * @param unchangedSampleSize number of samples to confirm there are no changes
     * @param minApsBreachingThreshold minimum number of access points that need to be
     *                                 out of range to detect WifiChange
     * @param periodInMs indicates period of scan to find changes
     * @param bssidInfos access points to watch
     */
    public void configureWifiChange(
            int rssiSampleSize,                             /* sample size for RSSI averaging */
            int lostApSampleSize,                           /* samples to confirm AP's loss */
            int unchangedSampleSize,                        /* samples to confirm no change */
            int minApsBreachingThreshold,                   /* change threshold to trigger event */
            int periodInMs,                                 /* period of scan */
            BssidInfo[] bssidInfos                          /* signal thresholds to crosss */
            )
    {
        validateChannel();

        WifiChangeSettings settings = new WifiChangeSettings();
        settings.rssiSampleSize = rssiSampleSize;
        settings.lostApSampleSize = lostApSampleSize;
        settings.unchangedSampleSize = unchangedSampleSize;
        settings.minApsBreachingThreshold = minApsBreachingThreshold;
        settings.periodInMs = periodInMs;
        settings.bssidInfos = bssidInfos;

        configureWifiChange(settings);
    }

    /**
     * interface to get wifi change events on; use this on {@link #startTrackingWifiChange}
     */
    public interface WifiChangeListener extends ActionListener {
        /** indicates that changes were detected in wifi environment
         * @param results indicate the access points that exhibited change
         */
        public void onChanging(ScanResult[] results);           /* changes are found */
        /** indicates that no wifi changes are being detected for a while
         * @param results indicate the access points that are bing monitored for change
         */
        public void onQuiescence(ScanResult[] results);         /* changes settled down */
    }

    /**
     * track changes in wifi environment
     * @param listener object to report events on; this object must be unique and must also be
     *                 provided on {@link #stopTrackingWifiChange}
     */
    public void startTrackingWifiChange(WifiChangeListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_START_TRACKING_CHANGE, 0, putListener(listener));
    }

    /**
     * stop tracking changes in wifi environment
     * @param listener object that was provided to report events on {@link
     * #stopTrackingWifiChange}
     */
    public void stopTrackingWifiChange(WifiChangeListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_STOP_TRACKING_CHANGE, 0, removeListener(listener));
    }

    /** @hide */
    @SystemApi
    public void configureWifiChange(WifiChangeSettings settings) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_CONFIGURE_WIFI_CHANGE, 0, 0, settings);
    }

    /** interface to receive hotlist events on; use this on {@link #setHotlist} */
    public static interface BssidListener extends ActionListener {
        /** indicates that access points were found by on going scans
         * @param results list of scan results, one for each access point visible currently
         */
        public void onFound(ScanResult[] results);
    }

    /** @hide */
    @SystemApi
    public static class HotlistSettings implements Parcelable {
        public BssidInfo[] bssidInfos;
        public int apLostThreshold;

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(apLostThreshold);

            if (bssidInfos != null) {
                dest.writeInt(bssidInfos.length);
                for (int i = 0; i < bssidInfos.length; i++) {
                    BssidInfo info = bssidInfos[i];
                    dest.writeString(info.bssid);
                    dest.writeInt(info.low);
                    dest.writeInt(info.high);
                    dest.writeInt(info.frequencyHint);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<HotlistSettings> CREATOR =
                new Creator<HotlistSettings>() {
                    public HotlistSettings createFromParcel(Parcel in) {
                        HotlistSettings settings = new HotlistSettings();
                        settings.apLostThreshold = in.readInt();
                        int n = in.readInt();
                        settings.bssidInfos = new BssidInfo[n];
                        for (int i = 0; i < n; i++) {
                            BssidInfo info = new BssidInfo();
                            info.bssid = in.readString();
                            info.low = in.readInt();
                            info.high = in.readInt();
                            info.frequencyHint = in.readInt();
                            settings.bssidInfos[i] = info;
                        }
                        return settings;
                    }

                    public HotlistSettings[] newArray(int size) {
                        return new HotlistSettings[size];
                    }
                };
    }

    /**
     * set interesting access points to find
     * @param bssidInfos access points of interest
     * @param apLostThreshold number of scans needed to indicate that AP is lost
     * @param listener object provided to report events on; this object must be unique and must
     *                 also be provided on {@link #stopTrackingBssids}
     */
    public void startTrackingBssids(BssidInfo[] bssidInfos,
                                    int apLostThreshold, BssidListener listener) {
        validateChannel();
        HotlistSettings settings = new HotlistSettings();
        settings.bssidInfos = bssidInfos;
        sAsyncChannel.sendMessage(CMD_SET_HOTLIST, 0, putListener(listener), settings);
    }

    /**
     * remove tracking of interesting access points
     * @param listener same object provided in {@link #startTrackingBssids}
     */
    public void stopTrackingBssids(BssidListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_RESET_HOTLIST, 0, removeListener(listener));
    }


    /* private members and methods */

    private static final String TAG = "WifiScanner";
    private static final boolean DBG = true;

    /* commands for Wifi Service */
    private static final int BASE = Protocol.BASE_WIFI_SCANNER;

    /** @hide */
    public static final int CMD_SCAN                        = BASE + 0;
    /** @hide */
    public static final int CMD_START_BACKGROUND_SCAN       = BASE + 2;
    /** @hide */
    public static final int CMD_STOP_BACKGROUND_SCAN        = BASE + 3;
    /** @hide */
    public static final int CMD_GET_SCAN_RESULTS            = BASE + 4;
    /** @hide */
    public static final int CMD_SCAN_RESULT                 = BASE + 5;
    /** @hide */
    public static final int CMD_SET_HOTLIST                 = BASE + 6;
    /** @hide */
    public static final int CMD_RESET_HOTLIST               = BASE + 7;
    /** @hide */
    public static final int CMD_AP_FOUND                    = BASE + 9;
    /** @hide */
    public static final int CMD_AP_LOST                     = BASE + 10;
    /** @hide */
    public static final int CMD_START_TRACKING_CHANGE       = BASE + 11;
    /** @hide */
    public static final int CMD_STOP_TRACKING_CHANGE        = BASE + 12;
    /** @hide */
    public static final int CMD_CONFIGURE_WIFI_CHANGE       = BASE + 13;
    /** @hide */
    public static final int CMD_WIFI_CHANGE_DETECTED        = BASE + 15;
    /** @hide */
    public static final int CMD_WIFI_CHANGES_STABILIZED     = BASE + 16;
    /** @hide */
    public static final int CMD_OP_SUCCEEDED                = BASE + 17;
    /** @hide */
    public static final int CMD_OP_FAILED                   = BASE + 18;
    /** @hide */
    public static final int CMD_PERIOD_CHANGED              = BASE + 19;
    /** @hide */
    public static final int CMD_FULL_SCAN_RESULT            = BASE + 20;

    private Context mContext;
    private IWifiScanner mService;

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
     * the standard {@link android.content.Context#WIFI_SERVICE Context.WIFI_SERVICE}.
     * @param context the application context
     * @param service the Binder interface
     * @hide
     */
    public WifiScanner(Context context, IWifiScanner service) {
        mContext = context;
        mService = service;
        init();
    }

    private void init() {
        synchronized (sThreadRefLock) {
            if (++sThreadRefCount == 1) {
                Messenger messenger = null;
                try {
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

    /** @hide */
    public static class OperationResult implements Parcelable {
        public int reason;
        public String description;

        public OperationResult(int reason, String description) {
            this.reason = reason;
            this.description = description;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(reason);
            dest.writeString(description);
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<OperationResult> CREATOR =
                new Creator<OperationResult>() {
                    public OperationResult createFromParcel(Parcel in) {
                        int reason = in.readInt();
                        String description = in.readString();
                        return new OperationResult(reason, description);
                    }

                    public OperationResult[] newArray(int size) {
                        return new OperationResult[size];
                    }
                };
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
                    ((ActionListener) listener).onSuccess();
                    break;
                case CMD_OP_FAILED : {
                        OperationResult result = (OperationResult)msg.obj;
                        ((ActionListener) listener).onFailure(result.reason, result.description);
                        removeListener(msg.arg2);
                    }
                    break;
                case CMD_SCAN_RESULT :
                    ((ScanListener) listener).onResults(
                            ((ParcelableScanResults) msg.obj).getResults());
                    return;
                case CMD_FULL_SCAN_RESULT :
                    ScanResult result = (ScanResult) msg.obj;
                    ((ScanListener) listener).onFullResult(result);
                    return;
                case CMD_PERIOD_CHANGED:
                    ((ScanListener) listener).onPeriodChanged(msg.arg1);
                    return;
                case CMD_AP_FOUND:
                    ((BssidListener) listener).onFound(
                            ((ParcelableScanResults) msg.obj).getResults());
                    return;
                case CMD_WIFI_CHANGE_DETECTED:
                    ((WifiChangeListener) listener).onChanging(
                            ((ParcelableScanResults) msg.obj).getResults());
                   return;
                case CMD_WIFI_CHANGES_STABILIZED:
                    ((WifiChangeListener) listener).onQuiescence(
                            ((ParcelableScanResults) msg.obj).getResults());
                    return;
                default:
                    if (DBG) Log.d(TAG, "Ignoring message " + msg.what);
                    return;
            }
        }
    }
}
