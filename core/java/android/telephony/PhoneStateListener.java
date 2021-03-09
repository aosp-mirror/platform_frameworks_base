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
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.telephony.Annotation.CallState;
import android.telephony.Annotation.DisconnectCauses;
import android.telephony.Annotation.PreciseDisconnectCauses;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SimActivationState;
import android.telephony.Annotation.SrvccState;
import android.telephony.TelephonyManager.DataEnabledReason;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IPhoneStateListener;

import dalvik.system.VMRuntime;

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
 *
 * @deprecated Use {@link TelephonyCallback} instead.
 */
@Deprecated
public class PhoneStateListener {
    private static final String LOG_TAG = "PhoneStateListener";
    private static final boolean DBG = false; // STOPSHIP if true

    /**
     * Stop listening for updates.
     *
     * The PhoneStateListener is not tied to any subscription and unregistered for any update.
     */
    public static final int LISTEN_NONE = 0;

    /**
     *  Listen for changes to the network service state (cellular).
     *
     *  @see #onServiceStateChanged
     *  @see ServiceState
     *  @deprecated Use {@link TelephonyCallback.ServiceStateListener} instead.
     */
    @Deprecated
    public static final int LISTEN_SERVICE_STATE                            = 0x00000001;

    /**
     * Listen for changes to the network signal strength (cellular).
     * {@more}
     *
     * @see #onSignalStrengthChanged
     * @deprecated Use {@link TelephonyCallback.SignalStrengthsListener} instead.
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
     * @deprecated Use {@link TelephonyCallback.MessageWaitingIndicatorListener} instead.
     */
    @Deprecated
    public static final int LISTEN_MESSAGE_WAITING_INDICATOR                = 0x00000004;

    /**
     * Listen for changes to the call-forwarding indicator.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE
     * READ_PHONE_STATE} or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onCallForwardingIndicatorChanged
     * @deprecated Use {@link TelephonyCallback.CallForwardingIndicatorListener} instead.
     */
    @Deprecated
    public static final int LISTEN_CALL_FORWARDING_INDICATOR                = 0x00000008;

    /**
     * Listen for changes to the device's cell location. Note that
     * this will result in frequent callbacks to the listener.
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#ACCESS_FINE_LOCATION
     * ACCESS_FINE_LOCATION}
     * <p>
     * If you need regular location updates but want more control over
     * the update interval or location precision, you can set up a listener
     * through the {@link android.location.LocationManager location manager}
     * instead.
     *
     * @see #onCellLocationChanged
     * @deprecated Use {@link TelephonyCallback.CellLocationListener} instead.
     */
    @Deprecated
    public static final int LISTEN_CELL_LOCATION                            = 0x00000010;

    /**
     * Listen for changes to the device call state.
     * {@more}
     *
     * @see #onCallStateChanged
     * @deprecated Use {@link TelephonyCallback.CallStateListener} instead.
     */
    @Deprecated
    public static final int LISTEN_CALL_STATE                               = 0x00000020;

    /**
     * Listen for changes to the data connection state (cellular).
     *
     * @see #onDataConnectionStateChanged
     * @deprecated Use {@link TelephonyCallback.DataConnectionStateListener} instead.
     */
    @Deprecated
    public static final int LISTEN_DATA_CONNECTION_STATE                    = 0x00000040;

    /**
     * Listen for changes to the direction of data traffic on the data
     * connection (cellular).
     * {@more}
     * Example: The status bar uses this to display the appropriate
     * data-traffic icon.
     *
     * @see #onDataActivity
     * @deprecated Use {@link TelephonyCallback.DataActivityListener} instead.
     */
    @Deprecated
    public static final int LISTEN_DATA_ACTIVITY                            = 0x00000080;

    /**
     * Listen for changes to the network signal strengths (cellular).
     * <p>
     * Example: The status bar uses this to control the signal-strength
     * icon.
     *
     * @see #onSignalStrengthsChanged
     * @deprecated Use {@link TelephonyCallback.SignalStrengthsListener} instead.
     */
    @Deprecated
    public static final int LISTEN_SIGNAL_STRENGTHS                         = 0x00000100;

