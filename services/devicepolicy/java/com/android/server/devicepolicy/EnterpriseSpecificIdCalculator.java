/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.VerifierDeviceIdentity;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.security.identity.Util;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.nio.ByteBuffer;

class EnterpriseSpecificIdCalculator {
    private static final int PADDED_HW_ID_LENGTH = 16;
    private static final int PADDED_PROFILE_OWNER_LENGTH = 64;
    private static final int PADDED_ENTERPRISE_ID_LENGTH = 64;
    private static final int ESID_LENGTH = 16;

    private final String mImei;
    private final String mMeid;
    private final String mSerialNumber;
    private final String mMacAddress;

    @VisibleForTesting
    EnterpriseSpecificIdCalculator(String imei, String meid, String serialNumber,
            String macAddress) {
        mImei = imei;
        mMeid = meid;
        mSerialNumber = serialNumber;
        mMacAddress = macAddress;
    }

    EnterpriseSpecificIdCalculator(Context context) {
        TelephonyManager telephonyService = context.getSystemService(TelephonyManager.class);
        Preconditions.checkState(telephonyService != null, "Unable to access telephony service");

        String imei;
        try {
            imei = telephonyService.getImei(0);
        } catch (UnsupportedOperationException doesNotSupportGms) {
            // Instead of catching the exception, we could check for FEATURE_TELEPHONY_GSM.
            // However that runs the risk of changing a device's existing ESID if on these devices
            // telephonyService.getImei() actually returns non-null even when the device does not
            // declare FEATURE_TELEPHONY_GSM.
            imei = null;
        }
        mImei = imei;
        String meid;
        try {
            meid = telephonyService.getMeid(0);
        } catch (UnsupportedOperationException doesNotSupportCdma) {
            // Instead of catching the exception, we could check for FEATURE_TELEPHONY_CDMA.
            // However that runs the risk of changing a device's existing ESID if on these devices
            // telephonyService.getMeid() actually returns non-null even when the device does not
            // declare FEATURE_TELEPHONY_CDMA.
            meid = null;
        }
        mMeid = meid;
        mSerialNumber = Build.getSerial();
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        String macAddress = "";
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            final String[] macAddresses = wifiManager.getFactoryMacAddresses();
            if (macAddresses != null && macAddresses.length > 0) {
                macAddress = macAddresses[0];
            }
        }
        mMacAddress = macAddress;
    }

    private static String getPaddedTruncatedString(String input, int maxLength) {
        final String paddedValue = String.format("%" + maxLength + "s", input);
        return paddedValue.substring(0, maxLength);
    }

    private static String getPaddedHardwareIdentifier(String hardwareIdentifier) {
        if (hardwareIdentifier == null) {
            hardwareIdentifier = "";
        }
        return getPaddedTruncatedString(hardwareIdentifier, PADDED_HW_ID_LENGTH);
    }

    String getPaddedImei() {
        return getPaddedHardwareIdentifier(mImei);
    }

    String getPaddedMeid() {
        return getPaddedHardwareIdentifier(mMeid);
    }

    String getPaddedSerialNumber() {
        return getPaddedHardwareIdentifier(mSerialNumber);
    }

    String getPaddedProfileOwnerName(String profileOwnerPackage) {
        return getPaddedTruncatedString(profileOwnerPackage, PADDED_PROFILE_OWNER_LENGTH);
    }

    String getPaddedEnterpriseId(String enterpriseId) {
        return getPaddedTruncatedString(enterpriseId, PADDED_ENTERPRISE_ID_LENGTH);
    }

    /**
     * Calculates the ESID.
     * @param profileOwnerPackage Package of the Device Policy Client that manages the device/
     *                            profile. May not be null.
     * @param enterpriseIdString The identifier for the enterprise in which the device/profile is
     *                           being enrolled. This parameter may not be empty, but may be null.
     *                           If called with {@code null}, will calculate an ESID with empty
     *                           Enterprise ID.
     */
    public String calculateEnterpriseId(String profileOwnerPackage, String enterpriseIdString) {
        Preconditions.checkArgument(!TextUtils.isEmpty(profileOwnerPackage),
                "owner package must be specified.");

        Preconditions.checkArgument(enterpriseIdString == null || !enterpriseIdString.isEmpty(),
                "enterprise ID must either be null or non-empty.");

        if (enterpriseIdString == null) {
            enterpriseIdString = "";
        }

        final byte[] serialNumber = getPaddedSerialNumber().getBytes();
        final byte[] imei = getPaddedImei().getBytes();
        final byte[] meid = getPaddedMeid().getBytes();
        final byte[] macAddress = mMacAddress.getBytes();
        final int totalIdentifiersLength = serialNumber.length + imei.length + meid.length
                + macAddress.length;
        final ByteBuffer fixedIdentifiers = ByteBuffer.allocate(totalIdentifiersLength);
        fixedIdentifiers.put(serialNumber);
        fixedIdentifiers.put(imei);
        fixedIdentifiers.put(meid);
        fixedIdentifiers.put(macAddress);

        final byte[] dpcPackage = getPaddedProfileOwnerName(profileOwnerPackage).getBytes();
        final byte[] enterpriseId = getPaddedEnterpriseId(enterpriseIdString).getBytes();
        final ByteBuffer info = ByteBuffer.allocate(dpcPackage.length + enterpriseId.length);
        info.put(dpcPackage);
        info.put(enterpriseId);
        final byte[] esidBytes = Util.computeHkdf("HMACSHA256", fixedIdentifiers.array(), null,
                info.array(), ESID_LENGTH);
        ByteBuffer esidByteBuffer = ByteBuffer.wrap(esidBytes);

        VerifierDeviceIdentity firstId = new VerifierDeviceIdentity(esidByteBuffer.getLong());
        VerifierDeviceIdentity secondId = new VerifierDeviceIdentity(esidByteBuffer.getLong());
        return firstId.toString() + secondId.toString();
    }
}
