/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * Constants used in SMS Cell Broadcast messages.
 *
 * {@hide}
 */
public interface SmsCbConstants {
    /** Start of PWS Message Identifier range (includes ETWS and CMAS). */
    public static final int MESSAGE_ID_PWS_FIRST_IDENTIFIER = 0x1100;

    /** Bitmask for messages of ETWS type (including future extensions). */
    public static final int MESSAGE_ID_ETWS_TYPE_MASK       = 0xFFF8;

    /** Value for messages of ETWS type after applying {@link #MESSAGE_ID_ETWS_TYPE_MASK}. */
    public static final int MESSAGE_ID_ETWS_TYPE            = 0x1100;

    /** ETWS Message Identifier for earthquake warning message. */
    public static final int MESSAGE_ID_ETWS_EARTHQUAKE_WARNING      = 0x1100;

    /** ETWS Message Identifier for tsunami warning message. */
    public static final int MESSAGE_ID_ETWS_TSUNAMI_WARNING         = 0x1101;

    /** ETWS Message Identifier for earthquake and tsunami combined warning message. */
    public static final int MESSAGE_ID_ETWS_EARTHQUAKE_AND_TSUNAMI_WARNING  = 0x1102;

    /** ETWS Message Identifier for test message. */
    public static final int MESSAGE_ID_ETWS_TEST_MESSAGE            = 0x1103;

    /** ETWS Message Identifier for messages related to other emergency types. */
    public static final int MESSAGE_ID_ETWS_OTHER_EMERGENCY_TYPE    = 0x1104;

    /** Start of CMAS Message Identifier range. */
    public static final int MESSAGE_ID_CMAS_FIRST_IDENTIFIER                = 0x1112;

    /** CMAS Message Identifier for Presidential Level alerts. */
    public static final int MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL        = 0x1112;

    /** CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED = 0x1113;

    /** CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY  = 0x1114;

    /** CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED = 0x1115;

    /** CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY   = 0x1116;

    /** CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED = 0x1117;

    /** CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY   = 0x1118;

    /** CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED  = 0x1119;

    /** CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY    = 0x111A;

    /** CMAS Message Identifier for Child Abduction Emergency (Amber Alert). */
    public static final int MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY = 0x111B;

    /** CMAS Message Identifier for the Required Monthly Test. */
    public static final int MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST     = 0x111C;

    /** CMAS Message Identifier for CMAS Exercise. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXERCISE                  = 0x111D;

    /** CMAS Message Identifier for operator defined use. */
    public static final int MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE      = 0x111E;

    /** End of CMAS Message Identifier range (including future extensions). */
    public static final int MESSAGE_ID_CMAS_LAST_IDENTIFIER                 = 0x112F;

    /** End of PWS Message Identifier range (includes ETWS, CMAS, and future extensions). */
    public static final int MESSAGE_ID_PWS_LAST_IDENTIFIER                  = 0x18FF;

    /** ETWS message code flag to activate the popup display. */
    public static final int MESSAGE_CODE_ETWS_ACTIVATE_POPUP                = 0x100;

    /** ETWS message code flag to activate the emergency user alert. */
    public static final int MESSAGE_CODE_ETWS_EMERGENCY_USER_ALERT          = 0x200;

    /** ETWS warning type value for earthquake. */
    public static final int ETWS_WARNING_TYPE_EARTHQUAKE                    = 0x00;

    /** ETWS warning type value for tsunami. */
    public static final int ETWS_WARNING_TYPE_TSUNAMI                       = 0x01;

    /** ETWS warning type value for earthquake and tsunami. */
    public static final int ETWS_WARNING_TYPE_EARTHQUAKE_AND_TSUNAMI        = 0x02;

    /** ETWS warning type value for test broadcast. */
    public static final int ETWS_WARNING_TYPE_TEST                          = 0x03;

    /** ETWS warning type value for other notifications. */
    public static final int ETWS_WARNING_TYPE_OTHER                         = 0x04;
}
