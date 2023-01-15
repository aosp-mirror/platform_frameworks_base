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

import android.telephony.ImsiEncryptionInfo;
import android.net.Uri;

/**
 * Interface used to retrieve various phone-related subscriber information.
 *
 */
interface IPhoneSubInfo {

    /** @deprecated Use {@link #getDeviceIdWithFeature(String, String) instead */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String getDeviceId(String callingPackage);

    /**
     * Retrieves the unique device ID, e.g., IMEI for GSM phones.
     */
    String getDeviceIdWithFeature(String callingPackage, String callingFeatureId);

     /**
     * Retrieves the unique Network Access ID
     */
    String getNaiForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Retrieves the unique device ID of a phone for the device, e.g., IMEI
     * for GSM phones.
     */
    String getDeviceIdForPhone(int phoneId, String callingPackage, String callingFeatureId);

    /**
     * Retrieves the IMEI.
     */
    String getImeiForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Retrieves the software version number for the device, e.g., IMEI/SV
     * for GSM phones.
     */
    String getDeviceSvn(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the software version number of a subId for the device, e.g., IMEI/SV
     * for GSM phones.
     */
    String getDeviceSvnUsingSubId(int subId, String callingPackage, String callingFeatureId);

    /** @deprecated Use {@link #getSubscriberIdWithFeature(String, String) instead */
    @UnsupportedAppUsage
    String getSubscriberId(String callingPackage);

    /**
     * Retrieves the unique sbuscriber ID, e.g., IMSI for GSM phones.
     */
    String getSubscriberIdWithFeature(String callingPackage, String callingComponenId);

    /**
     * Retrieves the unique subscriber ID of a given subId, e.g., IMSI for GSM phones.
     */
    String getSubscriberIdForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Retrieves the Group Identifier Level1 for GSM phones of a subId.
     */
    String getGroupIdLevel1ForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /** @deprecared Use {@link getIccSerialNumberWithFeature(String, String)} instead */
    @UnsupportedAppUsage
    String getIccSerialNumber(String callingPackage);

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    String getIccSerialNumberWithFeature(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the serial number of a given subId.
     */
    String getIccSerialNumberForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Retrieves the phone number string for line 1.
     */
    String getLine1Number(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the phone number string for line 1 of a subcription.
     */
    String getLine1NumberForSubscriber(int subId, String callingPackage, String callingFeatureId);


    /**
     * Retrieves the alpha identifier for line 1.
     */
    String getLine1AlphaTag(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the alpha identifier for line 1 of a subId.
     */
    String getLine1AlphaTagForSubscriber(int subId, String callingPackage,
            String callingFeatureId);


    /**
     * Retrieves MSISDN Number.
     */
    String getMsisdn(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the Msisdn of a subId.
     */
    String getMsisdnForSubscriber(int subId, String callingPackage, String callingFeatureId);

    /**
     * Retrieves the voice mail number.
     */
    String getVoiceMailNumber(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the voice mail number of a given subId.
     */
    String getVoiceMailNumberForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Retrieves the Carrier information used to encrypt IMSI and IMPI.
     */
    ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int subId, int keyType,
            String callingPackage);

    /**
     * Stores the Carrier information used to encrypt IMSI and IMPI.
     */
    void setCarrierInfoForImsiEncryption(int subId, String callingPackage,
    in ImsiEncryptionInfo imsiEncryptionInfo);

    /**
     * Resets the Carrier Keys in the database. This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     */
    void resetCarrierKeysForImsiEncryption(int subId, String callingPackage);

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    String getVoiceMailAlphaTag(String callingPackage, String callingFeatureId);

    /**
     * Retrieves the alpha identifier associated with the voice mail number
     * of a subId.
     */
    String getVoiceMailAlphaTagForSubscriber(int subId, String callingPackage,
            String callingFeatureId);

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     */
    String getIsimImpi(int subId);

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM.
     * @return the IMS domain name, or null if not present or not loaded
     */
    String getIsimDomain(int subId);

    /**
     * Returns the IMS public user identities (IMPU) that were loaded from the ISIM.
     * @return an array of IMPU strings, with one IMPU per string, or null if
     *      not present or not loaded
     */
    String[] getIsimImpu(int subId);

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     */
    String getIsimIst(int subId);

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of PCSCF strings with one PCSCF per string, or null if
     *      not present or not loaded
     */
    String[] getIsimPcscf(int subId);

    /**
     * Returns the response of the SIM application on the UICC to authentication
     * challenge/response algorithm. The data string and challenge response are
     * Base64 encoded Strings.
     * Can support EAP-SIM, EAP-AKA with results encoded per 3GPP TS 31.102.
     *
     * @param subId subscription ID to be queried
     * @param appType ICC application type (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param authType Authentication type, see PhoneConstants#AUTHTYPE_xxx
     * @param data authentication challenge data
     * @return challenge response
     */
    String getIccSimChallengeResponse(int subId, int appType, int authType, String data,
            String callingPackage, String callingFeatureId);

    /**
     * Fetches the EF_PSISMSC value from the UICC that contains the Public Service Identity of
     * the SM-SC (either a SIP URI or tel URI). The EF_PSISMSC of ISIM and USIM can be found in
     * DF_TELECOM.
     * The EF_PSISMSC value is used by the ME to submit SMS over IP as defined in 24.341 [55].
     *
     * @return Uri : Public Service Identity of SM-SC
     * @throws SecurityException if the caller does not have the required permission/privileges
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
    Uri getSmscIdentity(int subId, int appType);

    /**
     * Fetches the sim service table from the EFUST/EFIST based on the application type
     * {@link #APPTYPE_USIM} or {@link #APPTYPE_ISIM}. The return value is hexaString format
     * representing X bytes (x >= 1). Each bit of every byte indicates which optional services
     * are available for the given application type.
     * The USIM service table EF is described in as per Section 4.2.8 of 3GPP TS 31.102.
     * The ISIM service table EF is described in as per Section 4.2.7 of 3GPP TS 31.103.
     * The list of services mapped to the exact nth byte of response as mentioned in Section 4.2
     * .7 of 3GPP TS 31.103. Eg. Service n°1: Local Phone Book, Service n°2: Fixed Dialling
     * Numbers (FDN) - Bit 1 and 2 of the 1st Byte represent service Local Phone Book and Fixed
     * Dialling Numbers (FDN)respectively. The coding format for each service type  should be
     * interpreted as bit = 1: service available;bit = 0:service not available.
     *
     * @param appType of type int of either {@link #APPTYPE_USIM} or {@link #APPTYPE_ISIM}.
     * @return HexString represents sim service table else null.
     * @throws SecurityException if the caller does not have the required permission/privileges
     * @throws IllegalStateException in case if phone or UiccApplication is not available.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
    String getSimServiceTable(int subId, int appType);
}
