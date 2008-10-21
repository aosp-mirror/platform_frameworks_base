package com.android.internal.telephony;

import android.content.Context;
import android.os.ServiceManager;
import com.android.internal.telephony.*;

public class PhoneSubInfo extends IPhoneSubInfo.Stub {
    private Phone mPhone;
    private Context mContext;
    private static final String READ_PHONE_STATE =
        android.Manifest.permission.READ_PHONE_STATE;

    public PhoneSubInfo(Phone phone) {
        mPhone = phone;
        mContext = phone.getContext();
        ServiceManager.addService("iphonesubinfo", this);
    }
    /**
     * Retrieves the unique device ID, e.g., IMEI for GSM phones.
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
     * Retrieves the unique sbuscriber ID, e.g., IMSI for GSM phones.
     */
    public String getSubscriberId() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getSubscriberId();
    }

    /**
     * Retrieves the serial number of the SIM, if applicable.
     */
    public String getSimSerialNumber() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return mPhone.getSimSerialNumber();
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
        return (String) mPhone.getVoiceMailNumber();
    }

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    public String getVoiceMailAlphaTag() {
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, "Requires READ_PHONE_STATE");
        return (String) mPhone.getVoiceMailAlphaTag();
    }
}
