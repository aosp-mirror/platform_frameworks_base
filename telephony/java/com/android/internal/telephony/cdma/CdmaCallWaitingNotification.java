/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.util.Log;
import com.android.internal.telephony.Connection;

/**
 * Represents a Supplementary Service Notification received from the network.
 *
 * {@hide}
 */
public class CdmaCallWaitingNotification {
    static final String LOG_TAG = "CDMA";
    public String number = null;
    public int numberPresentation = 0;
    public String name = null;
    public int namePresentation = 0;
    public int numberType = 0;
    public int numberPlan = 0;
    public int isPresent = 0;
    public int signalType = 0;
    public int alertPitch = 0;
    public int signal = 0;

    public String toString()
    {
        return super.toString() + "Call Waiting Notification  "
            + " number: " + number
            + " numberPresentation: " + numberPresentation
            + " name: " + name
            + " namePresentation: " + namePresentation
            + " numberType: " + numberType
            + " numberPlan: " + numberPlan
            + " isPresent: " + isPresent
            + " signalType: " + signalType
            + " alertPitch: " + alertPitch
            + " signal: " + signal ;
    }

    public static int
    presentationFromCLIP(int cli)
    {
        switch(cli) {
            case 0: return Connection.PRESENTATION_ALLOWED;
            case 1: return Connection.PRESENTATION_RESTRICTED;
            case 2: return Connection.PRESENTATION_UNKNOWN;
            default:
                // This shouldn't happen, just log an error and treat as Unknown
                Log.d(LOG_TAG, "Unexpected presentation " + cli);
                return Connection.PRESENTATION_UNKNOWN;
        }
    }
}
