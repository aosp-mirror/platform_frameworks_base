/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.BaseColumns;
import android.telephony.Rlog;
import android.os.ServiceManager;
import android.os.RemoteException;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * SubscriptionManager is the application interface to SubscriptionController
 * and provides information about the current Telephony Subscriptions.
 *
 * The android.Manifest.permission.READ_PHONE_STATE to retrieve the information, except
 * getActiveSubIdList and getActiveSubIdCount for which no permission is needed.
 */
public class SubscriptionManager implements BaseColumns {
    private static final String LOG_TAG = "SUB";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /** An invalid subscription identifier */
    public static final int INVALID_SUB_ID = -1000;

    /** An invalid phone identifier */
    /** @hide */
    public static final int INVALID_PHONE_ID = -1;

    /** An invalid slot identifier */
    /** @hide */
    public static final int INVALID_SLOT_ID = -1;

    /** Indicates the caller wants the default sub id. */
    /** @hide */
    public static final int DEFAULT_SUB_ID = Integer.MAX_VALUE;

    /** Indicates the caller wants the default phone id. */
    /** @hide */
    public static final int DEFAULT_PHONE_ID = Integer.MAX_VALUE;

    /** Indicates the caller wants the default slot id. */
    /** @hide */
    public static final int DEFAULT_SLOT_ID = Integer.MAX_VALUE;

    /** Minimum possible subid that represents a subscription */
    /** @hide */
    public static final int MIN_SUB_ID_VALUE = 0;

    /** Maximum possible subid that represents a subscription */
    /** @hide */
    public static final int MAX_SUB_ID_VALUE = DEFAULT_SUB_ID - 1;


    /** @hide */
    public static final Uri CONTENT_URI = Uri.parse("content://telephony/siminfo");

    /**
     * The ICC ID of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String ICC_ID = "icc_id";

    /**
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String SIM_ID = "sim_id";

    /** SIM is not inserted */
    /** @hide */
    public static final int SIM_NOT_INSERTED = -1;

    /**
     * TelephonyProvider column name for user displayed name.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String DISPLAY_NAME = "display_name";

    /**
     * TelephonyProvider column name for the service provider name for the SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String CARRIER_NAME = "carrier_name";

    /**
     * Default name resource
     * @hide
     */
    public static final int DEFAULT_NAME_RES = com.android.internal.R.string.unknownName;

    /**
     * TelephonyProvider column name for source of the user displayed name.
     * <P>Type: INT (int)</P> with one of the NAME_SOURCE_XXXX values below
     *
     * @hide
     */
    public static final String NAME_SOURCE = "name_source";

    /**
     * The name_source is undefined
     * @hide
     */
    public static final int NAME_SOURCE_UNDEFINDED = -1;

    /**
     * The name_source is the default
     * @hide
     */
    public static final int NAME_SOURCE_DEFAULT_SOURCE = 0;

    /**
     * The name_source is from the SIM
     * @hide
     */
    public static final int NAME_SOURCE_SIM_SOURCE = 1;

    /**
     * The name_source is from the user
     * @hide
     */
    public static final int NAME_SOURCE_USER_INPUT = 2;

    /**
     * TelephonyProvider column name for the color of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String COLOR = "color";

    /** @hide */
    public static final int COLOR_1 = 0;

    /** @hide */
    public static final int COLOR_2 = 1;

    /** @hide */
    public static final int COLOR_3 = 2;

    /** @hide */
    public static final int COLOR_4 = 3;

    /** @hide */
    public static final int COLOR_DEFAULT = COLOR_1;

    /**
     * TelephonyProvider column name for the phone number of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String NUMBER = "number";

    /**
     * TelephonyProvider column name for the number display format of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String DISPLAY_NUMBER_FORMAT = "display_number_format";

    /** @hide */
    public static final int DISPLAY_NUMBER_NONE = 0;

    /** @hide */
    public static final int DISPLAY_NUMBER_FIRST = 1;

    /** @hide */
    public static final int DISPLAY_NUMBER_LAST = 2;

    /** @hide */
    public static final int DISPLAY_NUMBER_DEFAULT = DISPLAY_NUMBER_FIRST;

    /**
     * TelephonyProvider column name for permission for data roaming of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String DATA_ROAMING = "data_roaming";

    /** @hide */
    public static final int DATA_ROAMING_ENABLE = 1;

