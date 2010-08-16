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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

public class PhoneSubInfo extends IPhoneSubInfo.Stub {
    static final String LOG_TAG = "PHONE";
    private Phone mPhone;
    private Context mContext;
    private static final String READ_PHONE_STATE =
        android.Manifest.permission.READ_PHONE_STATE;
    private static final String CALL_PRIVILEGED =
        // TODO Add core/res/AndriodManifest.xml#READ_PRIVILEGED_PHONE_STATE
        android.Manifest.permission.CALL_PRIVILEGED;

    public PhoneSubInfo(Phone phone) {
        mPhone = phone;
        mContext = phone.getContext();
    }

    public void dispose() {
    }

    protected void finalize() {
        try {
            super.finalize();
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, "Error while finalizing:", throwable);
        }
        Log.d(LOG_TAG, "PhoneSubInfo finalized");
    }

    /**
     * Retrieves the unique device ID, e.g., IMEI for GSM phones and MEID for CDMA phones.
     */
    public String getDeviceId() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getDeviceId();
    }

    /**
     * Retrieves the software version number for the device, e.g., IMEI/SV
     * for GSM phones.
     */
    public String getDeviceSvn() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getDeviceSvn();
    }

    /**
     * Retrieves the unique subscriber ID, e.g., IMSI for GSM phones.
     */
    public String getSubscriberId() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getSubscriberId();
    }

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    public String getIccSerialNumber() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getIccSerialNumber();
    }

    /**
     * Retrieves the phone number string for line 1.
     */
    public String getLine1Number() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getLine1Number();
    }

    /**
     * Retrieves the alpha identifier for line 1.
     */
    public String getLine1AlphaTag() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return (String) mPhone.getLine1AlphaTag();
    }

    /**
     * Retrieves the voice mail number.
     */
    public String getVoiceMailNumber() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        String number = PhoneNumberUtils.extractNetworkPortion(mPhone.getVoiceMailNumber());
        Log.d(LOG_TAG, "VM: PhoneSubInfo.getVoiceMailNUmber: "); // + number);
        return number;
    }

    /**
     * Retrieves the complete voice mail number.
     *
     * @hide
     */
    public String getCompleteVoiceMailNumber() {
        mContext.enforceCallingOrSelfPermission(CALL_PRIVILEGED,
                "Requires CALL_PRIVILEGED");
        String number = mPhone.getVoiceMailNumber();
        Log.d(LOG_TAG, "VM: PhoneSubInfo.getCompleteVoiceMailNUmber: "); // + number);
        return number;
    }

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    public String getVoiceMailAlphaTag() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return (String) mPhone.getVoiceMailAlphaTag();
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PhoneSubInfo from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("Phone Subscriber Info:");
        pw.println("  Phone Type = " + mPhone.getPhoneName());
        pw.println("  Device ID = " + mPhone.getDeviceId());
    }

}
