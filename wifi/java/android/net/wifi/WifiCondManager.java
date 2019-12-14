/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.wificond.ChannelSettings;
import android.net.wifi.wificond.HiddenNetwork;
import android.net.wifi.wificond.NativeScanResult;
import android.net.wifi.wificond.NativeWifiClient;
import android.net.wifi.wificond.PnoSettings;
import android.net.wifi.wificond.SingleScanSettings;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 * @hide
 */
public class WifiCondManager implements IBinder.DeathRecipient {
    private static final String TAG = "WifiCondManager";
    private boolean mVerboseLoggingEnabled = false;

    /**
     * The {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}
     * timeout, in milliseconds, after which
     * {@link SendMgmtFrameCallback#onFailure(int)} will be called with reason
     * {@link #SEND_MGMT_FRAME_ERROR_TIMEOUT}.
     */
    public static final int SEND_MGMT_FRAME_TIMEOUT_MS = 1000;

    private static final String TIMEOUT_ALARM_TAG = TAG + " Send Management Frame Timeout";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SCAN_TYPE_"},
            value = {SCAN_TYPE_SINGLE_SCAN,
                    SCAN_TYPE_PNO_SCAN})
    public @interface ScanResultType {}

    /** Get scan results for a single scan */
    public static final int SCAN_TYPE_SINGLE_SCAN = 0;

    /** Get scan results for Pno Scan */
    public static final int SCAN_TYPE_PNO_SCAN = 1;

    private AlarmManager mAlarmManager;
    private Handler mEventHandler;

    // Cached wificond binder handlers.
    private IWificond mWificond;
    private HashMap<String, IClientInterface> mClientInterfaces = new HashMap<>();
    private HashMap<String, IApInterface> mApInterfaces = new HashMap<>();
    private HashMap<String, IWifiScannerImpl> mWificondScanners = new HashMap<>();
    private HashMap<String, IScanEvent> mScanEventHandlers = new HashMap<>();
    private HashMap<String, IPnoScanEvent> mPnoScanEventHandlers = new HashMap<>();
    private HashMap<String, IApInterfaceEventCallback> mApInterfaceListeners = new HashMap<>();
    private Runnable mDeathEventHandler;
    /**
     * Ensures that no more than one sendMgmtFrame operation runs concurrently.
     */
    private AtomicBoolean mSendMgmtFrameInProgress = new AtomicBoolean(false);

    /**
     * Interface for a callback to be used to handle scan results.
     */
    public interface ScanEventCallback {
        /**
         * Called when scan results are available.
         */
        void onScanResultReady();

        /**
         * Called when a scan has failed.
         */
        void onScanFailed();
    }

    /**
     * Interface for a callback to provide information about PNO scan request.
     */
    public interface PnoScanRequestCallback {
        /**
         * Called when the PNO scan is requested.
         */
        void onPnoRequestSucceeded();

        /**
         * Called when a PNO scan request fails.
         */
        void onPnoRequestFailed();
    }

    private class ScanEventHandler extends IScanEvent.Stub {
        private ScanEventCallback mCallback;

        ScanEventHandler(@NonNull ScanEventCallback callback) {
            mCallback = callback;
        }

        @Override
        public void OnScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            mCallback.onScanResultReady();
        }

        @Override
        public void OnScanFailed() {
            Log.d(TAG, "Scan failed event");
            mCallback.onScanFailed();
        }
    }

    /**
     * Result of a signal poll.
     */
    public static class SignalPollResult {
        // RSSI value in dBM.
        public int currentRssi;
        //Transmission bit rate in Mbps.
        public int txBitrate;
        // Association frequency in MHz.
        public int associationFrequency;
        //Last received packet bit rate in Mbps.
        public int rxBitrate;
    }

    /**
     * WiFi interface transimission counters.
     */
    public static class TxPacketCounters {
        // Number of successfully transmitted packets.
        public int txSucceeded;
        // Number of tramsmission failures.
        public int txFailed;
    }

    /**
     * Callbacks for SoftAp interface.
     */
    public interface SoftApListener {
        /**
         * Invoked when there is some fatal failure in the lower layers.
         */
        void onFailure();

        /**
         * Invoked when the associated stations changes.
         */
        void onConnectedClientsChanged(NativeWifiClient client, boolean isConnected);

        /**
         * Invoked when the channel switch event happens.
         */
        void onSoftApChannelSwitched(int frequency, int bandwidth);
    }

    /**
     * Callback to notify the results of a
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()} call.
     * Note: no callbacks will be triggered if the iface dies while sending a frame.
     */
    public interface SendMgmtFrameCallback {
        /**
         * Called when the management frame was successfully sent and ACKed by the recipient.
         * @param elapsedTimeMs The elapsed time between when the management frame was sent and when
         *                      the ACK was processed, in milliseconds, as measured by wificond.
         *                      This includes the time that the send frame spent queuing before it
         *                      was sent, any firmware retries, and the time the received ACK spent
         *                      queuing before it was processed.
         */
        void onAck(int elapsedTimeMs);

        /**
         * Called when the send failed.
         * @param reason The error code for the failure.
         */
        void onFailure(@SendMgmtFrameError int reason);
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SEND_MGMT_FRAME_ERROR_"},
            value = {SEND_MGMT_FRAME_ERROR_UNKNOWN,
                    SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED,
                    SEND_MGMT_FRAME_ERROR_NO_ACK,
                    SEND_MGMT_FRAME_ERROR_TIMEOUT,
                    SEND_MGMT_FRAME_ERROR_ALREADY_STARTED})
    public @interface SendMgmtFrameError {}

    // Send management frame error codes

    /**
     * Unknown error occurred during call to
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}.
     */
    public static final int SEND_MGMT_FRAME_ERROR_UNKNOWN = 1;

    /**
     * Specifying the MCS rate in
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()} is not
     * supported by this device.
     */
    public static final int SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED = 2;

    /**
     * Driver reported that no ACK was received for the frame transmitted using
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}.
     */
    public static final int SEND_MGMT_FRAME_ERROR_NO_ACK = 3;

    /**
     * Error code for when the driver fails to report on the status of the frame sent by
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}
     * after {@link #SEND_MGMT_FRAME_TIMEOUT_MS} milliseconds.
     */
    public static final int SEND_MGMT_FRAME_ERROR_TIMEOUT = 4;

    /**
     * An existing call to
     * {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}
     * is in progress. Another frame cannot be sent until the first call completes.
     */
    public static final int SEND_MGMT_FRAME_ERROR_ALREADY_STARTED = 5;


    public WifiCondManager(Context context) {
        mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
        mEventHandler = new Handler(context.getMainLooper());
    }

    @VisibleForTesting
    public WifiCondManager(Context context, IWificond wificond) {
        this(context);
        mWificond = wificond;
    }

    private class PnoScanEventHandler extends IPnoScanEvent.Stub {
        private ScanEventCallback mCallback;

        PnoScanEventHandler(@NonNull ScanEventCallback callback) {
            mCallback = callback;
        }

        @Override
        public void OnPnoNetworkFound() {
            Log.d(TAG, "Pno scan result event");
            mCallback.onScanResultReady();
        }

        @Override
        public void OnPnoScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            mCallback.onScanFailed();
        }
    }

    /**
     * Listener for AP Interface events.
     */
    private class ApInterfaceEventCallback extends IApInterfaceEventCallback.Stub {
        private SoftApListener mSoftApListener;

        ApInterfaceEventCallback(SoftApListener listener) {
            mSoftApListener = listener;
        }

        @Override
        public void onConnectedClientsChanged(NativeWifiClient client, boolean isConnected) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "onConnectedClientsChanged called with "
                        + client.macAddress + " isConnected: " + isConnected);
            }

            mSoftApListener.onConnectedClientsChanged(client, isConnected);
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            mSoftApListener.onSoftApChannelSwitched(frequency, bandwidth);
        }
    }

    /**
     * Callback triggered by wificond.
     */
    private class SendMgmtFrameEvent extends ISendMgmtFrameEvent.Stub {
        private SendMgmtFrameCallback mCallback;
        private AlarmManager.OnAlarmListener mTimeoutCallback;
        /**
         * ensures that mCallback is only called once
         */
        private boolean mWasCalled;

        private void runIfFirstCall(Runnable r) {
            if (mWasCalled) return;
            mWasCalled = true;

            mSendMgmtFrameInProgress.set(false);
            r.run();
        }

        SendMgmtFrameEvent(@NonNull SendMgmtFrameCallback callback) {
            mCallback = callback;
            // called in main thread
            mTimeoutCallback = () -> runIfFirstCall(() -> {
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "Timed out waiting for ACK");
                }
                mCallback.onFailure(SEND_MGMT_FRAME_ERROR_TIMEOUT);
            });
            mWasCalled = false;

            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + SEND_MGMT_FRAME_TIMEOUT_MS,
                    TIMEOUT_ALARM_TAG, mTimeoutCallback, mEventHandler);
        }

        // called in binder thread
        @Override
        public void OnAck(int elapsedTimeMs) {
            // post to main thread
            mEventHandler.post(() -> runIfFirstCall(() -> {
                mAlarmManager.cancel(mTimeoutCallback);
                mCallback.onAck(elapsedTimeMs);
            }));
        }

        // called in binder thread
        @Override
        public void OnFailure(int reason) {
            // post to main thread
            mEventHandler.post(() -> runIfFirstCall(() -> {
                mAlarmManager.cancel(mTimeoutCallback);
                mCallback.onFailure(reason);
            }));
        }
    }

    /**
     * Called by the binder subsystem upon remote object death.
     * Invoke all the register death handlers and clear state.
     */
    @Override
    public void binderDied() {
        mEventHandler.post(() -> {
            Log.e(TAG, "Wificond died!");
            clearState();
            // Invalidate the global wificond handle on death. Will be refreshed
            // on the next setup call.
            mWificond = null;
            if (mDeathEventHandler != null) {
                mDeathEventHandler.run();
            }
        });
    }

    /** Enable or disable verbose logging of WificondControl.
     *  @param enable True to enable verbose logging. False to disable verbose logging.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Initializes wificond & registers a death notification for wificond.
     * This method clears any existing state in wificond daemon.
     *
     * @return Returns true on success.
     */
    public boolean initialize(@NonNull Runnable deathEventHandler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = deathEventHandler;
        tearDownInterfaces();
        return true;
    }

    /**
     * Helper method to retrieve the global wificond handle and register for
     * death notifications.
     */
    private boolean retrieveWificondAndRegisterForDeath() {
        if (mWificond != null) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Wificond handle already retrieved");
            }
            // We already have a wificond handle.
            return true;
        }
        IBinder binder = ServiceManager.getService(Context.WIFI_COND_SERVICE);
        mWificond = IWificond.Stub.asInterface(binder);
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }
        try {
            mWificond.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register death notification for wificond");
            // The remote has already died.
            return false;
        }
        return true;
    }

    /**
    * Setup interface for client mode via wificond.
    * @return true on success.
    */
    public boolean setupInterfaceForClientMode(@NonNull String ifaceName,
            @NonNull ScanEventCallback scanCallback, @NonNull ScanEventCallback pnoScanCallback) {
        Log.d(TAG, "Setting up interface for client mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        IClientInterface clientInterface = null;
        try {
            clientInterface = mWificond.createClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IClientInterface due to remote exception");
            return false;
        }

        if (clientInterface == null) {
            Log.e(TAG, "Could not get IClientInterface instance from wificond");
            return false;
        }
        Binder.allowBlocking(clientInterface.asBinder());

        // Refresh Handlers
        mClientInterfaces.put(ifaceName, clientInterface);
        try {
            IWifiScannerImpl wificondScanner = clientInterface.getWifiScannerImpl();
            if (wificondScanner == null) {
                Log.e(TAG, "Failed to get WificondScannerImpl");
                return false;
            }
            mWificondScanners.put(ifaceName, wificondScanner);
            Binder.allowBlocking(wificondScanner.asBinder());
            ScanEventHandler scanEventHandler = new ScanEventHandler(scanCallback);
            mScanEventHandlers.put(ifaceName,  scanEventHandler);
            wificondScanner.subscribeScanEvents(scanEventHandler);
            PnoScanEventHandler pnoScanEventHandler = new PnoScanEventHandler(pnoScanCallback);
            mPnoScanEventHandlers.put(ifaceName,  pnoScanEventHandler);
            wificondScanner.subscribePnoScanEvents(pnoScanEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

        return true;
    }

    /**
     * Teardown a specific STA interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownClientInterface(@NonNull String ifaceName) {
        if (getClientInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }
        try {
            IWifiScannerImpl scannerImpl = mWificondScanners.get(ifaceName);
            if (scannerImpl != null) {
                scannerImpl.unsubscribeScanEvents();
                scannerImpl.unsubscribePnoScanEvents();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unsubscribe wificond scanner due to remote exception");
            return false;
        }

        if (mWificond == null) {
            Log.e(TAG, "Reference to wifiCond is null");
            return false;
        }

        boolean success;
        try {
            success = mWificond.tearDownClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown client interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown client interface");
            return false;
        }

        mClientInterfaces.remove(ifaceName);
        mWificondScanners.remove(ifaceName);
        mScanEventHandlers.remove(ifaceName);
        mPnoScanEventHandlers.remove(ifaceName);
        return true;
    }

    /**
    * Setup interface for softAp mode via wificond.
    * @return true on success.
    */
    public boolean setupInterfaceForSoftApMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for soft ap mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        IApInterface apInterface = null;
        try {
            apInterface = mWificond.createApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IApInterface due to remote exception");
            return false;
        }

        if (apInterface == null) {
            Log.e(TAG, "Could not get IApInterface instance from wificond");
            return false;
        }
        Binder.allowBlocking(apInterface.asBinder());

        // Refresh Handlers
        mApInterfaces.put(ifaceName, apInterface);
        return true;
    }

    /**
     * Teardown a specific AP interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownSoftApInterface(@NonNull String ifaceName) {
        if (getApInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond ap interface handler");
            return false;
        }

        if (mWificond == null) {
            Log.e(TAG, "Reference to wifiCond is null");
            return false;
        }

        boolean success;
        try {
            success = mWificond.tearDownApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown AP interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown AP interface");
            return false;
        }
        mApInterfaces.remove(ifaceName);
        mApInterfaceListeners.remove(ifaceName);
        return true;
    }

    /**
    * Teardown all interfaces configured in wificond.
    * @return Returns true on success.
    */
    public boolean tearDownInterfaces() {
        Log.d(TAG, "tearing down interfaces in wificond");
        // Explicitly refresh the wificodn handler because |tearDownInterfaces()|
        // could be used to cleanup before we setup any interfaces.
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        try {
            for (Map.Entry<String, IWifiScannerImpl> entry : mWificondScanners.entrySet()) {
                entry.getValue().unsubscribeScanEvents();
                entry.getValue().unsubscribePnoScanEvents();
            }
            mWificond.tearDownInterfaces();
            clearState();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to tear down interfaces due to remote exception");
        }

        return false;
    }

    /** Helper function to look up the interface handle using name */
    private IClientInterface getClientInterface(@NonNull String ifaceName) {
        return mClientInterfaces.get(ifaceName);
    }

    /**
     * Request signal polling to wificond.
     * @param ifaceName Name of the interface.
     * Returns an SignalPollResult object.
     * Returns null on failure.
     */
    public SignalPollResult signalPoll(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = iface.signalPoll();
            if (resultArray == null || resultArray.length != 4) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        SignalPollResult pollResult = new SignalPollResult();
        pollResult.currentRssi = resultArray[0];
        pollResult.txBitrate = resultArray[1];
        pollResult.associationFrequency = resultArray[2];
        pollResult.rxBitrate = resultArray[3];
        return pollResult;
    }

    /**
     * Fetch TX packet counters on current connection from wificond.
     * @param ifaceName Name of the interface.
     * Returns an TxPacketCounters object.
     * Returns null on failure.
     */
    public TxPacketCounters getTxPacketCounters(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = iface.getPacketCounters();
            if (resultArray == null || resultArray.length != 2) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        TxPacketCounters counters = new TxPacketCounters();
        counters.txSucceeded = resultArray[0];
        counters.txFailed = resultArray[1];
        return counters;
    }

    /** Helper function to look up the scanner impl handle using name */
    private IWifiScannerImpl getScannerImpl(@NonNull String ifaceName) {
        return mWificondScanners.get(ifaceName);
    }

    /**
    * Fetch the latest scan result from kernel via wificond.
    * @param ifaceName Name of the interface.
    * @return Returns an array of native scan results or an empty array on failure.
    */
    @NonNull public List<NativeScanResult> getScanResults(@NonNull String ifaceName,
            @ScanResultType int scanType) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return new ArrayList<>();
        }
        List<NativeScanResult> results = null;
        try {
            if (scanType == SCAN_TYPE_SINGLE_SCAN) {
                results = Arrays.asList(scannerImpl.getScanResults());
            } else {
                results = Arrays.asList(scannerImpl.getPnoScanResults());
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to create ScanDetail ArrayList");
        }
        if (results == null) {
            results = new ArrayList<>();
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "get " + results.size() + " scan results from wificond");
        }

        return results;
    }

    /**
     * Return scan type for the parcelable {@link SingleScanSettings}
     */
    private static int getScanType(@WifiScanner.ScanType int scanType) {
        switch (scanType) {
            case WifiScanner.SCAN_TYPE_LOW_LATENCY:
                return IWifiScannerImpl.SCAN_TYPE_LOW_SPAN;
            case WifiScanner.SCAN_TYPE_LOW_POWER:
                return IWifiScannerImpl.SCAN_TYPE_LOW_POWER;
            case WifiScanner.SCAN_TYPE_HIGH_ACCURACY:
                return IWifiScannerImpl.SCAN_TYPE_HIGH_ACCURACY;
            default:
                throw new IllegalArgumentException("Invalid scan type " + scanType);
        }
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param ifaceName Name of the interface.
     * @param scanType Type of scan to perform.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(@NonNull String ifaceName, @WifiScanner.ScanType int scanType,
            Set<Integer> freqs, List<byte[]> hiddenNetworkSSIDs) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        SingleScanSettings settings = new SingleScanSettings();
        try {
            settings.scanType = getScanType(scanType);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid scan type ", e);
            return false;
        }
        settings.channelSettings  = new ArrayList<>();
        settings.hiddenNetworks  = new ArrayList<>();

        if (freqs != null) {
            for (Integer freq : freqs) {
                ChannelSettings channel = new ChannelSettings();
                channel.frequency = freq;
                settings.channelSettings.add(channel);
            }
        }
        if (hiddenNetworkSSIDs != null) {
            for (byte[] ssid : hiddenNetworkSSIDs) {
                HiddenNetwork network = new HiddenNetwork();
                network.ssid = ssid;

                // settings.hiddenNetworks is expected to be very small, so this shouldn't cause
                // any performance issues.
                if (!settings.hiddenNetworks.contains(network)) {
                    settings.hiddenNetworks.add(network);
                }
            }
        }

        try {
            return scannerImpl.scan(settings);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request scan due to remote exception");
        }
        return false;
    }

    /**
     * Start PNO scan.
     * @param ifaceName Name of the interface.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(@NonNull String ifaceName, PnoSettings pnoSettings,
            PnoScanRequestCallback callback) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }

        try {
            boolean success = scannerImpl.startPnoScan(pnoSettings);
            if (success) {
                callback.onPnoRequestSucceeded();
            } else {
                callback.onPnoRequestFailed();
            }
            return success;
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to start pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Stop PNO scan.
     * @param ifaceName Name of the interface.
     * @return true on success.
     */
    public boolean stopPnoScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        try {
            return scannerImpl.stopPnoScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to stop pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Abort ongoing single scan.
     * @param ifaceName Name of the interface.
     */
    public void abortScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return;
        }
        try {
            scannerImpl.abortScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request abortScan due to remote exception");
        }
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * The following bands are supported {@link @WifiScanner.WifiBandBasic}:
     * WifiScanner.WIFI_BAND_24_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
     * WifiScanner.WIFI_BAND_6_GHZ
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(@WifiScanner.WifiBandBasic int band) {
        if (mWificond == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return null;
        }
        try {
            switch (band) {
                case WifiScanner.WIFI_BAND_24_GHZ:
                    return mWificond.getAvailable2gChannels();
                case WifiScanner.WIFI_BAND_5_GHZ:
                    return mWificond.getAvailable5gNonDFSChannels();
                case WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY:
                    return mWificond.getAvailableDFSChannels();
                case WifiScanner.WIFI_BAND_6_GHZ:
                    return mWificond.getAvailable6gChannels();
                default:
                    throw new IllegalArgumentException("unsupported band " + band);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request getChannelsForBand due to remote exception");
        }
        return null;
    }

    /** Helper function to look up the interface handle using name */
    private IApInterface getApInterface(@NonNull String ifaceName) {
        return mApInterfaces.get(ifaceName);
    }

    /**
     * Register the provided listener for SoftAp events.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean registerApListener(@NonNull String ifaceName, SoftApListener listener) {
        IApInterface iface = getApInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid ap interface handler");
            return false;
        }
        try {
            IApInterfaceEventCallback  callback = new ApInterfaceEventCallback(listener);
            mApInterfaceListeners.put(ifaceName, callback);
            boolean success = iface.registerCallback(callback);
            if (!success) {
                Log.e(TAG, "Failed to register ap callback.");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in registering AP callback: " + e);
            return false;
        }
        return true;
    }

    /**
     * See {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int)}
     */
    public void sendMgmtFrame(@NonNull String ifaceName, @NonNull byte[] frame,
            @NonNull SendMgmtFrameCallback callback, int mcs) {

        if (callback == null) {
            Log.e(TAG, "callback cannot be null!");
            return;
        }

        if (frame == null) {
            Log.e(TAG, "frame cannot be null!");
            callback.onFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        // TODO (b/112029045) validate mcs
        IClientInterface clientInterface = getClientInterface(ifaceName);
        if (clientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            callback.onFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        if (!mSendMgmtFrameInProgress.compareAndSet(false, true)) {
            Log.e(TAG, "An existing management frame transmission is in progress!");
            callback.onFailure(SEND_MGMT_FRAME_ERROR_ALREADY_STARTED);
            return;
        }

        SendMgmtFrameEvent sendMgmtFrameEvent = new SendMgmtFrameEvent(callback);
        try {
            clientInterface.SendMgmtFrame(frame, sendMgmtFrameEvent, mcs);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while starting link probe: " + e);
            // Call sendMgmtFrameEvent.OnFailure() instead of callback.onFailure() so that
            // sendMgmtFrameEvent can clean up internal state, such as cancelling the timer.
            sendMgmtFrameEvent.OnFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN);
        }
    }

    /**
     * Clear all internal handles.
     */
    private void clearState() {
        // Refresh handlers
        mClientInterfaces.clear();
        mWificondScanners.clear();
        mPnoScanEventHandlers.clear();
        mScanEventHandlers.clear();
        mApInterfaces.clear();
        mApInterfaceListeners.clear();
        mSendMgmtFrameInProgress.set(false);
    }
}
