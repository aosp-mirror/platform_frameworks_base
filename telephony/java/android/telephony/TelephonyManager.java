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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressAutoDoc;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.app.ActivityThread;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkStats;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings.SettingNotFoundException;
import android.service.carrier.CarrierIdentifier;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.VisualVoicemailService.VisualVoicemailTask;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.ITelecomService;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to information about the telephony services on
 * the device. Applications can use the methods in this class to
 * determine telephony services and states, as well as to access some
 * types of subscriber information. Applications can also register
 * a listener to receive notification of telephony state changes.
 * <p>
 * The returned TelephonyManager will use the default subscription for all calls.
 * To call an API for a specific subscription, use {@link #createForSubscriptionId(int)}. e.g.
 * <code>
 *   telephonyManager = defaultSubTelephonyManager.createForSubscriptionId(subId);
 * </code>
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application cannot access the protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * the methods through which you access the protected information.
 */
@SystemService(Context.TELEPHONY_SERVICE)
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    /**
     * The key to use when placing the result of {@link #requestModemActivityInfo(ResultReceiver)}
     * into the ResultReceiver Bundle.
     * @hide
     */
    public static final String MODEM_ACTIVITY_RESULT_KEY =
            BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY;

    /**
     * The process name of the Phone app as well as many other apps that use this process name, such
     * as settings and vendor components.
     * @hide
     */
    public static final String PHONE_PROCESS_NAME = "com.android.phone";

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

    /** The otaspMode passed to PhoneStateListener#onOtaspChanged */
    /** @hide */
    static public final int OTASP_UNINITIALIZED = 0;
    /** @hide */
    static public final int OTASP_UNKNOWN = 1;
    /** @hide */
    static public final int OTASP_NEEDED = 2;
    /** @hide */
    static public final int OTASP_NOT_NEEDED = 3;
    /* OtaUtil has conflict enum 4: OtaUtils.OTASP_FAILURE_SPC_RETRIES */
    /** @hide */
    static public final int OTASP_SIM_UNPROVISIONED = 5;


    /** @hide */
    static public final int KEY_TYPE_EPDG = 1;

    /** @hide */
    static public final int KEY_TYPE_WLAN = 2;

    private final Context mContext;
    private final int mSubId;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyScanManager mTelephonyScanManager;

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
      this(context, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    /** @hide */
    public TelephonyManager(Context context, int subId) {
        mSubId = subId;
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
        mSubscriptionManager = SubscriptionManager.from(mContext);
    }

    /** @hide */
    private TelephonyManager() {
        mContext = null;
        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private static TelephonyManager sInstance = new TelephonyManager();

    /** @hide
    /* @deprecated - use getSystemService as described above */
    public static TelephonyManager getDefault() {
        return sInstance;
    }

    private String getOpPackageName() {
        // For legacy reasons the TelephonyManager has API for getting
        // a static instance with no context set preventing us from
        // getting the op package name. As a workaround we do a best
        // effort and get the context from the current activity thread.
        if (mContext != null) {
            return mContext.getOpPackageName();
        }
        return ActivityThread.currentOpPackageName();
    }

    private boolean isSystemProcess() {
        return Process.myUid() == Process.SYSTEM_UID;
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
     * Returns 0 if none of voice, sms, data is not supported
     * Returns 1 for Single standby mode (Single SIM functionality)
     * Returns 2 for Dual standby mode.(Dual SIM functionality)
     */
    public int getPhoneCount() {
        int phoneCount = 1;
        switch (getMultiSimConfiguration()) {
            case UNKNOWN:
                // if voice or sms or data is supported, return 1 otherwise 0
                if (isVoiceCapable() || isSmsCapable()) {
                    phoneCount = 1;
                } else {
                    // todo: try to clean this up further by getting rid of the nested conditions
                    if (mContext == null) {
                        phoneCount = 1;
                    } else {
                        // check for data support
                        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                                Context.CONNECTIVITY_SERVICE);
                        if (cm == null) {
                            phoneCount = 1;
                        } else {
                            if (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)) {
                                phoneCount = 1;
                            } else {
                                phoneCount = 0;
                            }
                        }
                    }
                }
                break;
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

    /**
     * Create a new TelephonyManager object pinned to the given subscription ID.
     *
     * @return a TelephonyManager that uses the given subId for all calls.
     */
    public TelephonyManager createForSubscriptionId(int subId) {
      // Don't reuse any TelephonyManager objects.
      return new TelephonyManager(mContext, subId);
    }

    /**
     * Create a new TelephonyManager object pinned to the subscription ID associated with the given
     * phone account.
     *
     * @return a TelephonyManager that uses the given phone account for all calls, or {@code null}
     * if the phone account does not correspond to a valid subscription ID.
     */
    @Nullable
    public TelephonyManager createForPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        int subId = getSubIdForPhoneAccountHandle(phoneAccountHandle);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return null;
        }
        return new TelephonyManager(mContext, subId);
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
     * Broadcast intent action indicating that the call state
     * on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_STATE} extra indicates the new call state.
     * If the new state is RINGING, a second extra
     * {@link #EXTRA_INCOMING_NUMBER} provides the incoming phone number as
     * a String.
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
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
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_RESPOND_VIA_MESSAGE =
            "android.intent.action.RESPOND_VIA_MESSAGE";

    /**
     * The emergency dialer may choose to present activities with intent filters for this
     * action as emergency assistance buttons that launch the activity when clicked.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_EMERGENCY_ASSISTANCE =
            "android.telephony.action.EMERGENCY_ASSISTANCE";

    /**
     * A boolean meta-data value indicating whether the voicemail settings should be hidden in the
     * call settings page launched by
     * {@link android.telecom.TelecomManager#ACTION_SHOW_CALL_SETTINGS}.
     * Dialer implementations (see {@link android.telecom.TelecomManager#getDefaultDialerPackage()})
     * which would also like to manage voicemail settings should set this meta-data to {@code true}
     * in the manifest registration of their application.
     *
     * @see android.telecom.TelecomManager#ACTION_SHOW_CALL_SETTINGS
     * @see #ACTION_CONFIGURE_VOICEMAIL
     * @see #EXTRA_HIDE_PUBLIC_SETTINGS
     */
    public static final String METADATA_HIDE_VOICEMAIL_SETTINGS_MENU =
            "android.telephony.HIDE_VOICEMAIL_SETTINGS_MENU";

    /**
     * Open the voicemail settings activity to make changes to voicemail configuration.
     *
     * <p>
     * The {@link #EXTRA_PHONE_ACCOUNT_HANDLE} extra indicates which {@link PhoneAccountHandle} to
     * configure voicemail.
     * The {@link #EXTRA_HIDE_PUBLIC_SETTINGS} hides settings the dialer will modify through public
     * API if set.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @see #EXTRA_HIDE_PUBLIC_SETTINGS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONFIGURE_VOICEMAIL =
            "android.telephony.action.CONFIGURE_VOICEMAIL";

    /**
     * The boolean value indicating whether the voicemail settings activity launched by {@link
     * #ACTION_CONFIGURE_VOICEMAIL} should hide settings accessible through public API. This is
     * used by dialer implementations which provides their own voicemail settings UI, but still
     * needs to expose device specific voicemail settings to the user.
     *
     * @see #ACTION_CONFIGURE_VOICEMAIL
     * @see #METADATA_HIDE_VOICEMAIL_SETTINGS_MENU
     */
    public static final String EXTRA_HIDE_PUBLIC_SETTINGS =
            "android.telephony.extra.HIDE_PUBLIC_SETTINGS";

    /**
     * @hide
     */
    public static final boolean EMERGENCY_ASSISTANCE_ENABLED = true;

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the new call state.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     *
     * @see #EXTRA_STATE_IDLE
     * @see #EXTRA_STATE_RINGING
     * @see #EXTRA_STATE_OFFHOOK
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
     *
     * @deprecated If the app is running in the background, it won't be able to receive this
     * broadcast. Apps should use ConnectivityManager {@link #registerNetworkCallback(
     * android.net.NetworkRequest, ConnectivityManager.NetworkCallback)} to listen for network
     * changes.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
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

    /**
     * Broadcast intent action for letting the default dialer to know to show voicemail
     * notification.
     *
     * <p>
     * The {@link #EXTRA_PHONE_ACCOUNT_HANDLE} extra indicates which {@link PhoneAccountHandle} the
     * voicemail is received on.
     * The {@link #EXTRA_NOTIFICATION_COUNT} extra indicates the total numbers of unheard
     * voicemails.
     * The {@link #EXTRA_VOICEMAIL_NUMBER} extra indicates the voicemail number if available.
     * The {@link #EXTRA_CALL_VOICEMAIL_INTENT} extra is a {@link android.app.PendingIntent} that
     * will call the voicemail number when sent. This extra will be empty if the voicemail number
     * is not set, and {@link #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT} will be set instead.
     * The {@link #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT} extra is a
     * {@link android.app.PendingIntent} that will launch the voicemail settings. This extra is only
     * available when the voicemail number is not set.
     * The {@link #EXTRA_IS_REFRESH} extra indicates whether the notification is a refresh or a new
     * notification.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @see #EXTRA_NOTIFICATION_COUNT
     * @see #EXTRA_VOICEMAIL_NUMBER
     * @see #EXTRA_CALL_VOICEMAIL_INTENT
     * @see #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT
     * @see #EXTRA_IS_REFRESH
     */
    public static final String ACTION_SHOW_VOICEMAIL_NOTIFICATION =
            "android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION";

    /**
     * The extra used with an {@link #ACTION_CONFIGURE_VOICEMAIL} and
     * {@link #ACTION_SHOW_VOICEMAIL_NOTIFICATION} {@code Intent} to specify the
     * {@link PhoneAccountHandle} the configuration or notification is for.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.telephony.extra.PHONE_ACCOUNT_HANDLE";

    /**
     * The number of voice messages associated with the notification.
     */
    public static final String EXTRA_NOTIFICATION_COUNT =
            "android.telephony.extra.NOTIFICATION_COUNT";

    /**
     * The voicemail number.
     */
    public static final String EXTRA_VOICEMAIL_NUMBER =
            "android.telephony.extra.VOICEMAIL_NUMBER";

    /**
     * The intent to call voicemail.
     */
    public static final String EXTRA_CALL_VOICEMAIL_INTENT =
            "android.telephony.extra.CALL_VOICEMAIL_INTENT";

    /**
     * The intent to launch voicemail settings.
     */
    public static final String EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT =
            "android.telephony.extra.LAUNCH_VOICEMAIL_SETTINGS_INTENT";

    /**
     * Boolean value representing whether the {@link
     * TelephonyManager#ACTION_SHOW_VOICEMAIL_NOTIFICATION} is new or a refresh of an existing
     * notification. Notification refresh happens after reboot or connectivity changes. The user has
     * already been notified for the voicemail so it should not alert the user, and should not be
     * shown again if the user has dismissed it.
     */
    public static final String EXTRA_IS_REFRESH = "android.telephony.extra.IS_REFRESH";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call has be
     * successfully handed over from WIFI to LTE.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE =
            "android.telephony.event.EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call has be
     * successfully handed over from LTE to WIFI.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI =
            "android.telephony.event.EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call failed to be
     * handed over from LTE to WIFI.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_TO_WIFI_FAILED =
            "android.telephony.event.EVENT_HANDOVER_TO_WIFI_FAILED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a video call was downgraded to
     * audio because the data limit was reached.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_DOWNGRADE_DATA_LIMIT_REACHED =
            "android.telephony.event.EVENT_DOWNGRADE_DATA_LIMIT_REACHED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a video call was downgraded to
     * audio because the data was disabled.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_DOWNGRADE_DATA_DISABLED =
            "android.telephony.event.EVENT_DOWNGRADE_DATA_DISABLED";

    /**
     * {@link android.telecom.Connection} event used to indicate that the InCall UI should notify
     * the user when an international call is placed while on WFC only.
     * <p>
     * Used when the carrier config value
     * {@link CarrierConfigManager#KEY_NOTIFY_INTERNATIONAL_CALL_ON_WFC_BOOL} is true, the device
     * is on WFC (VoLTE not available) and an international number is dialed.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_NOTIFY_INTERNATIONAL_CALL_ON_WFC =
            "android.telephony.event.EVENT_NOTIFY_INTERNATIONAL_CALL_ON_WFC";

    /**
     * {@link android.telecom.Connection} event used to indicate that an outgoing call has been
     * forwarded to another number.
     * <p>
     * Sent in response to an IMS supplementary service notification indicating the call has been
     * forwarded.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_CALL_FORWARDED =
            "android.telephony.event.EVENT_CALL_FORWARDED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a supplementary service
     * notification has been received.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to include the following extras:
     * <ul>
     *     <li>{@link #EXTRA_NOTIFICATION_TYPE} - the notification type.</li>
     *     <li>{@link #EXTRA_NOTIFICATION_CODE} - the notification code.</li>
     *     <li>{@link #EXTRA_NOTIFICATION_MESSAGE} - human-readable message associated with the
     *     supplementary service notification.</li>
     * </ul>
     * @hide
     */
    public static final String EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION =
            "android.telephony.event.EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION";

    /**
     * Integer extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} which indicates
     * the type of supplementary service notification which occurred.
     * Will be either
     * {@link com.android.internal.telephony.gsm.SuppServiceNotification#NOTIFICATION_TYPE_CODE_1}
     * or
     * {@link com.android.internal.telephony.gsm.SuppServiceNotification#NOTIFICATION_TYPE_CODE_2}
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_TYPE =
            "android.telephony.extra.NOTIFICATION_TYPE";

    /**
     * Integer extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} which indicates
     * the supplementary service notification which occurred.
     * <p>
     * Depending on the {@link #EXTRA_NOTIFICATION_TYPE}, the code will be one of the {@code CODE_*}
     * codes defined in {@link com.android.internal.telephony.gsm.SuppServiceNotification}.
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_CODE =
            "android.telephony.extra.NOTIFICATION_CODE";

    /**
     * {@link CharSequence} extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION}
     * which contains a human-readable message which can be displayed to the user for the
     * supplementary service notification.
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_MESSAGE =
            "android.telephony.extra.NOTIFICATION_MESSAGE";

    /* Visual voicemail protocols */

    /**
     * The OMTP protocol.
     */
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";

    /**
     * A flavor of OMTP protocol with a different mobile originated (MO) format
     */
    public static final String VVM_TYPE_CVVM = "vvm_type_cvvm";

    /**
     * Key in bundle returned by {@link #getVisualVoicemailPackageName()}, indicating whether visual
     * voicemail was enabled or disabled by the user. If the user never explicitly changed this
     * setting, this key will not exist.
     *
     * @see #getVisualVoicemailSettings()
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL =
            "android.telephony.extra.VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL";

    /**
     * Key in bundle returned by {@link #getVisualVoicemailPackageName()}, indicating the voicemail
     * access PIN scrambled during the auto provisioning process. The user is expected to reset
     * their PIN if this value is not {@code null}.
     *
     * @see #getVisualVoicemailSettings()
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VOICEMAIL_SCRAMBLED_PIN_STRING =
            "android.telephony.extra.VOICEMAIL_SCRAMBLED_PIN_STRING";

    /**
     * @hide
     */
    public static final String USSD_RESPONSE = "USSD_RESPONSE";

    /**
     * USSD return code success.
     * @hide
     */
    public static final int USSD_RETURN_SUCCESS = 100;

    /**
     * Failed code returned when the mobile network has failed to complete a USSD request.
     * <p>
     * Returned via {@link TelephonyManager.UssdResponseCallback#onReceiveUssdResponseFailed(
     * TelephonyManager, String, int)}.
     */
    public static final int USSD_RETURN_FAILURE = -1;

    /**
     * Failure code returned when a USSD request has failed to execute because the Telephony
     * service is unavailable.
     * <p>
     * Returned via {@link TelephonyManager.UssdResponseCallback#onReceiveUssdResponseFailed(
     * TelephonyManager, String, int)}.
     */
    public static final int USSD_ERROR_SERVICE_UNAVAIL = -2;

    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which leaves the roaming
     * mode set to the radio default or to the user's preference if they've indicated one.
     */
    public static final int CDMA_ROAMING_MODE_RADIO_DEFAULT = -1;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which only permits
     * connections on home networks.
     */
    public static final int CDMA_ROAMING_MODE_HOME = 0;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which permits roaming on
     * affiliated networks.
     */
    public static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which permits roaming on
     * any network.
     */
    public static final int CDMA_ROAMING_MODE_ANY = 2;

    /**
     * An unknown carrier id. It could either be subscription unavailable or the subscription
     * carrier cannot be recognized. Unrecognized carriers here means
     * {@link #getSimOperator() MCC+MNC} cannot be identified.
     */
    public static final int UNKNOWN_CARRIER_ID = -1;

    /**
     * An unknown carrier id list version.
     * @hide
     */
    @TestApi
    public static final int UNKNOWN_CARRIER_ID_LIST_VERSION = -1;

    /**
     * Broadcast Action: The subscription carrier identity has changed.
     * This intent could be sent on the following events:
     * <ul>
     *   <li>Subscription absent. Carrier identity could change from a valid id to
     *   {@link TelephonyManager#UNKNOWN_CARRIER_ID}.</li>
     *   <li>Subscription loaded. Carrier identity could change from
     *   {@link TelephonyManager#UNKNOWN_CARRIER_ID} to a valid id.</li>
     *   <li>The subscription carrier is recognized after a remote update.</li>
     * </ul>
     * The intent will have the following extra values:
     * <ul>
     *   <li>{@link #EXTRA_CARRIER_ID} The up-to-date carrier id of the current subscription id.
     *   </li>
     *   <li>{@link #EXTRA_CARRIER_NAME} The up-to-date carrier name of the current subscription.
     *   </li>
     *   <li>{@link #EXTRA_SUBSCRIPTION_ID} The subscription id associated with the changed carrier
     *   identity.
     *   </li>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED =
            "android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} which indicates
     * the updated carrier id {@link TelephonyManager#getSimCarrierId()} of
     * the current subscription.
     * <p>Will be {@link TelephonyManager#UNKNOWN_CARRIER_ID} if the subscription is unavailable or
     * the carrier cannot be identified.
     */
    public static final String EXTRA_CARRIER_ID = "android.telephony.extra.CARRIER_ID";

    /**
     * An string extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} which
     * indicates the updated carrier name of the current subscription.
     * {@see TelephonyManager#getSimCarrierIdName()}
     * <p>Carrier name is a user-facing name of the carrier id {@link #EXTRA_CARRIER_ID},
     * usually the brand name of the subsidiary (e.g. T-Mobile).
     */
    public static final String EXTRA_CARRIER_NAME = "android.telephony.extra.CARRIER_NAME";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} to indicate the
     * subscription which has changed.
     */
    public static final String EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_ID";

    /**
     * Broadcast intent action indicating that when data stall recovery is attempted by Telephony,
     * intended for report every data stall recovery step attempted.
     *
     * <p>
     * The {@link #EXTRA_RECOVERY_ACTION} extra indicates the action associated with the data
     * stall recovery.
     * The phone id where the data stall recovery is attempted.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @see #EXTRA_RECOVERY_ACTION
     *
     * @hide
     */
    // TODO(b/78370030) : Restrict this to system applications only
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final String ACTION_DATA_STALL_DETECTED =
            "android.intent.action.DATA_STALL_DETECTED";

    /**
     * An int extra used with {@link #ACTION_DATA_STALL_DETECTED} to indicate the
     * action associated with the data stall recovery.
     *
     * @see #ACTION_DATA_STALL_DETECTED
     *
     * @hide
     */
    public static final String EXTRA_RECOVERY_ACTION = "recoveryAction";

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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceSoftwareVersion() {
        return getDeviceSoftwareVersion(getSlotIndex());
    }

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * @param slotIndex of which deviceID is returned
     */
    /** {@hide} */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceSoftwareVersion(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getDeviceSoftwareVersionForSlot(slotIndex, getOpPackageName());
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @deprecated Use (@link getImei} which returns IMEI for GSM or (@link getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceId() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getDeviceId(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the unique device ID of a subscription, for example, the IMEI for
     * GSM and the MEID for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which deviceID is returned
     *
     * @deprecated Use (@link getImei} which returns IMEI for GSM or (@link getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceId(int slotIndex) {
        // FIXME this assumes phoneId == slotIndex
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getDeviceIdForPhone(slotIndex, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the IMEI (International Mobile Equipment Identity). Return null if IMEI is not
     * available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getImei() {
        return getImei(getSlotIndex());
    }

    /**
     * Returns the IMEI (International Mobile Equipment Identity). Return null if IMEI is not
     * available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which IMEI is returned
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getImei(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getImeiForSlot(slotIndex, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the MEID (Mobile Equipment Identifier). Return null if MEID is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMeid() {
        return getMeid(getSlotIndex());
    }

    /**
     * Returns the MEID (Mobile Equipment Identifier). Return null if MEID is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which MEID is returned
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMeid(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getMeidForSlot(slotIndex, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the Network Access Identifier (NAI). Return null if NAI is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getNai() {
        return getNaiBySubscriberId(getSubId());
    }

    /**
     * Returns the NAI. Return null if NAI is not available.
     *
     *  @param slotIndex of which Nai is returned
     */
    /** {@hide}*/
    public String getNai(int slotIndex) {
        int[] subId = SubscriptionManager.getSubId(slotIndex);
        if (subId == null) {
            return null;
        }
        return getNaiBySubscriberId(subId[0]);
    }

    private String getNaiBySubscriberId(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            String nai = info.getNaiForSubscriber(subId, mContext.getOpPackageName());
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Rlog.v(TAG, "Nai = " + nai);
            }
            return nai;
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
     * @return Current location of the device or null if not available.
     *
     * @deprecated use {@link #getAllCellInfo} instead, which returns a superset of this API.
     */
    @Deprecated
    @RequiresPermission(anyOf = {
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION
    })
    public CellLocation getCellLocation() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                Rlog.d(TAG, "getCellLocation returning null because telephony is null");
                return null;
            }
            Bundle bundle = telephony.getCellLocation(mContext.getOpPackageName());
            if (bundle.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because bundle is empty");
                return null;
            }
            CellLocation cl = CellLocation.newFromBundle(bundle);
            if (cl.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because CellLocation is empty");
                return null;
            }
            return cl;
        } catch (RemoteException ex) {
            Rlog.d(TAG, "getCellLocation returning null due to RemoteException " + ex);
            return null;
        } catch (NullPointerException ex) {
            Rlog.d(TAG, "getCellLocation returning null due to NullPointerException " + ex);
            return null;
        }
    }

    /**
     * Enables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
    public void enableLocationUpdates() {
        enableLocationUpdates(getSubId());
    }

    /**
     * Enables location update notifications for a subscription.
     * {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * @param subId for which the location updates are enabled
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
    public void enableLocationUpdates(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.enableLocationUpdatesForSubscriber(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
    public void disableLocationUpdates() {
        disableLocationUpdates(getSubId());
    }

    /** @hide */
    public void disableLocationUpdates(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.disableLocationUpdatesForSubscriber(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the neighboring cell information of the device.
     *
     * @return List of NeighboringCellInfo or null if info unavailable.
     *
     * @deprecated Use {@link #getAllCellInfo} which returns a superset of the information
     *             from NeighboringCellInfo.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    public List<NeighboringCellInfo> getNeighboringCellInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getNeighboringCellInfo(mContext.getOpPackageName());
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
    @SystemApi
    public int getCurrentPhoneType() {
        return getCurrentPhoneType(getSubId());
    }

    /**
     * Returns a constant indicating the device phone type for a subscription.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     *
     * @param subId for which phone type is returned
     * @hide
     */
    @SystemApi
    public int getCurrentPhoneType(int subId) {
        int phoneId;
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // if we don't have any sims, we don't have subscriptions, but we
            // still may want to know what type of phone we've got.
            phoneId = 0;
        } else {
            phoneId = SubscriptionManager.getPhoneId(subId);
        }

        return getCurrentPhoneTypeForSlot(phoneId);
    }

    /**
     * See getCurrentPhoneType.
     *
     * @hide
     */
    public int getCurrentPhoneTypeForSlot(int slotIndex) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActivePhoneTypeForSlot(slotIndex);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return getPhoneTypeFromProperty(slotIndex);
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(slotIndex);
        } catch (NullPointerException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(slotIndex);
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
        return getPhoneTypeFromProperty(getPhoneId());
    }

    /** {@hide} */
    private int getPhoneTypeFromProperty(int phoneId) {
        String type = getTelephonyProperty(phoneId,
                TelephonyProperties.CURRENT_ACTIVE_PHONE, null);
        if (type == null || type.isEmpty()) {
            return getPhoneTypeFromNetworkType(phoneId);
        }
        return Integer.parseInt(type);
    }

    private int getPhoneTypeFromNetworkType() {
        return getPhoneTypeFromNetworkType(getPhoneId());
    }

    /** {@hide} */
    private int getPhoneTypeFromNetworkType(int phoneId) {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        String mode = getTelephonyProperty(phoneId, "ro.telephony.default_network", null);
        if (mode != null && !mode.isEmpty()) {
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
        case RILConstants.NETWORK_MODE_TDSCDMA_ONLY:
        case RILConstants.NETWORK_MODE_TDSCDMA_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA:
        case RILConstants.NETWORK_MODE_TDSCDMA_GSM:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
        case RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
            return PhoneConstants.PHONE_TYPE_GSM;

        // Use CDMA Phone for the global mode including CDMA
        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
        case RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
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
        return getNetworkOperatorName(getSubId());
    }

    /**
     * Returns the alphabetic name of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     * @param subId
     * @hide
     */
    public String getNetworkOperatorName(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ALPHA, "");
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperator() {
        return getNetworkOperatorForPhone(getPhoneId());
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
     * @hide
     */
    public String getNetworkOperator(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getNetworkOperatorForPhone(phoneId);
     }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param phoneId
     * @hide
     **/
    public String getNetworkOperatorForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
     }


    /**
     * Returns the network specifier of the subscription ID pinned to the TelephonyManager. The
     * network specifier is used by {@link
     * android.net.NetworkRequest.Builder#setNetworkSpecifier(String)} to create a {@link
     * android.net.NetworkRequest} that connects through the subscription.
     *
     * @see android.net.NetworkRequest.Builder#setNetworkSpecifier(String)
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     */
    public String getNetworkSpecifier() {
        return String.valueOf(getSubId());
    }

    /**
     * Returns the carrier config of the subscription ID pinned to the TelephonyManager. If an
     * invalid subscription ID is pinned to the TelephonyManager, the returned config will contain
     * default values.
     *
     * <p>This method may take several seconds to complete, so it should only be called from a
     * worker thread.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @see CarrierConfigManager#getConfigForSubId(int)
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @WorkerThread
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public PersistableBundle getCarrierConfig() {
        CarrierConfigManager carrierConfigManager = mContext
                .getSystemService(CarrierConfigManager.class);
        return carrierConfigManager.getConfigForSubId(getSubId());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network, for GSM purposes.
     * <p>
     * Availability: Only when user registered to a network.
     */
    public boolean isNetworkRoaming() {
        return isNetworkRoaming(getSubId());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId
     * @hide
     */
    public boolean isNetworkRoaming(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return Boolean.parseBoolean(getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_OPERATOR_ISROAMING, null));
    }

    /**
     * Returns the ISO country code equivalent of the MCC (Mobile Country Code) of the current
     * registered operator, or nearby cell information if not registered.
     * .
     * <p>
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     */
    public String getNetworkCountryIso() {
        return getNetworkCountryIsoForPhone(getPhoneId());
    }

    /**
     * Returns the ISO country code equivalent of the MCC (Mobile Country Code) of the current
     * registered operator, or nearby cell information if not registered.
     * <p>
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     *
     * @param subId for which Network CountryIso is returned
     * @hide
     */
    public String getNetworkCountryIso(int subId) {
        return getNetworkCountryIsoForPhone(getPhoneId(subId));
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code) of a subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param phoneId for which Network CountryIso is returned
     */
    /** {@hide} */
    public String getNetworkCountryIsoForPhone(int phoneId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) return "";
            return telephony.getNetworkCountryIsoForPhone(phoneId);
        } catch (RemoteException ex) {
            return "";
        }
    }

    /*
     * When adding a network type to the list below, make sure to add the correct icon to
     * MobileSignalController.mapIconSets().
     * Do not add negative types.
     */
    /** Network type is unknown */
    public static final int NETWORK_TYPE_UNKNOWN = TelephonyProtoEnums.NETWORK_TYPE_UNKNOWN; // = 0.
    /** Current network is GPRS */
    public static final int NETWORK_TYPE_GPRS = TelephonyProtoEnums.NETWORK_TYPE_GPRS; // = 1.
    /** Current network is EDGE */
    public static final int NETWORK_TYPE_EDGE = TelephonyProtoEnums.NETWORK_TYPE_EDGE; // = 2.
    /** Current network is UMTS */
    public static final int NETWORK_TYPE_UMTS = TelephonyProtoEnums.NETWORK_TYPE_UMTS; // = 3.
    /** Current network is CDMA: Either IS95A or IS95B*/
    public static final int NETWORK_TYPE_CDMA = TelephonyProtoEnums.NETWORK_TYPE_CDMA; // = 4.
    /** Current network is EVDO revision 0*/
    public static final int NETWORK_TYPE_EVDO_0 = TelephonyProtoEnums.NETWORK_TYPE_EVDO_0; // = 5.
    /** Current network is EVDO revision A*/
    public static final int NETWORK_TYPE_EVDO_A = TelephonyProtoEnums.NETWORK_TYPE_EVDO_A; // = 6.
    /** Current network is 1xRTT*/
    public static final int NETWORK_TYPE_1xRTT = TelephonyProtoEnums.NETWORK_TYPE_1XRTT; // = 7.
    /** Current network is HSDPA */
    public static final int NETWORK_TYPE_HSDPA = TelephonyProtoEnums.NETWORK_TYPE_HSDPA; // = 8.
    /** Current network is HSUPA */
    public static final int NETWORK_TYPE_HSUPA = TelephonyProtoEnums.NETWORK_TYPE_HSUPA; // = 9.
    /** Current network is HSPA */
    public static final int NETWORK_TYPE_HSPA = TelephonyProtoEnums.NETWORK_TYPE_HSPA; // = 10.
    /** Current network is iDen */
    public static final int NETWORK_TYPE_IDEN = TelephonyProtoEnums.NETWORK_TYPE_IDEN; // = 11.
    /** Current network is EVDO revision B*/
    public static final int NETWORK_TYPE_EVDO_B = TelephonyProtoEnums.NETWORK_TYPE_EVDO_B; // = 12.
    /** Current network is LTE */
    public static final int NETWORK_TYPE_LTE = TelephonyProtoEnums.NETWORK_TYPE_LTE; // = 13.
    /** Current network is eHRPD */
    public static final int NETWORK_TYPE_EHRPD = TelephonyProtoEnums.NETWORK_TYPE_EHRPD; // = 14.
    /** Current network is HSPA+ */
    public static final int NETWORK_TYPE_HSPAP = TelephonyProtoEnums.NETWORK_TYPE_HSPAP; // = 15.
    /** Current network is GSM */
    public static final int NETWORK_TYPE_GSM = TelephonyProtoEnums.NETWORK_TYPE_GSM; // = 16.
    /** Current network is TD_SCDMA */
    public static final int NETWORK_TYPE_TD_SCDMA =
            TelephonyProtoEnums.NETWORK_TYPE_TD_SCDMA; // = 17.
    /** Current network is IWLAN */
    public static final int NETWORK_TYPE_IWLAN = TelephonyProtoEnums.NETWORK_TYPE_IWLAN; // = 18.
    /** Current network is LTE_CA {@hide} */
    public static final int NETWORK_TYPE_LTE_CA = TelephonyProtoEnums.NETWORK_TYPE_LTE_CA; // = 19.

    /** Max network type number. Update as new types are added. Don't add negative types. {@hide} */
    public static final int MAX_NETWORK_TYPE = NETWORK_TYPE_LTE_CA;
    /**
     * @return the NETWORK_TYPE_xxxx for current data connection.
     */
    public int getNetworkType() {
       try {
           ITelephony telephony = getITelephony();
           if (telephony != null) {
               return telephony.getNetworkType();
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
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
   public int getNetworkType(int subId) {
       try {
           ITelephony telephony = getITelephony();
           if (telephony != null) {
               return telephony.getNetworkTypeForSubscriber(subId, getOpPackageName());
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
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
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
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getDataNetworkType() {
        return getDataNetworkType(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission for a subscription
     * @return the network type
     *
     * @param subId for which network type is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getDataNetworkType(int subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getDataNetworkTypeForSubscriber(subId, getOpPackageName());
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getVoiceNetworkType() {
        return getVoiceNetworkType(getSubId());
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice for a subId
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getVoiceNetworkType(int subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getVoiceNetworkTypeForSubscriber(subId, getOpPackageName());
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
     * Network Class Definitions.
     * Do not change this order, it is used for sorting during emergency calling in
     * {@link TelephonyConnectionService#getFirstPhoneForEmergencyCall()}. Any newer technologies
     * should be added after the current definitions.
     */
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
            case NETWORK_TYPE_TD_SCDMA:
                return NETWORK_CLASS_3_G;
            case NETWORK_TYPE_LTE:
            case NETWORK_TYPE_IWLAN:
            case NETWORK_TYPE_LTE_CA:
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
            case NETWORK_TYPE_TD_SCDMA:
                return "TD_SCDMA";
            case NETWORK_TYPE_IWLAN:
                return "IWLAN";
            case NETWORK_TYPE_LTE_CA:
                return "LTE_CA";
            default:
                return "UNKNOWN";
        }
    }

    //
    //
    // SIM Card
    //
    //

    /**
     * SIM card state: Unknown. Signifies that the SIM is in transition
     * between states. For example, when the user inputs the SIM pin
     * under PIN_REQUIRED state, a query for sim status returns
     * this state before turning to SIM_STATE_READY.
     *
     * These are the ordinal value of IccCardConstants.State.
     */
    public static final int SIM_STATE_UNKNOWN = 0;
    /** SIM card state: no SIM card is available in the device */
    public static final int SIM_STATE_ABSENT = 1;
    /** SIM card state: Locked: requires the user's SIM PIN to unlock */
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    /** SIM card state: Locked: requires the user's SIM PUK to unlock */
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    /** SIM card state: Locked: requires a network PIN to unlock */
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    /** SIM card state: Ready */
    public static final int SIM_STATE_READY = 5;
    /** SIM card state: SIM Card is NOT READY */
    public static final int SIM_STATE_NOT_READY = 6;
    /** SIM card state: SIM Card Error, permanently disabled */
    public static final int SIM_STATE_PERM_DISABLED = 7;
    /** SIM card state: SIM Card Error, present but faulty */
    public static final int SIM_STATE_CARD_IO_ERROR = 8;
    /** SIM card state: SIM Card restricted, present but not usable due to
     * carrier restrictions.
     */
    public static final int SIM_STATE_CARD_RESTRICTED = 9;
    /**
     * SIM card state: Loaded: SIM card applications have been loaded
     * @hide
     */
    @SystemApi
    public static final int SIM_STATE_LOADED = 10;
    /**
     * SIM card state: SIM Card is present
     * @hide
     */
    @SystemApi
    public static final int SIM_STATE_PRESENT = 11;

    /**
     * Extra included in {@link #ACTION_SIM_CARD_STATE_CHANGED} and
     * {@link #ACTION_SIM_APPLICATION_STATE_CHANGED} to indicate the card/application state.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SIM_STATE = "android.telephony.extra.SIM_STATE";

    /**
     * Broadcast Action: The sim card state has changed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>{@link #EXTRA_SIM_STATE}</dt>
     *   <dd>The sim card state. One of:
     *     <dl>
     *       <dt>{@link #SIM_STATE_ABSENT}</dt>
     *       <dd>SIM card not found</dd>
     *       <dt>{@link #SIM_STATE_CARD_IO_ERROR}</dt>
     *       <dd>SIM card IO error</dd>
     *       <dt>{@link #SIM_STATE_CARD_RESTRICTED}</dt>
     *       <dd>SIM card is restricted</dd>
     *       <dt>{@link #SIM_STATE_PRESENT}</dt>
     *       <dd>SIM card is present</dd>
     *     </dl>
     *   </dd>
     * </dl>
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The current state can also be queried using {@link #getSimCardState()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_CARD_STATE_CHANGED =
            "android.telephony.action.SIM_CARD_STATE_CHANGED";

    /**
     * Broadcast Action: The sim application state has changed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>{@link #EXTRA_SIM_STATE}</dt>
     *   <dd>The sim application state. One of:
     *     <dl>
     *       <dt>{@link #SIM_STATE_NOT_READY}</dt>
     *       <dd>SIM card applications not ready</dd>
     *       <dt>{@link #SIM_STATE_PIN_REQUIRED}</dt>
     *       <dd>SIM card PIN locked</dd>
     *       <dt>{@link #SIM_STATE_PUK_REQUIRED}</dt>
     *       <dd>SIM card PUK locked</dd>
     *       <dt>{@link #SIM_STATE_NETWORK_LOCKED}</dt>
     *       <dd>SIM card network locked</dd>
     *       <dt>{@link #SIM_STATE_PERM_DISABLED}</dt>
     *       <dd>SIM card permanently disabled due to PUK failures</dd>
     *       <dt>{@link #SIM_STATE_LOADED}</dt>
     *       <dd>SIM card data loaded</dd>
     *     </dl>
     *   </dd>
     * </dl>
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The current state can also be queried using
     * {@link #getSimApplicationState()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_APPLICATION_STATE_CHANGED =
            "android.telephony.action.SIM_APPLICATION_STATE_CHANGED";

    /**
     * Broadcast Action: Status of the SIM slots on the device has changed.
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The status can be queried using
     * {@link #getUiccSlotsInfo()}
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_SLOT_STATUS_CHANGED =
            "android.telephony.action.SIM_SLOT_STATUS_CHANGED";

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return hasIccCard(getSlotIndex());
    }

    /**
     * @return true if a ICC card is present for a subscription
     *
     * @param slotIndex for which icc card presence is checked
     */
    /** {@hide} */
    // FIXME Input argument slotIndex should be of type int
    public boolean hasIccCard(int slotIndex) {

        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return false;
            return telephony.hasIccCardUsingSlotIndex(slotIndex);
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return false;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return false;
        }
    }

    /**
     * Returns a constant indicating the state of the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_CARD_IO_ERROR
     * @see #SIM_STATE_CARD_RESTRICTED
     */
    public int getSimState() {
        int simState = getSimStateIncludingLoaded();
        if (simState == SIM_STATE_LOADED) {
            simState = SIM_STATE_READY;
        }
        return simState;
    }

    private int getSimStateIncludingLoaded() {
        int slotIndex = getSlotIndex();
        // slotIndex may be invalid due to sim being absent. In that case query all slots to get
        // sim state
        if (slotIndex < 0) {
            // query for all slots and return absent if all sim states are absent, otherwise
            // return unknown
            for (int i = 0; i < getPhoneCount(); i++) {
                int simState = getSimState(i);
                if (simState != SIM_STATE_ABSENT) {
                    Rlog.d(TAG, "getSimState: default sim:" + slotIndex + ", sim state for " +
                            "slotIndex=" + i + " is " + simState + ", return state as unknown");
                    return SIM_STATE_UNKNOWN;
                }
            }
            Rlog.d(TAG, "getSimState: default sim:" + slotIndex + ", all SIMs absent, return " +
                    "state as absent");
            return SIM_STATE_ABSENT;
        }
        return SubscriptionManager.getSimStateForSlotIndex(slotIndex);
    }

    /**
     * Returns a constant indicating the state of the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_CARD_IO_ERROR
     * @see #SIM_STATE_CARD_RESTRICTED
     * @see #SIM_STATE_PRESENT
     *
     * @hide
     */
    @SystemApi
    public int getSimCardState() {
        int simCardState = getSimState();
        switch (simCardState) {
            case SIM_STATE_UNKNOWN:
            case SIM_STATE_ABSENT:
            case SIM_STATE_CARD_IO_ERROR:
            case SIM_STATE_CARD_RESTRICTED:
                return simCardState;
            default:
                return SIM_STATE_PRESENT;
        }
    }

    /**
     * Returns a constant indicating the state of the card applications on the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_LOADED
     *
     * @hide
     */
    @SystemApi
    public int getSimApplicationState() {
        int simApplicationState = getSimStateIncludingLoaded();
        switch (simApplicationState) {
            case SIM_STATE_UNKNOWN:
            case SIM_STATE_ABSENT:
            case SIM_STATE_CARD_IO_ERROR:
            case SIM_STATE_CARD_RESTRICTED:
                return SIM_STATE_UNKNOWN;
            case SIM_STATE_READY:
                // Ready is not a valid state anymore. The state that is broadcast goes from
                // NOT_READY to either LOCKED or LOADED.
                return SIM_STATE_NOT_READY;
            default:
                return simApplicationState;
        }
    }

    /**
     * Returns a constant indicating the state of the device SIM card in a slot.
     *
     * @param slotIndex
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_CARD_IO_ERROR
     * @see #SIM_STATE_CARD_RESTRICTED
     */
    public int getSimState(int slotIndex) {
        int simState = SubscriptionManager.getSimStateForSlotIndex(slotIndex);
        if (simState == SIM_STATE_LOADED) {
            simState = SIM_STATE_READY;
        }
        return simState;
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
        return getSimOperatorNumeric();
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperator is returned
     * @hide
     */
    public String getSimOperator(int subId) {
        return getSimOperatorNumeric(subId);
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     * @hide
     */
    public String getSimOperatorNumeric() {
        int subId = mSubId;
        if (!SubscriptionManager.isUsableSubIdValue(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                    subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
                    if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                        subId = SubscriptionManager.getDefaultSubscriptionId();
                    }
                }
            }
        }
        return getSimOperatorNumeric(subId);
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
     * @hide
     */
    public String getSimOperatorNumeric(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimOperatorNumericForPhone(phoneId);
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM for a particular subscription. 5 or 6 decimal digits.
     * <p>
     *
     * @param phoneId for which SimOperator is returned
     * @hide
     */
    public String getSimOperatorNumericForPhone(int phoneId) {
        return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperatorName() {
        return getSimOperatorNameForPhone(getPhoneId());
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperatorName is returned
     * @hide
     */
    public String getSimOperatorName(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimOperatorNameForPhone(phoneId);
    }

    /**
     * Returns the Service Provider Name (SPN).
     *
     * @hide
     */
    public String getSimOperatorNameForPhone(int phoneId) {
         return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "");
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     */
    public String getSimCountryIso() {
        return getSimCountryIsoForPhone(getPhoneId());
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @param subId for which SimCountryIso is returned
     * @hide
     */
    public String getSimCountryIso(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimCountryIsoForPhone(phoneId);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @hide
     */
    public String getSimCountryIsoForPhone(int phoneId) {
        return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSimSerialNumber() {
         return getSimSerialNumber(getSubId());
    }

    /**
     * Returns the serial number for the given subscription, if applicable. Return null if it is
     * unavailable.
     * <p>
     * @param subId for which Sim Serial number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSimSerialNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIccSerialNumberForSubscriber(subId, mContext.getOpPackageName());
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getLteOnCdmaMode() {
        return getLteOnCdmaMode(getSubId());
    }

    /**
     * Return if the current radio is LTE on CDMA for Subscription. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param subId for which radio is LTE on CDMA is returned
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getLteOnCdmaMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
            return telephony.getLteOnCdmaModeForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        }
    }

    /**
     * Gets all the UICC slots.
     *
     * @return UiccSlotInfo array.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public UiccSlotInfo[] getUiccSlotsInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return null;
            }
            return telephony.getUiccSlotsInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Map logicalSlot to physicalSlot, and activate the physicalSlot if it is inactive. For
     * example, passing the physicalSlots array [1, 0] means mapping the first item 1, which is
     * physical slot index 1, to the logical slot 0; and mapping the second item 0, which is
     * physical slot index 0, to the logical slot 1. The index of the array means the index of the
     * logical slots.
     *
     * @param physicalSlots The content of the array represents the physical slot index. The array
     *        size should be same as {@link #getUiccSlotsInfo()}.
     * @return boolean Return true if the switch succeeds, false if the switch fails.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean switchSlots(int[] physicalSlots) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.switchSlots(physicalSlots);
        } catch (RemoteException e) {
            return false;
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
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSubscriberId() {
        return getSubscriberId(getSubId());
    }

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone
     * for a subscription.
     * Return null if it is unavailable.
     *
     * @param subId whose subscriber id is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSubscriberId(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getSubscriberIdForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * This includes the public key and the key identifier. For multi-sim devices, if no subId
     * has been specified, we will return the value for the dafault data sim.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param keyType whether the key is being used for wlan or epdg. Valid key types are
     *        {@link TelephonyManager#KEY_TYPE_EPDG} or
     *        {@link TelephonyManager#KEY_TYPE_WLAN}.
     * @return ImsiEncryptionInfo Carrier specific information that will be used to encrypt the
     *         IMSI and IMPI. This includes the public key and the key identifier. This information
     *         will be stored in the device keystore. The system will return a null when no key was
     *         found, and the carrier does not require a key. The system will throw
     *         IllegalArgumentException when an invalid key is sent or when key is required but
     *         not found.
     * @hide
     */
    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int keyType) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) {
                Rlog.e(TAG,"IMSI error: Subscriber Info is null");
                return null;
            }
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            if (keyType != KEY_TYPE_EPDG && keyType != KEY_TYPE_WLAN) {
                throw new IllegalArgumentException("IMSI error: Invalid key type");
            }
            ImsiEncryptionInfo imsiEncryptionInfo = info.getCarrierInfoForImsiEncryption(
                    subId, keyType, mContext.getOpPackageName());
            if (imsiEncryptionInfo == null && isImsiEncryptionRequired(subId, keyType)) {
                Rlog.e(TAG, "IMSI error: key is required but not found");
                throw new IllegalArgumentException("IMSI error: key is required but not found");
            }
            return imsiEncryptionInfo;
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption RemoteException" + ex);
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption NullPointerException" + ex);
        }
        return null;
    }

    /**
     * Resets the Carrier Keys in the database. This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * @hide
     */
    public void resetCarrierKeysForImsiEncryption() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) {
                Rlog.e(TAG, "IMSI error: Subscriber Info is null");
                if (!isSystemProcess()) {
                    throw new RuntimeException("IMSI error: Subscriber Info is null");
                }
                return;
            }
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            info.resetCarrierKeysForImsiEncryption(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption RemoteException" + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

   /**
     * @param keyAvailability bitmask that defines the availabilty of keys for a type.
     * @param keyType the key type which is being checked. (WLAN, EPDG)
     * @return true if the digit at position keyType is 1, else false.
     * @hide
     */
    private static boolean isKeyEnabled(int keyAvailability, int keyType) {
        int returnValue = (keyAvailability >> (keyType - 1)) & 1;
        return (returnValue == 1) ? true : false;
    }

    /**
     * If Carrier requires Imsi to be encrypted.
     * @hide
     */
    private boolean isImsiEncryptionRequired(int subId, int keyType) {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            return false;
        }
        PersistableBundle pb = configManager.getConfigForSubId(subId);
        if (pb == null) {
            return false;
        }
        int keyAvailability = pb.getInt(CarrierConfigManager.IMSI_KEY_AVAILABILITY_INT);
        return isKeyEnabled(keyAvailability, keyType);
    }

    /**
     * Sets the Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * This includes the public key and the key identifier. This information will be stored in the
     * device keystore.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * @param imsiEncryptionInfo which includes the Key Type, the Public Key
     *        (java.security.PublicKey) and the Key Identifier.and the Key Identifier.
     *        The keyIdentifier Attribute value pair that helps a server locate
     *        the private key to decrypt the permanent identity. This field is
     *        optional and if it is present then it’s always separated from encrypted
     *        permanent identity with “,”. Key identifier AVP is presented in ASCII string
     *        with “name=value” format.
     * @hide
     */
    public void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) return;
            info.setCarrierInfoForImsiEncryption(mSubId, mContext.getOpPackageName(),
                    imsiEncryptionInfo);
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return;
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setCarrierInfoForImsiEncryption RemoteException", ex);
            return;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone.
     * Return null if it is unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getGroupIdLevel1() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(getSubId(), mContext.getOpPackageName());
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
     *
     * @param subId whose subscriber id is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getGroupIdLevel1(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(subId, mContext.getOpPackageName());
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
     *
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE},
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges or default SMS app
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    public String getLine1Number() {
        return getLine1Number(getSubId());
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone for a particular subscription. Return null if it is unavailable.
     * <p>
     * The default SMS app can also use this.
     *
     * @param subId whose phone number for line 1 is returned
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    public String getLine1Number(int subId) {
        String number = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                number = telephony.getLine1NumberForDisplay(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (number != null) {
            return number;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getLine1NumberForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     */
    public boolean setLine1NumberForDisplay(String alphaTag, String number) {
        return setLine1NumberForDisplay(getSubId(), alphaTag, number);
    }

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId the subscriber that the alphatag and dialing number belongs to.
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     * @hide
     */
    public boolean setLine1NumberForDisplay(int subId, String alphaTag, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setLine1NumberForDisplayForSubscriber(subId, alphaTag, number);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number.
     * Return null if it is unavailable.
     * @hide
     * nobody seems to call this.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getLine1AlphaTag() {
        return getLine1AlphaTag(getSubId());
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number
     * for a subscription.
     * Return null if it is unavailable.
     * @param subId whose alphabetic identifier associated with line 1 is returned
     * nobody seems to call this.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getLine1AlphaTag(int subId) {
        String alphaTag = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                alphaTag = telephony.getLine1AlphaTagForDisplay(subId,
                        getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (alphaTag != null) {
            return alphaTag;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getLine1AlphaTagForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return the set of subscriber IDs that should be considered as "merged
     * together" for data usage purposes. This is commonly {@code null} to
     * indicate no merging is required. Any returned subscribers are sorted in a
     * deterministic order.
     *
     * @hide
     */
    public @Nullable String[] getMergedSubscriberIds() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getMergedSubscriberIds(getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMsisdn() {
        return getMsisdn(getSubId());
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     *
     * @param subId for which msisdn is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMsisdn(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getMsisdnForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail number. Return null if it is unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailNumber() {
        return getVoiceMailNumber(getSubId());
    }

    /**
     * Returns the voice mail number for a subscription.
     * Return null if it is unavailable.
     * @param subId whose voice mail number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getVoiceMailNumberForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CALL_PRIVILEGED)
    public String getCompleteVoiceMailNumber() {
        return getCompleteVoiceMailNumber(getSubId());
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     *
     * @param subId
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CALL_PRIVILEGED)
    public String getCompleteVoiceMailNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getCompleteVoiceMailNumberForSubscriber(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Sets the voice mail number.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     */
    public boolean setVoiceMailNumber(String alphaTag, String number) {
        return setVoiceMailNumber(getSubId(), alphaTag, number);
    }

    /**
     * Sets the voicemail number for the given subscriber.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     * @hide
     */
    public boolean setVoiceMailNumber(int subId, String alphaTag, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setVoiceMailNumber(subId, alphaTag, number);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Enables or disables the visual voicemail client for a phone account.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle the phone account to change the client state
     * @param enabled the new state of the client
     * @hide
     * @deprecated Visual voicemail no longer in telephony. {@link VisualVoicemailService} should
     * be implemented instead.
     */
    @SystemApi
    @SuppressLint("Doclava125")
    public void setVisualVoicemailEnabled(PhoneAccountHandle phoneAccountHandle, boolean enabled){
    }

    /**
     * Returns whether the visual voicemail client is enabled.
     *
     * @param phoneAccountHandle the phone account to check for.
     * @return {@code true} when the visual voicemail client is enabled for this client
     * @hide
     * @deprecated Visual voicemail no longer in telephony. {@link VisualVoicemailService} should
     * be implemented instead.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @SuppressLint("Doclava125")
    public boolean isVisualVoicemailEnabled(PhoneAccountHandle phoneAccountHandle){
        return false;
    }

    /**
     * Returns an opaque bundle of settings formerly used by the visual voicemail client for the
     * subscription ID pinned to the TelephonyManager, or {@code null} if the subscription ID is
     * invalid. This method allows the system dialer to migrate settings out of the pre-O visual
     * voicemail client in telephony.
     *
     * <p>Requires the caller to be the system dialer.
     *
     * @see #KEY_VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL
     * @see #KEY_VOICEMAIL_SCRAMBLED_PIN_STRING
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("Doclava125")
    @Nullable
    public Bundle getVisualVoicemailSettings(){
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailSettings(mContext.getOpPackageName(), mSubId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Returns the package responsible of processing visual voicemail for the subscription ID pinned
     * to the TelephonyManager. Returns {@code null} when there is no package responsible for
     * processing visual voicemail for the subscription.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     * @see VisualVoicemailService
     */
    @Nullable
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVisualVoicemailPackageName() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailPackageName(mContext.getOpPackageName(), getSubId());
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Set the visual voicemail SMS filter settings for the subscription ID pinned
     * to the TelephonyManager.
     * When the filter is enabled, {@link
     * VisualVoicemailService#onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)} will be
     * called when a SMS matching the settings is received. The caller should have
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} and implement a
     * VisualVoicemailService.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param settings The settings for the filter, or {@code null} to disable the filter.
     */
    public void setVisualVoicemailSmsFilterSettings(VisualVoicemailSmsFilterSettings settings) {
        if (settings == null) {
            disableVisualVoicemailSmsFilter(mSubId);
        } else {
            enableVisualVoicemailSmsFilter(mSubId, settings);
        }
    }

    /**
     * Send a visual voicemail SMS. The caller must be the current default dialer.
     * A {@link VisualVoicemailService} uses this method to send a command via SMS to the carrier's
     * visual voicemail server.  Some examples for carriers using the OMTP standard include
     * activating and deactivating visual voicemail, or requesting the current visual voicemail
     * provisioning status.  See the OMTP Visual Voicemail specification for more information on the
     * format of these SMS messages.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#SEND_SMS SEND_SMS}
     *
     * @param number The destination number.
     * @param port The destination port for data SMS, or 0 for text SMS.
     * @param text The message content. For data sms, it will be encoded as a UTF-8 byte stream.
     * @param sentIntent The sent intent passed to the {@link SmsManager}
     *
     * @throws SecurityException if the caller is not the current default dialer
     *
     * @see SmsManager#sendDataMessage(String, String, short, byte[], PendingIntent, PendingIntent)
     * @see SmsManager#sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     */
    public void sendVisualVoicemailSms(String number, int port, String text,
            PendingIntent sentIntent) {
        sendVisualVoicemailSmsForSubscriber(mSubId, number, port, text, sentIntent);
    }

    /**
     * Enables the visual voicemail SMS filter for a phone account. When the filter is
     * enabled, Incoming SMS messages matching the OMTP VVM SMS interface will be redirected to the
     * visual voicemail client with
     * {@link android.provider.VoicemailContract.ACTION_VOICEMAIL_SMS_RECEIVED}.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     *
     *
     * @param subId The subscription id of the phone account.
     * @param settings The settings for the filter.
     */
    /** @hide */
    public void enableVisualVoicemailSmsFilter(int subId,
            VisualVoicemailSmsFilterSettings settings) {
        if(settings == null){
            throw new IllegalArgumentException("Settings cannot be null");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.enableVisualVoicemailSmsFilter(mContext.getOpPackageName(), subId,
                        settings);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables the visual voicemail SMS filter for a phone account.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     */
    /** @hide */
    public void disableVisualVoicemailSmsFilter(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.disableVisualVoicemailSmsFilter(mContext.getOpPackageName(), subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * @returns the settings of the visual voicemail SMS filter for a phone account, or {@code null}
     * if the filter is disabled.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     */
    /** @hide */
    @Nullable
    public VisualVoicemailSmsFilterSettings getVisualVoicemailSmsFilterSettings(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailSmsFilterSettings(mContext.getOpPackageName(), subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }

        return null;
    }

    /**
     * @returns the settings of the visual voicemail SMS filter for a phone account set by the
     * current active visual voicemail client, or {@code null} if the filter is disabled.
     *
     * <p>Requires the calling app to have READ_PRIVILEGED_PHONE_STATE permission.
     */
    /** @hide */
    @Nullable
    public VisualVoicemailSmsFilterSettings getActiveVisualVoicemailSmsFilterSettings(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActiveVisualVoicemailSmsFilterSettings(subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }

        return null;
    }

    /**
     * Send a visual voicemail SMS. The IPC caller must be the current default dialer.
     *
     * @param phoneAccountHandle The account to send the SMS with.
     * @param number The destination number.
     * @param port The destination port for data SMS, or 0 for text SMS.
     * @param text The message content. For data sms, it will be encoded as a UTF-8 byte stream.
     * @param sentIntent The sent intent passed to the {@link SmsManager}
     *
     * @see SmsManager#sendDataMessage(String, String, short, byte[], PendingIntent, PendingIntent)
     * @see SmsManager#sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SEND_SMS)
    public void sendVisualVoicemailSmsForSubscriber(int subId, String number, int port,
            String text, PendingIntent sentIntent) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.sendVisualVoicemailSmsForSubscriber(
                        mContext.getOpPackageName(), subId, number, port, text, sentIntent);
            }
        } catch (RemoteException ex) {
        }
    }

    /**
     * Initial SIM activation state, unknown. Not set by any carrier apps.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_UNKNOWN = 0;

    /**
     * indicate SIM is under activation procedure now.
     * intermediate state followed by another state update with activation procedure result:
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_ACTIVATING = 1;

    /**
     * Indicate SIM has been successfully activated with full service
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_ACTIVATED = 2;

    /**
     * Indicate SIM has been deactivated by the carrier so that service is not available
     * and requires activation service to enable services.
     * Carrier apps could be signalled to set activation state to deactivated if detected
     * deactivated sim state and set it back to activated after successfully run activation service.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_DEACTIVATED = 3;

    /**
     * Restricted state indicate SIM has been activated but service are restricted.
     * note this is currently available for data activation state. For example out of byte sim.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_RESTRICTED = 4;

    /** @hide */
    @IntDef({
            SIM_ACTIVATION_STATE_UNKNOWN,
            SIM_ACTIVATION_STATE_ACTIVATING,
            SIM_ACTIVATION_STATE_ACTIVATED,
            SIM_ACTIVATION_STATE_DEACTIVATED,
            SIM_ACTIVATION_STATE_RESTRICTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SimActivationState{}

     /**
      * Sets the voice activation state
      *
      * <p>Requires Permission:
      * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
      * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
      *
      * @param activationState The voice activation state
      * @see #SIM_ACTIVATION_STATE_UNKNOWN
      * @see #SIM_ACTIVATION_STATE_ACTIVATING
      * @see #SIM_ACTIVATION_STATE_ACTIVATED
      * @see #SIM_ACTIVATION_STATE_DEACTIVATED
      * @hide
      */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoiceActivationState(@SimActivationState int activationState) {
        setVoiceActivationState(getSubId(), activationState);
    }

    /**
     * Sets the voice activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param activationState The voice activation state of the given subscriber.
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoiceActivationState(int subId, @SimActivationState int activationState) {
        try {
           ITelephony telephony = getITelephony();
           if (telephony != null)
               telephony.setVoiceActivationState(subId, activationState);
       } catch (RemoteException ex) {
       } catch (NullPointerException ex) {
       }
    }

    /**
     * Sets the data activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param activationState The data activation state
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataActivationState(@SimActivationState int activationState) {
        setDataActivationState(getSubId(), activationState);
    }

    /**
     * Sets the data activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param activationState The data activation state of the given subscriber.
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataActivationState(int subId, @SimActivationState int activationState) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setDataActivationState(subId, activationState);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the voice activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return voiceActivationState
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getVoiceActivationState() {
        return getVoiceActivationState(getSubId());
    }

    /**
     * Returns the voice activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     *
     * @return voiceActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getVoiceActivationState(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getVoiceActivationState(subId, getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return SIM_ACTIVATION_STATE_UNKNOWN;
    }

    /**
     * Returns the data activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return dataActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getDataActivationState() {
        return getDataActivationState(getSubId());
    }

    /**
     * Returns the data activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     *
     * @return dataActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getDataActivationState(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getDataActivationState(subId, getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return SIM_ACTIVATION_STATE_UNKNOWN;
    }

    /**
     * Returns the voice mail count. Return 0 if unavailable, -1 if there are unread voice messages
     * but the count is unknown.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getVoiceMessageCount() {
        return getVoiceMessageCount(getSubId());
    }

    /**
     * Returns the voice mail count for a subscription. Return 0 if unavailable.
     * @param subId whose voice message count is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getVoiceMessageCount(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return 0;
            return telephony.getVoiceMessageCountForSubscriber(subId);
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
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailAlphaTag() {
        return getVoiceMailAlphaTag(getSubId());
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number for a subscription.
     * @param subId whose alphabetic identifier associated with the
     * voice mail number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailAlphaTag(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getVoiceMailAlphaTagForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Send the special dialer code. The IPC caller must be the current default dialer or have
     * carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param inputCode The special dialer code to send
     *
     * @throws SecurityException if the caller does not have carrier privileges or is not the
     *         current default dialer
     */
    public void sendDialerSpecialCode(String inputCode) {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony == null) {
                if (!isSystemProcess()) {
                    throw new RuntimeException("Telephony service unavailable");
                }
                return;
            }
            telephony.sendDialerSpecialCode(mContext.getOpPackageName(), inputCode);
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     * @hide
     */
    public String getIsimImpi() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Impi based on subId
            return info.getIsimImpi(getSubId());
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
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Domain based on subId
            return info.getIsimDomain(getSubId());
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
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Impu based on subId
            return info.getIsimImpu(getSubId());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

   /**
    * @hide
    */
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
     * Returns one of the following constants that represents the current state of all
     * phone calls.
     *
     * {@link TelephonyManager#CALL_STATE_RINGING}
     * {@link TelephonyManager#CALL_STATE_OFFHOOK}
     * {@link TelephonyManager#CALL_STATE_IDLE}
     */
    public int getCallState() {
        try {
            ITelecomService telecom = getTelecomService();
            if (telecom != null) {
                return telecom.getCallState();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getCallState", e);
        }
        return CALL_STATE_IDLE;
    }

    /**
     * Returns a constant indicating the call state (cellular) on the device
     * for a subscription.
     *
     * @param subId whose call state is returned
     * @hide
     */
    public int getCallState(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getCallStateForSlot(phoneId);
    }

    /**
     * See getCallState.
     *
     * @hide
     */
    public int getCallStateForSlot(int slotIndex) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return CALL_STATE_IDLE;
            return telephony.getCallStateForSlot(slotIndex);
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
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return DATA_ACTIVITY_NONE;
            return telephony.getDataActivity();
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
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return DATA_DISCONNECTED;
            return telephony.getDataState();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_DISCONNECTED;
        } catch (NullPointerException ex) {
            return DATA_DISCONNECTED;
        }
    }

   /**
    * @hide
    */
    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }

    /**
    * @hide
    */
    private ITelecomService getTelecomService() {
        return ITelecomService.Stub.asInterface(ServiceManager.getService(Context.TELECOM_SERVICE));
    }

    private ITelephonyRegistry getTelephonyRegistry() {
        return ITelephonyRegistry.Stub.asInterface(ServiceManager.getService("telephony.registry"));
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
     * Note: if you call this method while in the middle of a binder transaction, you <b>must</b>
     * call {@link android.os.Binder#clearCallingIdentity()} before calling this method. A
     * {@link SecurityException} will be thrown otherwise.
     *
     * @param listener The {@link PhoneStateListener} object to register
     *                 (or unregister)
     * @param events The telephony state(s) of interest to the listener,
     *               as a bitwise-OR combination of {@link PhoneStateListener}
     *               LISTEN_ flags.
     */
    public void listen(PhoneStateListener listener, int events) {
        if (mContext == null) return;
        try {
            boolean notifyNow = (getITelephony() != null);
            // If the listener has not explicitly set the subId (for example, created with the
            // default constructor), replace the subId so it will listen to the account the
            // telephony manager is created with.
            if (listener.mSubId == null) {
                listener.mSubId = mSubId;
            }

            ITelephonyRegistry registry = getTelephonyRegistry();
            if (registry != null) {
                registry.listenForSubscriber(listener.mSubId, getOpPackageName(),
                        listener.callback, events, notifyNow);
            } else {
                Rlog.w(TAG, "telephony registry not ready.");
            }
        } catch (RemoteException ex) {
            // system process dead
        }
    }

    /**
     * Returns the CDMA ERI icon index to display
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconIndex() {
        return getCdmaEriIconIndex(getSubId());
    }

    /**
     * Returns the CDMA ERI icon index to display for a subscription
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconIndex(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconIndexForSubscriber(subId, getOpPackageName());
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconMode() {
        return getCdmaEriIconMode(getSubId());
    }

    /**
     * Returns the CDMA ERI icon mode for a subscription.
     * 0 - ON
     * 1 - FLASHING
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconModeForSubscriber(subId, getOpPackageName());
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getCdmaEriText() {
        return getCdmaEriText(getSubId());
    }

    /**
     * Returns the CDMA ERI text, of a subscription
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getCdmaEriText(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaEriTextForSubscriber(subId, getOpPackageName());
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
     * device including the primary and neighboring cells. Calling this method does
     * not trigger a call to {@link android.telephony.PhoneStateListener#onCellInfoChanged
     * onCellInfoChanged()}, or change the rate at which
     * {@link android.telephony.PhoneStateListener#onCellInfoChanged
     * onCellInfoChanged()} is called.
     *
     *<p>
     * The list can include one or more {@link android.telephony.CellInfoGsm CellInfoGsm},
     * {@link android.telephony.CellInfoCdma CellInfoCdma},
     * {@link android.telephony.CellInfoLte CellInfoLte}, and
     * {@link android.telephony.CellInfoWcdma CellInfoWcdma} objects, in any combination.
     * On devices with multiple radios it is typical to see instances of
     * one or more of any these in the list. In addition, zero, one, or more
     * of the returned objects may be considered registered; that is, their
     * {@link android.telephony.CellInfo#isRegistered CellInfo.isRegistered()}
     * methods may return true.
     *
     * <p>This method returns valid data for registered cells on devices with
     * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY}. In cases where only
     * partial information is available for a particular CellInfo entry, unavailable fields
     * will be reported as Integer.MAX_VALUE. All reported cells will include at least a
     * valid set of technology-specific identification info and a power level measurement.
     *
     *<p>
     * This method is preferred over using {@link
     * android.telephony.TelephonyManager#getCellLocation getCellLocation()}.
     * However, for older devices, <code>getAllCellInfo()</code> may return
     * null. In these cases, you should call {@link
     * android.telephony.TelephonyManager#getCellLocation getCellLocation()}
     * instead.
     *
     * @return List of {@link android.telephony.CellInfo}; null if cell
     * information is unavailable.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    public List<CellInfo> getAllCellInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getAllCellInfo(getOpPackageName());
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
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setCellInfoListRate(rateInMillis);
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @return an IccOpenLogicalChannelResponse object.
     * @deprecated Replaced by {@link #iccOpenLogicalChannel(String, int)}
     */
    @Deprecated
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID) {
        return iccOpenLogicalChannel(getSubId(), AID, -1);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID, int p2) {
        return iccOpenLogicalChannel(getSubId(), AID, p2);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     * @hide
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(int subId, String AID, int p2) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccOpenLogicalChannel(subId, getOpPackageName(), AID, p2);
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     */
    public boolean iccCloseLogicalChannel(int channel) {
        return iccCloseLogicalChannel(getSubId(), channel);
    }

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     * @hide
     */
    public boolean iccCloseLogicalChannel(int subId, int channel) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccCloseLogicalChannel(subId, channel);
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccTransmitApduLogicalChannel(getSubId(), channel, cla,
                    instruction, p1, p2, p3, data);
    }

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
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
     * @hide
     */
    public String iccTransmitApduLogicalChannel(int subId, int channel, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduLogicalChannel(subId, channel, cla,
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccTransmitApduBasicChannel(getSubId(), cla,
                    instruction, p1, p2, p3, data);
    }

    /**
     * Transmit an APDU to the ICC card over the basic channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     * @hide
     */
    public String iccTransmitApduBasicChannel(int subId, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduBasicChannel(subId, getOpPackageName(), cla,
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccExchangeSimIO(getSubId(), fileID, command, p1, p2, p3, filePath);
    }

    /**
     * Returns the response APDU for a command APDU sent through SIM_IO.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param fileID
     * @param command
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command.
     * @param filePath
     * @return The APDU response.
     * @hide
     */
    public byte[] iccExchangeSimIO(int subId, int fileID, int command, int p1, int p2,
            int p3, String filePath) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccExchangeSimIO(subId, fileID, command, p1, p2, p3, filePath);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card in hexadecimal format
     *         with the last 4 bytes being the status word. If the command fails,
     *         returns an empty string.
     */
    public String sendEnvelopeWithStatus(String content) {
        return sendEnvelopeWithStatus(getSubId(), content);
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card in hexadecimal format
     *         with the last 4 bytes being the status word. If the command fails,
     *         returns an empty string.
     * @hide
     */
    public String sendEnvelopeWithStatus(int subId, String content) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.sendEnvelopeWithStatus(subId, content);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Read one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param itemID the ID of the item to read.
     * @return the NV item as a String, or null on any failure.
     *
     * @hide
     */
    public String nvReadItem(int itemID) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvReadItem(itemID);
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param itemID the ID of the item to read.
     * @param itemValue the value to write, as a String.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteItem(int itemID, String itemValue) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvWriteItem(itemID, itemValue);
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param preferredRoamingList byte array containing the new PRL.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteCdmaPrl(byte[] preferredRoamingList) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvWriteCdmaPrl(preferredRoamingList);
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param resetType reset type: 1: reload NV reset, 2: erase NV reset, 3: factory NV reset
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvResetConfig(int resetType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvResetConfig(resetType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvResetConfig RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvResetConfig NPE", ex);
        }
        return false;
    }

    /**
     * Return an appropriate subscription ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the provided
     * subId is returned. Otherwise, the default subId will be returned.
     */
    private int getSubId() {
      if (SubscriptionManager.isUsableSubIdValue(mSubId)) {
        return mSubId;
      }
      return SubscriptionManager.getDefaultSubscriptionId();
    }

    /**
     * Return an appropriate subscription ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the provided
     * subId is returned. Otherwise, the preferred subId which is based on caller's context is
     * returned.
     * {@see SubscriptionManager#getDefaultDataSubscriptionId()}
     * {@see SubscriptionManager#getDefaultVoiceSubscriptionId()}
     * {@see SubscriptionManager#getDefaultSmsSubscriptionId()}
     */
    private int getSubId(int preferredSubId) {
        if (SubscriptionManager.isUsableSubIdValue(mSubId)) {
            return mSubId;
        }
        return preferredSubId;
    }

    /**
     * Return an appropriate phone ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the phoneId
     * associated with the provided subId is returned. Otherwise, the default phoneId associated
     * with the default subId will be returned.
     */
    private int getPhoneId() {
        return SubscriptionManager.getPhoneId(getSubId());
    }

    /**
     * Return an appropriate phone ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the phoneId
     * associated with the provided subId is returned. Otherwise, return the phoneId associated
     * with the preferred subId based on caller's context.
     * {@see SubscriptionManager#getDefaultDataSubscriptionId()}
     * {@see SubscriptionManager#getDefaultVoiceSubscriptionId()}
     * {@see SubscriptionManager#getDefaultSmsSubscriptionId()}
     */
    private int getPhoneId(int preferredSubId) {
        return SubscriptionManager.getPhoneId(getSubId(preferredSubId));
    }

    /**
     * Return an appropriate slot index for any situation.
     *
     * if this object has been created with {@link #createForSubscriptionId}, then the slot index
     * associated with the provided subId is returned. Otherwise, return the slot index associated
     * with the default subId.
     * If SIM is not inserted, return default SIM slot index.
     *
     * {@hide}
     */
    @VisibleForTesting
    public int getSlotIndex() {
        int slotIndex = SubscriptionManager.getSlotIndex(getSubId());
        if (slotIndex == SubscriptionManager.SIM_NOT_INSERTED) {
            slotIndex = SubscriptionManager.DEFAULT_SIM_SLOT_INDEX;
        }
        return slotIndex;
    }

    /**
     * Sets a per-phone telephony property with the value specified.
     *
     * @hide
     */
    public static void setTelephonyProperty(int phoneId, String property, String value) {
        String propVal = "";
        String p[] = null;
        String prop = SystemProperties.get(property);

        if (value == null) {
            value = "";
        }

        if (prop != null) {
            p = prop.split(",");
        }

        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            Rlog.d(TAG, "setTelephonyProperty: invalid phoneId=" + phoneId +
                    " property=" + property + " value: " + value + " prop=" + prop);
            return;
        }

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

        if (propVal.length() > SystemProperties.PROP_VALUE_MAX) {
            Rlog.d(TAG, "setTelephonyProperty: property too long phoneId=" + phoneId +
                    " property=" + property + " value: " + value + " propVal=" + propVal);
            return;
        }

        SystemProperties.set(property, propVal);
    }

    /**
     * Sets a global telephony property with the value specified.
     *
     * @hide
     */
    public static void setTelephonyProperty(String property, String value) {
        if (value == null) {
            value = "";
        }
        Rlog.d(TAG, "setTelephonyProperty: success" + " property=" +
                property + " value: " + value);
        SystemProperties.set(property, value);
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

        if (index == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("putIntAtIndex index == MAX_VALUE index=" + index);
        }
        if (index < 0) {
            throw new IllegalArgumentException("putIntAtIndex index < 0 index=" + index);
        }
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
     * Gets a per-phone telephony property.
     *
     * @hide
     */
    public static String getTelephonyProperty(int phoneId, String property, String defaultVal) {
        String propVal = null;
        String prop = SystemProperties.get(property);
        if ((prop != null) && (prop.length() > 0)) {
            String values[] = prop.split(",");
            if ((phoneId >= 0) && (phoneId < values.length) && (values[phoneId] != null)) {
                propVal = values[phoneId];
            }
        }
        return propVal == null ? defaultVal : propVal;
    }

    /**
     * Gets a global telephony property.
     *
     * See also getTelephonyProperty(phoneId, property, defaultVal). Most telephony properties are
     * per-phone.
     *
     * @hide
     */
    public static String getTelephonyProperty(String property, String defaultVal) {
        String propVal = SystemProperties.get(property);
        return propVal == null ? defaultVal : propVal;
    }

    /** @hide */
    public int getSimCount() {
        // FIXME Need to get it from Telephony Dev Controller when that gets implemented!
        // and then this method shouldn't be used at all!
        if(isMultiSimEnabled()) {
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
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Ist based on subId
            return info.getIsimIst(getSubId());
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
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Pcscf based on subId
            return info.getIsimPcscf(getSubId());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    // ICC SIM Application Types
    /** UICC application type is SIM */
    public static final int APPTYPE_SIM = PhoneConstants.APPTYPE_SIM;
    /** UICC application type is USIM */
    public static final int APPTYPE_USIM = PhoneConstants.APPTYPE_USIM;
    /** UICC application type is RUIM */
    public static final int APPTYPE_RUIM = PhoneConstants.APPTYPE_RUIM;
    /** UICC application type is CSIM */
    public static final int APPTYPE_CSIM = PhoneConstants.APPTYPE_CSIM;
    /** UICC application type is ISIM */
    public static final int APPTYPE_ISIM = PhoneConstants.APPTYPE_ISIM;
    // authContext (parameter P2) when doing UICC challenge,
    // per 3GPP TS 31.102 (Section 7.1.2)
    /** Authentication type for UICC challenge is EAP SIM. See RFC 4186 for details. */
    public static final int AUTHTYPE_EAP_SIM = PhoneConstants.AUTH_CONTEXT_EAP_SIM;
    /** Authentication type for UICC challenge is EAP AKA. See RFC 4187 for details. */
    public static final int AUTHTYPE_EAP_AKA = PhoneConstants.AUTH_CONTEXT_EAP_AKA;

    /**
     * Returns the response of authentication for the default subscription.
     * Returns null if the authentication hasn't been successful
     *
     * <p>Requires Permission: READ_PRIVILEGED_PHONE_STATE or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @param authType the authentication type, {@link #AUTHTYPE_EAP_AKA} or
     * {@link #AUTHTYPE_EAP_SIM}
     * @param data authentication challenge data, base64 encoded.
     * See 3GPP TS 31.102 7.1.2 for more details.
     * @return the response of authentication. This value will be null in the following cases:
     *   Authentication error, incorrect MAC
     *   Authentication error, security context not supported
     *   Key freshness failure
     *   Authentication error, no memory space available
     *   Authentication error, no memory space available in EFMUK
     */
    // TODO(b/73660190): This should probably require MODIFY_PHONE_STATE, not
    // READ_PRIVILEGED_PHONE_STATE. It certainly shouldn't reference the permission in Javadoc since
    // it's not public API.
    public String getIccAuthentication(int appType, int authType, String data) {
        return getIccAuthentication(getSubId(), appType, authType, data);
    }

    /**
     * Returns the response of USIM Authentication for specified subId.
     * Returns null if the authentication hasn't been successful
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @param authType the authentication type, {@link #AUTHTYPE_EAP_AKA} or
     * {@link #AUTHTYPE_EAP_SIM}
     * @param data authentication challenge data, base64 encoded.
     * See 3GPP TS 31.102 7.1.2 for more details.
     * @return the response of authentication. This value will be null in the following cases only
     * (see 3GPP TS 31.102 7.3.1):
     *   Authentication error, incorrect MAC
     *   Authentication error, security context not supported
     *   Key freshness failure
     *   Authentication error, no memory space available
     *   Authentication error, no memory space available in EFMUK
     * @hide
     */
    public String getIccAuthentication(int subId, int appType, int authType, String data) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIccSimChallengeResponse(subId, appType, authType, data);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Returns an array of Forbidden PLMNs from the USIM App
     * Returns null if the query fails.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return an array of forbidden PLMNs or null if not available
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String[] getForbiddenPlmns() {
      return getForbiddenPlmns(getSubId(), APPTYPE_USIM);
    }

    /**
     * Returns an array of Forbidden PLMNs from the specified SIM App
     * Returns null if the query fails.
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @return fplmns an array of forbidden PLMNs
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String[] getForbiddenPlmns(int subId, int appType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getForbiddenPlmns(subId, appType, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Get P-CSCF address from PCO after data connection is established or modified.
     * @param apnType the apnType, "ims" for IMS APN, "emergency" for EMERGENCY APN
     * @return array of P-CSCF address
     * @hide
     */
    public String[] getPcscfAddress(String apnType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return new String[0];
            return telephony.getPcscfAddress(apnType, getOpPackageName());
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * Enables IMS for the framework. This will trigger IMS registration and ImsFeature capability
     * status updates, if not already enabled.
     * @hide
     */
    public void enableIms(int slotId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.enableIms(slotId);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "enableIms, RemoteException: "
                    + e.getMessage());
        }
    }

    /**
     * Disables IMS for the framework. This will trigger IMS de-registration and trigger ImsFeature
     * status updates to disabled.
     * @hide
     */
    public void disableIms(int slotId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.disableIms(slotId);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "disableIms, RemoteException: "
                    + e.getMessage());
        }
    }

    /**
     * Returns the {@link IImsMmTelFeature} that corresponds to the given slot Id and MMTel
     * feature or {@link null} if the service is not available. If an MMTelFeature is available, the
     * {@link IImsServiceFeatureCallback} callback is registered as a listener for feature updates.
     * @param slotIndex The SIM slot that we are requesting the {@link IImsMmTelFeature} for.
     * @param callback Listener that will send updates to ImsManager when there are updates to
     * ImsServiceController.
     * @return {@link IImsMmTelFeature} interface for the feature specified or {@code null} if
     * it is unavailable.
     * @hide
     */
    public @Nullable IImsMmTelFeature getImsMmTelFeatureAndListen(int slotIndex,
            IImsServiceFeatureCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getMmTelFeatureAndListen(slotIndex, callback);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsMmTelFeatureAndListen, RemoteException: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the {@link IImsRcsFeature} that corresponds to the given slot Id and RCS
     * feature for emergency calling or {@link null} if the service is not available. If an
     * RcsFeature is available, the {@link IImsServiceFeatureCallback} callback is registered as a
     * listener for feature updates.
     * @param slotIndex The SIM slot that we are requesting the {@link IImsRcsFeature} for.
     * @param callback Listener that will send updates to ImsManager when there are updates to
     * ImsServiceController.
     * @return {@link IImsRcsFeature} interface for the feature specified or {@code null} if
     * it is unavailable.
     * @hide
     */
    public @Nullable IImsRcsFeature getImsRcsFeatureAndListen(int slotIndex,
            IImsServiceFeatureCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getRcsFeatureAndListen(slotIndex, callback);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRcsFeatureAndListen, RemoteException: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * @return the {@IImsRegistration} interface that corresponds with the slot index and feature.
     * @param slotIndex The SIM slot corresponding to the ImsService ImsRegistration is active for.
     * @param feature An integer indicating the feature that we wish to get the ImsRegistration for.
     * Corresponds to features defined in ImsFeature.
     * @hide
     */
    public @Nullable IImsRegistration getImsRegistration(int slotIndex, int feature) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getImsRegistration(slotIndex, feature);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRegistration, RemoteException: " + e.getMessage());
        }
        return null;
    }

    /**
     * @return the {@IImsConfig} interface that corresponds with the slot index and feature.
     * @param slotIndex The SIM slot corresponding to the ImsService ImsConfig is active for.
     * @param feature An integer indicating the feature that we wish to get the ImsConfig for.
     * Corresponds to features defined in ImsFeature.
     * @hide
     */
    public @Nullable IImsConfig getImsConfig(int slotIndex, int feature) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getImsConfig(slotIndex, feature);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRegistration, RemoteException: " + e.getMessage());
        }
        return null;
    }

    /**
     * @return true if the IMS resolver is busy resolving a binding and should not be considered
     * available, false if the IMS resolver is idle.
     * @hide
     */
    public boolean isResolvingImsBinding() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isResolvingImsBinding();
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "isResolvingImsBinding, RemoteException: " + e.getMessage());
        }
        return false;
    }

    /**
     * Set IMS registration state
     *
     * @param Registration state
     * @hide
     */
    public void setImsRegistrationState(boolean registered) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setImsRegistrationState(registered);
        } catch (RemoteException e) {
        }
    }

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the preferred network type, defined in RILConstants.java.
     * @hide
     */
    public int getPreferredNetworkType(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getPreferredNetworkType(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPreferredNetworkType NPE", ex);
        }
        return -1;
    }

    /**
     * Sets the network selection mode to automatic.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setNetworkSelectionModeAutomatic() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setNetworkSelectionModeAutomatic(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic NPE", ex);
        }
    }

    /**
     * Perform a radio scan and return the list of available networks.
     *
     * The return value is a list of the OperatorInfo of the networks found. Note that this
     * scan can take a long time (sometimes minutes) to happen.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @hide
     * TODO: Add an overload that takes no args.
     */
    public CellNetworkScanResult getCellNetworkScanResults(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getCellNetworkScanResults(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCellNetworkScanResults RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCellNetworkScanResults NPE", ex);
        }
        return null;
    }

    /**
     * Request a network scan.
     *
     * This method is asynchronous, so the network scan results will be returned by callback.
     * The returned NetworkScan will contain a callback method which can be used to stop the scan.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param request Contains all the RAT with bands/channels that need to be scanned.
     * @param executor The executor through which the callback should be invoked. Since the scan
     *        request may trigger multiple callbacks and they must be invoked in the same order as
     *        they are received by the platform, the user should provide an executor which executes
     *        tasks one at a time in serial order. For example AsyncTask.SERIAL_EXECUTOR.
     * @param callback Returns network scan results or errors.
     * @return A NetworkScan obj which contains a callback which can be used to stop the scan.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public NetworkScan requestNetworkScan(
            NetworkScanRequest request, Executor executor,
            TelephonyScanManager.NetworkScanCallback callback) {
        synchronized (this) {
            if (mTelephonyScanManager == null) {
                mTelephonyScanManager = new TelephonyScanManager();
            }
        }
        return mTelephonyScanManager.requestNetworkScan(getSubId(), request, executor, callback);
    }

    /**
     * @deprecated
     * Use {@link
     * #requestNetworkScan(NetworkScanRequest, Executor, TelephonyScanManager.NetworkScanCallback)}
     * @removed
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public NetworkScan requestNetworkScan(
        NetworkScanRequest request, TelephonyScanManager.NetworkScanCallback callback) {
        return requestNetworkScan(request, AsyncTask.SERIAL_EXECUTOR, callback);
    }

    /**
     * Ask the radio to connect to the input network and change selection mode to manual.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param operatorNumeric the PLMN ID of the network to select.
     * @param persistSelection whether the selection will persist until reboot. If true, only allows
     * attaching to the selected PLMN until reboot; otherwise, attach to the chosen PLMN and resume
     * normal network selection next time.
     * @return true on success; false on any failure.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setNetworkSelectionModeManual(String operatorNumeric, boolean persistSelection) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setNetworkSelectionModeManual(
                        getSubId(), operatorNumeric, persistSelection);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeManual RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeManual NPE", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId the id of the subscription to set the preferred network type for.
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     * @hide
     */
    public boolean setPreferredNetworkType(int subId, int networkType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setPreferredNetworkType(subId, networkType);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setPreferredNetworkType NPE", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type to global mode which includes LTE, CDMA, EvDo and GSM/WCDMA.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return true on success; false on any failure.
     */
    public boolean setPreferredNetworkTypeToGlobal() {
        return setPreferredNetworkTypeToGlobal(getSubId());
    }

    /**
     * Set the preferred network type to global mode which includes LTE, CDMA, EvDo and GSM/WCDMA.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return true on success; false on any failure.
     * @hide
     */
    public boolean setPreferredNetworkTypeToGlobal(int subId) {
        return setPreferredNetworkType(subId, RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA);
    }

    /**
     * Check TETHER_DUN_REQUIRED and TETHER_DUN_APN settings, net.tethering.noprovisioning
     * SystemProperty, and config_tether_apndata to decide whether DUN APN is required for
     * tethering.
     *
     * @return 0: Not required. 1: required. 2: Not set.
     * @hide
     */
    public int getTetherApnRequired() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getTetherApnRequired();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting NPE", ex);
        }
        return 2;
    }


    /**
     * Values used to return status for hasCarrierPrivileges call.
     */
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_HAS_ACCESS = 1;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_NO_ACCESS = 0;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED = -1;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES = -2;

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * @return true if the app has carrier privileges.
     */
    public boolean hasCarrierPrivileges() {
        return hasCarrierPrivileges(getSubId());
    }

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * @param subId The subscription to use.
     * @return true if the app has carrier privileges.
     * @hide
     */
    public boolean hasCarrierPrivileges(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCarrierPrivilegeStatus(mSubId) ==
                    CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges NPE", ex);
        }
        return false;
    }

    /**
     * Override the branding for the current ICCID.
     *
     * Once set, whenever the SIM is present in the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     */
    public boolean setOperatorBrandOverride(String brand) {
        return setOperatorBrandOverride(getSubId(), brand);
    }

    /**
     * Override the branding for the current ICCID.
     *
     * Once set, whenever the SIM is present in the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     * @hide
     */
    public boolean setOperatorBrandOverride(int subId, String brand) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setOperatorBrandOverride(subId, brand);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride NPE", ex);
        }
        return false;
    }

    /**
     * Override the roaming preference for the current ICCID.
     *
     * Using this call, the carrier app (see #hasCarrierPrivileges) can override
     * the platform's notion of a network operator being considered roaming or not.
     * The change only affects the ICCID that was active when this call was made.
     *
     * If null is passed as any of the input, the corresponding value is deleted.
     *
     * <p>Requires that the caller have carrier privilege. See #hasCarrierPrivileges.
     *
     * @param gsmRoamingList - List of MCCMNCs to be considered roaming for 3GPP RATs.
     * @param gsmNonRoamingList - List of MCCMNCs to be considered not roaming for 3GPP RATs.
     * @param cdmaRoamingList - List of SIDs to be considered roaming for 3GPP2 RATs.
     * @param cdmaNonRoamingList - List of SIDs to be considered not roaming for 3GPP2 RATs.
     * @return true if the operation was executed correctly.
     *
     * @hide
     */
    public boolean setRoamingOverride(List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        return setRoamingOverride(getSubId(), gsmRoamingList, gsmNonRoamingList,
                cdmaRoamingList, cdmaNonRoamingList);
    }

    /**
     * Override the roaming preference for the current ICCID.
     *
     * Using this call, the carrier app (see #hasCarrierPrivileges) can override
     * the platform's notion of a network operator being considered roaming or not.
     * The change only affects the ICCID that was active when this call was made.
     *
     * If null is passed as any of the input, the corresponding value is deleted.
     *
     * <p>Requires that the caller have carrier privilege. See #hasCarrierPrivileges.
     *
     * @param subId for which the roaming overrides apply.
     * @param gsmRoamingList - List of MCCMNCs to be considered roaming for 3GPP RATs.
     * @param gsmNonRoamingList - List of MCCMNCs to be considered not roaming for 3GPP RATs.
     * @param cdmaRoamingList - List of SIDs to be considered roaming for 3GPP2 RATs.
     * @param cdmaNonRoamingList - List of SIDs to be considered not roaming for 3GPP2 RATs.
     * @return true if the operation was executed correctly.
     *
     * @hide
     */
    public boolean setRoamingOverride(int subId, List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRoamingOverride(subId, gsmRoamingList, gsmNonRoamingList,
                        cdmaRoamingList, cdmaNonRoamingList);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setRoamingOverride RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setRoamingOverride NPE", ex);
        }
        return false;
    }

    /**
     * Expose the rest of ITelephony to @SystemApi
     */

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMdn() {
        return getCdmaMdn(getSubId());
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMdn(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaMdn(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMin() {
        return getCdmaMin(getSubId());
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMin(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaMin(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
    public int checkCarrierPrivilegesForPackage(String pkgName) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.checkCarrierPrivilegesForPackage(pkgName);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackage RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackage NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
    public int checkCarrierPrivilegesForPackageAnyPhone(String pkgName) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.checkCarrierPrivilegesForPackageAnyPhone(pkgName);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackageAnyPhone RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackageAnyPhone NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /** @hide */
    @SystemApi
    public List<String> getCarrierPackageNamesForIntent(Intent intent) {
        return getCarrierPackageNamesForIntentAndPhone(intent, getPhoneId());
    }

    /** @hide */
    @SystemApi
    public List<String> getCarrierPackageNamesForIntentAndPhone(Intent intent, int phoneId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getCarrierPackageNamesForIntentAndPhone(intent, phoneId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForIntentAndPhone RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForIntentAndPhone NPE", ex);
        }
        return null;
    }

    /** @hide */
    public List<String> getPackagesWithCarrierPrivileges() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getPackagesWithCarrierPrivileges();
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges NPE", ex);
        }
        return Collections.EMPTY_LIST;
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
    public void dial(String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.dial(number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#dial", e);
        }
    }

    /**
     * @deprecated Use  {@link android.telecom.TelecomManager#placeCall(Uri address,
     * Bundle extras)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public void call(String callingPackage, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.call(callingPackage, number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#call", e);
        }
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#endCall()} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public boolean endCall() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.endCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#endCall", e);
        }
        return false;
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#acceptRingingCall} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void answerRingingCall() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.answerRingingCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#answerRingingCall", e);
        }
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#silenceRinger} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public void silenceRinger() {
        try {
            getTelecomService().silenceRinger(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#silenceRinger", e);
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isOffhook() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isOffhook(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isOffhook", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isRinging() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isRinging(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRinging", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isIdle() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isIdle(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isIdle", e);
        }
        return true;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isRadioOn() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isRadioOn(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRadioOn", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean supplyPin(String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPin(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPin", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean supplyPuk(String puk, String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPuk(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPuk", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int[] supplyPinReportResult(String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPinReportResult(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinReportResult", e);
        }
        return new int[0];
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int[] supplyPukReportResult(String puk, String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPukReportResult(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#]", e);
        }
        return new int[0];
    }

    /**
     * Used to notify callers of
     * {@link TelephonyManager#sendUssdRequest(String, UssdResponseCallback, Handler)} when the
     * network either successfully executes a USSD request, or if there was a failure while
     * executing the request.
     * <p>
     * {@link #onReceiveUssdResponse(TelephonyManager, String, CharSequence)} will be called if the
     * USSD request has succeeded.
     * {@link #onReceiveUssdResponseFailed(TelephonyManager, String, int)} will be called if the
     * USSD request has failed.
     */
    public static abstract class UssdResponseCallback {
       /**
        * Called when a USSD request has succeeded.  The {@code response} contains the USSD
        * response received from the network.  The calling app can choose to either display the
        * response to the user or perform some operation based on the response.
        * <p>
        * USSD responses are unstructured text and their content is determined by the mobile network
        * operator.
        *
        * @param telephonyManager the TelephonyManager the callback is registered to.
        * @param request the USSD request sent to the mobile network.
        * @param response the response to the USSD request provided by the mobile network.
        **/
       public void onReceiveUssdResponse(final TelephonyManager telephonyManager,
                                         String request, CharSequence response) {};

       /**
        * Called when a USSD request has failed to complete.
        *
        * @param telephonyManager the TelephonyManager the callback is registered to.
        * @param request the USSD request sent to the mobile network.
        * @param failureCode failure code indicating why the request failed.  Will be either
        *        {@link TelephonyManager#USSD_RETURN_FAILURE} or
        *        {@link TelephonyManager#USSD_ERROR_SERVICE_UNAVAIL}.
        **/
       public void onReceiveUssdResponseFailed(final TelephonyManager telephonyManager,
                                               String request, int failureCode) {};
    }

    /**
     * Sends an Unstructured Supplementary Service Data (USSD) request to the mobile network and
     * informs the caller of the response via the supplied {@code callback}.
     * <p>Carriers define USSD codes which can be sent by the user to request information such as
     * the user's current data balance or minutes balance.
     * <p>Requires permission:
     * {@link android.Manifest.permission#CALL_PHONE}
     * @param ussdRequest the USSD command to be executed.
     * @param callback called by the framework to inform the caller of the result of executing the
     *                 USSD request (see {@link UssdResponseCallback}).
     * @param handler the {@link Handler} to run the request on.
     */
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public void sendUssdRequest(String ussdRequest,
                                final UssdResponseCallback callback, Handler handler) {
        checkNotNull(callback, "UssdResponseCallback cannot be null.");
        final TelephonyManager telephonyManager = this;

        ResultReceiver wrappedCallback = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle ussdResponse) {
                Rlog.d(TAG, "USSD:" + resultCode);
                checkNotNull(ussdResponse, "ussdResponse cannot be null.");
                UssdResponse response = ussdResponse.getParcelable(USSD_RESPONSE);

                if (resultCode == USSD_RETURN_SUCCESS) {
                    callback.onReceiveUssdResponse(telephonyManager, response.getUssdRequest(),
                            response.getReturnMessage());
                } else {
                    callback.onReceiveUssdResponseFailed(telephonyManager,
                            response.getUssdRequest(), resultCode);
                }
            }
        };

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.handleUssdRequest(getSubId(), ussdRequest, wrappedCallback);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#sendUSSDCode", e);
            UssdResponse response = new UssdResponse(ussdRequest, "");
            Bundle returnData = new Bundle();
            returnData.putParcelable(USSD_RESPONSE, response);
            wrappedCallback.send(USSD_ERROR_SERVICE_UNAVAIL, returnData);
        }
    }

    /**
     * Whether the device is currently on a technology (e.g. UMTS or LTE) which can support
     * voice and data simultaneously. This can change based on location or network condition.
     *
     * @return {@code true} if simultaneous voice and data supported, and {@code false} otherwise.
     */
    public boolean isConcurrentVoiceAndDataSupported() {
        try {
            ITelephony telephony = getITelephony();
            return (telephony == null ? false : telephony.isConcurrentVoiceAndDataAllowed(
                    getSubId()));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isConcurrentVoiceAndDataAllowed", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean handlePinMmi(String dialString) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.handlePinMmi(dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#handlePinMmi", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean handlePinMmiForSubscriber(int subId, String dialString) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.handlePinMmiForSubscriber(subId, dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#handlePinMmi", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void toggleRadioOnOff() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.toggleRadioOnOff();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#toggleRadioOnOff", e);
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setRadio(boolean turnOn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRadio(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadio", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setRadioPower(boolean turnOn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRadioPower(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadioPower", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
    public void updateServiceLocation() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.updateServiceLocation();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#updateServiceLocation", e);
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean enableDataConnectivity() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.enableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#enableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean disableDataConnectivity() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.disableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#disableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isDataConnectivityPossible() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isDataConnectivityPossible(getSubId(SubscriptionManager
                        .getDefaultDataSubscriptionId()));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataAllowed", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean needsOtaServiceProvisioning() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.needsOtaServiceProvisioning();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#needsOtaServiceProvisioning", e);
        }
        return false;
    }

    /**
     * Turns mobile data on or off.
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param enable Whether to enable mobile data.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataEnabled(boolean enable) {
        setDataEnabled(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), enable);
    }

    /**
     * @hide
     * @deprecated use {@link #setDataEnabled(boolean)} instead.
    */
    @SystemApi
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataEnabled(int subId, boolean enable) {
        try {
            Log.d(TAG, "setDataEnabled: enabled=" + enable);
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setUserDataEnabled(subId, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setUserDataEnabled", e);
        }
    }

    /**
     * @deprecated use {@link #isDataEnabled()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public boolean getDataEnabled() {
        return isDataEnabled();
    }

    /**
     * Returns whether mobile data is enabled or not per user setting. There are other factors
     * that could disable mobile data, but they are not considered here.
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires one of the following permissions:
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE ACCESS_NETWORK_STATE},
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}, or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * <p>Note that this does not take into account any data restrictions that may be present on the
     * calling app. Such restrictions may be inspected with
     * {@link ConnectivityManager#getRestrictBackgroundStatus}.
     *
     * @return true if mobile data is enabled.
     */
    public boolean isDataEnabled() {
        return getDataEnabled(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
    }

    /**
     * @deprecated use {@link #isDataEnabled()} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public boolean getDataEnabled(int subId) {
        boolean retVal = false;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.isUserDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isUserDataEnabled", e);
        } catch (NullPointerException e) {
        }
        return retVal;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void enableVideoCalling(boolean enable) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.enableVideoCalling(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#enableVideoCalling", e);
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isVideoCallingEnabled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isVideoCallingEnabled(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVideoCallingEnabled", e);
        }
        return false;
    }

    /**
     * Whether the device supports configuring the DTMF tone length.
     *
     * @return {@code true} if the DTMF tone length can be changed, and {@code false} otherwise.
     */
    public boolean canChangeDtmfToneLength() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.canChangeDtmfToneLength();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#canChangeDtmfToneLength", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#canChangeDtmfToneLength", e);
        }
        return false;
    }

    /**
     * Whether the device is a world phone.
     *
     * @return {@code true} if the device is a world phone, and {@code false} otherwise.
     */
    public boolean isWorldPhone() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isWorldPhone();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isWorldPhone", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isWorldPhone", e);
        }
        return false;
    }

    /**
     * @deprecated Use {@link TelecomManager#isTtySupported()} instead
     * Whether the phone supports TTY mode.
     *
     * @return {@code true} if the device supports TTY mode, and {@code false} otherwise.
     *
     */
    @Deprecated
    public boolean isTtyModeSupported() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isTtyModeSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isTtyModeSupported", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isTtyModeSupported", e);
        }
        return false;
    }

    /**
     * Whether the phone supports hearing aid compatibility.
     *
     * @return {@code true} if the device supports hearing aid compatibility, and {@code false}
     * otherwise.
     */
    public boolean isHearingAidCompatibilitySupported() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isHearingAidCompatibilitySupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isHearingAidCompatibilitySupported", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isHearingAidCompatibilitySupported", e);
        }
        return false;
    }

    /**
     * Returns the IMS Registration Status for a particular Subscription ID.
     *
     * @param subId Subscription ID
     * @return true if IMS status is registered, false if the IMS status is not registered or a
     * RemoteException occurred.
     * @hide
     */
    public boolean isImsRegistered(int subId) {
        try {
            return getITelephony().isImsRegistered(subId);
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * Returns the IMS Registration Status for a particular Subscription ID, which is determined
     * when the TelephonyManager is created using {@link #createForSubscriptionId(int)}. If an
     * invalid subscription ID is used during creation, will the default subscription ID will be
     * used.
     *
     * @return true if IMS status is registered, false if the IMS status is not registered or a
     * RemoteException occurred.
     * @see SubscriptionManager#getDefaultSubscriptionId()
     * @hide
     */
    public boolean isImsRegistered() {
       try {
           return getITelephony().isImsRegistered(getSubId());
       } catch (RemoteException | NullPointerException ex) {
           return false;
       }
    }

    /**
     * The current status of Voice over LTE for the subscription associated with this instance when
     * it was created using {@link #createForSubscriptionId(int)}. If an invalid subscription ID was
     * used during creation, the default subscription ID will be used.
     * @return true if Voice over LTE is available or false if it is unavailable or unknown.
     * @see SubscriptionManager#getDefaultSubscriptionId()
     * @hide
     */
    public boolean isVolteAvailable() {
        try {
            return getITelephony().isVolteAvailable(getSubId());
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * The availability of Video Telephony (VT) for the subscription ID specified when this instance
     * was created using {@link #createForSubscriptionId(int)}. If an invalid subscription ID was
     * used during creation, the default subscription ID will be used. To query the
     * underlying technology that VT is available on, use {@link #getImsRegTechnologyForMmTel}.
     * @return true if VT is available, or false if it is unavailable or unknown.
     * @hide
     */
    public boolean isVideoTelephonyAvailable() {
        try {
            return getITelephony().isVideoTelephonyAvailable(getSubId());
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * Returns the Status of Wi-Fi calling (Voice over WiFi) for the subscription ID specified.
     * @param subId the subscription ID.
     * @return true if VoWiFi is available, or false if it is unavailable or unknown.
     * @hide
     */
    public boolean isWifiCallingAvailable() {
       try {
           return getITelephony().isWifiCallingAvailable(getSubId());
       } catch (RemoteException | NullPointerException ex) {
           return false;
       }
   }

    /**
     * The technology that IMS is registered for for the MMTEL feature.
     * @param subId subscription ID to get IMS registration technology for.
     * @return The IMS registration technology that IMS is registered to for the MMTEL feature.
     * Valid return results are:
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} for LTE registration,
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN} for IWLAN registration, or
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_NONE} if we are not registered or the
     *  result is unavailable.
     *  @hide
     */
    public @ImsRegistrationImplBase.ImsRegistrationTech int getImsRegTechnologyForMmTel() {
        try {
            return getITelephony().getImsRegTechnologyForMmTel(getSubId());
        } catch (RemoteException ex) {
            return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
        }
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the default phone.
    *
    * @hide
    */
    public void setSimOperatorNumeric(String numeric) {
        int phoneId = getPhoneId();
        setSimOperatorNumericForPhone(phoneId, numeric);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
    *
    * @hide
    */
    public void setSimOperatorNumericForPhone(int phoneId, String numeric) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, numeric);
    }

    /**
     * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the default phone.
     *
     * @hide
     */
    public void setSimOperatorName(String name) {
        int phoneId = getPhoneId();
        setSimOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
     *
     * @hide
     */
    public void setSimOperatorNameForPhone(int phoneId, String name) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, name);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY for the default phone.
    *
    * @hide
    */
    public void setSimCountryIso(String iso) {
        int phoneId = getPhoneId();
        setSimCountryIsoForPhone(phoneId, iso);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY for the given phone.
    *
    * @hide
    */
    public void setSimCountryIsoForPhone(int phoneId, String iso) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, iso);
    }

    /**
     * Set TelephonyProperties.PROPERTY_SIM_STATE for the default phone.
     *
     * @hide
     */
    public void setSimState(String state) {
        int phoneId = getPhoneId();
        setSimStateForPhone(phoneId, state);
    }

    /**
     * Set TelephonyProperties.PROPERTY_SIM_STATE for the given phone.
     *
     * @hide
     */
    public void setSimStateForPhone(int phoneId, String state) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_SIM_STATE, state);
    }

    /**
     * Requested state of SIM
     *
     * CARD_POWER_DOWN
     * Powers down the SIM. SIM must be up prior.
     *
     * CARD_POWER_UP
     * Powers up the SIM normally. SIM must be down prior.
     *
     * CARD_POWER_UP_PASS_THROUGH
     * Powers up the SIM in PASS_THROUGH mode. SIM must be down prior.
     * When SIM is powered up in PASS_THOUGH mode, the modem does not send
     * any command to it (for example SELECT of MF, or TERMINAL CAPABILITY),
     * and the SIM card is controlled completely by Telephony sending APDUs
     * directly. The SIM card state will be RIL_CARDSTATE_PRESENT and the
     * number of card apps will be 0.
     * No new error code is generated. Emergency calls are supported in the
     * same way as if the SIM card is absent.
     * The PASS_THROUGH mode is valid only for the specific card session where it
     * is activated, and normal behavior occurs at the next SIM initialization,
     * unless PASS_THROUGH mode is requested again. Hence, the last power-up mode
     * is NOT persistent across boots. On reboot, SIM will power up normally.
     */
    /** @hide */
    public static final int CARD_POWER_DOWN = 0;
    /** @hide */
    public static final int CARD_POWER_UP = 1;
    /** @hide */
    public static final int CARD_POWER_UP_PASS_THROUGH = 2;

    /**
     * Set SIM card power state.
     *
     * @param state  State of SIM (power down, power up, pass through)
     * @see #CARD_POWER_DOWN
     * @see #CARD_POWER_UP
     * @see #CARD_POWER_UP_PASS_THROUGH
     * Callers should monitor for {@link TelephonyIntents#ACTION_SIM_STATE_CHANGED}
     * broadcasts to determine success or failure and timeout if needed.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * {@hide}
     **/
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSimPowerState(int state) {
        setSimPowerStateForSlot(getSlotIndex(), state);
    }

    /**
     * Set SIM card power state.
     *
     * @param slotIndex SIM slot id
     * @param state  State of SIM (power down, power up, pass through)
     * @see #CARD_POWER_DOWN
     * @see #CARD_POWER_UP
     * @see #CARD_POWER_UP_PASS_THROUGH
     * Callers should monitor for {@link TelephonyIntents#ACTION_SIM_STATE_CHANGED}
     * broadcasts to determine success or failure and timeout if needed.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * {@hide}
     **/
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSimPowerStateForSlot(int slotIndex, int state) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setSimPowerStateForSlot(slotIndex, state);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setSimPowerStateForSlot", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#setSimPowerStateForSlot", e);
        }
    }

    /**
     * Set baseband version for the default phone.
     *
     * @param version baseband version
     * @hide
     */
    public void setBasebandVersion(String version) {
        int phoneId = getPhoneId();
        setBasebandVersionForPhone(phoneId, version);
    }

    /**
     * Set baseband version by phone id.
     *
     * @param phoneId for which baseband version is set
     * @param version baseband version
     * @hide
     */
    public void setBasebandVersionForPhone(int phoneId, String version) {
        setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_BASEBAND_VERSION, version);
    }

    /**
     * Get baseband version for the default phone.
     *
     * @return baseband version.
     * @hide
     */
    public String getBasebandVersion() {
        int phoneId = getPhoneId();
        return getBasebandVersionForPhone(phoneId);
    }

    /**
     * Get baseband version for the default phone using the legacy approach.
     * This change was added in P, to ensure backward compatiblity.
     *
     * @return baseband version.
     * @hide
     */
    private String getBasebandVersionLegacy(int phoneId) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            String prop = TelephonyProperties.PROPERTY_BASEBAND_VERSION +
                    ((phoneId == 0) ? "" : Integer.toString(phoneId));
            return SystemProperties.get(prop);
        }
        return null;
    }

    /**
     * Get baseband version by phone id.
     *
     * @return baseband version.
     * @hide
     */
    public String getBasebandVersionForPhone(int phoneId) {
        String version = getBasebandVersionLegacy(phoneId);
        if (version != null && !version.isEmpty()) {
            setBasebandVersionForPhone(phoneId, version);
        }
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_BASEBAND_VERSION, "");
    }

    /**
     * Set phone type for the default phone.
     *
     * @param type phone type
     *
     * @hide
     */
    public void setPhoneType(int type) {
        int phoneId = getPhoneId();
        setPhoneType(phoneId, type);
    }

    /**
     * Set phone type by phone id.
     *
     * @param phoneId for which phone type is set
     * @param type phone type
     *
     * @hide
     */
    public void setPhoneType(int phoneId, int type) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            TelephonyManager.setTelephonyProperty(phoneId,
                    TelephonyProperties.CURRENT_ACTIVE_PHONE, String.valueOf(type));
        }
    }

    /**
     * Get OTASP number schema for the default phone.
     *
     * @param defaultValue default value
     * @return OTA SP number schema
     *
     * @hide
     */
    public String getOtaSpNumberSchema(String defaultValue) {
        int phoneId = getPhoneId();
        return getOtaSpNumberSchemaForPhone(phoneId, defaultValue);
    }

    /**
     * Get OTASP number schema by phone id.
     *
     * @param phoneId for which OTA SP number schema is get
     * @param defaultValue default value
     * @return OTA SP number schema
     *
     * @hide
     */
    public String getOtaSpNumberSchemaForPhone(int phoneId, String defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA, defaultValue);
        }

        return defaultValue;
    }

    /**
     * Get SMS receive capable from system property for the default phone.
     *
     * @param defaultValue default value
     * @return SMS receive capable
     *
     * @hide
     */
    public boolean getSmsReceiveCapable(boolean defaultValue) {
        int phoneId = getPhoneId();
        return getSmsReceiveCapableForPhone(phoneId, defaultValue);
    }

    /**
     * Get SMS receive capable from system property by phone id.
     *
     * @param phoneId for which SMS receive capable is get
     * @param defaultValue default value
     * @return SMS receive capable
     *
     * @hide
     */
    public boolean getSmsReceiveCapableForPhone(int phoneId, boolean defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return Boolean.parseBoolean(TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_SMS_RECEIVE, String.valueOf(defaultValue)));
        }

        return defaultValue;
    }

    /**
     * Get SMS send capable from system property for the default phone.
     *
     * @param defaultValue default value
     * @return SMS send capable
     *
     * @hide
     */
    public boolean getSmsSendCapable(boolean defaultValue) {
        int phoneId = getPhoneId();
        return getSmsSendCapableForPhone(phoneId, defaultValue);
    }

    /**
     * Get SMS send capable from system property by phone id.
     *
     * @param phoneId for which SMS send capable is get
     * @param defaultValue default value
     * @return SMS send capable
     *
     * @hide
     */
    public boolean getSmsSendCapableForPhone(int phoneId, boolean defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return Boolean.parseBoolean(TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_SMS_SEND, String.valueOf(defaultValue)));
        }

        return defaultValue;
    }

    /**
     * Set the alphabetic name of current registered operator.
     * @param name the alphabetic name of current registered operator.
     * @hide
     */
    public void setNetworkOperatorName(String name) {
        int phoneId = getPhoneId();
        setNetworkOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set the alphabetic name of current registered operator.
     * @param phoneId which phone you want to set
     * @param name the alphabetic name of current registered operator.
     * @hide
     */
    public void setNetworkOperatorNameForPhone(int phoneId, String name) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ALPHA, name);
        }
    }

    /**
     * Set the numeric name (MCC+MNC) of current registered operator.
     * @param operator the numeric name (MCC+MNC) of current registered operator
     * @hide
     */
    public void setNetworkOperatorNumeric(String numeric) {
        int phoneId = getPhoneId();
        setNetworkOperatorNumericForPhone(phoneId, numeric);
    }

    /**
     * Set the numeric name (MCC+MNC) of current registered operator.
     * @param phoneId for which phone type is set
     * @param operator the numeric name (MCC+MNC) of current registered operator
     * @hide
     */
    public void setNetworkOperatorNumericForPhone(int phoneId, String numeric) {
        setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, numeric);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    public void setNetworkRoaming(boolean isRoaming) {
        int phoneId = getPhoneId();
        setNetworkRoamingForPhone(phoneId, isRoaming);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param phoneId which phone you want to set
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    public void setNetworkRoamingForPhone(int phoneId, boolean isRoaming) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    isRoaming ? "true" : "false");
        }
    }

    /**
     * Set the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @param iso the ISO country code equivalent of the current registered
     * @hide
     */
    public void setNetworkCountryIso(String iso) {
        int phoneId = getPhoneId();
        setNetworkCountryIsoForPhone(phoneId, iso);
    }

    /**
     * Set the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @param phoneId which phone you want to set
     * @param iso the ISO country code equivalent of the current registered
     * @hide
     */
    public void setNetworkCountryIsoForPhone(int phoneId, String iso) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, iso);
        }
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the
     * phoneId associated with the given subId. Otherwise, applies to the phoneId associated with
     * {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    public void setDataNetworkType(int type) {
        int phoneId = getPhoneId(SubscriptionManager.getDefaultDataSubscriptionId());
        setDataNetworkTypeForPhone(phoneId, type);
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     * @param phoneId which phone you want to set
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    public void setDataNetworkTypeForPhone(int phoneId, int type) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(type));
        }
    }

    /**
     * Returns the subscription ID for the given phone account.
     * @hide
     */
    public int getSubIdForPhoneAccount(PhoneAccount phoneAccount) {
        int retval = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                retval = service.getSubIdForPhoneAccount(phoneAccount);
            }
        } catch (RemoteException e) {
        }

        return retval;
    }

    private int getSubIdForPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        int retval = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            ITelecomService service = getTelecomService();
            if (service != null) {
                retval = getSubIdForPhoneAccount(service.getPhoneAccount(phoneAccountHandle));
            }
        } catch (RemoteException e) {
        }

        return retval;
    }

    /**
     * Resets telephony manager settings back to factory defaults.
     *
     * @hide
     */
    public void factoryReset(int subId) {
        try {
            Log.d(TAG, "factoryReset: subId=" + subId);
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.factoryReset(subId);
        } catch (RemoteException e) {
        }
    }


    /** @hide */
    public String getLocaleFromDefaultSim() {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getLocaleFromDefaultSim();
            }
        } catch (RemoteException ex) {
        }
        return null;
    }

    /**
     * Requests the modem activity info. The recipient will place the result
     * in `result`.
     * @param result The object on which the recipient will send the resulting
     * {@link android.telephony.ModemActivityInfo} object.
     * @hide
     */
    public void requestModemActivityInfo(ResultReceiver result) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.requestModemActivityInfo(result);
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getModemActivityInfo", e);
        }
        result.send(0, null);
    }

    /**
     * Returns the current {@link ServiceState} information.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public ServiceState getServiceState() {
        return getServiceStateForSubscriber(getSubId());
    }

    /**
     * Returns the service state information on specified subscription. Callers require
     * either READ_PRIVILEGED_PHONE_STATE or READ_PHONE_STATE to retrieve the information.
     * @hide
     */
    public ServiceState getServiceStateForSubscriber(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getServiceStateForSubscriber(subId, getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getServiceStateForSubscriber", e);
        }
        return null;
    }

    /**
     * Returns the URI for the per-account voicemail ringtone set in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail ringtone.
     * @return The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     */
    public Uri getVoicemailRingtoneUri(PhoneAccountHandle accountHandle) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getVoicemailRingtoneUri(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getVoicemailRingtoneUri", e);
        }
        return null;
    }

    /**
     * Sets the per-account voicemail ringtone.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}, or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail ringtone.
     * @param uri The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     *
     * @deprecated Use {@link android.provider.Settings#ACTION_CHANNEL_NOTIFICATION_SETTINGS}
     * instead.
     */
    public void setVoicemailRingtoneUri(PhoneAccountHandle phoneAccountHandle, Uri uri) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setVoicemailRingtoneUri(getOpPackageName(), phoneAccountHandle, uri);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setVoicemailRingtoneUri", e);
        }
    }

    /**
     * Returns whether vibration is set for voicemail notification in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail vibration setting.
     * @return {@code true} if the vibration is set for this PhoneAccount, {@code false} otherwise.
     */
    public boolean isVoicemailVibrationEnabled(PhoneAccountHandle accountHandle) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isVoicemailVibrationEnabled(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVoicemailVibrationEnabled", e);
        }
        return false;
    }

    /**
     * Sets the per-account preference whether vibration is enabled for voicemail notifications.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}, or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail vibration setting.
     * @param enabled Whether to enable or disable vibration for voicemail notifications from a
     * specific PhoneAccount.
     *
     * @deprecated Use {@link android.provider.Settings#ACTION_CHANNEL_NOTIFICATION_SETTINGS}
     * instead.
     */
    public void setVoicemailVibrationEnabled(PhoneAccountHandle phoneAccountHandle,
            boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setVoicemailVibrationEnabled(getOpPackageName(), phoneAccountHandle,
                        enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVoicemailVibrationEnabled", e);
        }
    }

    /**
     * Returns carrier id of the current subscription.
     * <p>To recognize a carrier (including MVNO) as a first-class identity, Android assigns each
     * carrier with a canonical integer a.k.a. carrier id. The carrier ID is an Android
     * platform-wide identifier for a carrier. AOSP maintains carrier ID assignments in
     * <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/carrier_list.textpb">here</a>
     *
     * <p>Apps which have carrier-specific configurations or business logic can use the carrier id
     * as an Android platform-wide identifier for carriers.
     *
     * @return Carrier id of the current subscription. Return {@link #UNKNOWN_CARRIER_ID} if the
     * subscription is unavailable or the carrier cannot be identified.
     */
    public int getSimCarrierId() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionCarrierId(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

    /**
     * Returns carrier id name of the current subscription.
     * <p>Carrier id name is a user-facing name of carrier id
     * {@link #getSimCarrierId()}, usually the brand name of the subsidiary
     * (e.g. T-Mobile). Each carrier could configure multiple {@link #getSimOperatorName() SPN} but
     * should have a single carrier name. Carrier name is not a canonical identity,
     * use {@link #getSimCarrierId()} instead.
     * <p>The returned carrier name is unlocalized.
     *
     * @return Carrier name of the current subscription. Return {@code null} if the subscription is
     * unavailable or the carrier cannot be identified.
     */
    public CharSequence getSimCarrierIdName() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionCarrierName(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return null;
    }

    /**
     * Return the application ID for the app type like {@link APPTYPE_CSIM}.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param appType the uicc app type like {@link APPTYPE_CSIM}
     * @return Application ID for specificied app type or null if no uicc or error.
     * @hide
     */
    public String getAidForAppType(int appType) {
        return getAidForAppType(getSubId(), appType);
    }

    /**
     * Return the application ID for the app type like {@link APPTYPE_CSIM}.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param subId the subscription ID that this request applies to.
     * @param appType the uicc app type, like {@link APPTYPE_CSIM}
     * @return Application ID for specificied app type or null if no uicc or error.
     * @hide
     */
    public String getAidForAppType(int subId, int appType) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getAidForAppType(subId, appType);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getAidForAppType", e);
        }
        return null;
    }

    /**
     * Return the Electronic Serial Number.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @return ESN or null if error.
     * @hide
     */
    public String getEsn() {
        return getEsn(getSubId());
    }

    /**
     * Return the Electronic Serial Number.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param subId the subscription ID that this request applies to.
     * @return ESN or null if error.
     * @hide
     */
    public String getEsn(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getEsn(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getEsn", e);
        }
        return null;
    }

    /**
     * Return the Preferred Roaming List Version
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @return PRLVersion or null if error.
     * @hide
     */
    @SystemApi
    public String getCdmaPrlVersion() {
        return getCdmaPrlVersion(getSubId());
    }

    /**
     * Return the Preferred Roaming List Version
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param subId the subscription ID that this request applies to.
     * @return PRLVersion or null if error.
     * @hide
     */
    public String getCdmaPrlVersion(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getCdmaPrlVersion(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getCdmaPrlVersion", e);
        }
        return null;
    }

    /**
     * Get snapshot of Telephony histograms
     * @return List of Telephony histograms
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public List<TelephonyHistogram> getTelephonyHistograms() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getTelephonyHistograms();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getTelephonyHistograms", e);
        }
        return null;
    }

    /**
     * Set the allowed carrier list for slotIndex
     * Require system privileges. In the future we may add this to carrier APIs.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * <p>This method works only on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @return The number of carriers set successfully. Should be length of
     * carrierList on success; -1 if carrierList null or on error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int setAllowedCarriers(int slotIndex, List<CarrierIdentifier> carriers) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.setAllowedCarriers(slotIndex, carriers);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setAllowedCarriers", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error calling ITelephony#setAllowedCarriers", e);
        }
        return -1;
    }

    /**
     * Get the allowed carrier list for slotIndex.
     * Require system privileges. In the future we may add this to carrier APIs.
     *
     * <p>This method returns valid data on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @return List of {@link android.telephony.CarrierIdentifier}; empty list
     * means all carriers are allowed.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public List<CarrierIdentifier> getAllowedCarriers(int slotIndex) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getAllowedCarriers(slotIndex);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getAllowedCarriers", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error calling ITelephony#getAllowedCarriers", e);
        }
        return new ArrayList<CarrierIdentifier>(0);
    }

    /**
     * Action set from carrier signalling broadcast receivers to enable/disable metered apns
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param enabled control enable or disable metered apns.
     * @hide
     */
    public void carrierActionSetMeteredApnsEnabled(int subId, boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionSetMeteredApnsEnabled(subId, enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionSetMeteredApnsEnabled", e);
        }
    }

    /**
     * Action set from carrier signalling broadcast receivers to enable/disable radio
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param enabled control enable or disable radio.
     * @hide
     */
    public void carrierActionSetRadioEnabled(int subId, boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionSetRadioEnabled(subId, enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionSetRadioEnabled", e);
        }
    }

    /**
     * Action set from carrier signalling broadcast receivers to start/stop reporting default
     * network available events
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param report control start/stop reporting network status.
     * @hide
     */
    public void carrierActionReportDefaultNetworkStatus(int subId, boolean report) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionReportDefaultNetworkStatus(subId, report);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionReportDefaultNetworkStatus", e);
        }
    }

    /**
     * Get aggregated video call data usage since boot.
     * Permissions android.Manifest.permission.READ_NETWORK_USAGE_HISTORY is required.
     *
     * @param how one of the NetworkStats.STATS_PER_* constants depending on whether the request is
     * for data usage per uid or overall usage.
     * @return Snapshot of video call data usage
     * @hide
     */
    public NetworkStats getVtDataUsage(int how) {
        boolean perUidStats = (how == NetworkStats.STATS_PER_UID);
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getVtDataUsage(getSubId(), perUidStats);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getVtDataUsage", e);
        }
        return null;
    }

    /**
     * Policy control of data connection. Usually used when data limit is passed.
     * @param enabled True if enabling the data, otherwise disabling.
     * @param subId sub id
     * @hide
     */
    public void setPolicyDataEnabled(boolean enabled, int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setPolicyDataEnabled(enabled, subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setPolicyDataEnabled", e);
        }
    }

    /**
     * Get Client request stats which will contain statistical information
     * on each request made by client.
     * Callers require either READ_PRIVILEGED_PHONE_STATE or
     * READ_PHONE_STATE to retrieve the information.
     * @param subId sub id
     * @return List of Client Request Stats
     * @hide
     */
    public List<ClientRequestStats> getClientRequestStats(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getClientRequestStats(getOpPackageName(), subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getClientRequestStats", e);
        }

        return null;
    }

    /**
     * Check if phone is in emergency callback mode
     * @return true if phone is in emergency callback mode
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean getEmergencyCallbackMode() {
        return getEmergencyCallbackMode(getSubId());
    }

    /**
     * Check if phone is in emergency callback mode
     * @return true if phone is in emergency callback mode
     * @param subId the subscription ID that this action applies to.
     * @hide
     */
    public boolean getEmergencyCallbackMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.getEmergencyCallbackMode(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getEmergencyCallbackMode", e);
        }
        return false;
    }

    /**
     * Get the most recently available signal strength information.
     *
     * Get the most recent SignalStrength information reported by the modem. Due
     * to power saving this information may not always be current.
     * @return the most recent cached signal strength info from the modem
     */
    @Nullable
    public SignalStrength getSignalStrength() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSignalStrength(getSubId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getSignalStrength", e);
        }
        return null;
    }

    /**
     * @hide
     * It's similar to isDataEnabled, but unlike isDataEnabled, this API also evaluates
     * carrierDataEnabled, policyDataEnabled etc to give a final decision of whether mobile data is
     * capable of using.
     */
    public boolean isDataCapable() {
        boolean retVal = false;
        try {
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.isDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataEnabled", e);
        } catch (NullPointerException e) {
        }
        return retVal;
    }

    /**
     * In this mode, modem will not send specified indications when screen is off.
     * @hide
     */
    public static final int INDICATION_UPDATE_MODE_NORMAL                   = 1;

    /**
     * In this mode, modem will still send specified indications when screen is off.
     * @hide
     */
    public static final int INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF        = 2;

    /** @hide */
    @IntDef(prefix = { "INDICATION_UPDATE_MODE_" }, value = {
            INDICATION_UPDATE_MODE_NORMAL,
            INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationUpdateMode{}

    /**
     * The indication for signal strength update.
     * @hide
     */
    public static final int INDICATION_FILTER_SIGNAL_STRENGTH               = 0x1;

    /**
     * The indication for full network state update.
     * @hide
     */
    public static final int INDICATION_FILTER_FULL_NETWORK_STATE            = 0x2;

    /**
     * The indication for data call dormancy changed update.
     * @hide
     */
    public static final int INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED    = 0x4;

    /**
     * The indication for link capacity estimate update.
     * @hide
     */
    public static final int INDICATION_FILTER_LINK_CAPACITY_ESTIMATE        = 0x8;

    /**
     * The indication for physical channel config update.
     * @hide
     */
    public static final int INDICATION_FILTER_PHYSICAL_CHANNEL_CONFIG       = 0x10;

    /** @hide */
    @IntDef(flag = true, prefix = { "INDICATION_FILTER_" }, value = {
            INDICATION_FILTER_SIGNAL_STRENGTH,
            INDICATION_FILTER_FULL_NETWORK_STATE,
            INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED,
            INDICATION_FILTER_LINK_CAPACITY_ESTIMATE,
            INDICATION_FILTER_PHYSICAL_CHANNEL_CONFIG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationFilters{}

    /**
     * Sets radio indication update mode. This can be used to control the behavior of indication
     * update from modem to Android frameworks. For example, by default several indication updates
     * are turned off when screen is off, but in some special cases (e.g. carkit is connected but
     * screen is off) we want to turn on those indications even when the screen is off.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @param filters Indication filters. Should be a bitmask of INDICATION_FILTER_XXX.
     * @see #INDICATION_FILTER_SIGNAL_STRENGTH
     * @see #INDICATION_FILTER_FULL_NETWORK_STATE
     * @see #INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED
     * @param updateMode The voice activation state
     * @see #INDICATION_UPDATE_MODE_NORMAL
     * @see #INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setRadioIndicationUpdateMode(@IndicationFilters int filters,
                                             @IndicationUpdateMode int updateMode) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setRadioIndicationUpdateMode(getSubId(), filters, updateMode);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * A test API to override carrier information including mccmnc, imsi, iccid, gid1, gid2,
     * plmn and spn. This would be handy for, eg, forcing a particular carrier id, carrier's config
     * (also any country or carrier overlays) to be loaded when using a test SIM with a call box.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    public void setCarrierTestOverride(String mccmnc, String imsi, String iccid, String gid1,
            String gid2, String plmn, String spn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setCarrierTestOverride(
                        getSubId(), mccmnc, imsi, iccid, gid1, gid2, plmn, spn);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
    }

    /**
     * A test API to return installed carrier id list version
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    public int getCarrierIdListVersion() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCarrierIdListVersion(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID_LIST_VERSION;
    }
}