    /**
     * Listen for changes of the network signal strengths (cellular) always reported from modem,
     * even in some situations such as the screen of the device is off.
     *
     * @see #onSignalStrengthsChanged
     *
     * @hide
     * @deprecated Use {@link TelephonyCallback.AlwaysReportedSignalStrengthListener}
     * instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.LISTEN_ALWAYS_REPORTED_SIGNAL_STRENGTH)
    public static final int LISTEN_ALWAYS_REPORTED_SIGNAL_STRENGTH          = 0x00000200;

    /**
     * Listen for changes to observed cell info.
     *
     * Listening to this event requires the {@link Manifest.permission#ACCESS_FINE_LOCATION}
     * permission.
     *
     * @see #onCellInfoChanged
     * @deprecated Use {@link TelephonyCallback.CellInfoListener} instead.
     */
    @Deprecated
    public static final int LISTEN_CELL_INFO = 0x00000400;

    /**
     * Listen for {@link android.telephony.Annotation.PreciseCallStates} of ringing,
     * background and foreground calls.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @deprecated Use {@link TelephonyCallback.PreciseCallStateListener} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    @SystemApi
    public static final int LISTEN_PRECISE_CALL_STATE                       = 0x00000800;

    /**
     * Listen for {@link PreciseDataConnectionState} on the data connection (cellular).
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onPreciseDataConnectionStateChanged
     * @deprecated Use {@link TelephonyCallback.PreciseDataConnectionStateListener} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_PRECISE_DATA_CONNECTION_STATE            = 0x00001000;

    /**
     * Listen for real time info for all data connections (cellular)).
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     * @see #onDataConnectionRealTimeInfoChanged(DataConnectionRealTimeInfo)
     *
     * @deprecated Use {@link TelephonyManager#requestModemActivityInfo} instead.
     * @hide
     */
    @Deprecated
    public static final int LISTEN_DATA_CONNECTION_REAL_TIME_INFO           = 0x00002000;

    /**
     * Listen for changes to the SRVCC state of the active call.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @see #onServiceStateChanged(ServiceState)
     * @hide
     * @deprecated Use {@link TelephonyCallback.SrvccStateListener} instead.
     */
    @Deprecated
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
     * @see android.service.carrier.CarrierService#notifyCarrierNetworkChange(boolean)
     * @hide
     * @deprecated Use {@link TelephonyCallback.CarrierNetworkListener} instead.
     */
    @Deprecated
    public static final int LISTEN_CARRIER_NETWORK_CHANGE                   = 0x00010000;

    /**
     * Listen for changes to the sim voice activation state
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
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
     * @deprecated Use {@link TelephonyCallback.VoiceActivationStateListener} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int LISTEN_VOICE_ACTIVATION_STATE                   = 0x00020000;

    /**
     * Listen for changes to the sim data activation state
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     *
     * Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates data service has been
     * fully activated
     *
     * @see #onDataActivationStateChanged
     * @hide
     * @deprecated Use {@link TelephonyCallback.DataActivationStateListener} instead.
     */
    @Deprecated
    public static final int LISTEN_DATA_ACTIVATION_STATE                   = 0x00040000;

    /**
     *  Listen for changes to the user mobile data state
     *
     *  @see #onUserMobileDataStateChanged
     *  @deprecated Use {@link TelephonyCallback.UserMobileDataStateListener} instead.
     */
    @Deprecated
    public static final int LISTEN_USER_MOBILE_DATA_STATE                  = 0x00080000;

    /**
     *  Listen for display info changed event.
     *
     *  Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE
     *  READ_PHONE_STATE} or that the calling app has carrier privileges (see
     *  {@link TelephonyManager#hasCarrierPrivileges}).
     *
     *  @see #onDisplayInfoChanged
     * @deprecated Use {@link TelephonyCallback.DisplayInfoListener} instead.
     */
    @Deprecated
    public static final int LISTEN_DISPLAY_INFO_CHANGED = 0x00100000;