    /** @hide */
    public static final int DATA_ROAMING_DISABLE = 0;

    /** @hide */
    public static final int DATA_ROAMING_DEFAULT = DATA_ROAMING_DISABLE;

    /**
     * TelephonyProvider column name for the MCC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MCC = "mcc";

    /**
     * TelephonyProvider column name for the MNC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MNC = "mnc";

    /**
     * Broadcast Action: The user has changed one of the default subs related to
     * data, phone calls, or sms</p>
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUB_DEFAULT_CHANGED_ACTION =
        "android.intent.action.SUB_DEFAULT_CHANGED";

    /** @hide */
    public SubscriptionManager() {
        if (DBG) logd("SubscriptionManager created");
    }

    /**
     * Register for changes to events defined by SubscriptionListener.LISTEN_Xxx. Some of
     * the events will fire as registration completes, this could be before or after
     * this method returns.
     *
     * @param listener an instance of SubscriptionListner with overridden methods the
     *                 overridden method should match the bits defined in events.
     * @param events is one or more of the SubscriptionListener.LISTEN_Xxx bits
     */
    public static void register(Context context, SubscriptionListener listener, int events) {
        String pkgForDebug = context != null ? context.getPackageName() : "<unknown>";
        if (DBG) {
            logd("SubscriptionManager listen pkgForDebug=" + pkgForDebug
                    + " events=0x" + Integer.toHexString(events) + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as its runs in the system and thus is always
            // available where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.registerSubscriptionListener(pkgForDebug, listener.callback, events);
            }
        } catch (RemoteException ex) {
            // Should not happen
        }
    }

    /**
     * Unregister the listener.
     *
     * @param context
     * @param listener
     */
    public static void unregister(Context context, SubscriptionListener listener) {
        String pkgForDebug = context != null ? context.getPackageName() : "<unknown>";
        if (DBG) {
            logd("SubscriptionManager unregister pkgForDebug=" + pkgForDebug
                    + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as its runs in the system and thus is always
            // available where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.unregisterSubscriptionListener(pkgForDebug, listener.callback);
            }
        } catch (RemoteException ex) {
            // Should not happen
        }
    }

    /**
     * Get the SubscriptionInfo associated with the subId
     * @param subId The unique SubscriptionInfo index in database
     * @return SubscriptionInfo, maybe null
     */
    public static SubscriptionInfo getSubscriptionInfoForSubscriber(int subId) {
        if (!isValidSubId(subId)) {
            logd("[getSubscriptionInfoForSubscriber]- invalid subId");
            return null;
        }

        SubscriptionInfo subInfo = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subInfo = iSub.getSubInfoForSubscriber(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subInfo;

    }

    /**
     * Get the SubscriptionInfo according to an IccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo List, maybe empty but not null
     * @hide
     */
    public static List<SubscriptionInfo> getSubscriptionInfoUsingIccId(String iccId) {
        if (VDBG) logd("[getSubscriptionInfoUsingIccId]+ iccId=" + iccId);
        if (iccId == null) {
            logd("[getSubscriptionInfoUsingIccId]- null iccid");
            return null;
        }

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSubInfoUsingIccId(iccId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }


        if (result == null) {
            result = new ArrayList<SubscriptionInfo>();
        }
        return result;
    }

    /**
     * Get the SubscriptionInfo according to slotId
     * @param slotId the slot which the SIM is inserted
     * @return SubscriptionInfo list, maybe empty but not null
     */
    public static List<SubscriptionInfo> getSubscriptionInfoUsingSlotId(int slotId) {
        // FIXME: Consider never returning null
        if (!isValidSlotId(slotId)) {
            logd("[getSubscriptionInfoUsingSlotId]- invalid slotId");
            return null;
        }

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSubInfoUsingSlotId(slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }


        if (result == null) {
            result = new ArrayList<SubscriptionInfo>();
        }
        return result;
    }

    /**
     * Get all the SubscriptionInfo(s) in subInfo database
     * @return List of all SubscriptionInfos in database, include those that were inserted before
     * maybe empty but not null.
     * @hide
     */
    public static List<SubscriptionInfo> getAllSubscriptionInfoList() {
        if (VDBG) logd("[getAllSubscriptionInfoList]+");

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (result == null) {
            result = new ArrayList<SubscriptionInfo>();
        }
        return result;
    }

    /**
     * Get the SubscriptionInfo(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubscriptionInfo(s) maybe empty but not null
     */
    public static List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (result == null) {
            result = new ArrayList<SubscriptionInfo>();
        }
        return result;
    }

