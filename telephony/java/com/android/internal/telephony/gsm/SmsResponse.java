/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Object returned by the RIL upon successful completion of sendSMS.
 * Contains message reference and ackPdu.
 *
 */
class SmsResponse {
    /** Message reference of the just-sent SMS. */
    int messageRef;
    /** ackPdu for the just-sent SMS. */
    String ackPdu;

    SmsResponse(int messageRef, String ackPdu) {
        this.messageRef = messageRef;
        this.ackPdu = ackPdu;
    }
}
