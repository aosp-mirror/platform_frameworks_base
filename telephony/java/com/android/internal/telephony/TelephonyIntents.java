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

package com.android.internal.telephony;

import android.content.Intent;
import android.telephony.SubscriptionManager;

/**
 * The intents that the telephony services broadcast.
 *
 * <p class="warning">
 * THESE ARE NOT THE API!  Use the {@link android.telephony.TelephonyManager} class.
 * DON'T LISTEN TO THESE DIRECTLY.
 */
public class TelephonyIntents {

    /**
     * Broadcast Action: The phone service state has changed. The intent will have the following
     * extra values:</p>
     * <ul>
     *   <li><em>state</em> - An int with one of the following values:
     *          {@link android.telephony.ServiceState#STATE_IN_SERVICE},
     *          {@link android.telephony.ServiceState#STATE_OUT_OF_SERVICE},
     *          {@link android.telephony.ServiceState#STATE_EMERGENCY_ONLY}
     *          or {@link android.telephony.ServiceState#STATE_POWER_OFF}
     *   <li><em>roaming</em> - A boolean value indicating whether the phone is roaming.</li>
     *   <li><em>operator-alpha-long</em> - The carrier name as a string.</li>
     *   <li><em>operator-alpha-short</em> - A potentially shortened version of the carrier name,
     *          as a string.</li>
     *   <li><em>operator-numeric</em> - A number representing the carrier, as a string. This is
     *          a five or six digit number consisting of the MCC (Mobile Country Code, 3 digits)
     *          and MNC (Mobile Network code, 2-3 digits).</li>
     *   <li><em>manual</em> - A boolean, where true indicates that the user has chosen to select
     *          the network manually, and false indicates that network selection is handled by the
     *          phone.</li>
     * </ul>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     * @deprecated use {@link Intent#ACTION_SERVICE_STATE}
     */
    public static final String ACTION_SERVICE_STATE_CHANGED = Intent.ACTION_SERVICE_STATE;

