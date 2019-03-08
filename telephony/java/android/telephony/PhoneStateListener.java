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

package android.telephony;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IPhoneStateListener;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A listener class for monitoring changes in specific telephony states
 * on the device, including service state, signal strength, message
 * waiting indicator (voicemail), and others.
 * <p>
 * Override the methods for the state that you wish to receive updates for, and
 * pass your PhoneStateListener object, along with bitwise-or of the LISTEN_
 * flags to {@link TelephonyManager#listen TelephonyManager.listen()}. Methods are
 * called when the state changes, as well as once on initial registration.
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application won't receive updates for protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * appropriate LISTEN_ flags.
 */
public class PhoneStateListener {
    private static final String LOG_TAG = "PhoneStateListener";
    private static final boolean DBG = false; // STOPSHIP if true

    /**
     * Stop listening for updates.
     */
    public static final int LISTEN_NONE = 0;

    /**
     *  Listen for changes to the network service state (cellular).
     *
     *  @see #onServiceStateChanged
     *  @see ServiceState
     */
    public static final int LISTEN_SERVICE_STATE                            = 0x00000001;

    /**
     * Listen for changes to the network signal strength (cellular).
     * {@more}
     *
     * @see #onSignalStrengthChanged
     *
     * @deprecated by {@link #LISTEN_SIGNAL_STRENGTHS}
     */
    @Deprecated
    public static final int LISTEN_SIGNAL_STRENGTH                          = 0x00000002;

    /**
     * Listen for changes to the message-waiting indicator.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE
     * READ_PHONE_STATE} or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     * <p>
     * Example: The status bar uses this to determine when to display the
     * voicemail icon.
     *
     * @see #onMessageWaitingIndicatorChanged
     */
    public static final int LISTEN_MESSAGE_WAITING_INDICATOR                = 0x00000004;

    /**
     * Listen for changes to the call-forwarding indicator.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE
     * READ_PHONE_STATE} or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onCallForwardingIndicatorChanged
     */
    public static final int LISTEN_CALL_FORWARDING_INDICATOR                = 0x00000008;

    /**
     * Listen for changes to the device's cell location. Note that
     * this will result in frequent callbacks to the listener.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#ACCESS_COARSE_LOCATION
     * ACCESS_COARSE_LOCATION}
     * <p>
     * If you need regular location updates but want more control over
     * the update interval or location precision, you can set up a listener
     * through the {@link android.location.LocationManager location manager}
     * instead.
     *
     * @see #onCellLocationChanged
     */
    public static final int LISTEN_CELL_LOCATION                            = 0x00000010;

    /**
     * Listen for changes to the device call state.
     * {@more}
     *
     * @see #onCallStateChanged
     */
    public static final int LISTEN_CALL_STATE                               = 0x00000020;

    /**
     * Listen for changes to the data connection state (cellular).
     *
     * @see #onDataConnectionStateChanged
     */
    public static final int LISTEN_DATA_CONNECTION_STATE                    = 0x00000040;

    /**
     * Listen for changes to the direction of data traffic on the data
     * connection (cellular).
     * {@more}
     * Example: The status bar uses this to display the appropriate
     * data-traffic icon.
     *
     * @see #onDataActivity
     */
    public static final int LISTEN_DATA_ACTIVITY                            = 0x00000080;

    /**
     * Listen for changes to the network signal strengths (cellular).
     * <p>
     * Example: The status bar uses this to control the signal-strength
     * icon.
     *
     * @see #onSignalStrengthsChanged
     */
    public static final int LISTEN_SIGNAL_STRENGTHS                         = 0x00000100;

    /**
     * Listen for changes to OTASP mode.
     *
     * @see #onOtaspChanged
     * @hide
     */
    public static final int LISTEN_OTASP_CHANGED                            = 0x00000200;

    /**
     * Listen for changes to observed cell info.
     *
     * @see #onCellInfoChanged
     */
    public static final int LISTEN_CELL_INFO = 0x00000400;

    /**
     * Listen for {@link PreciseCallState.State} of ringing, background and foreground calls.
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public static final int LISTEN_PRECISE_CALL_STATE                       = 0x00000800;

    /**
     * Listen for {@link PreciseDataConnectionState} on the data connection (cellular).
     *
     * @see #onPreciseDataConnectionStateChanged
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public static final int LISTEN_PRECISE_DATA_CONNECTION_STATE            = 0x00001000;

    /**
     * Listen for real time info for all data connections (cellular)).
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     * @see #onDataConnectionRealTimeInfoChanged(DataConnectionRealTimeInfo)
     *
     * @deprecated Use {@link TelephonyManager#getModemActivityInfo()}
     * @hide
     */
    @Deprecated
    public static final int LISTEN_DATA_CONNECTION_REAL_TIME_INFO           = 0x00002000;

    /**
     * Listen for changes to the SRVCC state of the active call.
     * @see #onServiceStateChanged(ServiceState)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int LISTEN_SRVCC_STATE_CHANGED                     = 0x00004000;

    /**
     * Listen for OEM hook raw event
     *
     * @see #onOemHookRawEvent
     * @hide
     * @deprecated OEM needs a vendor-extension hal and their apps should use that instead
     */
    @Deprecated
    public static final int LISTEN_OEM_HOOK_RAW_EVENT                       = 0x00008000;

    /**
     * Listen for carrier network changes indicated by a carrier app.
     *
     * @see #onCarrierNetworkRequest
     * @see TelephonyManager#notifyCarrierNetworkChange(boolean)
     * @hide
     */
    public static final int LISTEN_CARRIER_NETWORK_CHANGE                   = 0x00010000;

    /**
     * Listen for changes to the sim voice activation state
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     * {@more}
     * Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates voice service has been
     * fully activated
     *
     * @see #onVoiceActivationStateChanged
     * @hide
     */
    @SystemApi
    public static final int LISTEN_VOICE_ACTIVATION_STATE                   = 0x00020000;

    /**
     * Listen for changes to the sim data activation state
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     * {@more}
     * Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates data service has been
     * fully activated
     *
     * @see #onDataActivationStateChanged
     * @hide
     */
    public static final int LISTEN_DATA_ACTIVATION_STATE                   = 0x00040000;

    /**
     *  Listen for changes to the user mobile data state
     *
     *  @see #onUserMobileDataStateChanged
     */
    public static final int LISTEN_USER_MOBILE_DATA_STATE                  = 0x00080000;

    /**
     *  Listen for changes to the physical channel configuration.
     *
     *  @see #onPhysicalChannelConfigurationChanged
     *  @hide
     */
    public static final int LISTEN_PHYSICAL_CHANNEL_CONFIGURATION          = 0x00100000;

    /**
     *  Listen for changes to the phone capability.
     *
     *  @see #onPhoneCapabilityChanged
     *  @hide
     */
    public static final int LISTEN_PHONE_CAPABILITY_CHANGE                 = 0x00200000;

    /**
     *  Listen for changes to active data subId. Active data subscription is
     *  the current subscription used to setup Cellular Internet data. For example,
     *  it could be the current active opportunistic subscription in use, or the
     *  subscription user selected as default data subscription in DSDS mode.
     *
     *  Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE
     *  READ_PHONE_STATE}
     *  @see #onActiveDataSubscriptionIdChanged
     */
    public static final int LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE = 0x00400000;

    /**
     *  Listen for changes to the radio power state.
     *
     *  @see #onRadioPowerStateChanged
     *  @hide
     */
    @SystemApi
    public static final int LISTEN_RADIO_POWER_STATE_CHANGED               = 0x00800000;

    /**
     * Listen for changes to emergency number list based on all active subscriptions.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onEmergencyNumberListChanged
     */
    public static final int LISTEN_EMERGENCY_NUMBER_LIST                   = 0x01000000;

    /**
     * Listen for call disconnect causes which contains {@link DisconnectCause} and
     * {@link PreciseDisconnectCause}.
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public static final int LISTEN_CALL_DISCONNECT_CAUSES                  = 0x02000000;

    /**
     * Listen for changes to the call attributes of a currently active call.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     *
     * @see #onCallAttributesChanged
     * @hide
     */
    @SystemApi
    public static final int LISTEN_CALL_ATTRIBUTES_CHANGED                 = 0x04000000;

    /**
     * Listen for IMS call disconnect causes which contains
     * {@link android.telephony.ims.ImsReasonInfo}
     *
     * @see #onImsCallDisconnectCauseChanged(ImsReasonInfo)
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public static final int LISTEN_IMS_CALL_DISCONNECT_CAUSES              = 0x08000000;

    /*
     * Subscription used to listen to the phone state changes
     * @hide
     */
    /** @hide */
    @UnsupportedAppUsage
    protected Integer mSubId;

    /**
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @UnsupportedAppUsage
    public final IPhoneStateListener callback;

    /**
     * Create a PhoneStateListener for the Phone with the default subscription.
     * This class requires Looper.myLooper() not return null.
     */
    public PhoneStateListener() {
        this(null, Looper.myLooper());
    }

    /**
     * Create a PhoneStateListener for the Phone with the default subscription
     * using a particular non-null Looper.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public PhoneStateListener(Looper looper) {
        this(null, looper);
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified subscription.
     * This class requires Looper.myLooper() not return null. To supply your
     * own non-null Looper use PhoneStateListener(int subId, Looper looper) below.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public PhoneStateListener(Integer subId) {
        this(subId, Looper.myLooper());
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified subscription
     * and non-null Looper.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public PhoneStateListener(Integer subId, Looper looper) {
        this(subId, new HandlerExecutor(new Handler(looper)));
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified Executor
     *
     * <p>Create a PhoneStateListener with a specified Executor for handling necessary callbacks.
     * The Executor must not be null.
     *
     * @param executor a non-null Executor that will execute callbacks for the PhoneStateListener.
     */
    public PhoneStateListener(@NonNull Executor executor) {
        this(null, executor);
    }

    private PhoneStateListener(Integer subId, Executor e) {
        if (e == null) {
            throw new IllegalArgumentException("PhoneStateListener Executor must be non-null");
        }
        mSubId = subId;
        callback = new IPhoneStateListenerStub(this, e);
    }

    /**
     * Callback invoked when device service state changes.
     *
     * @see ServiceState#STATE_EMERGENCY_ONLY
     * @see ServiceState#STATE_IN_SERVICE
     * @see ServiceState#STATE_OUT_OF_SERVICE
     * @see ServiceState#STATE_POWER_OFF
     */
    public void onServiceStateChanged(ServiceState serviceState) {
        // default implementation empty
    }

    /**
     * Callback invoked when network signal strength changes.
     *
     * @see ServiceState#STATE_EMERGENCY_ONLY
     * @see ServiceState#STATE_IN_SERVICE
     * @see ServiceState#STATE_OUT_OF_SERVICE
     * @see ServiceState#STATE_POWER_OFF
     * @deprecated Use {@link #onSignalStrengthsChanged(SignalStrength)}
     */
    @Deprecated
    public void onSignalStrengthChanged(int asu) {
        // default implementation empty
    }

    /**
     * Callback invoked when the message-waiting indicator changes.
     */
    public void onMessageWaitingIndicatorChanged(boolean mwi) {
        // default implementation empty
    }

    /**
     * Callback invoked when the call-forwarding indicator changes.
     */
    public void onCallForwardingIndicatorChanged(boolean cfi) {
        // default implementation empty
    }

    /**
     * Callback invoked when device cell location changes.
     */
    public void onCellLocationChanged(CellLocation location) {
        // default implementation empty
    }

    /**
     * Callback invoked when device call state changes.
     * <p>
     * Reports the state of Telephony (mobile) calls on the device.
     * <p>
     * Note: The state returned here may differ from that returned by
     * {@link TelephonyManager#getCallState()}. Receivers of this callback should be aware that
     * calling {@link TelephonyManager#getCallState()} from within this callback may return a
     * different state than the callback reports.
     *
     * @param state call state
     * @param phoneNumber call phone number. If application does not have
     * {@link android.Manifest.permission#READ_CALL_LOG READ_CALL_LOG} permission or carrier
     * privileges (see {@link TelephonyManager#hasCarrierPrivileges}), an empty string will be
     * passed as an argument.
     */
    public void onCallStateChanged(@TelephonyManager.CallState int state, String phoneNumber) {
        // default implementation empty
    }

    /**
     * Callback invoked when connection state changes.
     *
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     */
    public void onDataConnectionStateChanged(int state) {
        // default implementation empty
    }

    /**
     * same as above, but with the network type.  Both called.
     */
    public void onDataConnectionStateChanged(int state, int networkType) {
    }

    /**
     * Callback invoked when data activity state changes.
     *
     * @see TelephonyManager#DATA_ACTIVITY_NONE
     * @see TelephonyManager#DATA_ACTIVITY_IN
     * @see TelephonyManager#DATA_ACTIVITY_OUT
     * @see TelephonyManager#DATA_ACTIVITY_INOUT
     * @see TelephonyManager#DATA_ACTIVITY_DORMANT
     */
    public void onDataActivity(int direction) {
        // default implementation empty
    }

    /**
     * Callback invoked when network signal strengths changes.
     *
     * @see ServiceState#STATE_EMERGENCY_ONLY
     * @see ServiceState#STATE_IN_SERVICE
     * @see ServiceState#STATE_OUT_OF_SERVICE
     * @see ServiceState#STATE_POWER_OFF
     */
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        // default implementation empty
    }


    /**
     * The Over The Air Service Provisioning (OTASP) has changed. Requires
     * the READ_PHONE_STATE permission.
     * @param otaspMode is integer <code>OTASP_UNKNOWN=1<code>
     *   means the value is currently unknown and the system should wait until
     *   <code>OTASP_NEEDED=2<code> or <code>OTASP_NOT_NEEDED=3<code> is received before
     *   making the decision to perform OTASP or not.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void onOtaspChanged(int otaspMode) {
        // default implementation empty
    }

    /**
     * Callback invoked when a observed cell info has changed,
     * or new cells have been added or removed.
     * @param cellInfo is the list of currently visible cells.
     */
    public void onCellInfoChanged(List<CellInfo> cellInfo) {
    }

    /**
     * Callback invoked when precise device call state changes.
     * @param callState {@link PreciseCallState}
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public void onPreciseCallStateChanged(@NonNull PreciseCallState callState) {
        // default implementation empty
    }

    /**
     * Callback invoked when call disconnect cause changes.
     * @param disconnectCause {@link DisconnectCause}.
     * @param preciseDisconnectCause {@link PreciseDisconnectCause}.
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public void onCallDisconnectCauseChanged(int disconnectCause, int preciseDisconnectCause) {
        // default implementation empty
    }

    /**
     * Callback invoked when Ims call disconnect cause changes.
     * @param imsReasonInfo {@link ImsReasonInfo} contains details on why IMS call failed.
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public void onImsCallDisconnectCauseChanged(@NonNull ImsReasonInfo imsReasonInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when data connection state changes with precise information.
     * @param dataConnectionState {@link PreciseDataConnectionState}
     *
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRECISE_PHONE_STATE))
    @SystemApi
    public void onPreciseDataConnectionStateChanged(
            @NonNull PreciseDataConnectionState dataConnectionState) {
        // default implementation empty
    }

    /**
     * Callback invoked when data connection state changes with precise information.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void onDataConnectionRealTimeInfoChanged(
            DataConnectionRealTimeInfo dcRtInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when there has been a change in the Single Radio Voice Call Continuity
     * (SRVCC) state for the currently active call.
     * @hide
     */
    @SystemApi
    public void onSrvccStateChanged(@TelephonyManager.SrvccState int srvccState) {

    }

    /**
     * Callback invoked when the SIM voice activation state has changed
     * @param state is the current SIM voice activation state
     * @hide
     */
    @SystemApi
    public void onVoiceActivationStateChanged(@TelephonyManager.SimActivationState int state) {
    }

    /**
     * Callback invoked when the SIM data activation state has changed
     * @param state is the current SIM data activation state
     * @hide
     */
    public void onDataActivationStateChanged(@TelephonyManager.SimActivationState int state) {
    }

    /**
     * Callback invoked when the user mobile data state has changed
     * @param enabled indicates whether the current user mobile data state is enabled or disabled.
     */
    public void onUserMobileDataStateChanged(boolean enabled) {
        // default implementation empty
    }

    /**
     * Callback invoked when the current physical channel configuration has changed
     *
     * @param configs List of the current {@link PhysicalChannelConfig}s
     * @hide
     */
    public void onPhysicalChannelConfigurationChanged(
            @NonNull List<PhysicalChannelConfig> configs) {
        // default implementation empty
    }

    /**
     * Callback invoked when the current emergency number list has changed
     *
     * @param emergencyNumberList Map including the key as the active subscription ID
     *                           (Note: if there is no active subscription, the key is
     *                           {@link SubscriptionManager#getDefaultSubscriptionId})
     *                           and the value as the list of {@link EmergencyNumber};
     *                           null if this information is not available.
     * @hide
     */
    public void onEmergencyNumberListChanged(
            @NonNull Map<Integer, List<EmergencyNumber>> emergencyNumberList) {
        // default implementation empty
    }

    /**
     * Callback invoked when OEM hook raw event is received. Requires
     * the READ_PRIVILEGED_PHONE_STATE permission.
     * @param rawData is the byte array of the OEM hook raw data.
     * @hide
     */
    @UnsupportedAppUsage
    public void onOemHookRawEvent(byte[] rawData) {
        // default implementation empty
    }

    /**
     * Callback invoked when phone capability changes. Requires
     * the READ_PRIVILEGED_PHONE_STATE permission.
     * @param capability the new phone capability
     * @hide
     */
    public void onPhoneCapabilityChanged(PhoneCapability capability) {
        // default implementation empty
    }

    /**
     * Callback invoked when active data subId changes. Requires
     * the READ_PHONE_STATE permission.
     * @param subId current subscription used to setup Cellular Internet data.
     *              For example, it could be the current active opportunistic subscription in use,
     *              or the subscription user selected as default data subscription in DSDS mode.
     */
    public void onActiveDataSubscriptionIdChanged(int subId) {
        // default implementation empty
    }

    /**
     * Callback invoked when the call attributes changes. Requires
     * the READ_PRIVILEGED_PHONE_STATE permission.
     * @param callAttributes the call attributes
     * @hide
     */
    @SystemApi
    public void onCallAttributesChanged(@NonNull CallAttributes callAttributes) {
        // default implementation empty
    }

    /**
     * Callback invoked when modem radio power state changes. Requires
     * the READ_PRIVILEGED_PHONE_STATE permission.
     * @param state the modem radio power state
     * @hide
     */
    @SystemApi
    public void onRadioPowerStateChanged(@TelephonyManager.RadioPowerState int state) {
        // default implementation empty
    }

    /**
     * Callback invoked when telephony has received notice from a carrier
     * app that a network action that could result in connectivity loss
     * has been requested by an app using
     * {@link android.telephony.TelephonyManager#notifyCarrierNetworkChange(boolean)}
     *
     * @param active Whether the carrier network change is or shortly
     *               will be active. This value is true to indicate
     *               showing alternative UI and false to stop.
     *
     * @hide
     */
    public void onCarrierNetworkChange(boolean active) {
        // default implementation empty
    }

    /**
     * The callback methods need to be called on the handler thread where
     * this object was created.  If the binder did that for us it'd be nice.
     *
     * Using a static class and weak reference here to avoid memory leak caused by the
     * IPhoneStateListener.Stub callback retaining references to the outside PhoneStateListeners:
     * even caller has been destroyed and "un-registered" the PhoneStateListener, it is still not
     * eligible for GC given the references coming from:
     * Native Stack --> PhoneStateListener --> Context (Activity).
     * memory of caller's context will be collected after GC from service side get triggered
     */
    private static class IPhoneStateListenerStub extends IPhoneStateListener.Stub {
        private WeakReference<PhoneStateListener> mPhoneStateListenerWeakRef;
        private Executor mExecutor;

        IPhoneStateListenerStub(PhoneStateListener phoneStateListener, Executor executor) {
            mPhoneStateListenerWeakRef = new WeakReference<PhoneStateListener>(phoneStateListener);
            mExecutor = executor;
        }

        public void onServiceStateChanged(ServiceState serviceState) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onServiceStateChanged(serviceState)));
        }

        public void onSignalStrengthChanged(int asu) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onSignalStrengthChanged(asu)));
        }

        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onMessageWaitingIndicatorChanged(mwi)));
        }

        public void onCallForwardingIndicatorChanged(boolean cfi) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCallForwardingIndicatorChanged(cfi)));
        }

        public void onCellLocationChanged(Bundle bundle) {
            CellLocation location = CellLocation.newFromBundle(bundle);
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCellLocationChanged(location)));
        }

        public void onCallStateChanged(int state, String incomingNumber) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCallStateChanged(state, incomingNumber)));
        }

        public void onDataConnectionStateChanged(int state, int networkType) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> {
                        psl.onDataConnectionStateChanged(state, networkType);
                        psl.onDataConnectionStateChanged(state);
                    }));
        }

        public void onDataActivity(int direction) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onDataActivity(direction)));
        }

        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onSignalStrengthsChanged(signalStrength)));
        }

        public void onOtaspChanged(int otaspMode) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onOtaspChanged(otaspMode)));
        }

        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCellInfoChanged(cellInfo)));
        }

        public void onPreciseCallStateChanged(PreciseCallState callState) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onPreciseCallStateChanged(callState)));
        }

        public void onCallDisconnectCauseChanged(int disconnectCause, int preciseDisconnectCause) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCallDisconnectCauseChanged(
                            disconnectCause, preciseDisconnectCause)));
        }

        public void onPreciseDataConnectionStateChanged(
                PreciseDataConnectionState dataConnectionState) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onPreciseDataConnectionStateChanged(dataConnectionState)));
        }

        public void onDataConnectionRealTimeInfoChanged(DataConnectionRealTimeInfo dcRtInfo) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onDataConnectionRealTimeInfoChanged(dcRtInfo)));
        }

        public void onSrvccStateChanged(int state) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onSrvccStateChanged(state)));
        }

        public void onVoiceActivationStateChanged(int activationState) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onVoiceActivationStateChanged(activationState)));
        }

        public void onDataActivationStateChanged(int activationState) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onDataActivationStateChanged(activationState)));
        }

        public void onUserMobileDataStateChanged(boolean enabled) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onUserMobileDataStateChanged(enabled)));
        }

        public void onOemHookRawEvent(byte[] rawData) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onOemHookRawEvent(rawData)));
        }

        public void onCarrierNetworkChange(boolean active) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCarrierNetworkChange(active)));
        }

        public void onPhysicalChannelConfigurationChanged(List<PhysicalChannelConfig> configs) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onPhysicalChannelConfigurationChanged(configs)));
        }

        @Override
        public void onEmergencyNumberListChanged(Map emergencyNumberList) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onEmergencyNumberListChanged(emergencyNumberList)));
        }

        public void onPhoneCapabilityChanged(PhoneCapability capability) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onPhoneCapabilityChanged(capability)));
        }

        public void onRadioPowerStateChanged(@TelephonyManager.RadioPowerState int state) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onRadioPowerStateChanged(state)));
        }

        public void onCallAttributesChanged(CallAttributes callAttributes) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onCallAttributesChanged(callAttributes)));
        }

        public void onActiveDataSubIdChanged(int subId) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onActiveDataSubscriptionIdChanged(subId)));
        }

        public void onImsCallDisconnectCauseChanged(ImsReasonInfo disconnectCause) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onImsCallDisconnectCauseChanged(disconnectCause)));

        }
    }


    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
