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

import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.telephony.Rlog;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.util.DisplayMetrics;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * SubscriptionManager is the application interface to SubscriptionController
 * and provides information about the current Telephony Subscriptions.
 * * <p>
 * You do not instantiate this class directly; instead, you retrieve
 * a reference to an instance through {@link #from}.
 * <p>
 * All SDK public methods require android.Manifest.permission.READ_PHONE_STATE.
 */
public class SubscriptionManager {
    private static final String LOG_TAG = "SubscriptionManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /** An invalid subscription identifier */
    public static final int INVALID_SUBSCRIPTION_ID = -1;

    /** Base value for Dummy SUBSCRIPTION_ID's. */
    /** FIXME: Remove DummySubId's, but for now have them map just below INVALID_SUBSCRIPTION_ID
    /** @hide */
    public static final int DUMMY_SUBSCRIPTION_ID_BASE = INVALID_SUBSCRIPTION_ID - 1;

    /** An invalid phone identifier */
    /** @hide */
    public static final int INVALID_PHONE_INDEX = -1;

    /** An invalid slot identifier */
    /** @hide */
    public static final int INVALID_SIM_SLOT_INDEX = -1;

    /** Indicates the caller wants the default sub id. */
    /** @hide */
    public static final int DEFAULT_SUBSCRIPTION_ID = Integer.MAX_VALUE;

    /**
     * Indicates the caller wants the default phone id.
     * Used in SubscriptionController and Phone but do we really need it???
     * @hide
     */
    public static final int DEFAULT_PHONE_INDEX = Integer.MAX_VALUE;

    /** Indicates the caller wants the default slot id. NOT used remove? */
    /** @hide */
    public static final int DEFAULT_SIM_SLOT_INDEX = Integer.MAX_VALUE;

    /** Minimum possible subid that represents a subscription */
    /** @hide */
    public static final int MIN_SUBSCRIPTION_ID_VALUE = 0;

    /** Maximum possible subid that represents a subscription */
    /** @hide */
    public static final int MAX_SUBSCRIPTION_ID_VALUE = DEFAULT_SUBSCRIPTION_ID - 1;

    /** @hide */
    public static final Uri CONTENT_URI = Uri.parse("content://telephony/siminfo");

    /**
     * TelephonyProvider unique key column name is the subscription id.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String UNIQUE_KEY_SUBSCRIPTION_ID = "_id";

    /**
     * TelephonyProvider column name for SIM ICC Identifier
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String ICC_ID = "icc_id";

    /**
     * TelephonyProvider column name for user SIM_SlOT_INDEX
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String SIM_SLOT_INDEX = "sim_id";

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

    /** Indicates that data roaming is enabled for a subscription */
    public static final int DATA_ROAMING_ENABLE = 1;

    /** Indicates that data roaming is disabled for a subscription */
    public static final int DATA_ROAMING_DISABLE = 0;

    /** Sim provisioning status: provisioned */
    /** @hide */
    public static final int SIM_PROVISIONED = 0;

    /** Sim provisioning status: un-provisioned due to cold sim */
    /** @hide */
    public static final int SIM_UNPROVISIONED_COLD = 1;

    /** Sim provisioning status: un-provisioned due to out of credit */
    /** @hide */
    public static final int SIM_UNPROVISIONED_OUT_OF_CREDIT = 2;

    /** Maximum possible sim provisioning status */
    /** @hide */
    public static final int MAX_SIM_PROVISIONING_STATUS = SIM_UNPROVISIONED_OUT_OF_CREDIT;

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
     * TelephonyProvider column name for the sim provisioning status associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String SIM_PROVISIONING_STATUS = "sim_provisioning_status";

    /**
     *  TelephonyProvider column name for extreme threat in CB settings
     * @hide
     */
    public static final String CB_EXTREME_THREAT_ALERT = "enable_cmas_extreme_threat_alerts";

    /**
     * TelephonyProvider column name for severe threat in CB settings
     *@hide
     */
    public static final String CB_SEVERE_THREAT_ALERT = "enable_cmas_severe_threat_alerts";

    /**
     * TelephonyProvider column name for amber alert in CB settings
     *@hide
     */
    public static final String CB_AMBER_ALERT = "enable_cmas_amber_alerts";

    /**
     * TelephonyProvider column name for emergency alert in CB settings
     *@hide
     */
    public static final String CB_EMERGENCY_ALERT = "enable_emergency_alerts";

    /**
     * TelephonyProvider column name for alert sound duration in CB settings
     *@hide
     */
    public static final String CB_ALERT_SOUND_DURATION = "alert_sound_duration";

    /**
     * TelephonyProvider column name for alert reminder interval in CB settings
     *@hide
     */
    public static final String CB_ALERT_REMINDER_INTERVAL = "alert_reminder_interval";

    /**
     * TelephonyProvider column name for enabling vibrate in CB settings
     *@hide
     */
    public static final String CB_ALERT_VIBRATE = "enable_alert_vibrate";

    /**
     * TelephonyProvider column name for enabling alert speech in CB settings
     *@hide
     */
    public static final String CB_ALERT_SPEECH = "enable_alert_speech";

    /**
     * TelephonyProvider column name for ETWS test alert in CB settings
     *@hide
     */
    public static final String CB_ETWS_TEST_ALERT = "enable_etws_test_alerts";

    /**
     * TelephonyProvider column name for enable channel50 alert in CB settings
     *@hide
     */
    public static final String CB_CHANNEL_50_ALERT = "enable_channel_50_alerts";

    /**
     * TelephonyProvider column name for CMAS test alert in CB settings
     *@hide
     */
    public static final String CB_CMAS_TEST_ALERT= "enable_cmas_test_alerts";

    /**
     * TelephonyProvider column name for Opt out dialog in CB settings
     *@hide
     */
    public static final String CB_OPT_OUT_DIALOG = "show_cmas_opt_out_dialog";

    /**
     * Broadcast Action: The user has changed one of the default subs related to
     * data, phone calls, or sms</p>
     *
     * TODO: Change to a listener
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUB_DEFAULT_CHANGED_ACTION =
        "android.intent.action.SUB_DEFAULT_CHANGED";

    private final Context mContext;

    /**
     * A listener class for monitoring changes to {@link SubscriptionInfo} records.
     * <p>
     * Override the onSubscriptionsChanged method in the object that extends this
     * class and pass it to {@link #addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener)}
     * to register your listener and to unregister invoke
     * {@link #removeOnSubscriptionsChangedListener(OnSubscriptionsChangedListener)}
     * <p>
     * Permissions android.Manifest.permission.READ_PHONE_STATE is required
     * for #onSubscriptionsChanged to be invoked.
     */
    public static class OnSubscriptionsChangedListener {
        private final Handler mHandler  = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (DBG) {
                    log("handleMessage: invoke the overriden onSubscriptionsChanged()");
                }
                OnSubscriptionsChangedListener.this.onSubscriptionsChanged();
            }
        };

        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link #getActiveSubscriptionInfoList}
         */
        public void onSubscriptionsChanged() {
            if (DBG) log("onSubscriptionsChanged: NOT OVERRIDDEN");
        }

        /**
         * The callback methods need to be called on the handler thread where
         * this object was created.  If the binder did that for us it'd be nice.
         */
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged() {
                if (DBG) log("callback: received, sendEmptyMessage(0) to handler");
                mHandler.sendEmptyMessage(0);
            }
        };

        private void log(String s) {
            Rlog.d(LOG_TAG, s);
        }
    }

    /** @hide */
    public SubscriptionManager(Context context) {
        if (DBG) logd("SubscriptionManager created");
        mContext = context;
    }

    /**
     * Get an instance of the SubscriptionManager from the Context.
     * This invokes {@link android.content.Context#getSystemService
     * Context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)}.
     *
     * @param context to use.
     * @return SubscriptionManager instance
     */
    public static SubscriptionManager from(Context context) {
        return (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     *                 onSubscriptionsChanged overridden.
     */
    public void addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("register OnSubscriptionsChangedListener pkgForDebug=" + pkgForDebug
                    + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as it runs in the system and thus is always
            // available. Where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.addOnSubscriptionsChangedListener(pkgForDebug, listener.callback);
            }
        } catch (RemoteException ex) {
            // Should not happen
        }
    }

    /**
     * Unregister the {@link OnSubscriptionsChangedListener}. This is not strictly necessary
     * as the listener will automatically be unregistered if an attempt to invoke the listener
     * fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("unregister OnSubscriptionsChangedListener pkgForDebug=" + pkgForDebug
                    + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as its runs in the system and thus is always
            // available where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.removeOnSubscriptionsChangedListener(pkgForDebug, listener.callback);
            }
        } catch (RemoteException ex) {
            // Should not happen
        }
    }

    /**
     * Get the active SubscriptionInfo with the input subId.
     *
     * @param subId The unique SubscriptionInfo key in database.
     * @return SubscriptionInfo, maybe null if its not active.
     */
    public SubscriptionInfo getActiveSubscriptionInfo(int subId) {
        if (VDBG) logd("[getActiveSubscriptionInfo]+ subId=" + subId);
        if (!isValidSubscriptionId(subId)) {
            if (DBG) {
                logd("[getActiveSubscriptionInfo]- invalid subId");
            }
            return null;
        }

        SubscriptionInfo subInfo = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subInfo = iSub.getActiveSubscriptionInfo(subId, mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subInfo;

    }

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     * @hide
     */
    public SubscriptionInfo getActiveSubscriptionInfoForIccIndex(String iccId) {
        if (VDBG) logd("[getActiveSubscriptionInfoForIccIndex]+ iccId=" + iccId);
        if (iccId == null) {
            logd("[getActiveSubscriptionInfoForIccIndex]- null iccid");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForIccId(iccId, mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIdx
     * @param slotIdx the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIdx) {
        if (VDBG) logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIdx=" + slotIdx);
        if (!isValidSlotId(slotIdx)) {
            logd("[getActiveSubscriptionInfoForSimSlotIndex]- invalid slotIdx");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForSimSlotIndex(slotIdx,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * @return List of all SubscriptionInfo records in database,
     * include those that were inserted before, maybe empty but not null.
     * @hide
     */
    public List<SubscriptionInfo> getAllSubscriptionInfoList() {
        if (VDBG) logd("[getAllSubscriptionInfoList]+");

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoList(mContext.getOpPackageName());
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
     * Get the SubscriptionInfo(s) of the currently inserted SIM(s). The records will be sorted
     * by {@link SubscriptionInfo#getSimSlotIndex} then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the currently {@link SubscriptionInfo} records available on the device.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a {@link OnSubscriptionsChangedListener}
     * has been registered {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be
     * invoked in the future.
     * </li>
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * </li>
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </li>
     * </ul>
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * @return the count of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     * @hide
     */
    public int getAllSubscriptionInfoCount() {
        if (VDBG) logd("[getAllSubscriptionInfoCount]+");

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoCount(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * @return the current number of active subscriptions. There is no guarantee the value
     * returned by this method will be the same as the length of the list returned by
     * {@link #getActiveSubscriptionInfoList}.
     */
    public int getActiveSubscriptionInfoCount() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoCount(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * @return the maximum number of active subscriptions that will be returned by
     * {@link #getActiveSubscriptionInfoList} and the value returned by
     * {@link #getActiveSubscriptionInfoCount}.
     */
    public int getActiveSubscriptionInfoCountMax() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoCountMax();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Add a new SubscriptionInfo to SubscriptionInfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     * @hide
     */
    public Uri addSubscriptionInfoRecord(String iccId, int slotId) {
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
     * @param tint the RGB value of icon tint color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     * @hide
     */
    public int setIconTint(int tint, int subId) {
        if (VDBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        if (!isValidSubscriptionId(subId)) {
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
    public int setDisplayName(String displayName, int subId) {
        return setDisplayName(displayName, subId, NAME_SOURCE_UNDEFINDED);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated or < 0 if invalid subId
     * @hide
     */
    public int setDisplayName(String displayName, int subId, long nameSource) {
        if (VDBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                    + " nameSource:" + nameSource);
        }
        if (!isValidSubscriptionId(subId)) {
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
    public int setDisplayNumber(String number, int subId) {
        if (number == null || !isValidSubscriptionId(subId)) {
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
    public int setDataRoaming(int roaming, int subId) {
        if (VDBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        if (roaming < 0 || !isValidSubscriptionId(subId)) {
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
     * Set Sim Provisioning Status by subscription ID
     * @param simProvisioningStatus with the subscription
     * {@See SubscriptionManager#SIM_PROVISIONED}
     * {@See SubscriptionManager#SIM_UNPROVISIONED_COLD}
     * {@See SubscriptionManager#SIM_UNPROVISIONED_OUT_OF_CREDIT}
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @hide
     */
    public int setSimProvisioningStatus(int simProvisioningStatus, int subId) {
        if (VDBG) {
            logd("[setSimProvisioningStatus]+ status:" + simProvisioningStatus + " subId:" + subId);
        }
        if (simProvisioningStatus < 0 || simProvisioningStatus > MAX_SIM_PROVISIONING_STATUS ||
                !isValidSubscriptionId(subId)) {
            logd("[setSimProvisioningStatus]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setSimProvisioningStatus(simProvisioningStatus, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * Get slotId associated with the subscription.
     * @return slotId as a positive integer or a negative value if an error either
     * SIM_NOT_INSERTED or < 0 if an invalid slot index
     * @hide
     */
    public static int getSlotId(int subId) {
        if (!isValidSubscriptionId(subId)) {
            if (DBG) {
                logd("[getSlotId]- fail");
            }
        }

        int result = INVALID_SIM_SLOT_INDEX;

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
        if (!isValidSubscriptionId(subId)) {
            if (DBG) {
                logd("[getPhoneId]- fail");
            }
            return INVALID_PHONE_INDEX;
        }

        int result = INVALID_PHONE_INDEX;

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
        Rlog.d(LOG_TAG, msg);
    }

    /**
     * Returns the system's default subscription id.
     *
     * For a voice capable device, it will return getDefaultVoiceSubscriptionId.
     * For a data only device, it will return the getDefaultDataSubscriptionId.
     * May return an INVALID_SUBSCRIPTION_ID on error.
     *
     * @return the "system" default subscription id.
     */
    public static int getDefaultSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

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

    /**
     * Returns the system's default voice subscription id.
     *
     * On a data only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default voice subscription Id.
     */
    public static int getDefaultVoiceSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultVoiceSubscriptionId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public void setDefaultVoiceSubId(int subId) {
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

    /**
     * Return the SubscriptionInfo for default voice subscription.
     *
     * Will return null on data only devices, or on error.
     *
     * @return the SubscriptionInfo for the default voice subscription.
     * @hide
     */
    public SubscriptionInfo getDefaultVoiceSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultVoiceSubscriptionId());
    }

    /** @hide */
    public static int getDefaultVoicePhoneId() {
        return getPhoneId(getDefaultVoiceSubscriptionId());
    }

    /**
     * Returns the system's default SMS subscription id.
     *
     * On a data only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default SMS subscription Id.
     */
    public static int getDefaultSmsSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSmsSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSmsSubscriptionId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public void setDefaultSmsSubId(int subId) {
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

    /**
     * Return the SubscriptionInfo for default voice subscription.
     *
     * Will return null on data only devices, or on error.
     *
     * @return the SubscriptionInfo for the default SMS subscription.
     * @hide
     */
    public SubscriptionInfo getDefaultSmsSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultSmsSubscriptionId());
    }

    /** @hide */
    public int getDefaultSmsPhoneId() {
        return getPhoneId(getDefaultSmsSubscriptionId());
    }

    /**
     * Returns the system's default data subscription id.
     *
     * On a voice only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default data subscription Id.
     */
    public static int getDefaultDataSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultDataSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultDataSubscriptionId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public void setDefaultDataSubId(int subId) {
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

    /**
     * Return the SubscriptionInfo for default data subscription.
     *
     * Will return null on voice only devices, or on error.
     *
     * @return the SubscriptionInfo for the default data subscription.
     * @hide
     */
    public SubscriptionInfo getDefaultDataSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultDataSubscriptionId());
    }

    /** @hide */
    public int getDefaultDataPhoneId() {
        return getPhoneId(getDefaultDataSubscriptionId());
    }

    /** @hide */
    public void clearSubscriptionInfo() {
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
    public boolean allDefaultsSelected() {
        if (!isValidSubscriptionId(getDefaultDataSubscriptionId())) {
            return false;
        }
        if (!isValidSubscriptionId(getDefaultSmsSubscriptionId())) {
            return false;
        }
        if (!isValidSubscriptionId(getDefaultVoiceSubscriptionId())) {
            return false;
        }
        return true;
    }

    /**
     * If a default is set to subscription which is not active, this will reset that default back to
     * an invalid subscription id, i.e. < 0.
     * @hide
     */
    public void clearDefaultsForInactiveSubIds() {
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
    public static boolean isValidSubscriptionId(int subId) {
        return subId > INVALID_SUBSCRIPTION_ID ;
    }

    /**
     * @return true if subId is an usable subId value else false. A
     * usable subId means its neither a INVALID_SUBSCRIPTION_ID nor a DEFAULT_SUB_ID.
     * @hide
     */
    public static boolean isUsableSubIdValue(int subId) {
        return subId >= MIN_SUBSCRIPTION_ID_VALUE && subId <= MAX_SUBSCRIPTION_ID_VALUE;
    }

    /** @hide */
    public static boolean isValidSlotId(int slotId) {
        return slotId >= 0 && slotId < TelephonyManager.getDefault().getSimCount();
    }

    /** @hide */
    public static boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount();
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
    public @NonNull int[] getActiveSubscriptionIdList() {
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

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId The subscription ID
     * @return true if the network for the subscription is roaming, false otherwise
     */
    public boolean isNetworkRoaming(int subId) {
        final int phoneId = getPhoneId(subId);
        if (phoneId < 0) {
            // What else can we do?
            return false;
        }
        return TelephonyManager.getDefault().isNetworkRoaming(subId);
    }

    /**
     * Returns a constant indicating the state of sim for the slot idx.
     *
     * @param slotIdx
     *
     * {@See TelephonyManager#SIM_STATE_UNKNOWN}
     * {@See TelephonyManager#SIM_STATE_ABSENT}
     * {@See TelephonyManager#SIM_STATE_PIN_REQUIRED}
     * {@See TelephonyManager#SIM_STATE_PUK_REQUIRED}
     * {@See TelephonyManager#SIM_STATE_NETWORK_LOCKED}
     * {@See TelephonyManager#SIM_STATE_READY}
     * {@See TelephonyManager#SIM_STATE_NOT_READY}
     * {@See TelephonyManager#SIM_STATE_PERM_DISABLED}
     * {@See TelephonyManager#SIM_STATE_CARD_IO_ERROR}
     *
     * {@hide}
     */
    public static int getSimStateForSlotIdx(int slotIdx) {
        int simState = TelephonyManager.SIM_STATE_UNKNOWN;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                simState = iSub.getSimStateForSlotIdx(slotIdx);
            }
        } catch (RemoteException ex) {
        }

        return simState;
    }

    /**
     * Store properties associated with SubscriptionInfo in database
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in database associated with SubscriptionInfo
     * @param propValue Value to store in DB for particular subId & column name
     * @hide
     */
    public static void setSubscriptionProperty(int subId, String propKey, String propValue) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setSubscriptionProperty(subId, propKey, propValue);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Store properties associated with SubscriptionInfo in database
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @return Value associated with subId and propKey column in database
     * @hide
     */
    private static String getSubscriptionProperty(int subId, String propKey,
            Context context) {
        String resultValue = null;
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                resultValue = iSub.getSubscriptionProperty(subId, propKey, 
                    context.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return resultValue;
    }

    /**
     * Returns boolean value corresponding to query result.
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @param defValue Default boolean value to be returned
     * @return boolean result value to be returned
     * @hide
     */
    public static boolean getBooleanSubscriptionProperty(int subId, String propKey,
            boolean defValue, Context context) {
        String result = getSubscriptionProperty(subId, propKey, context);
        if (result != null) {
            try {
                return Integer.parseInt(result) == 1;
            } catch (NumberFormatException err) {
                logd("getBooleanSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns integer value corresponding to query result.
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @param defValue Default integer value to be returned
     * @return integer result value to be returned
     * @hide
     */
    public static int getIntegerSubscriptionProperty(int subId, String propKey, int defValue,
            Context context) {
        String result = getSubscriptionProperty(subId, propKey, context);
        if (result != null) {
            try {
                return Integer.parseInt(result);
            } catch (NumberFormatException err) {
                logd("getBooleanSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns the resources associated with Subscription.
     * @param context Context object
     * @param subId Subscription Id of Subscription who's resources are required
     * @return Resources associated with Subscription.
     * @hide
     */
    public static Resources getResourcesForSubId(Context context, int subId) {
        final SubscriptionInfo subInfo =
                SubscriptionManager.from(context).getActiveSubscriptionInfo(subId);

        Configuration config = context.getResources().getConfiguration();
        Configuration newConfig = new Configuration();
        newConfig.setTo(config);
        if (subInfo != null) {
            newConfig.mcc = subInfo.getMcc();
            newConfig.mnc = subInfo.getMnc();
            if (newConfig.mnc == 0) newConfig.mnc = Configuration.MNC_ZERO;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        DisplayMetrics newMetrics = new DisplayMetrics();
        newMetrics.setTo(metrics);
        return new Resources(context.getResources().getAssets(), newMetrics, newConfig);
    }

    /**
     * @return true if the sub ID is active. i.e. The sub ID corresponds to a known subscription
     * and the SIM providing the subscription is present in a slot and in "LOADED" state.
     * @hide
     */
    public boolean isActiveSubId(int subId) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                return iSub.isActiveSubId(subId);
            }
        } catch (RemoteException ex) {
        }
        return false;
    }
}
