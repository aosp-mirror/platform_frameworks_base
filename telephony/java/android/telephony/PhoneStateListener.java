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

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telecom.TelecomManager;

import com.android.internal.telephony.IPhoneStateListener;

import java.util.List;
import java.lang.ref.WeakReference;

/**
 * A listener class for monitoring changes in specific telephony states
 * on the device, including service state, signal strength, message
 * waiting indicator (voicemail), and others.
 * <p>
 * Override the methods for the state that you wish to receive updates for, and
 * pass your PhoneStateListener object, along with bitwise-or of the LISTEN_
 * flags to {@link TelephonyManager#listen TelephonyManager.listen()}.
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
     * Listen for precise changes and fails to the device calls (cellular).
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     *
     * @hide
     */
    public static final int LISTEN_PRECISE_CALL_STATE                       = 0x00000800;

    /**
     * Listen for precise changes and fails on the data connection (cellular).
     * {@more}
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     *
     * @see #onPreciseDataConnectionStateChanged
     * @hide
     */
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
     * Listen for changes to LTE network state
     *
     * @see #onLteNetworkStateChanged
     * @hide
     */
    public static final int LISTEN_VOLTE_STATE                              = 0x00004000;

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
     *  Listen for changes to the sim voice activation state
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     *  {@more}
     *  Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates voice service has been
     *  fully activated
     *
     *  @see #onVoiceActivationStateChanged
     *  @hide
     */
    public static final int LISTEN_VOICE_ACTIVATION_STATE                   = 0x00020000;

    /**
     *  Listen for changes to the sim data activation state
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     *  @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     *  {@more}
     *  Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates data service has been
     *  fully activated
     *
     *  @see #onDataActivationStateChanged
     *  @hide
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

    /*
     * Subscription used to listen to the phone state changes
     * @hide
     */
    /** @hide */
    protected Integer mSubId;

    private final Handler mHandler;

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
    public PhoneStateListener(Looper looper) {
        this(null, looper);
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified subscription.
     * This class requires Looper.myLooper() not return null. To supply your
     * own non-null Looper use PhoneStateListener(int subId, Looper looper) below.
     * @hide
     */
    public PhoneStateListener(Integer subId) {
        this(subId, Looper.myLooper());
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified subscription
     * and non-null Looper.
     * @hide
     */
    public PhoneStateListener(Integer subId, Looper looper) {
        if (DBG) log("ctor: subId=" + subId + " looper=" + looper);
        mSubId = subId;
        mHandler = new Handler(looper) {
            public void handleMessage(Message msg) {
                if (DBG) {
                    log("mSubId=" + mSubId + " what=0x" + Integer.toHexString(msg.what)
                            + " msg=" + msg);
                }
                switch (msg.what) {
                    case LISTEN_SERVICE_STATE:
                        PhoneStateListener.this.onServiceStateChanged((ServiceState)msg.obj);
                        break;
                    case LISTEN_SIGNAL_STRENGTH:
                        PhoneStateListener.this.onSignalStrengthChanged(msg.arg1);
                        break;
                    case LISTEN_MESSAGE_WAITING_INDICATOR:
                        PhoneStateListener.this.onMessageWaitingIndicatorChanged(msg.arg1 != 0);
                        break;
                    case LISTEN_CALL_FORWARDING_INDICATOR:
                        PhoneStateListener.this.onCallForwardingIndicatorChanged(msg.arg1 != 0);
                        break;
                    case LISTEN_CELL_LOCATION:
                        PhoneStateListener.this.onCellLocationChanged((CellLocation)msg.obj);
                        break;
                    case LISTEN_CALL_STATE:
                        PhoneStateListener.this.onCallStateChanged(msg.arg1, (String)msg.obj);
                        break;
                    case LISTEN_DATA_CONNECTION_STATE:
                        PhoneStateListener.this.onDataConnectionStateChanged(msg.arg1, msg.arg2);
                        PhoneStateListener.this.onDataConnectionStateChanged(msg.arg1);
                        break;
                    case LISTEN_DATA_ACTIVITY:
                        PhoneStateListener.this.onDataActivity(msg.arg1);
                        break;
                    case LISTEN_SIGNAL_STRENGTHS:
                        PhoneStateListener.this.onSignalStrengthsChanged((SignalStrength)msg.obj);
                        break;
                    case LISTEN_OTASP_CHANGED:
                        PhoneStateListener.this.onOtaspChanged(msg.arg1);
                        break;
                    case LISTEN_CELL_INFO:
                        PhoneStateListener.this.onCellInfoChanged((List<CellInfo>)msg.obj);
                        break;
                    case LISTEN_PRECISE_CALL_STATE:
                        PhoneStateListener.this.onPreciseCallStateChanged((PreciseCallState)msg.obj);
                        break;
                    case LISTEN_PRECISE_DATA_CONNECTION_STATE:
                        PhoneStateListener.this.onPreciseDataConnectionStateChanged(
                                (PreciseDataConnectionState)msg.obj);
                        break;
                    case LISTEN_DATA_CONNECTION_REAL_TIME_INFO:
                        PhoneStateListener.this.onDataConnectionRealTimeInfoChanged(
                                (DataConnectionRealTimeInfo)msg.obj);
                        break;
                    case LISTEN_VOLTE_STATE:
                        PhoneStateListener.this.onVoLteServiceStateChanged((VoLteServiceState)msg.obj);
                        break;
                    case LISTEN_VOICE_ACTIVATION_STATE:
                        PhoneStateListener.this.onVoiceActivationStateChanged((int)msg.obj);
                        break;
                    case LISTEN_DATA_ACTIVATION_STATE:
                        PhoneStateListener.this.onDataActivationStateChanged((int)msg.obj);
                        break;
                    case LISTEN_USER_MOBILE_DATA_STATE:
                        PhoneStateListener.this.onUserMobileDataStateChanged((boolean)msg.obj);
                        break;
                    case LISTEN_OEM_HOOK_RAW_EVENT:
                        PhoneStateListener.this.onOemHookRawEvent((byte[])msg.obj);
                        break;
                    case LISTEN_CARRIER_NETWORK_CHANGE:
                        PhoneStateListener.this.onCarrierNetworkChange((boolean)msg.obj);
                        break;
                    case LISTEN_PHYSICAL_CHANNEL_CONFIGURATION:
                        PhoneStateListener.this.onPhysicalChannelConfigurationChanged(
                                (List<PhysicalChannelConfig>)msg.obj);
                        break;
                }
            }
        };
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
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} permission or carrier
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
     *
     * @hide
     */
    public void onPreciseCallStateChanged(PreciseCallState callState) {
        // default implementation empty
    }

    /**
     * Callback invoked when data connection state changes with precise information.
     *
     * @hide
     */
    public void onPreciseDataConnectionStateChanged(
            PreciseDataConnectionState dataConnectionState) {
        // default implementation empty
    }

    /**
     * Callback invoked when data connection state changes with precise information.
     *
     * @hide
     */
    public void onDataConnectionRealTimeInfoChanged(
            DataConnectionRealTimeInfo dcRtInfo) {
        // default implementation empty
    }

    /**
     * Callback invoked when the service state of LTE network
     * related to the VoLTE service has changed.
     * @param stateInfo is the current LTE network information
     * @hide
     */
    public void onVoLteServiceStateChanged(VoLteServiceState stateInfo) {
    }

    /**
     * Callback invoked when the SIM voice activation state has changed
     * @param state is the current SIM voice activation state
     * @hide
     */
    public void onVoiceActivationStateChanged(int state) {

    }

    /**
     * Callback invoked when the SIM data activation state has changed
     * @param state is the current SIM data activation state
     * @hide
     */
    public void onDataActivationStateChanged(int state) {

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
     * Callback invoked when OEM hook raw event is received. Requires
     * the READ_PRIVILEGED_PHONE_STATE permission.
     * @param rawData is the byte array of the OEM hook raw data.
     * @hide
     */
    public void onOemHookRawEvent(byte[] rawData) {
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

        public IPhoneStateListenerStub(PhoneStateListener phoneStateListener) {
            mPhoneStateListenerWeakRef = new WeakReference<PhoneStateListener>(phoneStateListener);
        }

        private void send(int what, int arg1, int arg2, Object obj) {
            PhoneStateListener listener = mPhoneStateListenerWeakRef.get();
            if (listener != null) {
                Message.obtain(listener.mHandler, what, arg1, arg2, obj).sendToTarget();
            }
        }

        public void onServiceStateChanged(ServiceState serviceState) {
            send(LISTEN_SERVICE_STATE, 0, 0, serviceState);
        }

        public void onSignalStrengthChanged(int asu) {
            send(LISTEN_SIGNAL_STRENGTH, asu, 0, null);
        }

        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            send(LISTEN_MESSAGE_WAITING_INDICATOR, mwi ? 1 : 0, 0, null);
        }

        public void onCallForwardingIndicatorChanged(boolean cfi) {
            send(LISTEN_CALL_FORWARDING_INDICATOR, cfi ? 1 : 0, 0, null);
        }

        public void onCellLocationChanged(Bundle bundle) {
            CellLocation location = CellLocation.newFromBundle(bundle);
            send(LISTEN_CELL_LOCATION, 0, 0, location);
        }

        public void onCallStateChanged(int state, String incomingNumber) {
            send(LISTEN_CALL_STATE, state, 0, incomingNumber);
        }

        public void onDataConnectionStateChanged(int state, int networkType) {
            send(LISTEN_DATA_CONNECTION_STATE, state, networkType, null);
        }

        public void onDataActivity(int direction) {
            send(LISTEN_DATA_ACTIVITY, direction, 0, null);
        }

        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            send(LISTEN_SIGNAL_STRENGTHS, 0, 0, signalStrength);
        }

        public void onOtaspChanged(int otaspMode) {
            send(LISTEN_OTASP_CHANGED, otaspMode, 0, null);
        }

        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            send(LISTEN_CELL_INFO, 0, 0, cellInfo);
        }

        public void onPreciseCallStateChanged(PreciseCallState callState) {
            send(LISTEN_PRECISE_CALL_STATE, 0, 0, callState);
        }

        public void onPreciseDataConnectionStateChanged(
                PreciseDataConnectionState dataConnectionState) {
            send(LISTEN_PRECISE_DATA_CONNECTION_STATE, 0, 0, dataConnectionState);
        }

        public void onDataConnectionRealTimeInfoChanged(
                DataConnectionRealTimeInfo dcRtInfo) {
            send(LISTEN_DATA_CONNECTION_REAL_TIME_INFO, 0, 0, dcRtInfo);
        }

        public void onVoLteServiceStateChanged(VoLteServiceState lteState) {
            send(LISTEN_VOLTE_STATE, 0, 0, lteState);
        }

        public void onVoiceActivationStateChanged(int activationState) {
            send(LISTEN_VOICE_ACTIVATION_STATE, 0, 0, activationState);
        }

        public void onDataActivationStateChanged(int activationState) {
            send(LISTEN_DATA_ACTIVATION_STATE, 0, 0, activationState);
        }

        public void onUserMobileDataStateChanged(boolean enabled) {
            send(LISTEN_USER_MOBILE_DATA_STATE, 0, 0, enabled);
        }

        public void onOemHookRawEvent(byte[] rawData) {
            send(LISTEN_OEM_HOOK_RAW_EVENT, 0, 0, rawData);
        }

        public void onCarrierNetworkChange(boolean active) {
            send(LISTEN_CARRIER_NETWORK_CHANGE, 0, 0, active);
        }

        public void onPhysicalChannelConfigurationChanged(List<PhysicalChannelConfig> configs) {
            send(LISTEN_PHYSICAL_CHANNEL_CONFIGURATION, 0, 0, configs);
        }
    }

    IPhoneStateListener callback = new IPhoneStateListenerStub(this);

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