    /**
     *  Listen for changes to the phone capability.
     *
     *  @see #onPhoneCapabilityChanged
     *  @hide
     *  @deprecated Use {@link TelephonyCallback.PhoneCapabilityListener} instead.
     */
    @Deprecated
    public static final int LISTEN_PHONE_CAPABILITY_CHANGE                 = 0x00200000;

    /**
     *  Listen for changes to active data subId. Active data subscription is
     *  the current subscription used to setup Cellular Internet data. For example,
     *  it could be the current active opportunistic subscription in use, or the
     *  subscription user selected as default data subscription in DSDS mode.
     *
     *  @see #onActiveDataSubscriptionIdChanged
     *  @deprecated Use {@link TelephonyCallback.ActiveDataSubscriptionIdListener} instead.
     */
    @Deprecated
    public static final int LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE = 0x00400000;

    /**
     *  Listen for changes to the radio power state.
     *
     *  @see #onRadioPowerStateChanged
     *  @hide
     *  @deprecated Use {@link TelephonyCallback.RadioPowerStateListener} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int LISTEN_RADIO_POWER_STATE_CHANGED               = 0x00800000;

    /**
     * Listen for changes to emergency number list based on all active subscriptions.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @deprecated Use {@link TelephonyCallback.EmergencyNumberListListener} instead.
     */
    @Deprecated
    public static final int LISTEN_EMERGENCY_NUMBER_LIST                   = 0x01000000;

    /**
     * Listen for call disconnect causes which contains {@link DisconnectCause} and
     * {@link PreciseDisconnectCause}.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @deprecated Use {@link TelephonyCallback.CallDisconnectCauseListener} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_CALL_DISCONNECT_CAUSES                  = 0x02000000;

    /**
     * Listen for changes to the call attributes of a currently active call.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onCallAttributesChanged
     * @hide
     * @deprecated Use {@link TelephonyCallback.CallAttributesListener} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_CALL_ATTRIBUTES_CHANGED                 = 0x04000000;

    /**
     * Listen for IMS call disconnect causes which contains
     * {@link android.telephony.ims.ImsReasonInfo}
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @see #onImsCallDisconnectCauseChanged(ImsReasonInfo)
     * @deprecated Use {@link TelephonyCallback.ImsCallDisconnectCauseListener} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_IMS_CALL_DISCONNECT_CAUSES              = 0x08000000;

    /**
     * Listen for the emergency number placed from an outgoing call.
     *
     * @see #onOutgoingEmergencyCall
     * @hide
     * @deprecated Use {@link TelephonyCallback.OutgoingEmergencyCallListener} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    public static final int LISTEN_OUTGOING_EMERGENCY_CALL                  = 0x10000000;

    /**
     * Listen for the emergency number placed from an outgoing SMS.
     *
     * @see #onOutgoingEmergencySms
     * @hide
     * @deprecated Use {@link TelephonyCallback.OutgoingEmergencySmsListener} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    public static final int LISTEN_OUTGOING_EMERGENCY_SMS                   = 0x20000000;

    /**
     * Listen for Registration Failures.
     *
     * Listen for indications that a registration procedure has failed in either the CS or PS
     * domain. This indication does not necessarily indicate a change of service state, which should
     * be tracked via {@link #LISTEN_SERVICE_STATE}.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} or
     * the calling app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * <p>Also requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission, regardless
     * of whether the calling app has carrier privileges.
     *
     * @see #onRegistrationFailed
     * @deprecated Use {@link TelephonyCallback.RegistrationFailedListener} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_REGISTRATION_FAILURE = 0x40000000;

    /**
     * Listen for Barring Information for the current registered / camped cell.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} or
     * the calling app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * <p>Also requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission, regardless
     * of whether the calling app has carrier privileges.
     *
     * @see #onBarringInfoChanged
     * @deprecated Use {@link TelephonyCallback.BarringInfoListener} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int LISTEN_BARRING_INFO = 0x80000000;

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
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@code TelephonyManager#registerTelephonyCallback(" +
                    "Executor, TelephonyCallback)} instead")
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
        if (subId != null && VMRuntime.getRuntime().getTargetSdkVersion()
                >= Build.VERSION_CODES.Q) {
            throw new IllegalArgumentException("PhoneStateListener with subId: "
                    + subId + " is not supported, use default constructor");
        }
    }
    /**
     * Create a PhoneStateListener for the Phone using the specified subscription
     * and non-null Looper.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public PhoneStateListener(Integer subId, Looper looper) {
        this(subId, new HandlerExecutor(new Handler(looper)));
        if (subId != null && VMRuntime.getRuntime().getTargetSdkVersion()
                >= Build.VERSION_CODES.Q) {
            throw new IllegalArgumentException("PhoneStateListener with subId: "
                    + subId + " is not supported, use default constructor");
        }
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified Executor
     *
     * <p>Create a PhoneStateListener with a specified Executor for handling necessary callbacks.
     * The Executor must not be null.
     *
     * @param executor a non-null Executor that will execute callbacks for the PhoneStateListener.
     */
    @Deprecated
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
     * Callback invoked when device service state changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * The instance of {@link ServiceState} passed as an argument here will have various levels of
     * location information stripped from it depending on the location permissions that your app
     * holds. Only apps holding the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission will
     * receive all the information in {@link ServiceState}.
     *
     * @see ServiceState#STATE_EMERGENCY_ONLY
     * @see ServiceState#STATE_IN_SERVICE
     * @see ServiceState#STATE_OUT_OF_SERVICE
     * @see ServiceState#STATE_POWER_OFF
     * @deprecated Use {@link TelephonyCallback.ServiceStateListener} instead.
     */
    @Deprecated
    public void onServiceStateChanged(ServiceState serviceState) {
        // default implementation empty
    }