    /**
     * <p>Broadcast Action: The radio technology has changed. The intent will have the following
     * extra values:</p>
     * <ul>
     *   <li><em>phoneName</em> - A string version of the new phone name.</li>
     * </ul>
     *
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">
     * Requires no permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_RADIO_TECHNOLOGY_CHANGED
            = "android.intent.action.RADIO_TECHNOLOGY";

    /**
     * <p>Broadcast Action: The emergency callback mode is changed.
     * <ul>
     *   <li><em>phoneinECMState</em> - A boolean value,true=phone in ECM, false=ECM off</li>
     * </ul>
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">
     * Requires no permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_EMERGENCY_CALLBACK_MODE_CHANGED
            = "android.intent.action.EMERGENCY_CALLBACK_MODE_CHANGED";

    /**
     * <p>Broadcast Action: The emergency call state is changed.
     * <ul>
     *   <li><em>phoneInEmergencyCall</em> - A boolean value, true if phone in emergency call,
     *   false otherwise</li>
     * </ul>
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">
     * Requires no permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_EMERGENCY_CALL_STATE_CHANGED
            = "android.intent.action.EMERGENCY_CALL_STATE_CHANGED";

    /**
     * Broadcast Action: The phone's signal strength has changed. The intent will have the
     * following extra values:</p>
     * <ul>
     *   <li><em>phoneName</em> - A string version of the phone name.</li>
     *   <li><em>asu</em> - A numeric value for the signal strength.
     *          An ASU is 0-31 or -1 if unknown (for GSM, dBm = -113 - 2 * asu).
     *          The following special values are defined:
     *          <ul><li>0 means "-113 dBm or less".</li><li>31 means "-51 dBm or greater".</li></ul>
     *   </li>
     * </ul>
     *
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by exlicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SIGNAL_STRENGTH_CHANGED = "android.intent.action.SIG_STR";


    /**
     * Broadcast Action: The data connection state has changed for any one of the
     * phone's mobile data connections (eg, default, MMS or GPS specific connection).
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>phoneName</dt><dd>A string version of the phone name.</dd>
     *   <dt>state</dt><dd>One of {@code CONNECTED}, {@code CONNECTING},
     *      or {@code DISCONNECTED}.</dd>
     *   <dt>apn</dt><dd>A string that is the APN associated with this connection.</dd>
     *   <dt>apnType</dt><dd>A string array of APN types associated with this connection.
     *      The APN type {@code *} is a special type that means this APN services all types.</dd>
     * </dl>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_ANY_DATA_CONNECTION_STATE_CHANGED
            = "android.intent.action.ANY_DATA_STATE";

    /**
     * Broadcast Action: An attempt to establish a data connection has failed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>phoneName</dt><dd>A string version of the phone name.</dd>
     *   <dt>state</dt><dd>One of {@code CONNECTED}, {@code CONNECTING}, or {code DISCONNECTED}.</dd>
     *   <dt>reason</dt><dd>A string indicating the reason for the failure, if available.</dd>
     * </dl>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_DATA_CONNECTION_FAILED
            = "android.intent.action.DATA_CONNECTION_FAILED";

    /**
     * Broadcast Action: The sim card state has changed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>phoneName</dt><dd>A string version of the phone name.</dd>
     *   <dt>ss</dt><dd>The sim state. One of:
     *     <dl>
     *       <dt>{@code ABSENT}</dt><dd>SIM card not found</dd>
     *       <dt>{@code LOCKED}</dt><dd>SIM card locked (see {@code reason})</dd>
     *       <dt>{@code READY}</dt><dd>SIM card ready</dd>
     *       <dt>{@code IMSI}</dt><dd>FIXME: what is this state?</dd>
     *       <dt>{@code LOADED}</dt><dd>SIM card data loaded</dd>
     *     </dl></dd>
     *   <dt>reason</dt><dd>The reason why ss is {@code LOCKED}; null otherwise.</dd>
     *   <dl>
     *       <dt>{@code PIN}</dt><dd>locked on PIN1</dd>
     *       <dt>{@code PUK}</dt><dd>locked on PUK1</dd>
     *       <dt>{@code NETWORK}</dt><dd>locked on network personalization</dd>
     *   </dl>
     *   <dt>rebroadcastOnUnlock</dt>
     *   <dd>A boolean indicates a rebroadcast on unlock. optional extra, defaults to {@code false}
     *   if not specified </dd>
     * </dl>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SIM_STATE_CHANGED
            = Intent.ACTION_SIM_STATE_CHANGED;

    public static final String EXTRA_REBROADCAST_ON_UNLOCK= "rebroadcastOnUnlock";

    /**
     * Broadcast Action: The time was set by the carrier (typically by the NITZ string).
     * This is a sticky broadcast.
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li><em>time</em> - The time as a long in UTC milliseconds.</li>
     * </ul>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_NETWORK_SET_TIME = "android.intent.action.NETWORK_SET_TIME";


    /**
     * Broadcast Action: The timezone was set by the carrier (typically by the NITZ string).
     * This is a sticky broadcast.
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li><em>time-zone</em> - The java.util.TimeZone.getID() value identifying the new time
     *          zone.</li>
     * </ul>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_NETWORK_SET_TIMEZONE
            = "android.intent.action.NETWORK_SET_TIMEZONE";

    /**
     * <p>Broadcast Action: It indicates the Emergency callback mode blocks datacall/sms
     * <p class="note">.
     * This is to pop up a notice to show user that the phone is in emergency callback mode
     * and atacalls and outgoing sms are blocked.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS
            = "com.android.internal.intent.action.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS";

    /**
     * <p>Broadcast Action: Indicates that the action is forbidden by network.
     * <p class="note">
     * This is for the OEM applications to understand about possible provisioning issues.
     * Used in OMA-DM applications.
     */
    public static final String ACTION_FORBIDDEN_NO_SERVICE_AUTHORIZATION
            = "com.android.internal.intent.action.ACTION_FORBIDDEN_NO_SERVICE_AUTHORIZATION";

