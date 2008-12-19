/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * Contains a list of string constants used to get or set telephone properties
 * in the system. You can use {@link android.os.SystemProperties os.SystemProperties}
 * to get and set these values.
 * @hide
 */
public interface TelephonyProperties
{
    //****** Baseband and Radio Interface version

    /** 
     * Baseband version 
     * Availability: property is available any time radio is on
     */
    static final String PROPERTY_BASEBAND_VERSION = "gsm.version.baseband";

    /** Radio Interface Layer (RIL) library implementation. */
    static final String PROPERTY_RIL_IMPL = "gsm.version.ril-impl";

    //****** Current Network

    /** Alpha name of current registered operator.
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_ALPHA = "gsm.operator.alpha";

    /** Numeric name (MCC+MNC) of current registered operator.
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_NUMERIC = "gsm.operator.numeric";

    /** 'true' if the device is considered roaming on this network for GSM
     *  purposes.
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_ISROAMING = "gsm.operator.isroaming";

    /** The ISO country code equivalent of the current registered operator's
     *  MCC (Mobile Country Code)
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_ISO_COUNTRY = "gsm.operator.iso-country";

    //****** SIM Card
    /** 
     * One of <code>"UNKNOWN"</code> <code>"ABSENT"</code> <code>"PIN_REQUIRED"</code>
     * <code>"PUK_REQUIRED"</code> <code>"NETWORK_LOCKED"</code> or <code>"READY"</code>
     */
    static String PROPERTY_SIM_STATE = "gsm.sim.state";

    /** The MCC+MNC (mobile country code+mobile network code) of the
     *  provider of the SIM. 5 or 6 decimal digits.
     *  Availablity: SIM state must be "READY"
     */
    static String PROPERTY_SIM_OPERATOR_NUMERIC = "gsm.sim.operator.numeric";

    /** PROPERTY_SIM_OPERATOR_ALPHA is also known as the SPN, or Service Provider Name. 
     *  Availablity: SIM state must be "READY"
     */
    static String PROPERTY_SIM_OPERATOR_ALPHA = "gsm.sim.operator.alpha";

    /** ISO country code equivalent for the SIM provider's country code*/
    static String PROPERTY_SIM_OPERATOR_ISO_COUNTRY = "gsm.sim.operator.iso-country";

    /**
     * Indicates the available radio technology.  Values include: <code>"unknown"</code>,
     * <code>"GPRS"</code>, <code>"EDGE"</code> and <code>"UMTS"</code>.
     */
    static String PROPERTY_DATA_NETWORK_TYPE = "gsm.network.type";

}