    /**
     * Callback invoked when network signal strength changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
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
     * Callback invoked when the message-waiting indicator changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @deprecated Use {@link TelephonyCallback.MessageWaitingIndicatorListener} instead.
     */
    @Deprecated
    public void onMessageWaitingIndicatorChanged(boolean mwi) {
        // default implementation empty
    }

    /**
     * Callback invoked when the call-forwarding indicator changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @deprecated Use {@link TelephonyCallback.CallForwardingIndicatorListener} instead.
     */
    @Deprecated
    public void onCallForwardingIndicatorChanged(boolean cfi) {
        // default implementation empty
    }

    /**
     * Callback invoked when device cell location changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @deprecated Use {@link TelephonyCallback.CellLocationListener} instead.
     */
    @Deprecated
    public void onCellLocationChanged(CellLocation location) {
        // default implementation empty
    }

    /**
     * Callback invoked when device call state changes.
     * <p>
     * Reports the state of Telephony (mobile) calls on the device for the registered subscription.
     * <p>
     * Note: the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to all subIds.
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
     *
     * @deprecated Use {@link TelephonyCallback.CallStateListener} instead.
     */
    @Deprecated
    public void onCallStateChanged(@CallState int state, String phoneNumber) {
        // default implementation empty
    }

    /**
     * Callback invoked when connection state changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     * @deprecated Use {@link TelephonyCallback.DataConnectionStateListener} instead.
     */
    @Deprecated
    public void onDataConnectionStateChanged(int state) {
        // default implementation empty
    }

    /**
     * same as above, but with the network type.  Both called.
     *
     * @deprecated Use {@link TelephonyCallback.DataConnectionStateListener} instead.
     */
    @Deprecated
    public void onDataConnectionStateChanged(int state, int networkType) {
        // default implementation empty
    }

    /**
     * Callback invoked when data activity state changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @see TelephonyManager#DATA_ACTIVITY_NONE
     * @see TelephonyManager#DATA_ACTIVITY_IN
     * @see TelephonyManager#DATA_ACTIVITY_OUT
     * @see TelephonyManager#DATA_ACTIVITY_INOUT
     * @see TelephonyManager#DATA_ACTIVITY_DORMANT
     * @deprecated Use {@link TelephonyCallback.DataActivityListener} instead.
     */
    @Deprecated
    public void onDataActivity(int direction) {
        // default implementation empty
    }

