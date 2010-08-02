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

package com.android.internal.telephony;

/**
 * Interface used to retrieve various phone-related subscriber information.
 *
 */
interface IPhoneSubInfo {

    /**
     * Retrieves the unique device ID, e.g., IMEI for GSM phones.
     */
    String getDeviceId();

    /**
     * Retrieves the software version number for the device, e.g., IMEI/SV
     * for GSM phones.
     */
    String getDeviceSvn();

    /**
     * Retrieves the unique sbuscriber ID, e.g., IMSI for GSM phones.
     */
    String getSubscriberId();

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    String getIccSerialNumber();

    /**
     * Retrieves the phone number string for line 1.
     */
    String getLine1Number();

    /**
     * Retrieves the alpha identifier for line 1.
     */
    String getLine1AlphaTag();

    /**
     * Retrieves the voice mail number.
     */
    String getVoiceMailNumber();

    /**
     * Retrieves the complete voice mail number.
     */
    String getCompleteVoiceMailNumber();

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    String getVoiceMailAlphaTag();
}