    /**
     * Broadcast Action: A "secret code" has been entered in the dialer. Secret codes are
     * of the form {@code *#*#<code>#*#*}. The intent will have the data URI:
     *
     * {@code android_secret_code://<code>}
     */
    public static final String SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE";

    /**
     * Broadcast Action: The Service Provider string(s) have been updated.  Activities or
     * services that use these strings should update their display.
     * The intent will have the following extra values:</p>
     *
     * <dl>
     *   <dt>showPlmn</dt><dd>Boolean that indicates whether the PLMN should be shown.</dd>
     *   <dt>plmn</dt><dd>The operator name of the registered network, as a string.</dd>
     *   <dt>showSpn</dt><dd>Boolean that indicates whether the SPN should be shown.</dd>
     *   <dt>spn</dt><dd>The service provider name, as a string.</dd>
     * </dl>
     *
     * Note that <em>showPlmn</em> may indicate that <em>plmn</em> should be displayed, even
     * though the value for <em>plmn</em> is null.  This can happen, for example, if the phone
     * has not registered to a network yet.  In this case the receiver may substitute an
     * appropriate placeholder string (eg, "No service").
     *
     * It is recommended to display <em>plmn</em> before / above <em>spn</em> if
     * both are displayed.
     *
     * <p>Note: this is a protected intent that can only be sent by the system.
     */
    public static final String SPN_STRINGS_UPDATED_ACTION =
            "android.provider.Telephony.SPN_STRINGS_UPDATED";

    public static final String EXTRA_SHOW_PLMN  = "showPlmn";
    public static final String EXTRA_PLMN       = "plmn";
    public static final String EXTRA_SHOW_SPN   = "showSpn";
    public static final String EXTRA_SPN        = "spn";
    public static final String EXTRA_DATA_SPN   = "spnData";

    /**
     * <p>Broadcast Action: It indicates one column of a subinfo record has been changed
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SUBINFO_CONTENT_CHANGE
            = "android.intent.action.ACTION_SUBINFO_CONTENT_CHANGE";

    /**
     * <p>Broadcast Action: It indicates subinfo record update is completed
     * when SIM inserted state change
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SUBINFO_RECORD_UPDATED
            = "android.intent.action.ACTION_SUBINFO_RECORD_UPDATED";

    /**
     * Broadcast Action: The default subscription has changed.  This has the following
     * extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current default subscription.</li>
     * </ul>
     * @deprecated Use {@link SubscriptionManager#ACTION_DEFAULT_SUBSCRIPTION_CHANGED}
     */
    @Deprecated
    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED
            = SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED;

