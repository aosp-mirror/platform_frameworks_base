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

import android.annotation.SystemApi;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Pair;

import com.android.internal.telecomm.ITelecommService;
import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to information about the telephony services on
 * the device. Applications can use the methods in this class to
 * determine telephony services and states, as well as to access some
 * types of subscriber information. Applications can also register
 * a listener to receive notification of telephony state changes.
 * <p>
 * You do not instantiate this class directly; instead, you retrieve
 * a reference to an instance through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.TELEPHONY_SERVICE)}.
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application cannot access the protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * the methods through which you access the protected information.
 */
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    private static final String TELECOMM_SERVICE_NAME = "telecomm";

    private static ITelephonyRegistry sRegistry;

    /**
     * The allowed states of Wi-Fi calling.
     *
     * @hide
     */
    public interface WifiCallingChoices {
        /** Always use Wi-Fi calling */
        static final int ALWAYS_USE = 0;
        /** Ask the user whether to use Wi-Fi on every call */
        static final int ASK_EVERY_TIME = 1;
        /** Never use Wi-Fi calling */
        static final int NEVER_USE = 2;
    }

    private final Context mContext;

    private static String multiSimConfig =
            SystemProperties.get(TelephonyProperties.PROPERTY_MULTI_SIM_CONFIG);

    /** Enum indicating multisim variants
     *  DSDS - Dual SIM Dual Standby
     *  DSDA - Dual SIM Dual Active
     *  TSTS - Triple SIM Triple Standby
     **/
    /** @hide */
    public enum MultiSimVariants {
        DSDS,
        DSDA,
        TSTS,
        UNKNOWN
    };

    /** @hide */
    public TelephonyManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }

        if (sRegistry == null) {
            sRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
        }
    }

    /** @hide */
    private TelephonyManager() {
        mContext = null;
    }

    private static TelephonyManager sInstance = new TelephonyManager();

    /** @hide
    /* @deprecated - use getSystemService as described above */
    public static TelephonyManager getDefault() {
        return sInstance;
    }


    /**
     * Returns the multi SIM variant
     * Returns DSDS for Dual SIM Dual Standby
     * Returns DSDA for Dual SIM Dual Active
     * Returns TSTS for Triple SIM Triple Standby
     * Returns UNKNOWN for others
     */
    /** {@hide} */
    public MultiSimVariants getMultiSimConfiguration() {
        String mSimConfig =
            SystemProperties.get(TelephonyProperties.PROPERTY_MULTI_SIM_CONFIG);
        if (mSimConfig.equals("dsds")) {
            return MultiSimVariants.DSDS;
        } else if (mSimConfig.equals("dsda")) {
            return MultiSimVariants.DSDA;
        } else if (mSimConfig.equals("tsts")) {
            return MultiSimVariants.TSTS;
        } else {
            return MultiSimVariants.UNKNOWN;
        }
    }


    /**
     * Returns the number of phones available.
     * Returns 1 for Single standby mode (Single SIM functionality)
     * Returns 2 for Dual standby mode.(Dual SIM functionality)
     */
    /** {@hide} */
    public int getPhoneCount() {
        int phoneCount = 1;
        switch (getMultiSimConfiguration()) {
            case DSDS:
            case DSDA:
                phoneCount = PhoneConstants.MAX_PHONE_COUNT_DUAL_SIM;
                break;
            case TSTS:
                phoneCount = PhoneConstants.MAX_PHONE_COUNT_TRI_SIM;
                break;
        }
        return phoneCount;
    }

    /** {@hide} */
    public static TelephonyManager from(Context context) {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /** {@hide} */
    public boolean isMultiSimEnabled() {
        return (multiSimConfig.equals("dsds") || multiSimConfig.equals("dsda") ||
            multiSimConfig.equals("tsts"));
    }

    //
    // Broadcast Intent actions
    //

    /**
     * Broadcast intent action indicating that the call state (cellular)
     * on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_STATE} extra indicates the new call state.
     * If the new state is RINGING, a second extra
     * {@link #EXTRA_INCOMING_NUMBER} provides the incoming phone number as
     * a String.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">
     * This was a {@link android.content.Context#sendStickyBroadcast sticky}
     * broadcast in version 1.0, but it is no longer sticky.
     * Instead, use {@link #getCallState} to synchronously query the current call state.
     *
     * @see #EXTRA_STATE
     * @see #EXTRA_INCOMING_NUMBER
     * @see #getCallState
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PHONE_STATE_CHANGED =
            "android.intent.action.PHONE_STATE";

    /**
     * The Phone app sends this intent when a user opts to respond-via-message during an incoming
     * call. By default, the device's default SMS app consumes this message and sends a text message
     * to the caller. A third party app can also provide this functionality by consuming this Intent
     * with a {@link android.app.Service} and sending the message using its own messaging system.
     * <p>The intent contains a URI (available from {@link android.content.Intent#getData})
     * describing the recipient, using either the {@code sms:}, {@code smsto:}, {@code mms:},
     * or {@code mmsto:} URI schema. Each of these URI schema carry the recipient information the
     * same way: the path part of the URI contains the recipient's phone number or a comma-separated
     * set of phone numbers if there are multiple recipients. For example, {@code
     * smsto:2065551234}.</p>
     *
     * <p>The intent may also contain extras for the message text (in {@link
     * android.content.Intent#EXTRA_TEXT}) and a message subject
     * (in {@link android.content.Intent#EXTRA_SUBJECT}).</p>
     *
     * <p class="note"><strong>Note:</strong>
     * The intent-filter that consumes this Intent needs to be in a {@link android.app.Service}
     * that requires the
     * permission {@link android.Manifest.permission#SEND_RESPOND_VIA_MESSAGE}.</p>
     * <p>For example, the service that receives this intent can be declared in the manifest file
     * with an intent filter like this:</p>
     * <pre>
     * &lt;!-- Service that delivers SMS messages received from the phone "quick response" -->
     * &lt;service android:name=".HeadlessSmsSendService"
     *          android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"
     *          android:exported="true" >
     *   &lt;intent-filter>
     *     &lt;action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
     *     &lt;category android:name="android.intent.category.DEFAULT" />
     *     &lt;data android:scheme="sms" />
     *     &lt;data android:scheme="smsto" />
     *     &lt;data android:scheme="mms" />
     *     &lt;data android:scheme="mmsto" />
     *   &lt;/intent-filter>
     * &lt;/service></pre>
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_RESPOND_VIA_MESSAGE =
            "android.intent.action.RESPOND_VIA_MESSAGE";

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the new call state.
     *
     * @see #EXTRA_STATE_IDLE
     * @see #EXTRA_STATE_RINGING
     * @see #EXTRA_STATE_OFFHOOK
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_STATE = PhoneConstants.STATE_KEY;

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_IDLE}.
     */
    public static final String EXTRA_STATE_IDLE = PhoneConstants.State.IDLE.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_RINGING}.
     */
    public static final String EXTRA_STATE_RINGING = PhoneConstants.State.RINGING.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_OFFHOOK}.
     */
    public static final String EXTRA_STATE_OFFHOOK = PhoneConstants.State.OFFHOOK.toString();

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the incoming phone number.
     * Only valid when the new call state is RINGING.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";

    /**
     * Broadcast intent action indicating that a precise call state
     * (cellular) on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_RINGING_CALL_STATE} extra indicates the ringing call state.
     * The {@link #EXTRA_FOREGROUND_CALL_STATE} extra indicates the foreground call state.
     * The {@link #EXTRA_BACKGROUND_CALL_STATE} extra indicates the background call state.
     * The {@link #EXTRA_DISCONNECT_CAUSE} extra indicates the disconnect cause.
     * The {@link #EXTRA_PRECISE_DISCONNECT_CAUSE} extra indicates the precise disconnect cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_RINGING_CALL_STATE
     * @see #EXTRA_FOREGROUND_CALL_STATE
     * @see #EXTRA_BACKGROUND_CALL_STATE
     * @see #EXTRA_DISCONNECT_CAUSE
     * @see #EXTRA_PRECISE_DISCONNECT_CAUSE
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_CALL_STATE_CHANGED =
            "android.intent.action.PRECISE_CALL_STATE";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current ringing call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_RINGING_CALL_STATE = "ringing_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current foreground call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_FOREGROUND_CALL_STATE = "foreground_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current background call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_BACKGROUND_CALL_STATE = "background_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the disconnect cause.
     *
     * @see DisconnectCause
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DISCONNECT_CAUSE = "disconnect_cause";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the disconnect cause provided by the RIL.
     *
     * @see PreciseDisconnectCause
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_PRECISE_DISCONNECT_CAUSE = "precise_disconnect_cause";

    /**
     * Broadcast intent action indicating a data connection has changed,
     * providing precise information about the connection.
     *
     * <p>
     * The {@link #EXTRA_DATA_STATE} extra indicates the connection state.
     * The {@link #EXTRA_DATA_NETWORK_TYPE} extra indicates the connection network type.
     * The {@link #EXTRA_DATA_APN_TYPE} extra indicates the APN type.
     * The {@link #EXTRA_DATA_APN} extra indicates the APN.
     * The {@link #EXTRA_DATA_CHANGE_REASON} extra indicates the connection change reason.
     * The {@link #EXTRA_DATA_IFACE_PROPERTIES} extra indicates the connection interface.
     * The {@link #EXTRA_DATA_FAILURE_CAUSE} extra indicates the connection fail cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_DATA_STATE
     * @see #EXTRA_DATA_NETWORK_TYPE
     * @see #EXTRA_DATA_APN_TYPE
     * @see #EXTRA_DATA_APN
     * @see #EXTRA_DATA_CHANGE_REASON
     * @see #EXTRA_DATA_IFACE
     * @see #EXTRA_DATA_FAILURE_CAUSE
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED =
            "android.intent.action.PRECISE_DATA_CONNECTION_STATE_CHANGED";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the state of the current data connection.
     *
     * @see TelephonyManager#DATA_UNKNOWN
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_STATE = PhoneConstants.STATE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the network type.
     *
     * @see TelephonyManager#NETWORK_TYPE_UNKNOWN
     * @see TelephonyManager#NETWORK_TYPE_GPRS
     * @see TelephonyManager#NETWORK_TYPE_EDGE
     * @see TelephonyManager#NETWORK_TYPE_UMTS
     * @see TelephonyManager#NETWORK_TYPE_CDMA
     * @see TelephonyManager#NETWORK_TYPE_EVDO_0
     * @see TelephonyManager#NETWORK_TYPE_EVDO_A
     * @see TelephonyManager#NETWORK_TYPE_1xRTT
     * @see TelephonyManager#NETWORK_TYPE_HSDPA
     * @see TelephonyManager#NETWORK_TYPE_HSUPA
     * @see TelephonyManager#NETWORK_TYPE_HSPA
     * @see TelephonyManager#NETWORK_TYPE_IDEN
     * @see TelephonyManager#NETWORK_TYPE_EVDO_B
     * @see TelephonyManager#NETWORK_TYPE_LTE
     * @see TelephonyManager#NETWORK_TYPE_EHRPD
     * @see TelephonyManager#NETWORK_TYPE_HSPAP
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_NETWORK_TYPE = PhoneConstants.DATA_NETWORK_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String containing the data APN type.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_APN_TYPE = PhoneConstants.DATA_APN_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String containing the data APN.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_APN = PhoneConstants.DATA_APN_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the change reason.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_CHANGE_REASON = PhoneConstants.STATE_CHANGE_REASON_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the data interface.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_LINK_PROPERTIES_KEY = PhoneConstants.DATA_LINK_PROPERTIES_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for the data connection fail cause.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_FAILURE_CAUSE = PhoneConstants.DATA_FAILURE_CAUSE_KEY;

    //
    //
    // Device Info
    //
    //

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceSoftwareVersion() {
        try {
            return getSubscriberInfo().getDeviceSvn();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the unique device ID, for example, the IMEI for GSM and the MEID
     * or ESN for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceId() {
        return getDeviceId(getDefaultSim());
    }

    /**
     * Returns the unique device ID of a subscription, for example, the IMEI for
     * GSM and the MEID for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param slotId of which deviceID is returned
     */
    /** {@hide} */
    public String getDeviceId(int slotId) {
        long[] subId = SubscriptionManager.getSubId(slotId);
        try {
            return getSubscriberInfo().getDeviceIdUsingSubId(subId[0]);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the IMEI. Return null if IMEI is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public String getImei() {
        return getImei(getDefaultSim());
    }

    /**
     * Returns the IMEI. Return null if IMEI is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param slotId of which deviceID is returned
     */
    /** {@hide} */
    public String getImei(int slotId) {
        long[] subId = SubscriptionManager.getSubId(slotId);
        try {
            return getSubscriberInfo().getImeiUsingSubId(subId[0]);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the current location of the device.
     *<p>
     * If there is only one radio in the device and that radio has an LTE connection,
     * this method will return null. The implementation must not to try add LTE
     * identifiers into the existing cdma/gsm classes.
     *<p>
     * In the future this call will be deprecated.
     *<p>
     * @return Current location of the device or null if not available.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION}.
     */
    public CellLocation getCellLocation() {
        try {
            Bundle bundle = getITelephony().getCellLocation();
            if (bundle.isEmpty()) return null;
            CellLocation cl = CellLocation.newFromBundle(bundle);
            if (cl.isEmpty())
                return null;
            return cl;
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Enables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void enableLocationUpdates() {
            enableLocationUpdates(getDefaultSubscription());
    }

    /**
     * Enables location update notifications for a subscription.
     * {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @param subId for which the location updates are enabled
     */
    /** @hide */
    public void enableLocationUpdates(long subId) {
        try {
            getITelephony().enableLocationUpdatesUsingSubId(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void disableLocationUpdates() {
            disableLocationUpdates(getDefaultSubscription());
    }

    /** @hide */
    public void disableLocationUpdates(long subId) {
        try {
            getITelephony().disableLocationUpdatesUsingSubId(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the neighboring cell information of the device. The getAllCellInfo is preferred
     * and use this only if getAllCellInfo return nulls or an empty list.
     *<p>
     * In the future this call will be deprecated.
     *<p>
     * @return List of NeighboringCellInfo or null if info unavailable.
     *
     * <p>Requires Permission:
     * (@link android.Manifest.permission#ACCESS_COARSE_UPDATES}
     */
    public List<NeighboringCellInfo> getNeighboringCellInfo() {
        try {
            return getITelephony().getNeighboringCellInfo(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** No phone radio. */
    public static final int PHONE_TYPE_NONE = PhoneConstants.PHONE_TYPE_NONE;
    /** Phone radio is GSM. */
    public static final int PHONE_TYPE_GSM = PhoneConstants.PHONE_TYPE_GSM;
    /** Phone radio is CDMA. */
    public static final int PHONE_TYPE_CDMA = PhoneConstants.PHONE_TYPE_CDMA;
    /** Phone is via SIP. */
    public static final int PHONE_TYPE_SIP = PhoneConstants.PHONE_TYPE_SIP;

    /**
     * Returns the current phone type.
     * TODO: This is a last minute change and hence hidden.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     *
     * {@hide}
     */
    public int getCurrentPhoneType() {
        return getCurrentPhoneType(getDefaultSubscription());
    }

    /**
     * Returns a constant indicating the device phone type for a subscription.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     *
     * @param subId for which phone type is returned
     */
    /** {@hide} */
    public int getCurrentPhoneType(long subId) {

        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActivePhoneTypeUsingSubId(subId);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return getPhoneTypeFromProperty(subId);
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(subId);
        } catch (NullPointerException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(subId);
        }
    }

    /**
     * Returns a constant indicating the device phone type.  This
     * indicates the type of radio used to transmit voice calls.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     */
    public int getPhoneType() {
        if (!isVoiceCapable()) {
            return PHONE_TYPE_NONE;
        }
        return getCurrentPhoneType();
    }

    private int getPhoneTypeFromProperty() {
        return getPhoneTypeFromProperty(getDefaultSubscription());
    }

    /** {@hide} */
    private int getPhoneTypeFromProperty(long subId) {
        String type =
            getTelephonyProperty
                (TelephonyProperties.CURRENT_ACTIVE_PHONE, subId, null);
        if (type != null) {
            return (Integer.parseInt(type));
        } else {
            return getPhoneTypeFromNetworkType(subId);
        }
    }

    private int getPhoneTypeFromNetworkType() {
        return getPhoneTypeFromNetworkType(getDefaultSubscription());
    }

    /** {@hide} */
    private int getPhoneTypeFromNetworkType(long subId) {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        String mode = getTelephonyProperty("ro.telephony.default_network", subId, null);
        if (mode != null) {
            return TelephonyManager.getPhoneType(Integer.parseInt(mode));
        }
        return TelephonyManager.PHONE_TYPE_NONE;
    }

    /**
     * This function returns the type of the phone, depending
     * on the network mode.
     *
     * @param networkMode
     * @return Phone Type
     *
     * @hide
     */
    public static int getPhoneType(int networkMode) {
        switch(networkMode) {
        case RILConstants.NETWORK_MODE_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
        case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_WCDMA_PREF:
        case RILConstants.NETWORK_MODE_GSM_ONLY:
        case RILConstants.NETWORK_MODE_WCDMA_ONLY:
        case RILConstants.NETWORK_MODE_GSM_UMTS:
        case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
            return PhoneConstants.PHONE_TYPE_GSM;

        // Use CDMA Phone for the global mode including CDMA
        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_LTE_ONLY:
            if (getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                return PhoneConstants.PHONE_TYPE_CDMA;
            } else {
                return PhoneConstants.PHONE_TYPE_GSM;
            }
        default:
            return PhoneConstants.PHONE_TYPE_GSM;
        }
    }

    /**
     * The contents of the /proc/cmdline file
     */
    private static String getProcCmdLine()
    {
        String cmdline = "";
        FileInputStream is = null;
        try {
            is = new FileInputStream("/proc/cmdline");
            byte [] buffer = new byte[2048];
            int count = is.read(buffer);
            if (count > 0) {
                cmdline = new String(buffer, 0, count);
            }
        } catch (IOException e) {
            Rlog.d(TAG, "No /proc/cmdline exception=" + e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
        Rlog.d(TAG, "/proc/cmdline=" + cmdline);
        return cmdline;
    }

    /** Kernel command line */
    private static final String sKernelCmdLine = getProcCmdLine();

    /** Pattern for selecting the product type from the kernel command line */
    private static final Pattern sProductTypePattern =
        Pattern.compile("\\sproduct_type\\s*=\\s*(\\w+)");

    /** The ProductType used for LTE on CDMA devices */
    private static final String sLteOnCdmaProductType =
        SystemProperties.get(TelephonyProperties.PROPERTY_LTE_ON_CDMA_PRODUCT_TYPE, "");

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    public static int getLteOnCdmaModeStatic() {
        int retVal;
        int curVal;
        String productType = "";

        curVal = SystemProperties.getInt(TelephonyProperties.PROPERTY_LTE_ON_CDMA_DEVICE,
                    PhoneConstants.LTE_ON_CDMA_UNKNOWN);
        retVal = curVal;
        if (retVal == PhoneConstants.LTE_ON_CDMA_UNKNOWN) {
            Matcher matcher = sProductTypePattern.matcher(sKernelCmdLine);
            if (matcher.find()) {
                productType = matcher.group(1);
                if (sLteOnCdmaProductType.equals(productType)) {
                    retVal = PhoneConstants.LTE_ON_CDMA_TRUE;
                } else {
                    retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
                }
            } else {
                retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
            }
        }

        Rlog.d(TAG, "getLteOnCdmaMode=" + retVal + " curVal=" + curVal +
                " product_type='" + productType +
                "' lteOnCdmaProductType='" + sLteOnCdmaProductType + "'");
        return retVal;
    }

    //
    //
    // Current Network
    //
    //

    /**
     * Returns the alphabetic name of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperatorName() {
        return getNetworkOperatorName(getDefaultSubscription());
    }

    /**
     * Returns the alphabetic name of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     * @param subId
     */
    /** {@hide} */
    public String getNetworkOperatorName(long subId) {

        return getTelephonyProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                subId, "");
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperator() {
        return getNetworkOperator(getDefaultSubscription());
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param subId
     */
    /** {@hide} */
   public String getNetworkOperator(long subId) {

        return getTelephonyProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC,
                subId, "");
     }

    /**
     * Returns true if the device is considered roaming on the current
     * network, for GSM purposes.
     * <p>
     * Availability: Only when user registered to a network.
     */
    public boolean isNetworkRoaming() {
        return isNetworkRoaming(getDefaultSubscription());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId
     */
    /** {@hide} */
    public boolean isNetworkRoaming(long subId) {
        return "true".equals(getTelephonyProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                subId, null));
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkCountryIso() {
        return getNetworkCountryIso(getDefaultSubscription());
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code) of a subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param subId for which Network CountryIso is returned
     */
    /** {@hide} */
    public String getNetworkCountryIso(long subId) {
        return getTelephonyProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY,
                subId, "");
    }

    /** Network type is unknown */
    public static final int NETWORK_TYPE_UNKNOWN = 0;
    /** Current network is GPRS */
    public static final int NETWORK_TYPE_GPRS = 1;
    /** Current network is EDGE */
    public static final int NETWORK_TYPE_EDGE = 2;
    /** Current network is UMTS */
    public static final int NETWORK_TYPE_UMTS = 3;
    /** Current network is CDMA: Either IS95A or IS95B*/
    public static final int NETWORK_TYPE_CDMA = 4;
    /** Current network is EVDO revision 0*/
    public static final int NETWORK_TYPE_EVDO_0 = 5;
    /** Current network is EVDO revision A*/
    public static final int NETWORK_TYPE_EVDO_A = 6;
    /** Current network is 1xRTT*/
    public static final int NETWORK_TYPE_1xRTT = 7;
    /** Current network is HSDPA */
    public static final int NETWORK_TYPE_HSDPA = 8;
    /** Current network is HSUPA */
    public static final int NETWORK_TYPE_HSUPA = 9;
    /** Current network is HSPA */
    public static final int NETWORK_TYPE_HSPA = 10;
    /** Current network is iDen */
    public static final int NETWORK_TYPE_IDEN = 11;
    /** Current network is EVDO revision B*/
    public static final int NETWORK_TYPE_EVDO_B = 12;
    /** Current network is LTE */
    public static final int NETWORK_TYPE_LTE = 13;
    /** Current network is eHRPD */
    public static final int NETWORK_TYPE_EHRPD = 14;
    /** Current network is HSPA+ */
    public static final int NETWORK_TYPE_HSPAP = 15;
    /** Current network is GSM {@hide} */
    public static final int NETWORK_TYPE_GSM = 16;

    /**
     * @return the NETWORK_TYPE_xxxx for current data connection.
     */
    public int getNetworkType() {
        return getDataNetworkType();
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for a subscription.
     * @return the network type
     *
     * @param subId for which network type is returned
     *
     * @see #NETWORK_TYPE_UNKNOWN
     * @see #NETWORK_TYPE_GPRS
     * @see #NETWORK_TYPE_EDGE
     * @see #NETWORK_TYPE_UMTS
     * @see #NETWORK_TYPE_HSDPA
     * @see #NETWORK_TYPE_HSUPA
     * @see #NETWORK_TYPE_HSPA
     * @see #NETWORK_TYPE_CDMA
     * @see #NETWORK_TYPE_EVDO_0
     * @see #NETWORK_TYPE_EVDO_A
     * @see #NETWORK_TYPE_EVDO_B
     * @see #NETWORK_TYPE_1xRTT
     * @see #NETWORK_TYPE_IDEN
     * @see #NETWORK_TYPE_LTE
     * @see #NETWORK_TYPE_EHRPD
     * @see #NETWORK_TYPE_HSPAP
     */
    /** {@hide} */
   public int getNetworkType(long subId) {
       try {
           ITelephony telephony = getITelephony();
           if (telephony != null) {
               return telephony.getNetworkTypeUsingSubId(subId);
           } else {
               // This can happen when the ITelephony interface is not up yet.
               return NETWORK_TYPE_UNKNOWN;
           }
       } catch(RemoteException ex) {
           // This shouldn't happen in the normal case
           return NETWORK_TYPE_UNKNOWN;
       } catch (NullPointerException ex) {
           // This could happen before phone restarts due to crashing
           return NETWORK_TYPE_UNKNOWN;
       }
   }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission.
     * @return the network type
     *
     * @see #NETWORK_TYPE_UNKNOWN
     * @see #NETWORK_TYPE_GPRS
     * @see #NETWORK_TYPE_EDGE
     * @see #NETWORK_TYPE_UMTS
     * @see #NETWORK_TYPE_HSDPA
     * @see #NETWORK_TYPE_HSUPA
     * @see #NETWORK_TYPE_HSPA
     * @see #NETWORK_TYPE_CDMA
     * @see #NETWORK_TYPE_EVDO_0
     * @see #NETWORK_TYPE_EVDO_A
     * @see #NETWORK_TYPE_EVDO_B
     * @see #NETWORK_TYPE_1xRTT
     * @see #NETWORK_TYPE_IDEN
     * @see #NETWORK_TYPE_LTE
     * @see #NETWORK_TYPE_EHRPD
     * @see #NETWORK_TYPE_HSPAP
     *
     * @hide
     */
    public int getDataNetworkType() {
        return getDataNetworkType(getDefaultSubscription());
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission for a subscription
     * @return the network type
     *
     * @param subId for which network type is returned
     */
    /** {@hide} */
    public int getDataNetworkType(long subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getDataNetworkTypeUsingSubId(subId);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice
     *
     * @hide
     */
    public int getVoiceNetworkType() {
        return getVoiceNetworkType(getDefaultSubscription());
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice for a subId
     *
     */
    /** {@hide} */
    public int getVoiceNetworkType(long subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getVoiceNetworkTypeUsingSubId(subId);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /** Unknown network class. {@hide} */
    public static final int NETWORK_CLASS_UNKNOWN = 0;
    /** Class of broadly defined "2G" networks. {@hide} */
    public static final int NETWORK_CLASS_2_G = 1;
    /** Class of broadly defined "3G" networks. {@hide} */
    public static final int NETWORK_CLASS_3_G = 2;
    /** Class of broadly defined "4G" networks. {@hide} */
    public static final int NETWORK_CLASS_4_G = 3;

    /**
     * Return general class of network type, such as "3G" or "4G". In cases
     * where classification is contentious, this method is conservative.
     *
     * @hide
     */
    public static int getNetworkClass(int networkType) {
        switch (networkType) {
            case NETWORK_TYPE_GPRS:
            case NETWORK_TYPE_GSM:
            case NETWORK_TYPE_EDGE:
            case NETWORK_TYPE_CDMA:
            case NETWORK_TYPE_1xRTT:
            case NETWORK_TYPE_IDEN:
                return NETWORK_CLASS_2_G;
            case NETWORK_TYPE_UMTS:
            case NETWORK_TYPE_EVDO_0:
            case NETWORK_TYPE_EVDO_A:
            case NETWORK_TYPE_HSDPA:
            case NETWORK_TYPE_HSUPA:
            case NETWORK_TYPE_HSPA:
            case NETWORK_TYPE_EVDO_B:
            case NETWORK_TYPE_EHRPD:
            case NETWORK_TYPE_HSPAP:
                return NETWORK_CLASS_3_G;
            case NETWORK_TYPE_LTE:
                return NETWORK_CLASS_4_G;
            default:
                return NETWORK_CLASS_UNKNOWN;
        }
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @return the name of the radio technology
     *
     * @hide pending API council review
     */
    public String getNetworkTypeName() {
        return getNetworkTypeName(getNetworkType());
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @param subId for which network type is returned
     * @return the name of the radio technology
     *
     */
    /** {@hide} */
    public static String getNetworkTypeName(int type) {
        switch (type) {
            case NETWORK_TYPE_GPRS:
                return "GPRS";
            case NETWORK_TYPE_EDGE:
                return "EDGE";
            case NETWORK_TYPE_UMTS:
                return "UMTS";
            case NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case NETWORK_TYPE_HSPA:
                return "HSPA";
            case NETWORK_TYPE_CDMA:
                return "CDMA";
            case NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case NETWORK_TYPE_LTE:
                return "LTE";
            case NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case NETWORK_TYPE_IDEN:
                return "iDEN";
            case NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case NETWORK_TYPE_GSM:
                return "GSM";
            default:
                return "UNKNOWN";
        }
    }

    //
    //
    // SIM Card
    //
    //

    /** SIM card state: Unknown. Signifies that the SIM is in transition
     *  between states. For example, when the user inputs the SIM pin
     *  under PIN_REQUIRED state, a query for sim status returns
     *  this state before turning to SIM_STATE_READY. */
    public static final int SIM_STATE_UNKNOWN = 0;
    /** SIM card state: no SIM card is available in the device */
    public static final int SIM_STATE_ABSENT = 1;
    /** SIM card state: Locked: requires the user's SIM PIN to unlock */
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    /** SIM card state: Locked: requires the user's SIM PUK to unlock */
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    /** SIM card state: Locked: requries a network PIN to unlock */
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    /** SIM card state: Ready */
    public static final int SIM_STATE_READY = 5;
    /** SIM card state: SIM Card Error, Sim Card is present but faulty
     *@hide
     */
    public static final int SIM_STATE_CARD_IO_ERROR = 6;

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return hasIccCard(getDefaultSim());
    }

    /**
     * @return true if a ICC card is present for a subscription
     *
     * @param slotId for which icc card presence is checked
     */
    /** {@hide} */
    // FIXME Input argument slotId should be of type int
    public boolean hasIccCard(long slotId) {

        try {
            return getITelephony().hasIccCardUsingSlotId(slotId);
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return false;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return false;
        }
    }

    /**
     * Returns a constant indicating the state of the
     * device SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_CARD_IO_ERROR
     */
    public int getSimState() {
        return getSimState(getDefaultSim());
    }

    /**
     * Returns a constant indicating the state of the
     * device SIM card in a slot.
     *
     * @param slotId
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     */
    /** {@hide} */
    // FIXME the argument to pass is subId ??
    public int getSimState(int slotId) {
        long[] subId = SubscriptionManager.getSubId(slotId);
        if (subId == null) {
            return SIM_STATE_ABSENT;
        }
        // FIXME Do not use a property to determine SIM_STATE, call
        // appropriate method on some object.
        String prop =
            getTelephonyProperty(TelephonyProperties.PROPERTY_SIM_STATE, subId[0], "");
        if ("ABSENT".equals(prop)) {
            return SIM_STATE_ABSENT;
        }
        else if ("PIN_REQUIRED".equals(prop)) {
            return SIM_STATE_PIN_REQUIRED;
        }
        else if ("PUK_REQUIRED".equals(prop)) {
            return SIM_STATE_PUK_REQUIRED;
        }
        else if ("NETWORK_LOCKED".equals(prop)) {
            return SIM_STATE_NETWORK_LOCKED;
        }
        else if ("READY".equals(prop)) {
            return SIM_STATE_READY;
        }
        else if ("CARD_IO_ERROR".equals(prop)) {
            return SIM_STATE_CARD_IO_ERROR;
        }
        else {
            return SIM_STATE_UNKNOWN;
        }
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperator() {
        long subId = getDefaultSubscription();
        Rlog.d(TAG, "getSimOperator(): default subId=" + subId);
        return getSimOperator(subId);
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM for a particular subscription. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperator is returned
     */
    /** {@hide} */
    public String getSimOperator(long subId) {
        String operator = getTelephonyProperty(TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC,
                subId, "");
        Rlog.d(TAG, "getSimOperator: subId=" + subId + " operator=" + operator);
        return operator;
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperatorName() {
        return getSimOperatorName(getDefaultSubscription());
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperatorName is returned
     */
    /** {@hide} */
    public String getSimOperatorName(long subId) {
        return getTelephonyProperty(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA,
                subId, "");
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     */
    public String getSimCountryIso() {
        return getSimCountryIso(getDefaultSubscription());
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @param subId for which SimCountryIso is returned
     */
    /** {@hide} */
    public String getSimCountryIso(long subId) {
        return getTelephonyProperty(TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
                subId, "");
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSimSerialNumber() {
         return getSimSerialNumber(getDefaultSubscription());
    }

    /**
     * Returns the serial number for the given subscription, if applicable. Return null if it is
     * unavailable.
     * <p>
     * @param subId for which Sim Serial number is returned
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public String getSimSerialNumber(long subId) {
        try {
            return getSubscriberInfo().getIccSerialNumberUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    public int getLteOnCdmaMode() {
        return getLteOnCdmaMode(getDefaultSubscription());
    }

    /**
     * Return if the current radio is LTE on CDMA for Subscription. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param subId for which radio is LTE on CDMA is returned
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     */
    /** {@hide} */
    public int getLteOnCdmaMode(long subId) {
        try {
            return getITelephony().getLteOnCdmaModeUsingSubId(subId);
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        }
    }

    //
    //
    // Subscriber Info
    //
    //

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSubscriberId() {
        return getSubscriberId(getDefaultSubscription());
    }

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone
     * for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId whose subscriber id is returned
     */
    /** {@hide} */
    public String getSubscriberId(long subId) {
        try {
            return getSubscriberInfo().getSubscriberIdUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getGroupIdLevel1() {
        try {
            return getSubscriberInfo().getGroupIdLevel1();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone for a particular subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subscription whose subscriber id is returned
     */
    /** {@hide} */
    public String getGroupIdLevel1(long subId) {
        try {
            return getSubscriberInfo().getGroupIdLevel1UsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getLine1Number() {
        return getLine1Number(getDefaultSubscription());
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone for a particular subscription. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId whose phone number for line 1 is returned
     */
    /** {@hide} */
    public String getLine1Number(long subId) {
        String number = null;
        try {
            number = getITelephony().getLine1NumberForDisplay(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (number != null) {
            return number;
        }
        try {
            return getSubscriberInfo().getLine1NumberUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Set the phone number string and its alphatag for line 1 for display
     * purpose only, for example, displayed in Phone Status. It won't change
     * the actual MSISDN/MDN. This setting won't be persisted during power cycle
     * and it should be set again after reboot.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     */
    public void setLine1NumberForDisplay(String alphaTag, String number) {
        setLine1NumberForDisplay(getDefaultSubscription(), alphaTag, number);
    }

    /**
     * Set the phone number string and its alphatag for line 1 for display
     * purpose only, for example, displayed in Phone Status. It won't change
     * the actual MSISDN/MDN. This setting won't be persisted during power cycle
     * and it should be set again after reboot.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param subId the subscriber that the alphatag and dialing number belongs to.
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     */
    public void setLine1NumberForDisplay(long subId, String alphaTag, String number) {
        try {
            getITelephony().setLine1NumberForDisplay(subId, alphaTag, number);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     * nobody seems to call this.
     */
    public String getLine1AlphaTag() {
        return getLine1AlphaTag(getDefaultSubscription());
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number
     * for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose alphabetic identifier associated with line 1 is returned
     * nobody seems to call this.
     */
    /** {@hide} */
    public String getLine1AlphaTag(long subId) {
        String alphaTag = null;
        try {
            alphaTag = getITelephony().getLine1AlphaTagForDisplay(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (alphaTag != null) {
            return alphaTag;
        }
        try {
            return getSubscriberInfo().getLine1AlphaTagUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @hide
     */
    public String getMsisdn() {
        return getMsisdn(getDefaultSubscription());
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId for which msisdn is returned
     */
    /** {@hide} */
    public String getMsisdn(long subId) {
        try {
            return getSubscriberInfo().getMsisdnUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailNumber() {
        return getVoiceMailNumber(getDefaultSubscription());
    }

    /**
     * Returns the voice mail number for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose voice mail number is returned
     */
    /** {@hide} */
    public String getVoiceMailNumber(long subId) {
        try {
            return getSubscriberInfo().getVoiceMailNumberUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#CALL_PRIVILEGED CALL_PRIVILEGED}
     *
     * @hide
     */
    public String getCompleteVoiceMailNumber() {
        return getCompleteVoiceMailNumber(getDefaultSubscription());
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#CALL_PRIVILEGED CALL_PRIVILEGED}
     *
     * @param subId
     */
    /** {@hide} */
    public String getCompleteVoiceMailNumber(long subId) {
        try {
            return getSubscriberInfo().getCompleteVoiceMailNumberUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail count. Return 0 if unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getVoiceMessageCount() {
        return getVoiceMessageCount(getDefaultSubscription());
    }

    /**
     * Returns the voice mail count for a subscription. Return 0 if unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose voice message count is returned
     */
    /** {@hide} */
    public int getVoiceMessageCount(long subId) {
        try {
            return getITelephony().getVoiceMessageCountUsingSubId(subId);
        } catch (RemoteException ex) {
            return 0;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return 0;
        }
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailAlphaTag() {
        return getVoiceMailAlphaTag(getDefaultSubscription());
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number for a subscription.
     * <p>
     * Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose alphabetic identifier associated with the
     * voice mail number is returned
     */
    /** {@hide} */
    public String getVoiceMailAlphaTag(long subId) {
        try {
            return getSubscriberInfo().getVoiceMailAlphaTagUsingSubId(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     * @hide
     */
    public String getIsimImpi() {
        try {
            return getSubscriberInfo().getIsimImpi();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM.
     * @return the IMS domain name, or null if not present or not loaded
     * @hide
     */
    public String getIsimDomain() {
        try {
            return getSubscriberInfo().getIsimDomain();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS public user identities (IMPU) that were loaded from the ISIM.
     * @return an array of IMPU strings, with one IMPU per string, or null if
     *      not present or not loaded
     * @hide
     */
    public String[] getIsimImpu() {
        try {
            return getSubscriberInfo().getIsimImpu();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    private IPhoneSubInfo getSubscriberInfo() {
        // get it each time because that process crashes a lot
        return IPhoneSubInfo.Stub.asInterface(ServiceManager.getService("iphonesubinfo"));
    }

    /** Device call state: No activity. */
    public static final int CALL_STATE_IDLE = 0;
    /** Device call state: Ringing. A new call arrived and is
     *  ringing or waiting. In the latter case, another call is
     *  already active. */
    public static final int CALL_STATE_RINGING = 1;
    /** Device call state: Off-hook. At least one call exists
      * that is dialing, active, or on hold, and no calls are ringing
      * or waiting. */
    public static final int CALL_STATE_OFFHOOK = 2;

    /**
     * Returns a constant indicating the call state (cellular) on the device.
     */
    public int getCallState() {
        return getCallState(getDefaultSubscription());
    }

    /**
     * Returns a constant indicating the call state (cellular) on the device
     * for a subscription.
     *
     * @param subId whose call state is returned
     */
    /** {@hide} */
    public int getCallState(long subId) {
        try {
            return getITelephony().getCallStateUsingSubId(subId);
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return CALL_STATE_IDLE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return CALL_STATE_IDLE;
      }
    }

    /** Data connection activity: No traffic. */
    public static final int DATA_ACTIVITY_NONE = 0x00000000;
    /** Data connection activity: Currently receiving IP PPP traffic. */
    public static final int DATA_ACTIVITY_IN = 0x00000001;
    /** Data connection activity: Currently sending IP PPP traffic. */
    public static final int DATA_ACTIVITY_OUT = 0x00000002;
    /** Data connection activity: Currently both sending and receiving
     *  IP PPP traffic. */
    public static final int DATA_ACTIVITY_INOUT = DATA_ACTIVITY_IN | DATA_ACTIVITY_OUT;
    /**
     * Data connection is active, but physical link is down
     */
    public static final int DATA_ACTIVITY_DORMANT = 0x00000004;

    /**
     * Returns a constant indicating the type of activity on a data connection
     * (cellular).
     *
     * @see #DATA_ACTIVITY_NONE
     * @see #DATA_ACTIVITY_IN
     * @see #DATA_ACTIVITY_OUT
     * @see #DATA_ACTIVITY_INOUT
     * @see #DATA_ACTIVITY_DORMANT
     */
    public int getDataActivity() {
        try {
            return getITelephony().getDataActivity();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_ACTIVITY_NONE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return DATA_ACTIVITY_NONE;
      }
    }

    /** Data connection state: Unknown.  Used before we know the state.
     * @hide
     */
    public static final int DATA_UNKNOWN        = -1;
    /** Data connection state: Disconnected. IP traffic not available. */
    public static final int DATA_DISCONNECTED   = 0;
    /** Data connection state: Currently setting up a data connection. */
    public static final int DATA_CONNECTING     = 1;
    /** Data connection state: Connected. IP traffic should be available. */
    public static final int DATA_CONNECTED      = 2;
    /** Data connection state: Suspended. The connection is up, but IP
     * traffic is temporarily unavailable. For example, in a 2G network,
     * data activity may be suspended when a voice call arrives. */
    public static final int DATA_SUSPENDED      = 3;

    /**
     * Returns a constant indicating the current data connection state
     * (cellular).
     *
     * @see #DATA_DISCONNECTED
     * @see #DATA_CONNECTING
     * @see #DATA_CONNECTED
     * @see #DATA_SUSPENDED
     */
    public int getDataState() {
        try {
            return getITelephony().getDataState();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_DISCONNECTED;
        } catch (NullPointerException ex) {
            return DATA_DISCONNECTED;
        }
    }

    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }

    private ITelecommService getTelecommService() {
        return ITelecommService.Stub.asInterface(ServiceManager.getService(TELECOMM_SERVICE_NAME));
    }

    //
    //
    // PhoneStateListener
    //
    //

    /**
     * Registers a listener object to receive notification of changes
     * in specified telephony states.
     * <p>
     * To register a listener, pass a {@link PhoneStateListener}
     * and specify at least one telephony state of interest in
     * the events argument.
     *
     * At registration, and when a specified telephony state
     * changes, the telephony manager invokes the appropriate
     * callback method on the listener object and passes the
     * current (updated) values.
     * <p>
     * To unregister a listener, pass the listener object and set the
     * events argument to
     * {@link PhoneStateListener#LISTEN_NONE LISTEN_NONE} (0).
     *
     * @param listener The {@link PhoneStateListener} object to register
     *                 (or unregister)
     * @param events The telephony state(s) of interest to the listener,
     *               as a bitwise-OR combination of {@link PhoneStateListener}
     *               LISTEN_ flags.
     */
    public void listen(PhoneStateListener listener, int events) {
        String pkgForDebug = mContext != null ? mContext.getPackageName() : "<unknown>";
        try {
            Boolean notifyNow = (getITelephony() != null);
            sRegistry.listenUsingSubId(listener.mSubId, pkgForDebug, listener.callback, events, notifyNow);
        } catch (RemoteException ex) {
            // system process dead
        } catch (NullPointerException ex) {
            // system process dead
        }
    }

    /**
     * Returns the CDMA ERI icon index to display
     *
     * @hide
     */
    public int getCdmaEriIconIndex() {
        return getCdmaEriIconIndex(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI icon index to display for a subscription
     */
    /** {@hide} */
    public int getCdmaEriIconIndex(long subId) {
        try {
            return getITelephony().getCdmaEriIconIndexUsingSubId(subId);
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     *
     * @hide
     */
    public int getCdmaEriIconMode() {
        return getCdmaEriIconMode(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI icon mode for a subscription.
     * 0 - ON
     * 1 - FLASHING
     */
    /** {@hide} */
    public int getCdmaEriIconMode(long subId) {
        try {
            return getITelephony().getCdmaEriIconModeUsingSubId(subId);
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI text,
     *
     * @hide
     */
    public String getCdmaEriText() {
        return getCdmaEriText(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI text, of a subscription
     *
     */
    /** {@hide} */
    public String getCdmaEriText(long subId) {
        try {
            return getITelephony().getCdmaEriTextUsingSubId(subId);
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * @return true if the current device is "voice capable".
     * <p>
     * "Voice capable" means that this device supports circuit-switched
     * (i.e. voice) phone calls over the telephony network, and is allowed
     * to display the in-call UI while a cellular voice call is active.
     * This will be false on "data only" devices which can't make voice
     * calls and don't support any in-call UI.
     * <p>
     * Note: the meaning of this flag is subtly different from the
     * PackageManager.FEATURE_TELEPHONY system feature, which is available
     * on any device with a telephony radio, even if the device is
     * data-only.
     *
     * @hide pending API review
     */
    public boolean isVoiceCapable() {
        if (mContext == null) return true;
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }

    /**
     * @return true if the current device supports sms service.
     * <p>
     * If true, this means that the device supports both sending and
     * receiving sms via the telephony network.
     * <p>
     * Note: Voicemail waiting sms, cell broadcasting sms, and MMS are
     *       disabled when device doesn't support sms.
     */
    public boolean isSmsCapable() {
        if (mContext == null) return true;
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sms_capable);
    }

    /**
     * Returns all observed cell information from all radios on the
     * device including the primary and neighboring cells. This does
     * not cause or change the rate of PhoneStateListner#onCellInfoChanged.
     *<p>
     * The list can include one or more of {@link android.telephony.CellInfoGsm CellInfoGsm},
     * {@link android.telephony.CellInfoCdma CellInfoCdma},
     * {@link android.telephony.CellInfoLte CellInfoLte} and
     * {@link android.telephony.CellInfoWcdma CellInfoCdma} in any combination.
     * Specifically on devices with multiple radios it is typical to see instances of
     * one or more of any these in the list. In addition 0, 1 or more CellInfo
     * objects may return isRegistered() true.
     *<p>
     * This is preferred over using getCellLocation although for older
     * devices this may return null in which case getCellLocation should
     * be called.
     *<p>
     * @return List of CellInfo or null if info unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}
     */
    public List<CellInfo> getAllCellInfo() {
        try {
            return getITelephony().getAllCellInfo();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Sets the minimum time in milli-seconds between {@link PhoneStateListener#onCellInfoChanged
     * PhoneStateListener.onCellInfoChanged} will be invoked.
     *<p>
     * The default, 0, means invoke onCellInfoChanged when any of the reported
     * information changes. Setting the value to INT_MAX(0x7fffffff) means never issue
     * A onCellInfoChanged.
     *<p>
     * @param rateInMillis the rate
     *
     * @hide
     */
    public void setCellInfoListRate(int rateInMillis) {
        try {
            getITelephony().setCellInfoListRate(rateInMillis);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the MMS user agent.
     */
    public String getMmsUserAgent() {
        if (mContext == null) return null;
        return mContext.getResources().getString(
                com.android.internal.R.string.config_mms_user_agent);
    }

    /**
     * Returns the MMS user agent profile URL.
     */
    public String getMmsUAProfUrl() {
        if (mContext == null) return null;
        return mContext.getResources().getString(
                com.android.internal.R.string.config_mms_user_agent_profile_url);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @return an IccOpenLogicalChannelResponse object.
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID) {
        try {
            return getITelephony().iccOpenLogicalChannel(AID);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     */
    public boolean iccCloseLogicalChannel(int channel) {
        try {
            return getITelephony().iccCloseLogicalChannel(channel);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    public String iccTransmitApduLogicalChannel(int channel, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            return getITelephony().iccTransmitApduLogicalChannel(channel, cla,
                    instruction, p1, p2, p3, data);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Transmit an APDU to the ICC card over the basic channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    public String iccTransmitApduBasicChannel(int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            return getITelephony().iccTransmitApduBasicChannel(cla,
                    instruction, p1, p2, p3, data);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Returns the response APDU for a command APDU sent through SIM_IO.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param fileID
     * @param command
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command.
     * @param filePath
     * @return The APDU response.
     */
    public byte[] iccExchangeSimIO(int fileID, int command, int p1, int p2, int p3,
            String filePath) {
        try {
            return getITelephony().iccExchangeSimIO(fileID, command, p1, p2,
                p3, filePath);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card, with the last 4 bytes
     *         being the status word. If the command fails, returns an empty
     *         string.
     */
    public String sendEnvelopeWithStatus(String content) {
        try {
            return getITelephony().sendEnvelopeWithStatus(content);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Read one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param itemID the ID of the item to read.
     * @return the NV item as a String, or null on any failure.
     *
     * @hide
     */
    public String nvReadItem(int itemID) {
        try {
            return getITelephony().nvReadItem(itemID);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvReadItem RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvReadItem NPE", ex);
        }
        return "";
    }

    /**
     * Write one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param itemID the ID of the item to read.
     * @param itemValue the value to write, as a String.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteItem(int itemID, String itemValue) {
        try {
            return getITelephony().nvWriteItem(itemID, itemValue);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvWriteItem RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvWriteItem NPE", ex);
        }
        return false;
    }

    /**
     * Update the CDMA Preferred Roaming List (PRL) in the radio NV storage.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param preferredRoamingList byte array containing the new PRL.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteCdmaPrl(byte[] preferredRoamingList) {
        try {
            return getITelephony().nvWriteCdmaPrl(preferredRoamingList);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvWriteCdmaPrl RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvWriteCdmaPrl NPE", ex);
        }
        return false;
    }

    /**
     * Perform the specified type of NV config reset. The radio will be taken offline
     * and the device must be rebooted after the operation. Used for device
     * configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param resetType reset type: 1: reload NV reset, 2: erase NV reset, 3: factory NV reset
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvResetConfig(int resetType) {
        try {
            return getITelephony().nvResetConfig(resetType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvResetConfig RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvResetConfig NPE", ex);
        }
        return false;
    }

    /**
     * Returns Default subscription.
     */
    private static long getDefaultSubscription() {
        return SubscriptionManager.getDefaultSubId();
    }

    /** {@hide} */
    public int getDefaultSim() {
        //TODO Need to get it from Telephony Devcontroller
        return 0;
    }

    /**
     * Sets the telephony property with the value specified.
     *
     * @hide
     */
    public static void setTelephonyProperty(String property, long subId, String value) {
        String propVal = "";
        String p[] = null;
        String prop = SystemProperties.get(property);
        int phoneId = SubscriptionManager.getPhoneId(subId);

        if (value == null) {
            value = "";
        }

        if (prop != null) {
            p = prop.split(",");
        }

        if (phoneId < 0) return;

        for (int i = 0; i < phoneId; i++) {
            String str = "";
            if ((p != null) && (i < p.length)) {
                str = p[i];
            }
            propVal = propVal + str + ",";
        }

        propVal = propVal + value;
        if (p != null) {
            for (int i = phoneId + 1; i < p.length; i++) {
                propVal = propVal + "," + p[i];
            }
        }

        // TODO: workaround for QC
        if (property.length() > SystemProperties.PROP_NAME_MAX || propVal.length() > SystemProperties.PROP_VALUE_MAX) {
            Rlog.d(TAG, "setTelephonyProperty length too long:" + property + ", " + propVal);
            return;
        }

        Rlog.d(TAG, "setTelephonyProperty property=" + property + " propVal=" + propVal);
        SystemProperties.set(property, propVal);
    }

    /**
     * Convenience function for retrieving a value from the secure settings
     * value list as an integer.  Note that internally setting values are
     * always stored as strings; this function converts the string to an
     * integer for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link SettingNotFoundException}.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to retrieve.
     * @param index The index of the list
     *
     * @throws SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not an integer.
     *
     * @return The value at the given index of settings.
     * @hide
     */
    public static int getIntAtIndex(android.content.ContentResolver cr,
            String name, int index)
            throws android.provider.Settings.SettingNotFoundException {
        String v = android.provider.Settings.Global.getString(cr, name);
        if (v != null) {
            String valArray[] = v.split(",");
            if ((index >= 0) && (index < valArray.length) && (valArray[index] != null)) {
                try {
                    return Integer.parseInt(valArray[index]);
                } catch (NumberFormatException e) {
                    //Log.e(TAG, "Exception while parsing Integer: ", e);
                }
            }
        }
        throw new android.provider.Settings.SettingNotFoundException(name);
    }

    /**
     * Convenience function for updating settings value as coma separated
     * integer values. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to modify.
     * @param index The index of the list
     * @param value The new value for the setting to be added to the list.
     * @return true if the value was set, false on database errors
     * @hide
     */
    public static boolean putIntAtIndex(android.content.ContentResolver cr,
            String name, int index, int value) {
        String data = "";
        String valArray[] = null;
        String v = android.provider.Settings.Global.getString(cr, name);

        if (v != null) {
            valArray = v.split(",");
        }

        // Copy the elements from valArray till index
        for (int i = 0; i < index; i++) {
            String str = "";
            if ((valArray != null) && (i < valArray.length)) {
                str = valArray[i];
            }
            data = data + str + ",";
        }

        data = data + value;

        // Copy the remaining elements from valArray if any.
        if (valArray != null) {
            for (int i = index+1; i < valArray.length; i++) {
                data = data + "," + valArray[i];
            }
        }
        return android.provider.Settings.Global.putString(cr, name, data);
    }

    /**
     * Gets the telephony property.
     *
     * @hide
     */
    public static String getTelephonyProperty(String property, long subId, String defaultVal) {
        String propVal = null;
        int phoneId = SubscriptionManager.getPhoneId(subId);
        String prop = SystemProperties.get(property);
        if ((prop != null) && (prop.length() > 0)) {
            String values[] = prop.split(",");
            if ((phoneId >= 0) && (phoneId < values.length) && (values[phoneId] != null)) {
                propVal = values[phoneId];
            }
        }
        return propVal == null ? defaultVal : propVal;
    }

    /** @hide */
    public int getSimCount() {
        if(isMultiSimEnabled()) {
        //TODO Need to get it from Telephony Devcontroller
            return 2;
        } else {
           return 1;
        }
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     * @hide
     */
    public String getIsimIst() {
        try {
            return getSubscriberInfo().getIsimIst();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of PCSCF strings with one PCSCF per string, or null if
     *         not present or not loaded
     * @hide
     */
    public String[] getIsimPcscf() {
        try {
            return getSubscriberInfo().getIsimPcscf();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the response of ISIM Authetification through RIL.
     * Returns null if the Authentification hasn't been successed or isn't present iphonesubinfo.
     * @return the response of ISIM Authetification, or null if not available
     * @hide
     * @deprecated
     * @see getIccSimChallengeResponse with appType=PhoneConstants.APPTYPE_ISIM
     */
    public String getIsimChallengeResponse(String nonce){
        try {
            return getSubscriberInfo().getIsimChallengeResponse(nonce);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the response of SIM Authentication through RIL.
     * Returns null if the Authentication hasn't been successful
     * @param subId subscription ID to be queried
     * @param appType ICC application type (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param data authentication challenge data
     * @return the response of SIM Authentication, or null if not available
     * @hide
     */
    public String getIccSimChallengeResponse(long subId, int appType, String data) {
        try {
            return getSubscriberInfo().getIccSimChallengeResponse(subId, appType, data);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Returns the response of SIM Authentication through RIL for the default subscription.
     * Returns null if the Authentication hasn't been successful
     * @param appType ICC application type (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param data authentication challenge data
     * @return the response of SIM Authentication, or null if not available
     * @hide
     */
    public String getIccSimChallengeResponse(int appType, String data) {
        return getIccSimChallengeResponse(getDefaultSubscription(), appType, data);
    }

    /**
     * Get P-CSCF address from PCO after data connection is established or modified.
     * @param apnType the apnType, "ims" for IMS APN, "emergency" for EMERGENCY APN
     * @return array of P-CSCF address
     * @hide
     */
    public String[] getPcscfAddress(String apnType) {
        try {
            return getITelephony().getPcscfAddress(apnType);
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * Set IMS registration state
     *
     * @param Registration state
     * @hide
     */
    public void setImsRegistrationState(boolean registered) {
        try {
            getITelephony().setImsRegistrationState(registered);
        } catch (RemoteException e) {
        }
    }

    /**
     * Get the calculated preferred network type.
     * Used for debugging incorrect network type.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @return the preferred network type, defined in RILConstants.java or -1 if
     *         none available.
     */
    public int getCalculatedPreferredNetworkType() {
        try {
            return getITelephony().getCalculatedPreferredNetworkType();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCalculatedPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCalculatedPreferredNetworkType NPE", ex);
        }
        return -1;
    }

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @return the preferred network type, defined in RILConstants.java.
     */
    public int getPreferredNetworkType() {
        try {
            return getITelephony().getPreferredNetworkType();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPreferredNetworkType NPE", ex);
        }
        return -1;
    }

    /**
     * Set the preferred network type.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     */
    public boolean setPreferredNetworkType(int networkType) {
        try {
            return getITelephony().setPreferredNetworkType(networkType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setPreferredNetworkType NPE", ex);
        }
        return false;
    }

    /**
     * Set the CDMA subscription source.
     * Used for device supporting both NV and RUIM for CDMA.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param subscriptionType the subscription type, 0 for RUIM, 1 for NV.
     * @return true on success; false on any failure.
     */
    public boolean setCdmaSubscription(int subscriptionType) {
        try {
            return getITelephony().setCdmaSubscription(subscriptionType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setCdmaSubscription RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setCdmaSubscription NPE", ex);
        }
        return false;
    }

    /**
     * Values used to return status for hasCarrierPrivileges call.
     */
    public static final int CARRIER_PRIVILEGE_STATUS_HAS_ACCESS = 1;
    public static final int CARRIER_PRIVILEGE_STATUS_NO_ACCESS = 0;
    public static final int CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED = -1;
    public static final int CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES = -2;

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * TODO: Add a link to documentation.
     *
     * @return CARRIER_PRIVILEGE_STATUS_HAS_ACCESS if the app has carrier privileges.
     *         CARRIER_PRIVILEGE_STATUS_NO_ACCESS if the app does not have carrier privileges.
     *         CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED if the carrier rules are not loaded.
     *         CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES if there was an error loading carrier
     *             rules (or if there are no rules).
     */
    public int hasCarrierPrivileges() {
        try {
            return getITelephony().hasCarrierPrivileges();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /**
     * Override the branding for the input ICCID.
     *
     * Once set, whenever the ICCID is inserted into the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *  or has to be carrier app - see #hasCarrierPrivileges.
     *
     * @param iccId The ICCID of that the branding applies to.
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     */
    public boolean setOperatorBrandOverride(String iccId, String brand) {
        // TODO: Validate ICCID format.
        try {
            return getITelephony().setOperatorBrandOverride(iccId, brand);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride NPE", ex);
        }
        return false;
    }

    /**
     * Expose the rest of ITelephony to @SystemApi
     */

    /** @hide */
    @SystemApi
    public String getCdmaMdn() {
        return getCdmaMdn(getDefaultSubscription());
    }

    /** @hide */
    @SystemApi
    public String getCdmaMdn(long subId) {
        try {
            return getITelephony().getCdmaMdn(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    public String getCdmaMin() {
        return getCdmaMin(getDefaultSubscription());
    }

    /** @hide */
    @SystemApi
    public String getCdmaMin(long subId) {
        try {
            return getITelephony().getCdmaMin(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    public int checkCarrierPrivilegesForPackage(String pkgname) {
        try {
            return getITelephony().checkCarrierPrivilegesForPackage(pkgname);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /** @hide */
    @SystemApi
    public List<String> getCarrierPackageNamesForBroadcastIntent(Intent intent) {
        try {
            return getITelephony().getCarrierPackageNamesForBroadcastIntent(intent);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForBroadcastIntent RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForBroadcastIntent NPE", ex);
        }
        return null;
    }

    /** @hide */
    @SystemApi
    public void dial(String number) {
        try {
            getITelephony().dial(number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#dial", e);
        }
    }

    /** @hide */
    @SystemApi
    public void call(String callingPackage, String number) {
        try {
            getITelephony().call(callingPackage, number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#call", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean endCall() {
        try {
            return getITelephony().endCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#endCall", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void answerRingingCall() {
        try {
            getITelephony().answerRingingCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#answerRingingCall", e);
        }
    }

    /** @hide */
    @SystemApi
    public void silenceRinger() {
        try {
            getTelecommService().silenceRinger();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#silenceRinger", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean isOffhook() {
        try {
            return getITelephony().isOffhook();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isOffhook", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isRinging() {
        try {
            return getITelephony().isRinging();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRinging", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isIdle() {
        try {
            return getITelephony().isIdle();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isIdle", e);
        }
        return true;
    }

    /** @hide */
    @SystemApi
    public boolean isRadioOn() {
        try {
            return getITelephony().isRadioOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRadioOn", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isSimPinEnabled() {
        try {
            return getITelephony().isSimPinEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isSimPinEnabled", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean supplyPin(String pin) {
        try {
            return getITelephony().supplyPin(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPin", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean supplyPuk(String puk, String pin) {
        try {
            return getITelephony().supplyPuk(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPuk", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public int[] supplyPinReportResult(String pin) {
        try {
            return getITelephony().supplyPinReportResult(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinReportResult", e);
        }
        return new int[0];
    }

    /** @hide */
    @SystemApi
    public int[] supplyPukReportResult(String puk, String pin) {
        try {
            return getITelephony().supplyPukReportResult(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#]", e);
        }
        return new int[0];
    }

    /** @hide */
    @SystemApi
    public boolean handlePinMmi(String dialString) {
        try {
            return getITelephony().handlePinMmi(dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#handlePinMmi", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void toggleRadioOnOff() {
        try {
            getITelephony().toggleRadioOnOff();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#toggleRadioOnOff", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean setRadio(boolean turnOn) {
        try {
            return getITelephony().setRadio(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadio", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean setRadioPower(boolean turnOn) {
        try {
            return getITelephony().setRadioPower(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadioPower", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void updateServiceLocation() {
        try {
            getITelephony().updateServiceLocation();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#updateServiceLocation", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean enableDataConnectivity() {
        try {
            return getITelephony().enableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#enableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean disableDataConnectivity() {
        try {
            return getITelephony().disableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#disableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isDataConnectivityPossible() {
        try {
            return getITelephony().isDataConnectivityPossible();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataConnectivityPossible", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean needsOtaServiceProvisioning() {
        try {
            return getITelephony().needsOtaServiceProvisioning();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#needsOtaServiceProvisioning", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void setDataEnabled(boolean enable) {
        try {
            getITelephony().setDataEnabled(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setDataEnabled", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean getDataEnabled() {
        try {
            return getITelephony().getDataEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getDataEnabled", e);
        }
        return false;
    }

    /**
     * Set whether Android should display a simplified Mobile Network Settings UI.
     * The setting won't be persisted during power cycle.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param enable true means enabling the simplified UI.
     */
    public void enableSimplifiedNetworkSettings(boolean enable) {
        enableSimplifiedNetworkSettings(getDefaultSubscription(), enable);
    }

    /**
     * Set whether Android should display a simplified Mobile Network Settings UI.
     * The setting won't be persisted during power cycle.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param subId for which the simplified UI should be enabled or disabled.
     * @param enable true means enabling the simplified UI.
     */
    public void enableSimplifiedNetworkSettings(long subId, boolean enable) {
        try {
            getITelephony().enableSimplifiedNetworkSettings(subId, enable);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Get whether a simplified Mobile Network Settings UI is enabled.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @return true if the simplified UI is enabled.
     */
    public boolean getSimplifiedNetworkSettingsEnabled() {
        return getSimplifiedNetworkSettingsEnabled(getDefaultSubscription());
    }

    /**
     * Get whether a simplified Mobile Network Settings UI is enabled.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId for which the simplified UI should be enabled or disabled.
     * @return true if the simplified UI is enabled.
     */
    public boolean getSimplifiedNetworkSettingsEnabled(long subId) {
        try {
            return getITelephony().getSimplifiedNetworkSettingsEnabled(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Returns the result and response from RIL for oem request
     *
     * @param oemReq the data is sent to ril.
     * @param oemResp the respose data from RIL.
     * @return negative value request was not handled or get error
     *         0 request was handled succesfully, but no response data
     *         positive value success, data length of response
     * @hide
     */
    public int invokeOemRilRequestRaw(byte[] oemReq, byte[] oemResp) {
        try {
            return getITelephony().invokeOemRilRequestRaw(oemReq, oemResp);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return -1;
    }
}
