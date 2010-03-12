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
     */
    public static final String ACTION_SERVICE_STATE_CHANGED = "android.intent.action.SERVICE_STATE";

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
     * <ul>
     *   <li><em>phoneName</em> - A string version of the phone name.</li>
     *   <li><em>state</em> - One of <code>"CONNECTED"</code>
     *      <code>"CONNECTING"</code> or <code>"DISCONNNECTED"</code></li>
     *   <li><em>apn</em> - A string that is the APN associated with this
     *      connection.</li>
     *   <li><em>apnType</em> - A string array of APN types associated with
     *      this connection.  The APN type <code>"*"</code> is a special
     *      type that means this APN services all types.</li>
     * </ul>
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
     * <ul>
     *   <li><em>phoneName</em> &mdash A string version of the phone name.</li>
     *   <li><em>state</em> &mdash; One of <code>"CONNECTED"</code>
     *      <code>"CONNECTING"</code> or <code>"DISCONNNECTED"</code></li>
     * <li><em>reason</em> &mdash; A string indicating the reason for the failure, if available</li>
     * </ul>
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
     * <ul>
     *   <li><em>phoneName</em> - A string version of the phone name.</li>
     *   <li><em>ss</em> - The sim state.  One of
     *   <code>"ABSENT"</code> <code>"LOCKED"</code>
     *   <code>"READY"</code> <code>"ISMI"</code> <code>"LOADED"</code> </li>
     *   <li><em>reason</em> - The reason while ss is LOCKED, otherwise is null
     *   <code>"PIN"</code> locked on PIN1
     *   <code>"PUK"</code> locked on PUK1
     *   <code>"NETWORK"</code> locked on Network Personalization </li>
     * </ul>
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     * 
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_SIM_STATE_CHANGED
            = "android.intent.action.SIM_STATE_CHANGED";


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
            = "android.intent.action.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS";
}
