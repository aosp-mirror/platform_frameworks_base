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

package android.net.wifi.nl80211;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Bundle;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class encapsulates the interface the wificond daemon presents to the Wi-Fi framework - used
 * to encapsulate the Wi-Fi 80211nl management interface. The
 * interface is only for use by the Wi-Fi framework and access is protected by SELinux permissions.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.WIFI_NL80211_SERVICE)
public class WifiNl80211Manager {
    private static final String TAG = "WifiNl80211Manager";
    private boolean mVerboseLoggingEnabled = false;

    /**
     * The {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)}
     * timeout, in milliseconds, after which
     * {@link SendMgmtFrameCallback#onFailure(int)} will be called with reason
     * {@link #SEND_MGMT_FRAME_ERROR_TIMEOUT}.
     */
    private static final int SEND_MGMT_FRAME_TIMEOUT_MS = 1000;

    private static final String TIMEOUT_ALARM_TAG = TAG + " Send Management Frame Timeout";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SCAN_TYPE_"},
            value = {SCAN_TYPE_SINGLE_SCAN,
                    SCAN_TYPE_PNO_SCAN})
    public @interface ScanResultType {}

    /**
     * Specifies a scan type: single scan initiated by the framework. Can be used in
     * {@link #getScanResults(String, int)} to specify the type of scan result to fetch.
     */
    public static final int SCAN_TYPE_SINGLE_SCAN = 0;

    /**
     * Specifies a scan type: PNO scan. Can be used in {@link #getScanResults(String, int)} to
     * specify the type of scan result to fetch.
     */
    public static final int SCAN_TYPE_PNO_SCAN = 1;

    // Extra scanning parameter used to enable 6Ghz RNR (Reduced Neighbour Support).
    public static final String SCANNING_PARAM_ENABLE_6GHZ_RNR =
            "android.net.wifi.nl80211.SCANNING_PARAM_ENABLE_6GHZ_RNR";

    private AlarmManager mAlarmManager;
    private Handler mEventHandler;

    // Cached wificond binder handlers.
    private IWificond mWificond;
    private WificondEventHandler mWificondEventHandler = new WificondEventHandler();
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
     * Interface used to listen country code event
     */
    public interface CountryCodeChangeListener {
        /**
         * Called when country code changed.
         *
         * @param countryCode A new country code which is 2-Character alphanumeric.
         */
        void onChanged(@NonNull String countryCode);
    }

    /**
     * Interface used when waiting for scans to be completed (with results).
     */
    public interface ScanEventCallback {
        /**
         * Called when scan results are available. Scans results should then be obtained from
         * {@link #getScanResults(String, int)}.
         */
        void onScanResultReady();

        /**
         * Called when a scan has failed.
         */
        void onScanFailed();
    }

    /**
     * Interface for a callback to provide information about PNO scan request requested with
     * {@link #startPnoScan(String, PnoSettings, Executor, PnoScanRequestCallback)}. Note that the
     * callback are for the status of the request - not the scan itself. The results of the scan
     * are returned with {@link ScanEventCallback}.
     */
    public interface PnoScanRequestCallback {
        /**
         * Called when a PNO scan request has been successfully submitted.
         */
        void onPnoRequestSucceeded();

        /**
         * Called when a PNO scan request fails.
         */
        void onPnoRequestFailed();
    }

    /** @hide */
    @VisibleForTesting
    public class WificondEventHandler extends IWificondEventCallback.Stub {
        private Map<CountryCodeChangeListener, Executor> mCountryCodeChangeListenerHolder =
                new HashMap<>();

        /**
         * Register CountryCodeChangeListener with pid.
         *
         * @param executor The Executor on which to execute the callbacks.
         * @param listener listener for country code changed events.
         */
        public void registerCountryCodeChangeListener(Executor executor,
                CountryCodeChangeListener listener) {
            mCountryCodeChangeListenerHolder.put(listener, executor);
        }

        /**
         * Unregister CountryCodeChangeListener with pid.
         *
         * @param listener listener which registered country code changed events.
         */
        public void unregisterCountryCodeChangeListener(CountryCodeChangeListener listener) {
            mCountryCodeChangeListenerHolder.remove(listener);
        }

        @Override
        public void OnRegDomainChanged(String countryCode) {
            Log.d(TAG, "OnRegDomainChanged " + countryCode);
            final long token = Binder.clearCallingIdentity();
            try {
                mCountryCodeChangeListenerHolder.forEach((listener, executor) -> {
                    executor.execute(() -> listener.onChanged(countryCode));
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private class ScanEventHandler extends IScanEvent.Stub {
        private Executor mExecutor;
        private ScanEventCallback mCallback;

        ScanEventHandler(@NonNull Executor executor, @NonNull ScanEventCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void OnScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onScanResultReady());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void OnScanFailed() {
            Log.d(TAG, "Scan failed event");
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onScanFailed());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * Result of a signal poll requested using {@link #signalPoll(String)}.
     */
    public static class SignalPollResult {
        /** @hide */
        public SignalPollResult(int currentRssiDbm, int txBitrateMbps, int rxBitrateMbps,
                int associationFrequencyMHz) {
            this.currentRssiDbm = currentRssiDbm;
            this.txBitrateMbps = txBitrateMbps;
            this.rxBitrateMbps = rxBitrateMbps;
            this.associationFrequencyMHz = associationFrequencyMHz;
        }

        /**
         * RSSI value in dBM.
         */
        public final int currentRssiDbm;

        /**
         * Transmission bit rate in Mbps.
         */
        public final int txBitrateMbps;

        /**
         * Last received packet bit rate in Mbps.
         */
        public final int rxBitrateMbps;

        /**
         * Association frequency in MHz.
         */
        public final int associationFrequencyMHz;
    }

    /**
     * Transmission counters obtained using {@link #getTxPacketCounters(String)}.
     */
    public static class TxPacketCounters {
        /** @hide */
        public TxPacketCounters(int txPacketSucceeded, int txPacketFailed) {
            this.txPacketSucceeded = txPacketSucceeded;
            this.txPacketFailed = txPacketFailed;
        }

        /**
         * Number of successfully transmitted packets.
         */
        public final int txPacketSucceeded;

        /**
         * Number of packet transmission failures.
         */
        public final int txPacketFailed;
    }

    /**
     * Callbacks for SoftAp interface registered using
     * {@link #registerApCallback(String, Executor, SoftApCallback)}.
     *
     * @deprecated The usage is replaced by vendor HAL
     * {@code android.hardware.wifi.hostapd.V1_3.IHostapdCallback}.
     */
    @Deprecated
    public interface SoftApCallback {
        /**
         * Invoked when there is a fatal failure and the SoftAp is shutdown.
         */
        void onFailure();

        /**
         * Invoked when there is a change in the associated station (STA).
         * @param client Information about the client whose status has changed.
         * @param isConnected Indication as to whether the client is connected (true), or
         *                    disconnected (false).
         */
        void onConnectedClientsChanged(@NonNull NativeWifiClient client, boolean isConnected);

        /**
         * Invoked when a channel switch event happens - i.e. the SoftAp is moved to a different
         * channel. Also called on initial registration.
         * @param frequencyMhz The new frequency of the SoftAp. A value of 0 is invalid and is an
         *                     indication that the SoftAp is not enabled.
         * @param bandwidth The new bandwidth of the SoftAp.
         */
        void onSoftApChannelSwitched(int frequencyMhz, @WifiAnnotations.Bandwidth int bandwidth);
    }

    /**
     * Callback to notify the results of a
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)} call.
     * Note: no callbacks will be triggered if the interface dies while sending a frame.
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

    /** @hide */
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
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)}.
     */
    public static final int SEND_MGMT_FRAME_ERROR_UNKNOWN = 1;

    /**
     * Specifying the MCS rate in
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)} is not
     * supported by this device.
     */
    public static final int SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED = 2;

    /**
     * Driver reported that no ACK was received for the frame transmitted using
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)}.
     */
    public static final int SEND_MGMT_FRAME_ERROR_NO_ACK = 3;

    /**
     * Error code for when the driver fails to report on the status of the frame sent by
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)}
     * after {@link #SEND_MGMT_FRAME_TIMEOUT_MS} milliseconds.
     */
    public static final int SEND_MGMT_FRAME_ERROR_TIMEOUT = 4;

    /**
     * An existing call to
     * {@link #sendMgmtFrame(String, byte[], int, Executor, SendMgmtFrameCallback)}
     * is in progress. Another frame cannot be sent until the first call completes.
     */
    public static final int SEND_MGMT_FRAME_ERROR_ALREADY_STARTED = 5;

    /** @hide */
    public WifiNl80211Manager(Context context) {
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mEventHandler = new Handler(context.getMainLooper());
    }

    /** @hide */
    @VisibleForTesting
    public WifiNl80211Manager(Context context, IWificond wificond) {
        this(context);
        mWificond = wificond;
    }

    /** @hide */
    @VisibleForTesting
    public WificondEventHandler getWificondEventHandler() {
        return mWificondEventHandler;
    }

    private class PnoScanEventHandler extends IPnoScanEvent.Stub {
        private Executor mExecutor;
        private ScanEventCallback mCallback;

        PnoScanEventHandler(@NonNull Executor executor, @NonNull ScanEventCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void OnPnoNetworkFound() {
            Log.d(TAG, "Pno scan result event");
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onScanResultReady());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void OnPnoScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onScanFailed());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * Listener for AP Interface events.
     */
    private class ApInterfaceEventCallback extends IApInterfaceEventCallback.Stub {
        private Executor mExecutor;
        private SoftApCallback mSoftApListener;

        ApInterfaceEventCallback(Executor executor, SoftApCallback listener) {
            mExecutor = executor;
            mSoftApListener = listener;
        }

        @Override
        public void onConnectedClientsChanged(NativeWifiClient client, boolean isConnected) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "onConnectedClientsChanged called with "
                        + client.getMacAddress() + " isConnected: " + isConnected);
            }

            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mSoftApListener.onConnectedClientsChanged(client, isConnected));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mSoftApListener.onSoftApChannelSwitched(frequency,
                        toFrameworkBandwidth(bandwidth)));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private @WifiAnnotations.Bandwidth int toFrameworkBandwidth(int bandwidth) {
            switch(bandwidth) {
                case IApInterfaceEventCallback.BANDWIDTH_INVALID:
                    return SoftApInfo.CHANNEL_WIDTH_INVALID;
                case IApInterfaceEventCallback.BANDWIDTH_20_NOHT:
                    return SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT;
                case IApInterfaceEventCallback.BANDWIDTH_20:
                    return SoftApInfo.CHANNEL_WIDTH_20MHZ;
                case IApInterfaceEventCallback.BANDWIDTH_40:
                    return SoftApInfo.CHANNEL_WIDTH_40MHZ;
                case IApInterfaceEventCallback.BANDWIDTH_80:
                    return SoftApInfo.CHANNEL_WIDTH_80MHZ;
                case IApInterfaceEventCallback.BANDWIDTH_80P80:
                    return SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
                case IApInterfaceEventCallback.BANDWIDTH_160:
                    return SoftApInfo.CHANNEL_WIDTH_160MHZ;
                default:
                    return SoftApInfo.CHANNEL_WIDTH_INVALID;
            }
        }
    }

    /**
     * Callback triggered by wificond.
     */
    private class SendMgmtFrameEvent extends ISendMgmtFrameEvent.Stub {
        private Executor mExecutor;
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

        SendMgmtFrameEvent(@NonNull Executor executor, @NonNull SendMgmtFrameCallback callback) {
            mExecutor = executor;
            mCallback = callback;
            // called in main thread
            mTimeoutCallback = () -> runIfFirstCall(() -> {
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "Timed out waiting for ACK");
                }
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onFailure(SEND_MGMT_FRAME_ERROR_TIMEOUT));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
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
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onAck(elapsedTimeMs));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }));
        }

        // called in binder thread
        @Override
        public void OnFailure(int reason) {
            // post to main thread
            mEventHandler.post(() -> runIfFirstCall(() -> {
                mAlarmManager.cancel(mTimeoutCallback);
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onFailure(reason));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }));
        }
    }

    /**
     * Called by the binder subsystem upon remote object death.
     * Invoke all the register death handlers and clear state.
     * @hide
     */
    @VisibleForTesting
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

    /**
     * Enable or disable verbose logging of the WifiNl80211Manager module.
     * @param enable True to enable verbose logging. False to disable verbose logging.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Register a death notification for the WifiNl80211Manager which acts as a proxy for the
     * wificond daemon (i.e. the death listener will be called when and if the wificond daemon
     * dies).
     *
     * @param deathEventHandler A {@link Runnable} to be called whenever the wificond daemon dies.
     */
    public void setOnServiceDeadCallback(@NonNull Runnable deathEventHandler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = deathEventHandler;
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
        IBinder binder = ServiceManager.getService(Context.WIFI_NL80211_SERVICE);
        mWificond = IWificond.Stub.asInterface(binder);
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }
        try {
            mWificond.asBinder().linkToDeath(() -> binderDied(), 0);
            mWificond.registerWificondEventCallback(mWificondEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register death notification for wificond");
            // The remote has already died.
            return false;
        }
        return true;
    }

    /**
     * Set up an interface for client (STA) mode.
     *
     * @param ifaceName Name of the interface to configure.
     * @param executor The Executor on which to execute the callbacks.
     * @param scanCallback A callback for framework initiated scans.
     * @param pnoScanCallback A callback for PNO (offloaded) scans.
     * @return true on success.
     */
    public boolean setupInterfaceForClientMode(@NonNull String ifaceName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ScanEventCallback scanCallback, @NonNull ScanEventCallback pnoScanCallback) {
        Log.d(TAG, "Setting up interface for client mode: " + ifaceName);
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        if (scanCallback == null || pnoScanCallback == null || executor == null) {
            Log.e(TAG, "setupInterfaceForClientMode invoked with null callbacks");
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
            ScanEventHandler scanEventHandler = new ScanEventHandler(executor, scanCallback);
            mScanEventHandlers.put(ifaceName,  scanEventHandler);
            wificondScanner.subscribeScanEvents(scanEventHandler);
            PnoScanEventHandler pnoScanEventHandler = new PnoScanEventHandler(executor,
                    pnoScanCallback);
            mPnoScanEventHandlers.put(ifaceName,  pnoScanEventHandler);
            wificondScanner.subscribePnoScanEvents(pnoScanEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

        return true;
    }

    /**
     * Tear down a specific client (STA) interface configured using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}.
     *
     * @param ifaceName Name of the interface to tear down.
     * @return Returns true on success, false on failure (e.g. when called before an interface was
     * set up).
     */
    public boolean tearDownClientInterface(@NonNull String ifaceName) {
        if (getClientInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond client interface handler for iface=" + ifaceName);
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
            Log.e(TAG, "tearDownClientInterface: mWificond binder is null! Did wificond die?");
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
     * Set up interface as a Soft AP.
     *
     * @param ifaceName Name of the interface to configure.
     * @return true on success.
     */
    public boolean setupInterfaceForSoftApMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for soft ap mode for iface=" + ifaceName);
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
     * Tear down a Soft AP interface configured using
     * {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface to tear down.
     * @return Returns true on success, false on failure (e.g. when called before an interface was
     * set up).
     */
    public boolean tearDownSoftApInterface(@NonNull String ifaceName) {
        if (getApInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond ap interface handler for iface=" + ifaceName);
            return false;
        }

        if (mWificond == null) {
            Log.e(TAG, "tearDownSoftApInterface: mWificond binder is null! Did wificond die?");
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
    * Tear down all interfaces, whether clients (STA) or Soft AP.
     *
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
     * Request signal polling.
     *
     * @param ifaceName Name of the interface on which to poll. The interface must have been
     *                  already set up using
     *{@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     *                  or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @return A {@link SignalPollResult} object containing interface statistics, or a null on
     * error (e.g. the interface hasn't been set up yet).
     */
    @Nullable public SignalPollResult signalPoll(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler for iface=" + ifaceName);
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
        return new SignalPollResult(resultArray[0], resultArray[1], resultArray[3], resultArray[2]);
    }

    /**
     * Get current transmit (Tx) packet counters of the specified interface. The interface must
     * have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface.
     * @return {@link TxPacketCounters} of the current interface or null on error (e.g. when
     * called before the interface has been set up).
     */
    @Nullable public TxPacketCounters getTxPacketCounters(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler for iface=" + ifaceName);
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
        return new TxPacketCounters(resultArray[0], resultArray[1]);
    }

    /** Helper function to look up the scanner impl handle using name */
    private IWifiScannerImpl getScannerImpl(@NonNull String ifaceName) {
        return mWificondScanners.get(ifaceName);
    }

    /**
     * Fetch the latest scan results of the indicated type for the specified interface. Note that
     * this method fetches the latest results - it does not initiate a scan. Initiating a scan can
     * be done using {@link #startScan(String, int, Set, List)} or
     * {@link #startPnoScan(String, PnoSettings, Executor, PnoScanRequestCallback)}.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface.
     * @param scanType The type of scan result to be returned, can be
     * {@link #SCAN_TYPE_SINGLE_SCAN} or {@link #SCAN_TYPE_PNO_SCAN}.
     * @return Returns an array of {@link NativeScanResult} or an empty array on failure (e.g. when
     * called before the interface has been set up).
     */
    @NonNull public List<NativeScanResult> getScanResults(@NonNull String ifaceName,
            @ScanResultType int scanType) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler for iface=" + ifaceName);
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
    private static int getScanType(@WifiAnnotations.ScanType int scanType) {
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
     * @deprecated replaced by {@link #startScan(String, int, Set, List, Bundle)}
     **/
    @Deprecated
    public boolean startScan(@NonNull String ifaceName, @WifiAnnotations.ScanType int scanType,
            @Nullable Set<Integer> freqs, @Nullable List<byte[]> hiddenNetworkSSIDs) {
        return startScan(ifaceName, scanType, freqs, hiddenNetworkSSIDs, null);
    }

    /**
     * Start a scan using the specified parameters. A scan is an asynchronous operation. The
     * result of the operation is returned in the {@link ScanEventCallback} registered when
     * setting up an interface using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}.
     * The latest scans can be obtained using {@link #getScanResults(String, int)} and using a
     * {@link #SCAN_TYPE_SINGLE_SCAN} for the {@code scanType}.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface on which to initiate the scan.
     * @param scanType Type of scan to perform, can be any of
     * {@link WifiScanner#SCAN_TYPE_HIGH_ACCURACY}, {@link WifiScanner#SCAN_TYPE_LOW_POWER}, or
     * {@link WifiScanner#SCAN_TYPE_LOW_LATENCY}.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for, a null indicates that
     *                           no hidden frequencies will be scanned for.
     * @param extraScanningParams bundle of extra scanning parameters.
     * @return Returns true on success, false on failure (e.g. when called before the interface
     * has been set up).
     */
    public boolean startScan(@NonNull String ifaceName, @WifiAnnotations.ScanType int scanType,
            @SuppressLint("NullableCollection") @Nullable Set<Integer> freqs,
            @SuppressLint("NullableCollection") @Nullable List<byte[]> hiddenNetworkSSIDs,
            @SuppressLint("NullableCollection") @Nullable Bundle extraScanningParams) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler for iface=" + ifaceName);
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
        if (extraScanningParams != null) {
            settings.enable6GhzRnr = extraScanningParams.getBoolean(SCANNING_PARAM_ENABLE_6GHZ_RNR);
        }

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
     * Request a PNO (Preferred Network Offload). The offload request and the scans are asynchronous
     * operations. The result of the request are returned in the {@code callback} parameter which
     * is an {@link PnoScanRequestCallback}. The scan results are are return in the
     * {@link ScanEventCallback} which is registered when setting up an interface using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}.
     * The latest PNO scans can be obtained using {@link #getScanResults(String, int)} with the
     * {@code scanType} set to {@link #SCAN_TYPE_PNO_SCAN}.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface on which to request a PNO.
     * @param pnoSettings PNO scan configuration.
     * @param executor The Executor on which to execute the callback.
     * @param callback Callback for the results of the offload request.
     * @return true on success, false on failure (e.g. when called before the interface has been set
     * up).
     */
    public boolean startPnoScan(@NonNull String ifaceName, @NonNull PnoSettings pnoSettings,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull PnoScanRequestCallback callback) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler for iface=" + ifaceName);
            return false;
        }

        if (callback == null || executor == null) {
            Log.e(TAG, "startPnoScan called with a null callback");
            return false;
        }

        try {
            boolean success = scannerImpl.startPnoScan(pnoSettings);
            if (success) {
                executor.execute(callback::onPnoRequestSucceeded);
            } else {
                executor.execute(callback::onPnoRequestFailed);
            }
            return success;
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to start pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Stop PNO scan configured with
     * {@link #startPnoScan(String, PnoSettings, Executor, PnoScanRequestCallback)}.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName Name of the interface on which the PNO scan was configured.
     * @return true on success, false on failure (e.g. when called before the interface has been
     * set up).
     */
    public boolean stopPnoScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler for iface=" + ifaceName);
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
     * Abort ongoing single scan started with {@link #startScan(String, int, Set, List)}. No failure
     * callback, e.g. {@link ScanEventCallback#onScanFailed()}, is triggered by this operation.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}. If the interface has not been set up then
     * this method has no impact.
     *
     * @param ifaceName Name of the interface on which the scan was started.
     */
    public void abortScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler for iface=" + ifaceName);
            return;
        }
        try {
            scannerImpl.abortScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request abortScan due to remote exception");
        }
    }

    /**
     * Query the list of valid frequencies (in MHz) for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * The following bands are supported:
     * {@link WifiScanner#WIFI_BAND_24_GHZ},
     * {@link WifiScanner#WIFI_BAND_5_GHZ},
     * {@link WifiScanner#WIFI_BAND_5_GHZ_DFS_ONLY},
     * {@link WifiScanner#WIFI_BAND_6_GHZ}
     * {@link WifiScanner.WIFI_BAND_60_GHZ}
     * @return frequencies vector of valid frequencies (MHz), or an empty array for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public @NonNull int[] getChannelsMhzForBand(@WifiAnnotations.WifiBandBasic int band) {
        if (mWificond == null) {
            Log.e(TAG, "getChannelsMhzForBand: mWificond binder is null! Did wificond die?");
            return new int[0];
        }
        int[] result = null;
        try {
            switch (band) {
                case WifiScanner.WIFI_BAND_24_GHZ:
                    result = mWificond.getAvailable2gChannels();
                    break;
                case WifiScanner.WIFI_BAND_5_GHZ:
                    result = mWificond.getAvailable5gNonDFSChannels();
                    break;
                case WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY:
                    result = mWificond.getAvailableDFSChannels();
                    break;
                case WifiScanner.WIFI_BAND_6_GHZ:
                    result = mWificond.getAvailable6gChannels();
                    break;
                case WifiScanner.WIFI_BAND_60_GHZ:
                    result = mWificond.getAvailable60gChannels();
                    break;
                default:
                    throw new IllegalArgumentException("unsupported band " + band);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request getChannelsForBand due to remote exception");
        }
        if (result == null) {
            result = new int[0];
        }
        return result;
    }

    /** Helper function to look up the interface handle using name */
    private IApInterface getApInterface(@NonNull String ifaceName) {
        return mApInterfaces.get(ifaceName);
    }

    /**
     * Get the device phy capabilities for a given interface.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @return DeviceWiphyCapabilities or null on error (e.g. when called on an interface which has
     * not been set up).
     */
    @Nullable public DeviceWiphyCapabilities getDeviceWiphyCapabilities(@NonNull String ifaceName) {
        if (mWificond == null) {
            Log.e(TAG, "getDeviceWiphyCapabilities: mWificond binder is null! Did wificond die?");
            return null;
        }

        try {
            return mWificond.getDeviceWiphyCapabilities(ifaceName);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Register the provided listener for country code event.
     *
     * @param executor The Executor on which to execute the callbacks.
     * @param listener listener for country code changed events.
     * @return true on success, false on failure.
     */
    public boolean registerCountryCodeChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull CountryCodeChangeListener listener) {
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }
        Log.d(TAG, "registerCountryCodeEventListener called");
        mWificondEventHandler.registerCountryCodeChangeListener(executor, listener);
        return true;
    }


    /**
     * Unregister CountryCodeChangeListener with pid.
     *
     * @param listener listener which registered country code changed events.
     */
    public void unregisterCountryCodeChangeListener(@NonNull CountryCodeChangeListener listener) {
        Log.d(TAG, "unregisterCountryCodeEventListener called");
        mWificondEventHandler.unregisterCountryCodeChangeListener(listener);
    }

    /**
     * Register the provided callback handler for SoftAp events. The interface must first be created
     * using {@link #setupInterfaceForSoftApMode(String)}. The callback registration is valid until
     * the interface is deleted using {@link #tearDownSoftApInterface(String)} (no deregistration
     * method is provided).
     * <p>
     * Note that only one callback can be registered at a time - any registration overrides previous
     * registrations.
     *
     * @param ifaceName Name of the interface on which to register the callback.
     * @param executor The Executor on which to execute the callbacks.
     * @param callback Callback for AP events.
     * @return true on success, false on failure (e.g. when called on an interface which has not
     * been set up).
     *
     * @deprecated The usage is replaced by vendor HAL
     * {@code android.hardware.wifi.hostapd.V1_3.IHostapdCallback}.
     */
    @Deprecated
    public boolean registerApCallback(@NonNull String ifaceName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SoftApCallback callback) {
        IApInterface iface = getApInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid ap interface handler for iface=" + ifaceName);
            return false;
        }

        if (callback == null || executor == null) {
            Log.e(TAG, "registerApCallback called with a null callback");
            return false;
        }

        try {
            IApInterfaceEventCallback wificondCallback = new ApInterfaceEventCallback(executor,
                    callback);
            mApInterfaceListeners.put(ifaceName, wificondCallback);
            boolean success = iface.registerCallback(wificondCallback);
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
     * Send a management frame on the specified interface at the specified rate. Useful for probing
     * the link with arbitrary frames.
     *
     * Note: The interface must have been already set up using
     * {@link #setupInterfaceForClientMode(String, Executor, ScanEventCallback, ScanEventCallback)}
     * or {@link #setupInterfaceForSoftApMode(String)}.
     *
     * @param ifaceName The interface on which to send the frame.
     * @param frame The raw byte array of the management frame to tramit.
     * @param mcs The MCS (modulation and coding scheme), i.e. rate, at which to transmit the
     *            frame. Specified per IEEE 802.11.
     * @param executor The Executor on which to execute the callbacks.
     * @param callback A {@link SendMgmtFrameCallback} callback for results of the operation.
     */
    public void sendMgmtFrame(@NonNull String ifaceName, @NonNull byte[] frame, int mcs,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SendMgmtFrameCallback callback) {

        if (callback == null || executor == null) {
            Log.e(TAG, "callback cannot be null!");
            return;
        }

        if (frame == null) {
            Log.e(TAG, "frame cannot be null!");
            executor.execute(() -> callback.onFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN));
            return;
        }

        // TODO (b/112029045) validate mcs
        IClientInterface clientInterface = getClientInterface(ifaceName);
        if (clientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler for iface=" + ifaceName);
            executor.execute(() -> callback.onFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN));
            return;
        }

        if (!mSendMgmtFrameInProgress.compareAndSet(false, true)) {
            Log.e(TAG, "An existing management frame transmission is in progress!");
            executor.execute(() -> callback.onFailure(SEND_MGMT_FRAME_ERROR_ALREADY_STARTED));
            return;
        }

        SendMgmtFrameEvent sendMgmtFrameEvent = new SendMgmtFrameEvent(executor, callback);
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

    /**
     * OEM parsed security type
     */
    public static class OemSecurityType {
        /** The protocol defined in {@link android.net.wifi.WifiAnnotations.Protocol}. */
        public final @WifiAnnotations.Protocol int protocol;
        /**
         * Supported key management types defined
         * in {@link android.net.wifi.WifiAnnotations.KeyMgmt}.
         */
        @NonNull public final List<Integer> keyManagement;
        /**
         * Supported pairwise cipher types defined
         * in {@link android.net.wifi.WifiAnnotations.Cipher}.
         */
        @NonNull public final List<Integer> pairwiseCipher;
        /** The group cipher type defined in {@link android.net.wifi.WifiAnnotations.Cipher}. */
        public final @WifiAnnotations.Cipher int groupCipher;
        /**
         * Default constructor for OemSecurityType
         *
         * @param protocol The protocol defined in
         *                 {@link android.net.wifi.WifiAnnotations.Protocol}.
         * @param keyManagement Supported key management types defined
         *                      in {@link android.net.wifi.WifiAnnotations.KeyMgmt}.
         * @param pairwiseCipher Supported pairwise cipher types defined
         *                       in {@link android.net.wifi.WifiAnnotations.Cipher}.
         * @param groupCipher The group cipher type defined
         *                    in {@link android.net.wifi.WifiAnnotations.Cipher}.
         */
        public OemSecurityType(
                @WifiAnnotations.Protocol int protocol,
                @NonNull List<Integer> keyManagement,
                @NonNull List<Integer> pairwiseCipher,
                @WifiAnnotations.Cipher int groupCipher) {
            this.protocol = protocol;
            this.keyManagement = (keyManagement != null)
                ? keyManagement : new ArrayList<Integer>();
            this.pairwiseCipher = (pairwiseCipher != null)
                ? pairwiseCipher : new ArrayList<Integer>();
            this.groupCipher = groupCipher;
        }
    }

    /**
     * OEM information element parser for security types not parsed by the framework.
     *
     * The OEM method should use the method inputs {@code id}, {@code idExt}, and {@code bytes}
     * to perform the parsing. The method should place the results in an OemSecurityType objct.
     *
     * @param id The information element id.
     * @param idExt The information element extension id. This is valid only when id is
     *        the extension id, {@code 255}.
     * @param bytes The raw bytes of information element data, 'Element ID' and 'Length' are
     *              stripped off already.
     * @return an OemSecurityType object if this IE is parsed successfully, null otherwise.
     */
    @Nullable public static OemSecurityType parseOemSecurityTypeElement(
            int id,
            int idExt,
            @NonNull byte[] bytes) {
        return null;
    }
}
