/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

/**
 * Constants used in SMS Cell Broadcast messages (see 3GPP TS 23.041). This class is used by the
 * boot-time broadcast channel enable and database upgrade code in CellBroadcastReceiver, so it
 * is public, but should be avoided in favor of the radio technology independent constants in
 * {@link android.telephony.SmsCbMessage}, {@link android.telephony.SmsCbEtwsInfo}, and
 * {@link android.telephony.SmsCbCmasInfo} classes.
 *
 * {@hide}
 */
public class SmsCbConstants {

    /** Private constructor for utility class. */
    private SmsCbConstants() { }

    /** Start of PWS Message Identifier range (includes ETWS and CMAS). */
    public static final int MESSAGE_ID_PWS_FIRST_IDENTIFIER
            = 0x1100; // 4352

    /** Bitmask for messages of ETWS type (including future extensions). */
    public static final int MESSAGE_ID_ETWS_TYPE_MASK
            = 0xFFF8;

    /** Value for messages of ETWS type after applying {@link #MESSAGE_ID_ETWS_TYPE_MASK}. */
    public static final int MESSAGE_ID_ETWS_TYPE
            = 0x1100; // 4352

    /** ETWS Message Identifier for earthquake warning message. */
    public static final int MESSAGE_ID_ETWS_EARTHQUAKE_WARNING
            = 0x1100; // 4352

    /** ETWS Message Identifier for tsunami warning message. */
    public static final int MESSAGE_ID_ETWS_TSUNAMI_WARNING
            = 0x1101; // 4353

    /** ETWS Message Identifier for earthquake and tsunami combined warning message. */
    public static final int MESSAGE_ID_ETWS_EARTHQUAKE_AND_TSUNAMI_WARNING
            = 0x1102; // 4354

    /** ETWS Message Identifier for test message. */
    public static final int MESSAGE_ID_ETWS_TEST_MESSAGE
            = 0x1103; // 4355

    /** ETWS Message Identifier for messages related to other emergency types. */
    public static final int MESSAGE_ID_ETWS_OTHER_EMERGENCY_TYPE
            = 0x1104; // 4356

    /** Start of CMAS Message Identifier range. */
    public static final int MESSAGE_ID_CMAS_FIRST_IDENTIFIER
            = 0x1112; // 4370

    /** CMAS Message Identifier for Presidential Level alerts. */
    public static final int MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL
            = 0x1112; // 4370

    /** CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED
            = 0x1113; // 4371

    /** CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY
            = 0x1114; // 4372

    /** CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED
            = 0x1115; // 4373

    /** CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY
            = 0x1116; // 4374

    /** CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED
            = 0x1117; // 4375

    /** CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY
            = 0x1118; // 4376

    /** CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Observed. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED
            = 0x1119; // 4377

    /** CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Likely. */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY
            = 0x111A; // 4378

    /** CMAS Message Identifier for Child Abduction Emergency (Amber Alert). */
    public static final int MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY
            = 0x111B; // 4379

    /** CMAS Message Identifier for the Required Monthly Test. */
    public static final int MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST
            = 0x111C; // 4380

    /** CMAS Message Identifier for CMAS Exercise. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXERCISE
            = 0x111D; // 4381

    /** CMAS Message Identifier for operator defined use. */
    public static final int MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE
            = 0x111E; // 4382

    /**
     * CMAS Message Identifier for Presidential Level alerts for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL_LANGUAGE
            = 0x111F; // 4383

    /**
     * CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Observed
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE
            = 0x1120; // 4384

    /**
     * CMAS Message Identifier for Extreme alerts, Urgency=Immediate, Certainty=Likely
     *  for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE
            = 0x1121; // 4385

    /**
     * CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Observed
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE
            = 0x1122; // 4386

    /**
     * CMAS Message Identifier for Extreme alerts, Urgency=Expected, Certainty=Likely
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE
            = 0x1123; // 4387

    /**
     * CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Observed
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE
            = 0x1124; // 4388

    /**
     * CMAS Message Identifier for Severe alerts, Urgency=Immediate, Certainty=Likely
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE
            = 0x1125; // 4389

    /**
     * CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Observed
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE
            = 0x1126; // 4390

    /**
     * CMAS Message Identifier for Severe alerts, Urgency=Expected, Certainty=Likely
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE
            = 0x1127; // 4391

    /**
     * CMAS Message Identifier for Child Abduction Emergency (Amber Alert)
     * for additional languages.
     */
    public static final int MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY_LANGUAGE
            = 0x1128; // 4392

    /** CMAS Message Identifier for the Required Monthly Test  for additional languages. */
    public static final int MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST_LANGUAGE
            = 0x1129; // 4393

    /** CMAS Message Identifier for CMAS Exercise for additional languages. */
    public static final int MESSAGE_ID_CMAS_ALERT_EXERCISE_LANGUAGE
            = 0x112A; // 4394

    /** CMAS Message Identifier for operator defined use for additional languages. */
    public static final int MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE_LANGUAGE
            = 0x112B; // 4395

    /** CMAS Message Identifier for CMAS Public Safety Alerts. */
    public static final int MESSAGE_ID_CMAS_ALERT_PUBLIC_SAFETY
            = 0x112C; // 4396

    /** CMAS Message Identifier for CMAS Public Safety Alerts for additional languages. */
    public static final int MESSAGE_ID_CMAS_ALERT_PUBLIC_SAFETY_LANGUAGE
            = 0x112D; // 4397

    /** CMAS Message Identifier for CMAS State/Local Test. */
    public static final int MESSAGE_ID_CMAS_ALERT_STATE_LOCAL_TEST
            = 0x112E; // 4398

    /** CMAS Message Identifier for CMAS State/Local Test for additional languages. */
    public static final int MESSAGE_ID_CMAS_ALERT_STATE_LOCAL_TEST_LANGUAGE
            = 0x112F; // 4399

    /** End of CMAS Message Identifier range (including future extensions). */
    public static final int MESSAGE_ID_CMAS_LAST_IDENTIFIER
            = 0x112F; // 4399

    /** End of PWS Message Identifier range (includes ETWS, CMAS, and future extensions). */
    public static final int MESSAGE_ID_PWS_LAST_IDENTIFIER
            = 0x18FF; // 6399

    /** ETWS serial number flag to activate the popup display. */
    public static final int SERIAL_NUMBER_ETWS_ACTIVATE_POPUP
            = 0x1000; // 4096

    /** ETWS serial number flag to activate the emergency user alert. */
    public static final int SERIAL_NUMBER_ETWS_EMERGENCY_USER_ALERT
            = 0x2000; // 8192
}