    /**
     * Get the SUB count of all SUB(s) in subinfo database
     * @return all SIM count in database, include what was inserted before
     * @hide
     */
    public static int getAllSubscriptionInfoCount() {
        if (VDBG) logd("[getAllSubscriptionInfoCount]+");

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoCount();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the count of active SUB(s)
     * @return active SIM count
     * @hide
     */
    public static int getActiveSubscriptionInfoCount() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoCount();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Add a new SubscriptionInfo to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     * @hide
     */
    public static Uri addSubscriptionInfoRecord(String iccId, int slotId) {
        if (VDBG) logd("[addSubscriptionInfoRecord]+ iccId:" + iccId + " slotId:" + slotId);
        if (iccId == null) {
            logd("[addSubscriptionInfoRecord]- null iccId");
        }
        if (!isValidSlotId(slotId)) {
            logd("[addSubscriptionInfoRecord]- invalid slotId");
        }

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                // FIXME: This returns 1 on success, 0 on error should should we return it?
                iSub.addSubInfoRecord(iccId, slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        // FIXME: Always returns null?
        return null;

    }

    /**
     * Set SIM icon tint color by simInfo index
     * @param tint the rgb value of icon tint color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     * @hide
     */
    public static int setIconTint(int tint, int subId) {
        if (VDBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        if (!isValidSubId(subId)) {
            logd("[setIconTint]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setIconTint(tint, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    public static int setDisplayName(String displayName, int subId) {
        return setDisplayName(displayName, subId, NAME_SOURCE_UNDEFINDED);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated or -1 if invalid subId
     * @hide
     */
    public static int setDisplayName(String displayName, int subId, long nameSource) {
        if (VDBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                    + " nameSource:" + nameSource);
        }
        if (!isValidSubId(subId)) {
            logd("[setDisplayName]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDisplayNameUsingSrc(displayName, subId, nameSource);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    public static int setDisplayNumber(String number, int subId) {
        if (number == null || !isValidSubId(subId)) {
            logd("[setDisplayNumber]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDisplayNumber(number, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    public static int setDataRoaming(int roaming, int subId) {
        if (VDBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        if (roaming < 0 || !isValidSubId(subId)) {
            logd("[setDataRoaming]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDataRoaming(roaming, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get slotId associated with the subscription.
     * @return slotId as a positive integer or a negative value if an error either
     * SIM_NOT_INSERTED or INVALID_SLOT_ID.
     * @hide
     */
    public static int getSlotId(int subId) {
        if (!isValidSubId(subId)) {
            logd("[getSlotId]- fail");
        }

        int result = INVALID_SLOT_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSlotId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /** @hide */
    public static int[] getSubId(int slotId) {
        if (!isValidSlotId(slotId)) {
            logd("[getSubId]- fail");
            return null;
        }

        int[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getSubId(slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subId;
    }

    /** @hide */
    public static int getPhoneId(int subId) {
        if (!isValidSubId(subId)) {
            logd("[getPhoneId]- fail");
            return INVALID_PHONE_ID;
        }

        int result = INVALID_PHONE_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getPhoneId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("[getPhoneId]- phoneId=" + result);
        return result;

    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, "[SubManager] " + msg);
    }

    /**
     * @return the "system" defaultSubId on a voice capable device this
     * will be getDefaultVoiceSubId() and on a data only device it will be
     * getDefaultDataSubId().
     * @hide
     */
    public static int getDefaultSubId() {
        int subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSubId=" + subId);
        return subId;
    }

    /** @hide */
    public static int getDefaultVoiceSubId() {
        int subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultVoiceSubId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public static void setDefaultVoiceSubId(int subId) {
        if (VDBG) logd("setDefaultVoiceSubId sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultVoiceSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /** @hide */
    public static SubscriptionInfo getDefaultVoiceSubscriptionInfo() {
        return getSubscriptionInfoForSubscriber(getDefaultVoiceSubId());
    }

    /** @hide */
    public static int getDefaultVoicePhoneId() {
        return getPhoneId(getDefaultVoiceSubId());
    }

    /**
     * @return subId of the DefaultSms subscription or the value INVALID_SUB_ID if an error.
     * @hide
     */
    public static int getDefaultSmsSubId() {
        int subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSmsSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSmsSubId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public static void setDefaultSmsSubId(int subId) {
        if (VDBG) logd("setDefaultSmsSubId sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultSmsSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /** @hide */
    public static SubscriptionInfo getDefaultSmsSubscriptionInfo() {
        return getSubscriptionInfoForSubscriber(getDefaultSmsSubId());
    }

    /** @hide */
    public static int getDefaultSmsPhoneId() {
        return getPhoneId(getDefaultSmsSubId());
    }

    /** @hide */
    public static int getDefaultDataSubId() {
        int subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultDataSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultDataSubId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public static void setDefaultDataSubId(int subId) {
        if (VDBG) logd("setDataSubscription sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultDataSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /** @hide */
    public static SubscriptionInfo getDefaultDataSubscriptionInfo() {
        return getSubscriptionInfoForSubscriber(getDefaultDataSubId());
    }

    /** @hide */
    public static int getDefaultDataPhoneId() {
        return getPhoneId(getDefaultDataSubId());
    }

    /** @hide */
    public static void clearSubscriptionInfo() {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearSubInfo();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return;
    }

    //FIXME this is vulnerable to race conditions
    /** @hide */
    public static boolean allDefaultsSelected() {
        if (getDefaultDataSubId() == INVALID_SUB_ID) {
            return false;
        }
        if (getDefaultSmsSubId() == INVALID_SUB_ID) {
            return false;
        }
        if (getDefaultVoiceSubId() == INVALID_SUB_ID) {
            return false;
        }
        return true;
    }

    /**
     * If a default is set to subscription which is not active, this will reset that default back to
     * INVALID_SUB_ID.
     * @hide
     */
    public static void clearDefaultsForInactiveSubIds() {
        if (VDBG) logd("clearDefaultsForInactiveSubIds");
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearDefaultsForInactiveSubIds();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * @return true if a valid subId else false
     * @hide
     */
    public static boolean isValidSubId(int subId) {
        return subId > INVALID_SUB_ID ;
    }

    /**
     * @return true if subId is an usable subId value else false. A
     * usable subId means its neither a INVALID_SUB_ID nor a DEFAUL_SUB_ID.
     * @hide
     */
    public static boolean isUsableSubIdValue(int subId) {
        return subId >= MIN_SUB_ID_VALUE && subId <= MAX_SUB_ID_VALUE;
    }

    /** @hide */
    public static boolean isValidSlotId(int slotId) {
        // We are testing INVALID_SLOT_ID and slotId >= 0 independently because we should
        // not assume that INVALID_SLOT_ID will always be a negative value.  Any negative
        // value is invalid.
        return slotId != INVALID_SLOT_ID && slotId >= 0 &&
                slotId < TelephonyManager.getDefault().getSimCount();
    }

    /** @hide */
    public static boolean isValidPhoneId(int phoneId) {
        // We are testing INVALID_PHONE_ID and phoneId >= 0 independently because we should
        // not assume that INVALID_PHONE_ID will always be a negative value.  Any negative
        // value is invalid.
        return phoneId != INVALID_PHONE_ID && phoneId >= 0 &&
                phoneId < TelephonyManager.getDefault().getPhoneCount();
    }

    /** @hide */
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId) {
        int[] subIds = SubscriptionManager.getSubId(phoneId);
        if (subIds != null && subIds.length > 0) {
            putPhoneIdAndSubIdExtra(intent, phoneId, subIds[0]);
        } else {
            logd("putPhoneIdAndSubIdExtra: no valid subs");
        }
    }

    /** @hide */
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId, int subId) {
        if (VDBG) logd("putPhoneIdAndSubIdExtra: phoneId=" + phoneId + " subId=" + subId);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
        //FIXME this is using phoneId and slotId interchangeably
        //Eventually, this should be removed as it is not the slot id
        intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
    }

    /**
     * @return the list of subId's that are active,
     *         is never null but the length maybe 0.
     * @hide
     */
    public static int[] getActiveSubIdList() {
        int[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getActiveSubIdList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (subId == null) {
            subId = new int[0];
        }

        return subId;

    }
}