    /**
     * Callback invoked when network signal strengths changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @deprecated Use {@link TelephonyCallback.SignalStrengthsListener} instead.
     */
    @Deprecated
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        // default implementation empty
    }

    /**
     * Callback invoked when a observed cell info has changed or new cells have been added
     * or removed on the registered subscription.
     * Note, the registration subId s from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param cellInfo is the list of currently visible cells.
     * @deprecated Use {@link TelephonyCallback.CellInfoListener} instead.
     */
    @Deprecated
    public void onCellInfoChanged(List<CellInfo> cellInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when precise device call state changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param callState {@link PreciseCallState}
     * @hide
     * @deprecated Use {@link TelephonyCallback.PreciseCallStateListener} instead.
     */
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    @SystemApi
    @Deprecated
    public void onPreciseCallStateChanged(@NonNull PreciseCallState callState) {
        // default implementation empty
    }

    /**
     * Callback invoked when call disconnect cause changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param disconnectCause {@link DisconnectCause}.
     * @param preciseDisconnectCause {@link PreciseDisconnectCause}.
     * @deprecated Use {@link TelephonyCallback.CallDisconnectCauseListener} instead.
     */
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    @Deprecated
    public void onCallDisconnectCauseChanged(@DisconnectCauses int disconnectCause,
            @PreciseDisconnectCauses int preciseDisconnectCause) {
        // default implementation empty
    }

    /**
     * Callback invoked when Ims call disconnect cause changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param imsReasonInfo {@link ImsReasonInfo} contains details on why IMS call failed.
     * @deprecated Use {@link TelephonyCallback.ImsCallDisconnectCauseListener} instead.
     */
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    @Deprecated
    public void onImsCallDisconnectCauseChanged(@NonNull ImsReasonInfo imsReasonInfo) {
        // default implementation empty
    }

    /**
     * Callback providing update about the default/internet data connection on the registered
     * subscription.
     *
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * <p>Requires permission {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param dataConnectionState {@link PreciseDataConnectionState}
     * @deprecated Use {@link TelephonyCallback.PreciseDataConnectionStateListener} instead.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @Deprecated
    public void onPreciseDataConnectionStateChanged(
            @NonNull PreciseDataConnectionState dataConnectionState) {
        // default implementation empty
    }

    /**
     * Callback invoked when data connection real time info changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @hide
     * @deprecated Use {@link TelephonyManager#requestModemActivityInfo}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
    public void onDataConnectionRealTimeInfoChanged(
            DataConnectionRealTimeInfo dcRtInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when there has been a change in the Single Radio Voice Call Continuity
     * (SRVCC) state for the currently active call on the registered subscription.
     *
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @hide
     * @deprecated Use {@link TelephonyCallback.SrvccStateListener} instead.
     */
    @SystemApi
    @Deprecated
    public void onSrvccStateChanged(@SrvccState int srvccState) {
        // default implementation empty
    }

    /**
     * Callback invoked when the SIM voice activation state has changed on the registered
     * subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param state is the current SIM voice activation state
     * @hide
     * @deprecated Use {@link TelephonyCallback.VoiceActivationStateListener} instead.
     */
    @SystemApi
    @Deprecated
    public void onVoiceActivationStateChanged(@SimActivationState int state) {
        // default implementation empty
    }

    /**
     * Callback invoked when the SIM data activation state has changed on the registered
     * subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param state is the current SIM data activation state
     * @hide
     * @deprecated Use {@link TelephonyCallback.DataActivationStateListener} instead.
     */
    @Deprecated
    public void onDataActivationStateChanged(@SimActivationState int state) {
        // default implementation empty
    }

    /**
     * Callback invoked when the user mobile data state has changed on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param enabled indicates whether the current user mobile data state is enabled or disabled.
     * @deprecated Use {@link TelephonyCallback.UserMobileDataStateListener} instead.
     */
    @Deprecated
    public void onUserMobileDataStateChanged(boolean enabled) {
        // default implementation empty
    }

    /**
     * Callback invoked when the display info has changed on the registered subscription.
     * <p> The {@link TelephonyDisplayInfo} contains status information shown to the user based on
     * carrier policy.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param telephonyDisplayInfo The display information.
     * @deprecated Use {@link TelephonyCallback.DisplayInfoListener} instead.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @Deprecated
    public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when the current emergency number list has changed on the registered
     * subscription.
     *
     * Note, the registered subscription is associated with {@link TelephonyManager} object
     * on which {@link TelephonyManager#listen(PhoneStateListener, int)} was called.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * given subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @param emergencyNumberList Map associating all active subscriptions on the device with the
     *                            list of emergency numbers originating from that subscription.
     *                            If there are no active subscriptions, the map will contain a
     *                            single entry with
     *                            {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} as
     *                            the key and a list of emergency numbers as the value. If no
     *                            emergency number information is available, the value will be null.
     * @deprecated Use {@link TelephonyCallback.EmergencyNumberListListener} instead.
     */
    @Deprecated
    public void onEmergencyNumberListChanged(
            @NonNull Map<Integer, List<EmergencyNumber>> emergencyNumberList) {
        // default implementation empty
    }

    /**
     * Callback invoked when an outgoing call is placed to an emergency number.
     *
     * This method will be called when an emergency call is placed on any subscription (including
     * the no-SIM case), regardless of which subscription this listener was registered on.
     *
     * @param placedEmergencyNumber The {@link EmergencyNumber} the emergency call was placed to.
     * @deprecated Use {@link #onOutgoingEmergencyCall(EmergencyNumber, int)}.
     * @hide
     */
    @SystemApi
    @Deprecated
    public void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber) {
        // default implementation empty
    }

    /**
     * Callback invoked when an outgoing call is placed to an emergency number.
     *
     * This method will be called when an emergency call is placed on any subscription (including
     * the no-SIM case), regardless of which subscription this listener was registered on.
     *
     * The default implementation of this method calls
     * {@link #onOutgoingEmergencyCall(EmergencyNumber)} for backwards compatibility purposes. Do
     * not call {@code super(...)} from within your implementation unless you want
     * {@link #onOutgoingEmergencyCall(EmergencyNumber)} to be called as well.
     *
     * @param placedEmergencyNumber The {@link EmergencyNumber} the emergency call was placed to.
     * @param subscriptionId The subscription ID used to place the emergency call. If the
     *                       emergency call was placed without a valid subscription (e.g. when there
     *                       are no SIM cards in the device), this will be equal to
     *                       {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     * @hide
     * @deprecated Use {@link TelephonyCallback.OutgoingEmergencyCallListener} instead.
     */
    @SystemApi
    @TestApi
    @Deprecated
    public void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber,
            int subscriptionId) {
        // Default implementation for backwards compatibility
        onOutgoingEmergencyCall(placedEmergencyNumber);
    }

    /**
     * Callback invoked when an outgoing SMS is placed to an emergency number.
     *
     * This method will be called when an emergency sms is sent on any subscription.
     * @param sentEmergencyNumber the emergency number {@link EmergencyNumber} the SMS is sent to.
     *
     * @deprecated Use {@link #onOutgoingEmergencySms(EmergencyNumber, int)}.
     * @hide
     * @deprecated Use {@link TelephonyCallback.OutgoingEmergencySmsListener} instead.
     */
    @SystemApi
    @TestApi
    @Deprecated
    public void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber) {
        // default implementation empty
    }

    /**
     * Smsback invoked when an outgoing sms is sent to an emergency number.
     *
     * This method will be called when an emergency sms is sent on any subscription,
     * regardless of which subscription this listener was registered on.
     *
     * The default implementation of this method calls
     * {@link #onOutgoingEmergencySms(EmergencyNumber)} for backwards compatibility purposes. Do
     * not call {@code super(...)} from within your implementation unless you want
     * {@link #onOutgoingEmergencySms(EmergencyNumber)} to be called as well.
     *
     * @param sentEmergencyNumber The {@link EmergencyNumber} the emergency sms was sent to.
     * @param subscriptionId The subscription ID used to send the emergency sms.
     * @hide
     * @deprecated Use {@link TelephonyCallback.OutgoingEmergencySmsListener} instead.
     */
    @SystemApi
    @TestApi
    @Deprecated
    public void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber,
            int subscriptionId) {
        // Default implementation for backwards compatibility
        onOutgoingEmergencySms(sentEmergencyNumber);
    }

    /**
     * Callback invoked when OEM hook raw event is received on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * Requires the READ_PRIVILEGED_PHONE_STATE permission.
     * @param rawData is the byte array of the OEM hook raw data.
     * @hide
     * @deprecated OEM needs a vendor-extension hal and their apps should use that instead
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
    public void onOemHookRawEvent(byte[] rawData) {
        // default implementation empty
    }

    /**
     * Callback invoked when phone capability changes.
     * Note, this callback triggers regardless of registered subscription.
     *
     * @param capability the new phone capability
     * @hide
     * @deprecated Use {@link TelephonyCallback.PhoneCapabilityListener} instead.
     */
    @Deprecated
    public void onPhoneCapabilityChanged(@NonNull PhoneCapability capability) {
        // default implementation empty
    }

    /**
     * Callback invoked when active data subId changes.
     * Note, this callback triggers regardless of registered subscription.
     *
     * Requires the READ_PHONE_STATE permission.
     * @param subId current subscription used to setup Cellular Internet data.
     *              For example, it could be the current active opportunistic subscription in use,
     *              or the subscription user selected as default data subscription in DSDS mode.
     * @deprecated Use {@link TelephonyCallback.ActiveDataSubscriptionIdListener} instead.
     */
    @Deprecated
    public void onActiveDataSubscriptionIdChanged(int subId) {
        // default implementation empty
    }

    /**
     * Callback invoked when the call attributes changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * Requires the READ_PRECISE_PHONE_STATE permission.
     * @param callAttributes the call attributes
     * @hide
     * @deprecated Use {@link TelephonyCallback.CallAttributesListener} instead.
     */
    @SystemApi
    @Deprecated
    public void onCallAttributesChanged(@NonNull CallAttributes callAttributes) {
        // default implementation empty
    }

    /**
     * Callback invoked when modem radio power state changes on the registered subscription.
     * Note, the registration subId comes from {@link TelephonyManager} object which registers
     * PhoneStateListener by {@link TelephonyManager#listen(PhoneStateListener, int)}.
     * If this TelephonyManager object was created with
     * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
     * subId. Otherwise, this callback applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * Requires permission {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @param state the modem radio power state
     * @hide
     * @deprecated Use {@link TelephonyCallback.RadioPowerStateListener} instead.
     */
    @SystemApi
    @Deprecated
    public void onRadioPowerStateChanged(@RadioPowerState int state) {
        // default implementation empty
    }

    /**
     * Callback invoked when telephony has received notice from a carrier
     * app that a network action that could result in connectivity loss
     * has been requested by an app using
     * {@link android.telephony.TelephonyManager#notifyCarrierNetworkChange(boolean)}
     *
     * Note, this callback is pinned to the registered subscription and will be invoked when
     * the notifying carrier app has carrier privilege rule on the registered
     * subscription. {@link android.telephony.TelephonyManager#hasCarrierPrivileges}
     *
     * @param active Whether the carrier network change is or shortly
     *               will be active. This value is true to indicate
     *               showing alternative UI and false to stop.
     * @hide
     * @deprecated Use {@link TelephonyCallback.CarrierNetworkListener} instead.
     */
    @Deprecated
    public void onCarrierNetworkChange(boolean active) {
        // default implementation empty
    }

    /**
     * Report that Registration or a Location/Routing/Tracking Area update has failed.
     *
     * <p>Indicate whenever a registration procedure, including a location, routing, or tracking
     * area update fails. This includes procedures that do not necessarily result in a change of
     * the modem's registration status. If the modem's registration status changes, that is
     * reflected in the onNetworkStateChanged() and subsequent get{Voice/Data}RegistrationState().
     *
     * <p>Because registration failures are ephemeral, this callback is not sticky.
     * Registrants will not receive the most recent past value when registering.
     *
     * @param cellIdentity the CellIdentity, which must include the globally unique identifier
     *        for the cell (for example, all components of the CGI or ECGI).
     * @param chosenPlmn a 5 or 6 digit alphanumeric PLMN (MCC|MNC) among those broadcast by the
     *         cell that was chosen for the failed registration attempt.
     * @param domain DOMAIN_CS, DOMAIN_PS or both in case of a combined procedure.
     * @param causeCode the primary failure cause code of the procedure.
     *        For GSM/UMTS (MM), values are in TS 24.008 Sec 10.5.95
     *        For GSM/UMTS (GMM), values are in TS 24.008 Sec 10.5.147
     *        For LTE (EMM), cause codes are TS 24.301 Sec 9.9.3.9
     *        For NR (5GMM), cause codes are TS 24.501 Sec 9.11.3.2
     *        Integer.MAX_VALUE if this value is unused.
     * @param additionalCauseCode the cause code of any secondary/combined procedure if appropriate.
     *        For UMTS, if a combined attach succeeds for PS only, then the GMM cause code shall be
     *        included as an additionalCauseCode. For LTE (ESM), cause codes are in
     *        TS 24.301 9.9.4.4. Integer.MAX_VALUE if this value is unused.
     * @deprecated Use {@link TelephonyCallback.RegistrationFailedListener} instead.
     */
    @Deprecated
    public void onRegistrationFailed(@NonNull CellIdentity cellIdentity, @NonNull String chosenPlmn,
            int domain, int causeCode, int additionalCauseCode) {
        // default implementation empty
    }

    /**
     * Report updated barring information for the current camped/registered cell.
     *
     * <p>Barring info is provided for all services applicable to the current camped/registered
     * cell, for the registered PLMN and current access class/access category.
     *
     * @param barringInfo for all services on the current cell.
     * @see android.telephony.BarringInfo
     * @deprecated Use {@link TelephonyCallback.BarringInfoListener} instead.
     */
    @Deprecated
    public void onBarringInfoChanged(@NonNull BarringInfo barringInfo) {
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

        public void onCellLocationChanged(CellIdentity cellIdentity) {
            // There is no system/public API to create an CellIdentity in system server,
            // so the server pass a null to indicate an empty initial location.
            CellLocation location =
                    cellIdentity == null ? CellLocation.getEmpty() : cellIdentity.asCellLocation();
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

            if (state == TelephonyManager.DATA_DISCONNECTING
                    && VMRuntime.getRuntime().getTargetSdkVersion() < Build.VERSION_CODES.R) {
                Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                        () -> {
                            psl.onDataConnectionStateChanged(
                                    TelephonyManager.DATA_CONNECTED, networkType);
                            psl.onDataConnectionStateChanged(TelephonyManager.DATA_CONNECTED);
                        }));
            } else {
                Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                        () -> {
                            psl.onDataConnectionStateChanged(state, networkType);
                            psl.onDataConnectionStateChanged(state);
                        }));
            }
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

        public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onDisplayInfoChanged(telephonyDisplayInfo)));
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

        public void onEmergencyNumberListChanged(Map emergencyNumberList) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onEmergencyNumberListChanged(emergencyNumberList)));
        }

        public void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber,
                int subscriptionId) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onOutgoingEmergencyCall(placedEmergencyNumber,
                                    subscriptionId)));
        }

        public void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber,
                int subscriptionId) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> psl.onOutgoingEmergencySms(sentEmergencyNumber, subscriptionId)));
        }

        public void onPhoneCapabilityChanged(PhoneCapability capability) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onPhoneCapabilityChanged(capability)));
        }

        public void onRadioPowerStateChanged(@RadioPowerState int state) {
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

        public void onRegistrationFailed(@NonNull CellIdentity cellIdentity,
                @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onRegistrationFailed(
                            cellIdentity, chosenPlmn, domain, causeCode, additionalCauseCode)));
            // default implementation empty
        }

        public void onBarringInfoChanged(BarringInfo barringInfo) {
            PhoneStateListener psl = mPhoneStateListenerWeakRef.get();
            if (psl == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> psl.onBarringInfoChanged(barringInfo)));
        }

        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
            // default implementation empty
        }

        public void onDataEnabledChanged(boolean enabled, @DataEnabledReason int reason) {
            // default implementation empty
        }

        public void onAllowedNetworkTypesChanged(Map allowedNetworkTypesList) {
            // default implementation empty
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