    /**
     * Broadcast Action: The default data subscription has changed.  This has the following
     * extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current data default subscription.</li>
     * </ul>
     */
    public static final String ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED
            = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: The default voice subscription has changed.  This has the following
     * extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current voice default subscription.</li>
     * </ul>
     */
    public static final String ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED
            = "android.intent.action.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: The default sms subscription has changed.  This has the following
     * extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current sms default subscription.</li>
     * </ul>
     * @deprecated Use {@link SubscriptionManager#ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED}
     */
    @Deprecated
    public static final String ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED
            = SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED;

    /*
     * Broadcast Action: An attempt to set phone radio type and access technology has changed.
     * This has the following extra values:
     * <ul>
     *   <li><em>phones radio access family </em> - A RadioAccessFamily
     *   array, contain phone ID and new radio access family for each phone.</li>
     * </ul>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     */
    public static final String ACTION_SET_RADIO_CAPABILITY_DONE =
            "android.intent.action.ACTION_SET_RADIO_CAPABILITY_DONE";

    public static final String EXTRA_RADIO_ACCESS_FAMILY = "rafs";

    /*
     * Broadcast Action: An attempt to set phone radio access family has failed.
     */
    public static final String ACTION_SET_RADIO_CAPABILITY_FAILED =
            "android.intent.action.ACTION_SET_RADIO_CAPABILITY_FAILED";

    /**
     * <p>Broadcast Action: when data connections get redirected with validation failure.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>apnType</li><dd>A string with the apn type.</dd>
     *   <li>redirectionUrl</li><dd>redirection url string</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     */
    public static final String ACTION_CARRIER_SIGNAL_REDIRECTED =
            "com.android.internal.telephony.CARRIER_SIGNAL_REDIRECTED";
    /**
     * <p>Broadcast Action: when data connections setup fails.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>apnType</li><dd>A string with the apn type.</dd>
     *   <li>errorCode</li><dd>A integer with dataFailCause.</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     */
    public static final String ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED =
            "com.android.internal.telephony.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED";

    /**
     * <p>Broadcast Action: when pco value is available.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>apnType</li><dd>A string with the apn type.</dd>
     *   <li>apnProto</li><dd>A string with the protocol of the apn connection (IP,IPV6,
     *                        IPV4V6)</dd>
     *   <li>pcoId</li><dd>An integer indicating the pco id for the data.</dd>
     *   <li>pcoValue</li><dd>A byte array of pco data read from modem.</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     */
    public static final String ACTION_CARRIER_SIGNAL_PCO_VALUE =
            "com.android.internal.telephony.CARRIER_SIGNAL_PCO_VALUE";

    /**
     * <p>Broadcast Action: when system default network available/unavailable with
     * carrier-disabled mobile data. Intended for carrier apps to set/reset carrier actions when
     * other network becomes system default network, Wi-Fi for example.
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>defaultNetworkAvailable</li><dd>A boolean indicates default network available.</dd>
     *   <li>subId</li><dd>Sub Id which associated the default data.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     */
    public static final String ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE =
            "com.android.internal.telephony.CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE";

    /**
     * <p>Broadcast Action: when framework reset all carrier actions on sim load or absent.
     * intended for carrier apps clean up (clear UI e.g.) and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     */
    public static final String ACTION_CARRIER_SIGNAL_RESET =
            "com.android.internal.telephony.CARRIER_SIGNAL_RESET";

    // CARRIER_SIGNAL_ACTION extra keys
    public static final String EXTRA_REDIRECTION_URL_KEY = "redirectionUrl";
    public static final String EXTRA_ERROR_CODE_KEY = "errorCode";
    public static final String EXTRA_APN_TYPE_KEY = "apnType";
    public static final String EXTRA_APN_PROTO_KEY = "apnProto";
    public static final String EXTRA_PCO_ID_KEY = "pcoId";
    public static final String EXTRA_PCO_VALUE_KEY = "pcoValue";
    public static final String EXTRA_DEFAULT_NETWORK_AVAILABLE_KEY = "defaultNetworkAvailable";

    /**
     * Broadcast action to trigger CI OMA-DM Session.
     */
    public static final String ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE =
            "com.android.omadm.service.CONFIGURATION_UPDATE";

    /**
     * Broadcast action to trigger the Carrier Certificate download.
     */
    public static final String ACTION_CARRIER_CERTIFICATE_DOWNLOAD =
            "com.android.internal.telephony.ACTION_CARRIER_CERTIFICATE_DOWNLOAD";

    /**
     * Broadcast action to indicate an error related to Line1Number has been detected.
     *
     * Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * @hide
     */
    public static final String ACTION_LINE1_NUMBER_ERROR_DETECTED =
            "com.android.internal.telephony.ACTION_LINE1_NUMBER_ERROR_DETECTED";

    /**
     * Broadcast action to notify radio bug.
     *
     * Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * @hide
     */
    public static final String ACTION_REPORT_RADIO_BUG =
            "com.android.internal.telephony.ACTION_REPORT_RADIO_BUG";

    // ACTION_REPORT_RADIO_BUG extra keys
    public static final String EXTRA_SLOT_ID = "slotId";
    public static final String EXTRA_RADIO_BUG_TYPE = "radioBugType";
}
