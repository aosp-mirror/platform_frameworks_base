/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony.satellite.stub;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * @hide
 */
public class SatelliteImplBase {
    private static final String TAG = "SatelliteImplBase";

    /**@hide*/
    @IntDef(
            prefix = "TECHNOLOGY_",
            value = {
                    TECHNOLOGY_NB_IOT_NTN,
                    TECHNOLOGY_NR_NTN,
                    TECHNOLOGY_EMTC_NTN,
                    TECHNOLOGY_PROPRIETARY
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NTRadioTechnology {}

    /** 3GPP NB-IoT (Narrowband Internet of Things) over Non-Terrestrial-Networks technology */
    public static final int TECHNOLOGY_NB_IOT_NTN =
            android.hardware.radio.satellite.NTRadioTechnology.NB_IOT_NTN;
    /** 3GPP 5G NR over Non-Terrestrial-Networks technology */
    public static final int TECHNOLOGY_NR_NTN =
            android.hardware.radio.satellite.NTRadioTechnology.NR_NTN;
    /** 3GPP eMTC (enhanced Machine-Type Communication) over Non-Terrestrial-Networks technology */
    public static final int TECHNOLOGY_EMTC_NTN =
            android.hardware.radio.satellite.NTRadioTechnology.EMTC_NTN;
    /** Proprietary technology like Iridium or Bullitt */
    public static final int TECHNOLOGY_PROPRIETARY =
            android.hardware.radio.satellite.NTRadioTechnology.PROPRIETARY;

    /**@hide*/
    @IntDef(
            prefix = "FEATURE_",
            value = {
                    FEATURE_SOS_SMS,
                    FEATURE_EMERGENCY_SMS,
                    FEATURE_SMS,
                    FEATURE_LOCATION_SHARING,
                    FEATURE_UNKNOWN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Feature {}

    /** Able to send and receive SMS messages to/from SOS numbers like call/service centers */
    public static final int FEATURE_SOS_SMS =
            android.hardware.radio.satellite.SatelliteFeature.SOS_SMS;
    /** Able to send and receive SMS messages to/from emergency numbers like 911 */
    public static final int FEATURE_EMERGENCY_SMS =
            android.hardware.radio.satellite.SatelliteFeature.EMERGENCY_SMS;
    /** Able to send and receive SMS messages to/from any allowed contacts */
    public static final int FEATURE_SMS = android.hardware.radio.satellite.SatelliteFeature.SMS;
    /** Able to send device location to allowed contacts */
    public static final int FEATURE_LOCATION_SHARING =
            android.hardware.radio.satellite.SatelliteFeature.LOCATION_SHARING;
    /** This feature is not defined in satellite HAL APIs */
    public static final int FEATURE_UNKNOWN = 0xFFFF;

    /**@hide*/
    @IntDef(
            prefix = "MODE_",
            value = {
                    MODE_POWERED_OFF,
                    MODE_OUT_OF_SERVICE_NOT_SEARCHING,
                    MODE_OUT_OF_SERVICE_SEARCHING,
                    MODE_ACQUIRED,
                    MODE_MESSAGE_TRANSFERRING
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /** Satellite modem is powered off */
    public static final int MODE_POWERED_OFF =
            android.hardware.radio.satellite.SatelliteMode.POWERED_OFF;
    /** Satellite modem is in out of service state and not searching for satellite signal */
    public static final int MODE_OUT_OF_SERVICE_NOT_SEARCHING =
            android.hardware.radio.satellite.SatelliteMode.OUT_OF_SERVICE_NOT_SEARCHING;
    /** Satellite modem is in out of service state and searching for satellite signal */
    public static final int MODE_OUT_OF_SERVICE_SEARCHING =
            android.hardware.radio.satellite.SatelliteMode.OUT_OF_SERVICE_SEARCHING;
    /** Satellite modem has found satellite signal and gets connected to the satellite network */
    public static final int MODE_ACQUIRED = android.hardware.radio.satellite.SatelliteMode.ACQUIRED;
    /** Satellite modem is sending and/or receiving messages */
    public static final int MODE_MESSAGE_TRANSFERRING =
            android.hardware.radio.satellite.SatelliteMode.MESSAGE_TRANSFERRING;
}
