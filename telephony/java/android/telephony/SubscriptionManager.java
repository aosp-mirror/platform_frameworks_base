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

import static android.net.NetworkPolicyManager.SUBSCRIPTION_OVERRIDE_CONGESTED;
import static android.net.NetworkPolicyManager.SUBSCRIPTION_OVERRIDE_UNMETERED;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.ColorInt;
import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.PendingIntent;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Telephony.SimInfo;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;
import android.util.Pair;

import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.util.HandlerExecutor;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SubscriptionManager is the application interface to SubscriptionController
 * and provides information about the current Telephony Subscriptions.
 */
@SystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
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

    /** Indicates invalid sim slot. This can be returned by {@link #getSlotIndex(int)}. */
    public static final int INVALID_SIM_SLOT_INDEX = -1;

    /** Indicates the default subscription ID in Telephony. */
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
    @UnsupportedAppUsage
    public static final Uri CONTENT_URI = SimInfo.CONTENT_URI;

    /** @hide */
    public static final String CACHE_KEY_DEFAULT_SUB_ID_PROPERTY =
            "cache_key.telephony.get_default_sub_id";

    /** @hide */
    public static final String CACHE_KEY_DEFAULT_DATA_SUB_ID_PROPERTY =
            "cache_key.telephony.get_default_data_sub_id";

    /** @hide */
    public static final String CACHE_KEY_DEFAULT_SMS_SUB_ID_PROPERTY =
            "cache_key.telephony.get_default_sms_sub_id";

    /** @hide */
    public static final String CACHE_KEY_ACTIVE_DATA_SUB_ID_PROPERTY =
            "cache_key.telephony.get_active_data_sub_id";

    /** @hide */
    public static final String CACHE_KEY_SLOT_INDEX_PROPERTY =
            "cache_key.telephony.get_slot_index";

    private static final int MAX_CACHE_SIZE = 4;

    private static class VoidPropertyInvalidatedCache<T>
            extends PropertyInvalidatedCache<Void, T> {
        private final FunctionalUtils.ThrowingFunction<ISub, T> mInterfaceMethod;
        private final String mCacheKeyProperty;
        private final T mDefaultValue;

        VoidPropertyInvalidatedCache(
                FunctionalUtils.ThrowingFunction<ISub, T> subscriptionInterfaceMethod,
                String cacheKeyProperty,
                T defaultValue) {
            super(MAX_CACHE_SIZE, cacheKeyProperty);
            mInterfaceMethod = subscriptionInterfaceMethod;
            mCacheKeyProperty = cacheKeyProperty;
            mDefaultValue = defaultValue;
        }

        @Override
        protected T recompute(Void aVoid) {
            T result = mDefaultValue;

            try {
                ISub iSub = TelephonyManager.getSubscriptionService();
                if (iSub != null) {
                    result = mInterfaceMethod.applyOrThrow(iSub);
                }
            } catch (Exception ex) {
                Rlog.w(LOG_TAG, "Failed to recompute cache key for " + mCacheKeyProperty);
            }

            if (VDBG) logd("recomputing " + mCacheKeyProperty + ", result = " + result);
            return result;
        }
    }

    private static class IntegerPropertyInvalidatedCache<T>
            extends PropertyInvalidatedCache<Integer, T> {
        private final FunctionalUtils.ThrowingBiFunction<ISub, Integer, T> mInterfaceMethod;
        private final String mCacheKeyProperty;
        private final T mDefaultValue;

        IntegerPropertyInvalidatedCache(
                FunctionalUtils.ThrowingBiFunction<ISub, Integer, T> subscriptionInterfaceMethod,
                String cacheKeyProperty,
                T defaultValue) {
            super(MAX_CACHE_SIZE, cacheKeyProperty);
            mInterfaceMethod = subscriptionInterfaceMethod;
            mCacheKeyProperty = cacheKeyProperty;
            mDefaultValue = defaultValue;
        }

        @Override
        protected T recompute(Integer query) {
            T result = mDefaultValue;

            try {
                ISub iSub = TelephonyManager.getSubscriptionService();
                if (iSub != null) {
                    result = mInterfaceMethod.applyOrThrow(iSub, query);
                }
            } catch (Exception ex) {
                Rlog.w(LOG_TAG, "Failed to recompute cache key for " + mCacheKeyProperty);
            }

            if (VDBG) logd("recomputing " + mCacheKeyProperty + ", result = " + result);
            return result;
        }
    }

    private static VoidPropertyInvalidatedCache<Integer> sDefaultSubIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getDefaultSubId,
                    CACHE_KEY_DEFAULT_SUB_ID_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static VoidPropertyInvalidatedCache<Integer> sDefaultDataSubIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getDefaultDataSubId,
                    CACHE_KEY_DEFAULT_DATA_SUB_ID_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static VoidPropertyInvalidatedCache<Integer> sDefaultSmsSubIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getDefaultSmsSubId,
                    CACHE_KEY_DEFAULT_SMS_SUB_ID_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static VoidPropertyInvalidatedCache<Integer> sActiveDataSubIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getActiveDataSubscriptionId,
                    CACHE_KEY_ACTIVE_DATA_SUB_ID_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static IntegerPropertyInvalidatedCache<Integer> sSlotIndexCache =
            new IntegerPropertyInvalidatedCache<>(ISub::getSlotIndex,
                    CACHE_KEY_SLOT_INDEX_PROPERTY,
                    INVALID_SIM_SLOT_INDEX);

    /** Cache depends on getDefaultSubId, so we use the defaultSubId cache key */
    private static IntegerPropertyInvalidatedCache<Integer> sPhoneIdCache =
            new IntegerPropertyInvalidatedCache<>(ISub::getPhoneId,
                    CACHE_KEY_DEFAULT_SUB_ID_PROPERTY,
                    INVALID_PHONE_INDEX);

    /**
     * Generates a content {@link Uri} used to receive updates on simInfo change
     * on the given subscriptionId
     * @param subscriptionId the subscriptionId to receive updates on
     * @return the Uri used to observe carrier identity changes
     * @hide
     */
    public static Uri getUriForSubscriptionId(int subscriptionId) {
        return Uri.withAppendedPath(CONTENT_URI, String.valueOf(subscriptionId));
    }

    /**
     * A content {@link Uri} used to receive updates on wfc enabled user setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc enabled {@link ImsMmTelManager#isVoWiFiSettingEnabled()}
     * while your app is running. You can also use a {@link android.app.job.JobService}
     * to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri WFC_ENABLED_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI, "wfc");

    /**
     * A content {@link Uri} used to receive updates on advanced calling user setting
     * @see ImsMmTelManager#isAdvancedCallingSettingEnabled().
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription advanced calling enabled
     * {@link ImsMmTelManager#isAdvancedCallingSettingEnabled()} while your app is running.
     * You can also use a {@link android.app.job.JobService} to ensure your app is notified of
     * changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri ADVANCED_CALLING_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "advanced_calling");

    /**
     * A content {@link Uri} used to receive updates on wfc mode setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc mode {@link ImsMmTelManager#getVoWiFiModeSetting()}
     * while your app is running. You can also use a {@link android.app.job.JobService} to ensure
     * your app is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri WFC_MODE_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI, "wfc_mode");

    /**
     * A content {@link Uri} used to receive updates on wfc roaming mode setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc roaming mode {@link ImsMmTelManager#getVoWiFiRoamingModeSetting()}
     * while your app is running. You can also use a {@link android.app.job.JobService}
     * to ensure your app is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri WFC_ROAMING_MODE_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "wfc_roaming_mode");

    /**
     * A content {@link Uri} used to receive updates on vt(video telephony over IMS) enabled
     * setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription vt enabled {@link ImsMmTelManager#isVtSettingEnabled()}
     * while your app is running. You can also use a {@link android.app.job.JobService} to ensure
     * your app is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri VT_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "vt_enabled");

    /**
     * A content {@link Uri} used to receive updates on wfc roaming enabled setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc roaming enabled {@link ImsMmTelManager#isVoWiFiRoamingSettingEnabled()}
     * while your app is running. You can also use a {@link android.app.job.JobService} to ensure
     * your app is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
     * delivery of updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public static final Uri WFC_ROAMING_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "wfc_roaming_enabled");

    /**
     * TelephonyProvider unique key column name is the subscription id.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String UNIQUE_KEY_SUBSCRIPTION_ID =
            SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID;

    /**
     * TelephonyProvider column name for a unique identifier for the subscription within the
     * specific subscription type. For example, it contains SIM ICC Identifier subscriptions
     * on Local SIMs. and Mac-address for Remote-SIM Subscriptions for Bluetooth devices.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String ICC_ID = SimInfo.COLUMN_ICC_ID;

    /**
     * TelephonyProvider column name for user SIM_SlOT_INDEX
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String SIM_SLOT_INDEX = SimInfo.COLUMN_SIM_SLOT_INDEX;

    /** SIM is not inserted */
    /** @hide */
    public static final int SIM_NOT_INSERTED = SimInfo.SIM_NOT_INSERTED;

    /**
     * The slot-index for Bluetooth Remote-SIM subscriptions
     * @hide
     */
    public static final int SLOT_INDEX_FOR_REMOTE_SIM_SUB = INVALID_SIM_SLOT_INDEX;

    /**
     * TelephonyProvider column name Subscription-type.
     * <P>Type: INTEGER (int)</P> {@link #SUBSCRIPTION_TYPE_LOCAL_SIM} for Local-SIM Subscriptions,
     * {@link #SUBSCRIPTION_TYPE_REMOTE_SIM} for Remote-SIM Subscriptions.
     * Default value is 0.
     */
    /** @hide */
    public static final String SUBSCRIPTION_TYPE = SimInfo.COLUMN_SUBSCRIPTION_TYPE;

    /**
     * TelephonyProvider column name data_enabled_override_rules.
     * It's a list of rules for overriding data enabled settings. The syntax is
     * For example, "mms=nonDefault" indicates enabling data for mms in non-default subscription.
     * "default=nonDefault&inVoiceCall" indicates enabling data for internet in non-default
     * subscription and while is in voice call.
     *
     * Default value is empty string.
     *
     * @hide
     */
    public static final String DATA_ENABLED_OVERRIDE_RULES =
            SimInfo.COLUMN_DATA_ENABLED_OVERRIDE_RULES;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SUBSCRIPTION_TYPE_"},
        value = {
            SUBSCRIPTION_TYPE_LOCAL_SIM,
            SUBSCRIPTION_TYPE_REMOTE_SIM})
    public @interface SubscriptionType {}

    /**
     * This constant is to designate a subscription as a Local-SIM Subscription.
     * <p> A Local-SIM can be a physical SIM inserted into a sim-slot in the device, or eSIM on the
     * device.
     * </p>
     */
    public static final int SUBSCRIPTION_TYPE_LOCAL_SIM = SimInfo.SUBSCRIPTION_TYPE_LOCAL_SIM;

    /**
     * This constant is to designate a subscription as a Remote-SIM Subscription.
     * <p>
     * A Remote-SIM subscription is for a SIM on a phone connected to this device via some
     * connectivity mechanism, for example bluetooth. Similar to Local SIM, this subscription can
     * be used for SMS, Voice and data by proxying data through the connected device.
     * Certain data of the SIM, such as IMEI, are not accessible for Remote SIMs.
     * </p>
     *
     * <p>
     * A Remote-SIM is available only as long the phone stays connected to this device.
     * When the phone disconnects, Remote-SIM subscription is removed from this device and is
     * no longer known. All data associated with the subscription, such as stored SMS, call logs,
     * contacts etc, are removed from this device.
     * </p>
     *
     * <p>
     * If the phone re-connects to this device, a new Remote-SIM subscription is created for
     * the phone. The Subscription Id associated with the new subscription is different from
     * the Subscription Id of the previous Remote-SIM subscription created (and removed) for the
     * phone; i.e., new Remote-SIM subscription treats the reconnected phone as a Remote-SIM that
     * was never seen before.
     * </p>
     */
    public static final int SUBSCRIPTION_TYPE_REMOTE_SIM = SimInfo.SUBSCRIPTION_TYPE_REMOTE_SIM;

    /**
     * TelephonyProvider column name for user displayed name.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String DISPLAY_NAME = SimInfo.COLUMN_DISPLAY_NAME;

    /**
     * TelephonyProvider column name for the service provider name for the SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String CARRIER_NAME = SimInfo.COLUMN_CARRIER_NAME;

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
    public static final String NAME_SOURCE = SimInfo.COLUMN_NAME_SOURCE;

    /**
     * The name_source is from the carrier id.
     * @hide
     */
    public static final int NAME_SOURCE_CARRIER_ID = SimInfo.NAME_SOURCE_CARRIER_ID;

    /**
     * The name_source is from SIM EF_SPN.
     * @hide
     */
    public static final int NAME_SOURCE_SIM_SPN = SimInfo.NAME_SOURCE_SIM_SPN;

    /**
     * The name_source is from user input
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final int NAME_SOURCE_USER_INPUT = SimInfo.NAME_SOURCE_USER_INPUT;

    /**
     * The name_source is carrier (carrier app, carrier config, etc.)
     * @hide
     */
    public static final int NAME_SOURCE_CARRIER = SimInfo.NAME_SOURCE_CARRIER;

    /**
     * The name_source is from SIM EF_PNN.
     * @hide
     */
    public static final int NAME_SOURCE_SIM_PNN = SimInfo.NAME_SOURCE_SIM_PNN;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NAME_SOURCE_"},
            value = {
                    NAME_SOURCE_CARRIER_ID,
                    NAME_SOURCE_SIM_SPN,
                    NAME_SOURCE_USER_INPUT,
                    NAME_SOURCE_CARRIER,
                    NAME_SOURCE_SIM_PNN
            })
    public @interface SimDisplayNameSource {}

    /**
     * TelephonyProvider column name for the color of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String HUE = SimInfo.COLUMN_COLOR;

    /**
     * TelephonyProvider column name for the phone number of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String NUMBER = SimInfo.COLUMN_NUMBER;

    /**
     * TelephonyProvider column name for whether data roaming is enabled.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String DATA_ROAMING = SimInfo.COLUMN_DATA_ROAMING;

    /** Indicates that data roaming is enabled for a subscription */
    public static final int DATA_ROAMING_ENABLE = SimInfo.DATA_ROAMING_ENABLE;

    /** Indicates that data roaming is disabled for a subscription */
    public static final int DATA_ROAMING_DISABLE = SimInfo.DATA_ROAMING_DISABLE;

    /**
     * TelephonyProvider column name for subscription carrier id.
     * @see TelephonyManager#getSimCarrierId()
     * <p>Type: INTEGER (int) </p>
     * @hide
     */
    public static final String CARRIER_ID = SimInfo.COLUMN_CARRIER_ID;

    /**
     * @hide A comma-separated list of EHPLMNs associated with the subscription
     * <P>Type: TEXT (String)</P>
     */
    public static final String EHPLMNS = SimInfo.COLUMN_EHPLMNS;

    /**
     * @hide A comma-separated list of HPLMNs associated with the subscription
     * <P>Type: TEXT (String)</P>
     */
    public static final String HPLMNS = SimInfo.COLUMN_HPLMNS;

    /**
     * TelephonyProvider column name for the MCC associated with a SIM, stored as a string.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String MCC_STRING = SimInfo.COLUMN_MCC_STRING;

    /**
     * TelephonyProvider column name for the MNC associated with a SIM, stored as a string.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String MNC_STRING = SimInfo.COLUMN_MNC_STRING;

    /**
     * TelephonyProvider column name for the MCC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MCC = SimInfo.COLUMN_MCC;

    /**
     * TelephonyProvider column name for the MNC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MNC = SimInfo.COLUMN_MNC;

    /**
     * TelephonyProvider column name for the iso country code associated with a SIM.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String ISO_COUNTRY_CODE = SimInfo.COLUMN_ISO_COUNTRY_CODE;

    /**
     * TelephonyProvider column name for whether a subscription is embedded (that is, present on an
     * eSIM).
     * <p>Type: INTEGER (int), 1 for embedded or 0 for non-embedded.
     * @hide
     */
    public static final String IS_EMBEDDED = SimInfo.COLUMN_IS_EMBEDDED;

    /**
     * TelephonyProvider column name for SIM card identifier. For UICC card it is the ICCID of the
     * current enabled profile on the card, while for eUICC card it is the EID of the card.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String CARD_ID = SimInfo.COLUMN_CARD_ID;

    /**
     * TelephonyProvider column name for the encoded {@link UiccAccessRule}s from
     * {@link UiccAccessRule#encodeRules}. Only present if {@link #IS_EMBEDDED} is 1.
     * <p>TYPE: BLOB
     * @hide
     */
    public static final String ACCESS_RULES = SimInfo.COLUMN_ACCESS_RULES;

    /**
     * TelephonyProvider column name for the encoded {@link UiccAccessRule}s from
     * {@link UiccAccessRule#encodeRules} but for the rules that come from CarrierConfigs.
     * Only present if there are access rules in CarrierConfigs
     * <p>TYPE: BLOB
     * @hide
     */
    public static final String ACCESS_RULES_FROM_CARRIER_CONFIGS =
            SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS;

    /**
     * TelephonyProvider column name identifying whether an embedded subscription is on a removable
     * card. Such subscriptions are marked inaccessible as soon as the current card is removed.
     * Otherwise, they will remain accessible unless explicitly deleted. Only present if
     * {@link #IS_EMBEDDED} is 1.
     * <p>TYPE: INTEGER (int), 1 for removable or 0 for non-removable.
     * @hide
     */
    public static final String IS_REMOVABLE = SimInfo.COLUMN_IS_REMOVABLE;

    /**
     *  TelephonyProvider column name for extreme threat in CB settings
     * @hide
     */
    public static final String CB_EXTREME_THREAT_ALERT =
            SimInfo.COLUMN_CB_EXTREME_THREAT_ALERT;

    /**
     * TelephonyProvider column name for severe threat in CB settings
     *@hide
     */
    public static final String CB_SEVERE_THREAT_ALERT = SimInfo.COLUMN_CB_SEVERE_THREAT_ALERT;

    /**
     * TelephonyProvider column name for amber alert in CB settings
     *@hide
     */
    public static final String CB_AMBER_ALERT = SimInfo.COLUMN_CB_AMBER_ALERT;

    /**
     * TelephonyProvider column name for emergency alert in CB settings
     *@hide
     */
    public static final String CB_EMERGENCY_ALERT = SimInfo.COLUMN_CB_EMERGENCY_ALERT;

    /**
     * TelephonyProvider column name for alert sound duration in CB settings
     *@hide
     */
    public static final String CB_ALERT_SOUND_DURATION =
            SimInfo.COLUMN_CB_ALERT_SOUND_DURATION;

    /**
     * TelephonyProvider column name for alert reminder interval in CB settings
     *@hide
     */
    public static final String CB_ALERT_REMINDER_INTERVAL =
            SimInfo.COLUMN_CB_ALERT_REMINDER_INTERVAL;

    /**
     * TelephonyProvider column name for enabling vibrate in CB settings
     *@hide
     */
    public static final String CB_ALERT_VIBRATE = SimInfo.COLUMN_CB_ALERT_VIBRATE;

    /**
     * TelephonyProvider column name for enabling alert speech in CB settings
     *@hide
     */
    public static final String CB_ALERT_SPEECH = SimInfo.COLUMN_CB_ALERT_SPEECH;

    /**
     * TelephonyProvider column name for ETWS test alert in CB settings
     *@hide
     */
    public static final String CB_ETWS_TEST_ALERT = SimInfo.COLUMN_CB_ETWS_TEST_ALERT;

    /**
     * TelephonyProvider column name for enable channel50 alert in CB settings
     *@hide
     */
    public static final String CB_CHANNEL_50_ALERT = SimInfo.COLUMN_CB_CHANNEL_50_ALERT;

    /**
     * TelephonyProvider column name for CMAS test alert in CB settings
     *@hide
     */
    public static final String CB_CMAS_TEST_ALERT = SimInfo.COLUMN_CB_CMAS_TEST_ALERT;

    /**
     * TelephonyProvider column name for Opt out dialog in CB settings
     *@hide
     */
    public static final String CB_OPT_OUT_DIALOG = SimInfo.COLUMN_CB_OPT_OUT_DIALOG;

    /**
     * TelephonyProvider column name for enable Volte.
     *
     * If this setting is not initialized (set to -1)  then we use the Carrier Config value
     * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
     *@hide
     */
    public static final String ENHANCED_4G_MODE_ENABLED =
            SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED;

    /**
     * TelephonyProvider column name for enable VT (Video Telephony over IMS)
     *@hide
     */
    public static final String VT_IMS_ENABLED = SimInfo.COLUMN_VT_IMS_ENABLED;

    /**
     * TelephonyProvider column name for enable Wifi calling
     *@hide
     */
    public static final String WFC_IMS_ENABLED = SimInfo.COLUMN_WFC_IMS_ENABLED;

    /**
     * TelephonyProvider column name for Wifi calling mode
     *@hide
     */
    public static final String WFC_IMS_MODE = SimInfo.COLUMN_WFC_IMS_MODE;

    /**
     * TelephonyProvider column name for Wifi calling mode in roaming
     *@hide
     */
    public static final String WFC_IMS_ROAMING_MODE = SimInfo.COLUMN_WFC_IMS_ROAMING_MODE;

    /**
     * TelephonyProvider column name for enable Wifi calling in roaming
     *@hide
     */
    public static final String WFC_IMS_ROAMING_ENABLED = SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED;

    /**
     * Determines if the user has enabled IMS RCS User Capability Exchange (UCE) for this
     * subscription.
     * @hide
     */
    public static final String IMS_RCS_UCE_ENABLED = SimInfo.COLUMN_IMS_RCS_UCE_ENABLED;

    /**
     * TelephonyProvider column name for whether a subscription is opportunistic, that is,
     * whether the network it connects to is limited in functionality or coverage.
     * For example, CBRS.
     * <p>Type: INTEGER (int), 1 for opportunistic or 0 for non-opportunistic.
     * @hide
     */
    public static final String IS_OPPORTUNISTIC = SimInfo.COLUMN_IS_OPPORTUNISTIC;

    /**
     * TelephonyProvider column name for group ID. Subscriptions with same group ID
     * are considered bundled together, and should behave as a single subscription at
     * certain scenarios.
     *
     * @hide
     */
    public static final String GROUP_UUID = SimInfo.COLUMN_GROUP_UUID;

    /**
     * TelephonyProvider column name for group owner. It's the package name who created
     * the subscription group.
     *
     * @hide
     */
    public static final String GROUP_OWNER = SimInfo.COLUMN_GROUP_OWNER;

    /**
     * TelephonyProvider column name for the profile class of a subscription
     * Only present if {@link #IS_EMBEDDED} is 1.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String PROFILE_CLASS = SimInfo.COLUMN_PROFILE_CLASS;

    /**
     * Profile class of the subscription
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PROFILE_CLASS_" }, value = {
            SimInfo.PROFILE_CLASS_TESTING,
            SimInfo.PROFILE_CLASS_PROVISIONING,
            SimInfo.PROFILE_CLASS_OPERATIONAL,
            SimInfo.PROFILE_CLASS_UNSET,
    })
    public @interface ProfileClass {}

    /**
     * A testing profile can be pre-loaded or downloaded onto
     * the eUICC and provides connectivity to test equipment
     * for the purpose of testing the device and the eUICC. It
     * is not intended to store any operator credentials.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_TESTING = SimInfo.PROFILE_CLASS_TESTING;

    /**
     * A provisioning profile is pre-loaded onto the eUICC and
     * provides connectivity to a mobile network solely for the
     * purpose of provisioning profiles.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_PROVISIONING = SimInfo.PROFILE_CLASS_PROVISIONING;

    /**
     * An operational profile can be pre-loaded or downloaded
     * onto the eUICC and provides services provided by the
     * operator.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_OPERATIONAL = SimInfo.PROFILE_CLASS_OPERATIONAL;

    /**
     * The profile class is unset. This occurs when profile class
     * info is not available. The subscription either has no profile
     * metadata or the profile metadata did not encode profile class.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_UNSET = SimInfo.PROFILE_CLASS_UNSET;

    /**
     * Default profile class
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final int PROFILE_CLASS_DEFAULT = SimInfo.PROFILE_CLASS_UNSET;

    /**
     * IMSI (International Mobile Subscriber Identity).
     * <P>Type: TEXT </P>
     * @hide
     */
    //TODO: add @SystemApi
    public static final String IMSI = SimInfo.COLUMN_IMSI;

    /**
     * Whether uicc applications is set to be enabled or disabled. By default it's enabled.
     * @hide
     */
    public static final String UICC_APPLICATIONS_ENABLED = SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED;

    /**
     * Indicate which network type is allowed. By default it's enabled.
     * @hide
     */
    public static final String ALLOWED_NETWORK_TYPES = SimInfo.COLUMN_ALLOWED_NETWORK_TYPES;

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

    /**
     * Broadcast Action: The default subscription has changed.  This has the following
     * extra values:</p>
     * The {@link #EXTRA_SUBSCRIPTION_INDEX} extra indicates the current default subscription index
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED
            = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: The default sms subscription has changed.  This has the following
     * extra values:</p>
     * {@link #EXTRA_SUBSCRIPTION_INDEX} extra indicates the current default sms
     * subscription index
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED
            = "android.telephony.action.DEFAULT_SMS_SUBSCRIPTION_CHANGED";

    /**
     * Activity Action: Display UI for managing the billing relationship plans
     * between a carrier and a specific subscriber.
     * <p>
     * Carrier apps are encouraged to implement this activity, and the OS will
     * provide an affordance to quickly enter this activity, typically via
     * Settings. This affordance will only be shown when the carrier app is
     * actively providing subscription plan information via
     * {@link #setSubscriptionPlans(int, List)}.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * the user is interested in.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_SUBSCRIPTION_PLANS
            = "android.telephony.action.MANAGE_SUBSCRIPTION_PLANS";

    /**
     * Broadcast Action: Request a refresh of the billing relationship plans
     * between a carrier and a specific subscriber.
     * <p>
     * Carrier apps are encouraged to implement this receiver, and the OS will
     * provide an affordance to request a refresh. This affordance will only be
     * shown when the carrier app is actively providing subscription plan
     * information via {@link #setSubscriptionPlans(int, List)}.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * the user is interested in.
     * <p>
     * Receivers should protect themselves by checking that the sender holds the
     * {@code android.permission.MANAGE_SUBSCRIPTION_PLANS} permission.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_REFRESH_SUBSCRIPTION_PLANS
            = "android.telephony.action.REFRESH_SUBSCRIPTION_PLANS";

    /**
     * Broadcast Action: The billing relationship plans between a carrier and a
     * specific subscriber has changed.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * changed.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.MANAGE_SUBSCRIPTION_PLANS)
    public static final String ACTION_SUBSCRIPTION_PLANS_CHANGED
            = "android.telephony.action.SUBSCRIPTION_PLANS_CHANGED";

    /**
     * Integer extra used with {@link #ACTION_DEFAULT_SUBSCRIPTION_CHANGED} and
     * {@link #ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED} to indicate the subscription
     * which has changed.
     */
    public static final String EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX";

    /**
     * Integer extra to specify SIM slot index.
     */
    public static final String EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX";

    private final Context mContext;

    // Cache of Resource that has been created in getResourcesForSubId. Key is a Pair containing
    // the Context and subId.
    private static final Map<Pair<Context, Integer>, Resources> sResourcesCache =
            new ConcurrentHashMap<>();

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
        private class OnSubscriptionsChangedListenerHandler extends Handler {
            OnSubscriptionsChangedListenerHandler() {
                super();
            }

            OnSubscriptionsChangedListenerHandler(Looper looper) {
                super(looper);
            }
        }

        /**
         * Posted executor callback on the handler associated with a given looper.
         * The looper can be the calling thread's looper or the looper passed from the
         * constructor {@link #OnSubscriptionsChangedListener(Looper)}.
         */
        private final HandlerExecutor mExecutor;

        /**
         * @hide
         */
        public HandlerExecutor getHandlerExecutor() {
            return mExecutor;
        }

        public OnSubscriptionsChangedListener() {
            mExecutor = new HandlerExecutor(new OnSubscriptionsChangedListenerHandler());
        }

        /**
         * Allow a listener to be created with a custom looper
         * @param looper the looper that the underlining handler should run on
         * @hide
         */
        public OnSubscriptionsChangedListener(Looper looper) {
            mExecutor = new HandlerExecutor(new OnSubscriptionsChangedListenerHandler(looper));
        }

        /**
         * Callback invoked when there is any change to any SubscriptionInfo, as well as once on
         * registering for changes with {@link #addOnSubscriptionsChangedListener}. Typically
         * this method would invoke {@link #getActiveSubscriptionInfoList}
         */
        public void onSubscriptionsChanged() {
            if (DBG) log("onSubscriptionsChanged: NOT OVERRIDDEN");
        }

        /**
         * Callback invoked when {@link SubscriptionManager#addOnSubscriptionsChangedListener(
         * Executor, OnSubscriptionsChangedListener)} or
         * {@link SubscriptionManager#addOnSubscriptionsChangedListener(
         * OnSubscriptionsChangedListener)} fails to complete due to the
         * {@link Context#TELEPHONY_REGISTRY_SERVICE} being unavailable.
         * @hide
         */
        public void onAddListenerFailed() {
            Rlog.w(LOG_TAG, "onAddListenerFailed not overridden");
        }

        private void log(String s) {
            Rlog.d(LOG_TAG, s);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public SubscriptionManager(Context context) {
        if (DBG) logd("SubscriptionManager created");
        mContext = context;
    }

    private NetworkPolicyManager getNetworkPolicyManager() {
        return (NetworkPolicyManager) mContext
                .getSystemService(Context.NETWORK_POLICY_SERVICE);
    }

    /**
     * @deprecated developers should always obtain references directly from
     *             {@link Context#getSystemService(Class)}.
     */
    @Deprecated
    public static SubscriptionManager from(Context context) {
        return (SubscriptionManager) context
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification. The
     * onSubscriptionChanged method will also be triggered once initially when calling this
     * function.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     *                 onSubscriptionsChanged overridden.
     */
    public void addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        if (listener == null) return;
        addOnSubscriptionsChangedListener(listener.mExecutor, listener);
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification. The
     * onSubscriptionChanged method will also be triggered once initially when calling this
     * function.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     *                 onSubscriptionsChanged overridden.
     * @param executor the executor that will execute callbacks.
     */
    public void addOnSubscriptionsChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSubscriptionsChangedListener listener) {
        String pkgName = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("register OnSubscriptionsChangedListener pkgName=" + pkgName
                    + " listener=" + listener);
        }
        // We use the TelephonyRegistry as it runs in the system and thus is always
        // available. Where as SubscriptionController could crash and not be available
        TelephonyRegistryManager telephonyRegistryManager = (TelephonyRegistryManager)
                mContext.getSystemService(Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistryManager != null) {
            telephonyRegistryManager.addOnSubscriptionsChangedListener(listener,
                    executor);
        } else {
            // If the telephony registry isn't available, we will inform the caller on their
            // listener that it failed so they can try to re-register.
            loge("addOnSubscriptionsChangedListener: pkgname=" + pkgName + " failed to be added "
                    + " due to TELEPHONY_REGISTRY_SERVICE being unavailable.");
            executor.execute(() -> listener.onAddListenerFailed());
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
        if (listener == null) return;
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("unregister OnSubscriptionsChangedListener pkgForDebug=" + pkgForDebug
                    + " listener=" + listener);
        }
        // We use the TelephonyRegistry as it runs in the system and thus is always
        // available where as SubscriptionController could crash and not be available
        TelephonyRegistryManager telephonyRegistryManager = (TelephonyRegistryManager)
                mContext.getSystemService(Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistryManager != null) {
            telephonyRegistryManager.removeOnSubscriptionsChangedListener(listener);
        }
    }

    /**
     * A listener class for monitoring changes to {@link SubscriptionInfo} records of opportunistic
     * subscriptions.
     * <p>
     * Override the onOpportunisticSubscriptionsChanged method in the object that extends this
     * or {@link #addOnOpportunisticSubscriptionsChangedListener(
     * Executor, OnOpportunisticSubscriptionsChangedListener)}
     * to register your listener and to unregister invoke
     * {@link #removeOnOpportunisticSubscriptionsChangedListener(
     * OnOpportunisticSubscriptionsChangedListener)}
     * <p>
     * Permissions android.Manifest.permission.READ_PHONE_STATE is required
     * for #onOpportunisticSubscriptionsChanged to be invoked.
     */
    public static class OnOpportunisticSubscriptionsChangedListener {
        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link #getActiveSubscriptionInfoList}
         */
        public void onOpportunisticSubscriptionsChanged() {
            if (DBG) log("onOpportunisticSubscriptionsChanged: NOT OVERRIDDEN");
        }

        private void log(String s) {
            Rlog.d(LOG_TAG, s);
        }
    }

    /**
     * Register for changes to the list of opportunistic subscription records or to the
     * individual records themselves. When a change occurs the onOpportunisticSubscriptionsChanged
     * method of the listener will be invoked immediately if there has been a notification.
     *
     * @param listener an instance of {@link OnOpportunisticSubscriptionsChangedListener} with
     *                 onOpportunisticSubscriptionsChanged overridden.
     */
    public void addOnOpportunisticSubscriptionsChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnOpportunisticSubscriptionsChangedListener listener) {
        if (executor == null || listener == null) {
            return;
        }

        String pkgName = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("register addOnOpportunisticSubscriptionsChangedListener pkgName=" + pkgName
                    + " listener=" + listener);
        }

        // We use the TelephonyRegistry as it runs in the system and thus is always
        // available where as SubscriptionController could crash and not be available
        TelephonyRegistryManager telephonyRegistryManager = (TelephonyRegistryManager)
                mContext.getSystemService(Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistryManager != null) {
            telephonyRegistryManager.addOnOpportunisticSubscriptionsChangedListener(
                    listener, executor);
        }
    }

    /**
     * Unregister the {@link OnOpportunisticSubscriptionsChangedListener} that is currently
     * listening opportunistic subscriptions change. This is not strictly necessary
     * as the listener will automatically be unregistered if an attempt to invoke the listener
     * fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnOpportunisticSubscriptionsChangedListener(
            @NonNull OnOpportunisticSubscriptionsChangedListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("unregister OnOpportunisticSubscriptionsChangedListener pkgForDebug="
                    + pkgForDebug + " listener=" + listener);
        }
        TelephonyRegistryManager telephonyRegistryManager = (TelephonyRegistryManager)
                mContext.getSystemService(Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistryManager != null) {
            telephonyRegistryManager.removeOnOpportunisticSubscriptionsChangedListener(listener);
        }
    }

    /**
     * Get the active SubscriptionInfo with the input subId.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param subId The unique SubscriptionInfo key in database.
     * @return SubscriptionInfo, maybe null if its not active.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
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
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subInfo = iSub.getActiveSubscriptionInfo(subId, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subInfo;
    }

    /**
     * Gets an active SubscriptionInfo {@link SubscriptionInfo} associated with the Sim IccId.
     *
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @Nullable
    @SystemApi
    public SubscriptionInfo getActiveSubscriptionInfoForIcc(@NonNull String iccId) {
        if (VDBG) logd("[getActiveSubscriptionInfoForIccIndex]+ iccId=" + iccId);
        if (iccId == null) {
            logd("[getActiveSubscriptionInfoForIccIndex]- null iccid");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForIccId(iccId, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIndex
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param slotIndex the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex) {
        if (VDBG) logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIndex=" + slotIndex);
        if (!isValidSlotIndex(slotIndex)) {
            logd("[getActiveSubscriptionInfoForSimSlotIndex]- invalid slotIndex");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForSimSlotIndex(slotIndex,
                        mContext.getOpPackageName(), mContext.getAttributionTag());
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
    @UnsupportedAppUsage
    public List<SubscriptionInfo> getAllSubscriptionInfoList() {
        if (VDBG) logd("[getAllSubscriptionInfoList]+");

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getAllSubInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (result == null) {
            result = new ArrayList<>();
        }
        return result;
    }

    /**
     * Get the SubscriptionInfo(s) of the currently active SIM(s). The records will be sorted
     * by {@link SubscriptionInfo#getSimSlotIndex} then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}). In the latter case, only records accessible
     * to the calling app are returned.
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
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        return getActiveSubscriptionInfoList(/* userVisibleonly */true);
    }

    /**
     * Get both hidden and visible SubscriptionInfo(s) of the currently active SIM(s).
     * The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * Hidden subscriptions refer to those are not meant visible to the users.
     * For example, an opportunistic subscription that is grouped with other
     * subscriptions should remain invisible to users as they are only functionally
     * supplementary to primary ones.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}). In the latter case, only records accessible
     * to the calling app are returned.
     *
     * @return Sorted list of the currently available {@link SubscriptionInfo}
     * records on the device.
     * This is similar to {@link #getActiveSubscriptionInfoList} except that it will return
     * both active and hidden SubscriptionInfos.
     *
     */
    public @NonNull List<SubscriptionInfo> getCompleteActiveSubscriptionInfoList() {
        List<SubscriptionInfo> completeList = getActiveSubscriptionInfoList(
                /* userVisibleonly */false);
        if (completeList == null) {
            completeList = new ArrayList<>();
        }
        return completeList;
    }

    /**
    * This is similar to {@link #getActiveSubscriptionInfoList()}, but if userVisibleOnly
    * is true, it will filter out the hidden subscriptions.
    *
    * @hide
    */
    public @Nullable List<SubscriptionInfo> getActiveSubscriptionInfoList(boolean userVisibleOnly) {
        List<SubscriptionInfo> activeList = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                activeList = iSub.getActiveSubscriptionInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (!userVisibleOnly || activeList == null) {
            return activeList;
        } else {
            return activeList.stream().filter(subInfo -> isSubscriptionVisible(subInfo))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the SubscriptionInfo(s) of all available subscriptions, if any.
     *
     * <p>Available subscriptions include active ones (those with a non-negative
     * {@link SubscriptionInfo#getSimSlotIndex()}) as well as inactive but installed embedded
     * subscriptions.
     *
     * <p>The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the current {@link SubscriptionInfo} records available on the
     * device.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a
     * {@link OnSubscriptionsChangedListener} has been registered
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be invoked in the future.
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </ul>
     *
     * <p>
     * Permissions android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE is required
     * for #getAvailableSubscriptionInfoList to be invoked.
     * @hide
     */
    @SystemApi
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getAvailableSubscriptionInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * Gets the SubscriptionInfo(s) of all embedded subscriptions accessible to the calling app, if
     * any.
     *
     * <p>Only those subscriptions for which the calling app has carrier privileges per the
     * subscription metadata, if any, will be included in the returned list.
     *
     * <p>The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the current embedded {@link SubscriptionInfo} records available on the
     * device which are accessible to the caller.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a
     * {@link OnSubscriptionsChangedListener} has been registered
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be invoked in the future.
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </ul>
     */
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getAccessibleSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * Request a refresh of the platform cache of profile information for the eUICC which
     * corresponds to the card ID returned by {@link TelephonyManager#getCardIdForDefaultEuicc()}.
     *
     * <p>Should be called by the EuiccService implementation whenever this information changes due
     * to an operation done outside the scope of a request initiated by the platform to the
     * EuiccService. There is no need to refresh for downloads, deletes, or other operations that
     * were made through the EuiccService.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @see {@link TelephonyManager#getCardIdForDefaultEuicc()} for more information on the card ID.
     *
     * @hide
     */
    @SystemApi
    public void requestEmbeddedSubscriptionInfoListRefresh() {
        int cardId = TelephonyManager.from(mContext).getCardIdForDefaultEuicc();
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.requestEmbeddedSubscriptionInfoListRefresh(cardId);
            }
        } catch (RemoteException ex) {
            logd("requestEmbeddedSubscriptionInfoListFresh for card = " + cardId + " failed.");
        }
    }

    /**
     * Request a refresh of the platform cache of profile information for the eUICC with the given
     * {@code cardId}.
     *
     * <p>Should be called by the EuiccService implementation whenever this information changes due
     * to an operation done outside the scope of a request initiated by the platform to the
     * EuiccService. There is no need to refresh for downloads, deletes, or other operations that
     * were made through the EuiccService.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param cardId the card ID of the eUICC.
     *
     * @see {@link TelephonyManager#getCardIdForDefaultEuicc()} for more information on the card ID.
     *
     * @hide
     */
    @SystemApi
    public void requestEmbeddedSubscriptionInfoListRefresh(int cardId) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.requestEmbeddedSubscriptionInfoListRefresh(cardId);
            }
        } catch (RemoteException ex) {
            logd("requestEmbeddedSubscriptionInfoListFresh for card = " + cardId + " failed.");
        }
    }

    /**
     * @return the count of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     * @hide
     */
    @UnsupportedAppUsage
    public int getAllSubscriptionInfoCount() {
        if (VDBG) logd("[getAllSubscriptionInfoCount]+");

        int result = 0;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getAllSubInfoCount(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}). In the latter case, the count will include
     * only those subscriptions accessible to the caller.
     *
     * @return the current number of active subscriptions. There is no guarantee the value
     * returned by this method will be the same as the length of the list returned by
     * {@link #getActiveSubscriptionInfoList}.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getActiveSubscriptionInfoCount() {
        int result = 0;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getActiveSubInfoCount(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
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
            ISub iSub = TelephonyManager.getSubscriptionService();
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
     * @param slotIndex the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     * @hide
     */
    public Uri addSubscriptionInfoRecord(String iccId, int slotIndex) {
        if (VDBG) logd("[addSubscriptionInfoRecord]+ iccId:" + iccId + " slotIndex:" + slotIndex);
        if (iccId == null) {
            logd("[addSubscriptionInfoRecord]- null iccId");
        }
        if (!isValidSlotIndex(slotIndex)) {
            logd("[addSubscriptionInfoRecord]- invalid slotIndex");
        }

        addSubscriptionInfoRecord(iccId, null, slotIndex, SUBSCRIPTION_TYPE_LOCAL_SIM);

        // FIXME: Always returns null?
        return null;

    }

    /**
     * Add a new SubscriptionInfo to SubscriptionInfo database if needed
     * @param uniqueId This is the unique identifier for the subscription within the
     *                 specific subscription type.
     * @param displayName human-readable name of the device the subscription corresponds to.
     * @param slotIndex the slot assigned to this subscription. It is ignored for subscriptionType
     *                  of {@link #SUBSCRIPTION_TYPE_REMOTE_SIM}.
     * @param subscriptionType the {@link #SUBSCRIPTION_TYPE}
     * @hide
     */
    public void addSubscriptionInfoRecord(String uniqueId, String displayName, int slotIndex,
            int subscriptionType) {
        if (VDBG) {
            logd("[addSubscriptionInfoRecord]+ uniqueId:" + uniqueId
                    + ", displayName:" + displayName + ", slotIndex:" + slotIndex
                    + ", subscriptionType: " + subscriptionType);
        }
        if (uniqueId == null) {
            Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- uniqueId is null");
            return;
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub == null) {
                Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- ISub service is null");
                return;
            }
            int result = iSub.addSubInfo(uniqueId, displayName, slotIndex, subscriptionType);
            if (result < 0) {
                Log.e(LOG_TAG, "Adding of subscription didn't succeed: error = " + result);
            } else {
                logd("successfully added new subscription");
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Remove SubscriptionInfo record from the SubscriptionInfo database
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                 subscription type.
     * @param subscriptionType the {@link #SUBSCRIPTION_TYPE}
     * @hide
     */
    public void removeSubscriptionInfoRecord(String uniqueId, int subscriptionType) {
        if (VDBG) {
            logd("[removeSubscriptionInfoRecord]+ uniqueId:" + uniqueId
                    + ", subscriptionType: " + subscriptionType);
        }
        if (uniqueId == null) {
            Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- uniqueId is null");
            return;
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub == null) {
                Log.e(LOG_TAG, "[removeSubscriptionInfoRecord]- ISub service is null");
                return;
            }
            int result = iSub.removeSubInfo(uniqueId, subscriptionType);
            if (result < 0) {
                Log.e(LOG_TAG, "Removal of subscription didn't succeed: error = " + result);
            } else {
                logd("successfully removed subscription");
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Set SIM icon tint color for subscription ID
     * @param tint the RGB value of icon tint color of the SIM
     * @param subId the unique Subscritpion ID in database
     * @return the number of records updated
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setIconTint(@ColorInt int tint, int subId) {
        if (VDBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setIconTint",
                (iSub)-> iSub.setIconTint(tint, subId)
        );
    }

    /**
     * Set the display name for a subscription ID
     * @param displayName the display name of SIM card
     * @param subId the unique Subscritpion ID in database
     * @param nameSource SIM display name source
     * @return the number of records updated or < 0 if invalid subId
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDisplayName(@Nullable String displayName, int subId,
            @SimDisplayNameSource int nameSource) {
        if (VDBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                    + " nameSource:" + nameSource);
        }
        return setSubscriptionPropertyHelper(subId, "setDisplayName",
                (iSub)-> iSub.setDisplayNameUsingSrc(displayName, subId, nameSource)
        );
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    @UnsupportedAppUsage
    public int setDisplayNumber(String number, int subId) {
        if (number == null) {
            logd("[setDisplayNumber]- fail");
            return -1;
        }
        return setSubscriptionPropertyHelper(subId, "setDisplayNumber",
                (iSub)-> iSub.setDisplayNumber(number, subId)
        );
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    @UnsupportedAppUsage
    public int setDataRoaming(int roaming, int subId) {
        if (VDBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setDataRoaming",
                (iSub)->iSub.setDataRoaming(roaming, subId)
        );
    }

    /**
     * Get slotIndex associated with the subscription.
     *
     * @param subscriptionId the unique SubscriptionInfo index in database
     * @return slotIndex as a positive integer or {@link #INVALID_SIM_SLOT_INDEX} if the supplied
     * subscriptionId doesn't have an associated slot index.
     */
    public static int getSlotIndex(int subscriptionId) {
        return sSlotIndexCache.query(subscriptionId);
    }

    /**
     * Get an array of Subscription Ids for specified slot Index.
     * @param slotIndex the slot index.
     * @return subscription Ids or null if the given slot Index is not valid or there are no active
     * subscriptions in the slot.
     */
    @Nullable
    public int[] getSubscriptionIds(int slotIndex) {
        return getSubId(slotIndex);
    }

    /** @hide */
    @UnsupportedAppUsage
    public static int[] getSubId(int slotIndex) {
        if (!isValidSlotIndex(slotIndex)) {
            logd("[getSubId]- fail");
            return null;
        }

        int[] subId = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subId = iSub.getSubId(slotIndex);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subId;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static int getPhoneId(int subId) {
        return sPhoneIdCache.query(subId);
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private static void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
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
        return sDefaultSubIdCache.query(null);
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
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultVoiceSubscriptionId, sub id = " + subId);
        return subId;
    }

    /**
     * Sets the system's default voice subscription id.
     *
     * On a data-only device, this is a no-op.
     *
     * May throw a {@link RuntimeException} if the provided subscription id is equal to
     * {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID}
     *
     * @param subscriptionId A valid subscription ID to set as the system default, or
     *                       {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultVoiceSubscriptionId(int subscriptionId) {
        if (VDBG) logd("setDefaultVoiceSubId sub id = " + subscriptionId);
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setDefaultVoiceSubId(subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Same as {@link #setDefaultVoiceSubscriptionId(int)}, but preserved for backwards
     * compatibility.
     * @hide
     */
    public void setDefaultVoiceSubId(int subId) {
        setDefaultVoiceSubscriptionId(subId);
    }

    /**
     * Return the SubscriptionInfo for default voice subscription.
     *
     * Will return null on data only devices, or on error.
     *
     * @return the SubscriptionInfo for the default voice subscription.
     * @hide
     */
    @UnsupportedAppUsage
    public SubscriptionInfo getDefaultVoiceSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultVoiceSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage
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
        return sDefaultSmsSubIdCache.query(null);
    }

    /**
     * Set the subscription which will be used by default for SMS, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultSmsSubId(int subscriptionId) {
        if (VDBG) logd("setDefaultSmsSubId sub id = " + subscriptionId);
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setDefaultSmsSubId(subscriptionId);
            }
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
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
    @UnsupportedAppUsage
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
        return sDefaultDataSubIdCache.query(null);
    }

    /**
     * Set the subscription which will be used by default for data, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultDataSubId(int subscriptionId) {
        if (VDBG) logd("setDataSubscription sub id = " + subscriptionId);
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setDefaultDataSubId(subscriptionId);
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
    @UnsupportedAppUsage
    public SubscriptionInfo getDefaultDataSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultDataSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getDefaultDataPhoneId() {
        return getPhoneId(getDefaultDataSubscriptionId());
    }

    /** @hide */
    public void clearSubscriptionInfo() {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
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
     * Check if the supplied subscription ID is valid.
     *
     * <p>A valid subscription ID is not necessarily an active subscription ID
     * (see {@link #isActiveSubscriptionId(int)}) or an usable subscription ID
     * (see {@link #isUsableSubscriptionId(int)}). Unless specifically noted, subscription
     * APIs work with a valid subscription ID.
     *
     * @param subscriptionId The subscription ID.
     * @return {@code true} if the supplied subscriptionId is valid; {@code false} otherwise.
     */
    public static boolean isValidSubscriptionId(int subscriptionId) {
        return subscriptionId > INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Check if the supplied subscription ID is usable.
     *
     * <p>A usable subscription ID is a valid subscription ID, but not necessarily an active
     * subscription ID (see {@link #isActiveSubscriptionId(int)}). Some subscription APIs
     * require a usable subscription ID, and this is noted in their documentation; otherwise, a
     * subscription ID does not need to be usable for subscription functions, only valid.
     *
     * @param subscriptionId the subscription ID
     * @return {@code true} if the subscription ID is usable; {@code false} otherwise.
     */
    public static boolean isUsableSubscriptionId(int subscriptionId) {
        return isUsableSubIdValue(subscriptionId);
    }

    /**
     * @return true if subId is an usable subId value else false. A
     * usable subId means its neither a INVALID_SUBSCRIPTION_ID nor a DEFAULT_SUB_ID.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static boolean isUsableSubIdValue(int subId) {
        return subId >= MIN_SUBSCRIPTION_ID_VALUE && subId <= MAX_SUBSCRIPTION_ID_VALUE;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static boolean isValidSlotIndex(int slotIndex) {
        return slotIndex >= 0 && slotIndex < TelephonyManager.getDefault().getActiveModemCount();
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < TelephonyManager.getDefault().getActiveModemCount();
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId) {
        int[] subIds = SubscriptionManager.getSubId(phoneId);
        if (subIds != null && subIds.length > 0) {
            putPhoneIdAndSubIdExtra(intent, phoneId, subIds[0]);
        } else {
            logd("putPhoneIdAndSubIdExtra: no valid subs");
            intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
            intent.putExtra(EXTRA_SLOT_INDEX, phoneId);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId, int subId) {
        if (VDBG) logd("putPhoneIdAndSubIdExtra: phoneId=" + phoneId + " subId=" + subId);
        intent.putExtra(EXTRA_SLOT_INDEX, phoneId);
        intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
        putSubscriptionIdExtra(intent, subId);
    }

    /**
     * Get visible subscription Id(s) of the currently active SIM(s).
     *
     * @return the list of subId's that are active,
     *         is never null but the length may be 0.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NonNull int[] getActiveSubscriptionIdList() {
        return getActiveSubscriptionIdList(/* visibleOnly */ true);
    }

    /**
     * Get both hidden and visible subscription Id(s) of the currently active SIM(s).
     *
     * Hidden subscriptions refer to those are not meant visible to the users.
     * For example, an opportunistic subscription that is grouped with other
     * subscriptions should remain invisible to users as they are only functionally
     * supplementary to primary ones.
     *
     * @return the list of subId's that are active,
     *         is never null but the length may be 0.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NonNull int[] getCompleteActiveSubscriptionIdList() {
        return getActiveSubscriptionIdList(/* visibleOnly */false);
    }

    /**
     * @return a non-null list of subId's that are active.
     *
     * @hide
     */
    public @NonNull int[] getActiveSubscriptionIdList(boolean visibleOnly) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                int[] subId = iSub.getActiveSubIdList(visibleOnly);
                if (subId != null) return subId;
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return new int[0];
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
     * Returns a constant indicating the state of sim for the slot index.
     *
     * @param slotIndex
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
    public static int getSimStateForSlotIndex(int slotIndex) {
        int simState = TelephonyManager.SIM_STATE_UNKNOWN;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                simState = iSub.getSimStateForSlotIndex(slotIndex);
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
            ISub iSub = TelephonyManager.getSubscriptionService();
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
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                resultValue = iSub.getSubscriptionProperty(subId, propKey,
                        context.getOpPackageName(), context.getAttributionTag());
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
                logd("getIntegerSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns long value corresponding to query result.
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @param defValue Default long value to be returned
     * @return long result value to be returned
     * @hide
     */
    public static long getLongSubscriptionProperty(int subId, String propKey, long defValue,
                                                     Context context) {
        String result = getSubscriptionProperty(subId, propKey, context);
        if (result != null) {
            try {
                return Long.parseLong(result);
            } catch (NumberFormatException err) {
                logd("getLongSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns the {@link Resources} from the given {@link Context} for the MCC/MNC associated with
     * the subscription. If the subscription ID is invalid, the base resources are returned instead.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param context Context object
     * @param subId Subscription Id of Subscription whose resources are required
     * @return Resources associated with Subscription.
     * @hide
     */
    @NonNull
    @SystemApi
    public static Resources getResourcesForSubId(@NonNull Context context, int subId) {
        return getResourcesForSubId(context, subId, false);
    }

    /**
     * Returns the resources associated with Subscription.
     * @param context Context object
     * @param subId Subscription Id of Subscription who's resources are required
     * @param useRootLocale if root locale should be used. Localized locale is used if false.
     * @return Resources associated with Subscription.
     * @hide
     */
    @NonNull
    public static Resources getResourcesForSubId(Context context, int subId,
            boolean useRootLocale) {
        // Check if resources for this context and subId already exist in the resource cache.
        // Resources that use the root locale are not cached.
        Pair<Context, Integer> cacheKey = null;
        if (isValidSubscriptionId(subId) && !useRootLocale) {
            cacheKey = Pair.create(context, subId);
            if (sResourcesCache.containsKey(cacheKey)) {
                // Cache hit. Use cached Resources.
                return sResourcesCache.get(cacheKey);
            }
        }

        final SubscriptionInfo subInfo =
                SubscriptionManager.from(context).getActiveSubscriptionInfo(subId);

        Configuration overrideConfig = new Configuration();
        if (subInfo != null) {
            overrideConfig.mcc = subInfo.getMcc();
            overrideConfig.mnc = subInfo.getMnc();
            if (overrideConfig.mnc == 0) overrideConfig.mnc = Configuration.MNC_ZERO;
        }

        if (useRootLocale) {
            overrideConfig.setLocale(Locale.ROOT);
        }

        // Create new context with new configuration so that we can avoid modifying the passed in
        // context.
        // Note that if the original context configuration changes, the resources here will also
        // change for all values except those overridden by newConfig (e.g. if the device has an
        // orientation change).
        Context newContext = context.createConfigurationContext(overrideConfig);
        Resources res = newContext.getResources();

        if (cacheKey != null) {
            // Save the newly created Resources in the resource cache.
            sResourcesCache.put(cacheKey, res);
        }
        return res;
    }

    /**
     * Checks if the supplied subscription ID corresponds to a subscription which is actively in
     * use on the device. An active subscription ID is a valid and usable subscription ID.
     *
     * @param subscriptionId the subscription ID.
     * @return {@code true} if the supplied subscription ID corresponds to an active subscription;
     * {@code false} if it does not correspond to an active subscription; or throw a
     * SecurityException if the caller hasn't got the right permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isActiveSubscriptionId(int subscriptionId) {
        return isActiveSubId(subscriptionId);
    }

    /**
     * @return true if the sub ID is active. i.e. The sub ID corresponds to a known subscription
     * and the SIM providing the subscription is present in a slot and in "LOADED" state.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isActiveSubId(int subId) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.isActiveSubId(subId, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
        }
        return false;
    }

    /**
     * Get the description of the billing relationship plan between a carrier
     * and a specific subscriber.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this relationship applies to
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public @NonNull List<SubscriptionPlan> getSubscriptionPlans(int subId) {
        SubscriptionPlan[] subscriptionPlans =
                getNetworkPolicyManager().getSubscriptionPlans(subId, mContext.getOpPackageName());
        return subscriptionPlans == null
                ? Collections.emptyList() : Arrays.asList(subscriptionPlans);
    }

    /**
     * Set the description of the billing relationship plan between a carrier
     * and a specific subscriber.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this relationship applies to. An empty list
     *            may be sent to clear any existing plans.
     * @param plans the list of plans. The first plan is always the primary and
     *            most important plan. Any additional plans are secondary and
     *            may not be displayed or used by decision making logic.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if plans don't meet the requirements
     *             defined in {@link SubscriptionPlan}.
     */
    public void setSubscriptionPlans(int subId, @NonNull List<SubscriptionPlan> plans) {
        getNetworkPolicyManager().setSubscriptionPlans(subId,
                plans.toArray(new SubscriptionPlan[plans.size()]), mContext.getOpPackageName());
    }

    /**
     * Temporarily override the billing relationship plan between a carrier and
     * a specific subscriber to be considered unmetered. This will be reflected
     * to apps via {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED}.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this override applies to.
     * @param overrideUnmetered set if the billing relationship should be
     *            considered unmetered.
     * @param timeoutMillis the timeout after which the requested override will
     *            be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            @DurationMillisLong long timeoutMillis) {

        final int overrideValue = overrideUnmetered ? SUBSCRIPTION_OVERRIDE_UNMETERED : 0;
        getNetworkPolicyManager().setSubscriptionOverride(subId, SUBSCRIPTION_OVERRIDE_UNMETERED,
                overrideValue, timeoutMillis, mContext.getOpPackageName());
    }

    /**
     * Temporarily override the billing relationship plan between a carrier and
     * a specific subscriber to be considered congested. This will cause the
     * device to delay certain network requests when possible, such as developer
     * jobs that are willing to run in a flexible time window.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this override applies to.
     * @param overrideCongested set if the subscription should be considered
     *            congested.
     * @param timeoutMillis the timeout after which the requested override will
     *            be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            @DurationMillisLong long timeoutMillis) {
        final int overrideValue = overrideCongested ? SUBSCRIPTION_OVERRIDE_CONGESTED : 0;
        getNetworkPolicyManager().setSubscriptionOverride(subId, SUBSCRIPTION_OVERRIDE_CONGESTED,
                overrideValue, timeoutMillis, mContext.getOpPackageName());
    }

    /**
     * Checks whether the app with the given context is authorized to manage the given subscription
     * according to its metadata.
     *
     * @param info The subscription to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
     */
    public boolean canManageSubscription(SubscriptionInfo info) {
        return canManageSubscription(info, mContext.getPackageName());
    }

    /**
     * Checks whether the given app is authorized to manage the given subscription. An app can only
     * be authorized if it is included in the {@link android.telephony.UiccAccessRule} of the
     * {@link android.telephony.SubscriptionInfo} with the access status.
     *
     * @param info The subscription to check.
     * @param packageName Package name of the app to check.
     * @return whether the app is authorized to manage this subscription per its access rules.
     * @hide
     */
    @SystemApi
    public boolean canManageSubscription(@NonNull SubscriptionInfo info,
            @NonNull String packageName) {
        if (info == null || info.getAllAccessRules() == null || packageName == null) {
            return false;
        }
        PackageManager packageManager = mContext.getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName,
                PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            logd("Unknown package: " + packageName);
            return false;
        }
        for (UiccAccessRule rule : info.getAllAccessRules()) {
            if (rule.getCarrierPrivilegeStatus(packageInfo)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set which subscription is preferred for cellular data.
     * It's also usually the subscription we set up internet connection on.
     *
     * PreferredData overwrites user setting of default data subscription. And it's used
     * by AlternativeNetworkService or carrier apps to switch primary and CBRS
     * subscription dynamically in multi-SIM devices.
     *
     * @param subId which subscription is preferred to for cellular data. If it's
     *              {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID}, it means
     *              it's unset and {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *              is used to determine which modem is preferred.
     * @param needValidation whether Telephony will wait until the network is validated by
     *              connectivity service before switching data to it. More details see
     *              {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED}.
     * @param executor The executor of where the callback will execute.
     * @param callback Callback will be triggered once it succeeds or failed.
     *                 Pass null if don't care about the result.
     *
     * @hide
     *
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setPreferredDataSubscriptionId(int subId, boolean needValidation,
            @Nullable @CallbackExecutor Executor executor, @Nullable
            @TelephonyManager.SetOpportunisticSubscriptionResult Consumer<Integer> callback) {
        if (VDBG) logd("[setPreferredDataSubscriptionId]+ subId:" + subId);
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub == null) return;

            ISetOpportunisticDataCallback callbackStub = new ISetOpportunisticDataCallback.Stub() {
                @Override
                public void onComplete(int result) {
                    if (executor == null || callback == null) {
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> {
                            callback.accept(result);
                        });
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            };
            iSub.setPreferredDataSubscriptionId(subId, needValidation, callbackStub);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Get which subscription is preferred for cellular data.
     * It's also usually the subscription we set up internet connection on.
     *
     * PreferredData overwrites user setting of default data subscription. And it's used
     * by AlternativeNetworkService or carrier apps to switch primary and CBRS
     * subscription dynamically in multi-SIM devices.
     *
     * @return preferred subscription id for cellular data. {@link DEFAULT_SUBSCRIPTION_ID} if
     * there's no prefered subscription.
     *
     * @hide
     *
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getPreferredDataSubscriptionId() {
        int preferredSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                preferredSubId = iSub.getPreferredDataSubscriptionId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return preferredSubId;
    }

    /**
     * Return opportunistic subscriptions that can be visible to the caller.
     * Opportunistic subscriptions are for opportunistic networks, which are cellular
     * networks with limited capabilities and coverage, for example, CBRS.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @return the list of opportunistic subscription info. If none exists, an empty list.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @NonNull List<SubscriptionInfo> getOpportunisticSubscriptions() {
        String contextPkg = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        String contextAttributionTag = mContext != null ? mContext.getAttributionTag() : null;
        List<SubscriptionInfo> subInfoList = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subInfoList = iSub.getOpportunisticSubscriptions(contextPkg,
                        contextAttributionTag);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (subInfoList == null) {
            subInfoList = new ArrayList<>();
        }

        return subInfoList;
    }

    /**
     * Switch to a certain subscription
     *
     *  @param subId sub id
     *  @param callbackIntent pending intent that will be sent after operation is done.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void switchToSubscription(int subId, @NonNull PendingIntent callbackIntent) {
        Preconditions.checkNotNull(callbackIntent, "callbackIntent cannot be null");
        EuiccManager euiccManager = new EuiccManager(mContext);
        euiccManager.switchToSubscription(subId, callbackIntent);
    }

    /**
     * Set whether a subscription is opportunistic, that is, whether the network it connects
     * to has limited coverage. For example, CBRS. Setting a subscription opportunistic has
     * following impacts:
     *  1) Even if it's active, it will be dormant most of the time. The modem will not try
     *     to scan or camp until it knows an available network is nearby to save power.
     *  2) Telephony relies on system app or carrier input to notify nearby available networks.
     *     See {@link TelephonyManager#updateAvailableNetworks(List, Executor, Consumer)}
     *     for more information.
     *  3) In multi-SIM devices, when the network is nearby and camped, system may automatically
     *     switch internet data between it and default data subscription, based on carrier
     *     recommendation and its signal strength and metered-ness, etc.
     *
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE} or carrier
     * privilege permission of the subscription.
     *
     * @param opportunistic whether it’s opportunistic subscription.
     * @param subId the unique SubscriptionInfo index in database
     * @return {@code true} if the operation is succeed, {@code false} otherwise.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setOpportunistic(boolean opportunistic, int subId) {
        if (VDBG) logd("[setOpportunistic]+ opportunistic:" + opportunistic + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setOpportunistic",
                (iSub)-> iSub.setOpportunistic(
                        opportunistic, subId, mContext.getOpPackageName())) == 1;
    }

    /**
     * Inform SubscriptionManager that subscriptions in the list are bundled
     * as a group. It can be multiple primary (non-opportunistic) subscriptions,
     * or one or more primary plus one or more opportunistic subscriptions.
     *
     * This API will always create a new immutable group and assign group UUID to all the
     * subscriptions, regardless whether they are in a group already or not.
     *
     * Grouped subscriptions will have below behaviors:
     * 1) They will share the same user settings.
     * 2) The opportunistic subscriptions in the group is considered invisible and will not
     *    return from {@link #getActiveSubscriptionInfoList()}, unless caller has carrier
     *    privilege permission of the subscriptions.
     * 3) The opportunistic subscriptions in the group can't be active by itself. If all other
     *    non-opportunistic ones are deactivated (unplugged or disabled in Settings),
     *    the opportunistic ones will be deactivated automatically.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link #canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if any of the subscriptions in the list doesn't exist.
     * @throws IllegalStateException if Telephony service is in bad state.
     *
     * @param subIdList list of subId that will be in the same group
     * @return groupUUID a UUID assigned to the subscription group.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public @NonNull ParcelUuid createSubscriptionGroup(@NonNull List<Integer> subIdList) {
        Preconditions.checkNotNull(subIdList, "can't create group for null subId list");
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[createSubscriptionGroup]");
        }

        ParcelUuid groupUuid = null;
        int[] subIdArray = subIdList.stream().mapToInt(i->i).toArray();
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                groupUuid = iSub.createSubscriptionGroup(subIdArray, pkgForDebug);
            } else {
                if (!isSystemProcess()) {
                    throw new IllegalStateException("telephony service is null.");
                }
            }
        } catch (RemoteException ex) {
            loge("createSubscriptionGroup RemoteException " + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }

        return groupUuid;
    }

    /**
     * Add a list of subscriptions into a group.
     * See {@link #createSubscriptionGroup(List)} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link #canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't exist.
     * @throws IllegalStateException if Telephony service is in bad state.
     *
     * @param subIdList list of subId that need adding into the group
     * @param groupUuid the groupUuid the subscriptions are being added to.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void addSubscriptionsIntoGroup(@NonNull List<Integer> subIdList,
            @NonNull ParcelUuid groupUuid) {
        Preconditions.checkNotNull(subIdList, "subIdList can't be null.");
        Preconditions.checkNotNull(groupUuid, "groupUuid can't be null.");
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[addSubscriptionsIntoGroup]");
        }

        int[] subIdArray = subIdList.stream().mapToInt(i->i).toArray();

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.addSubscriptionsIntoGroup(subIdArray, groupUuid, pkgForDebug);
            } else {
                if (!isSystemProcess()) {
                    throw new IllegalStateException("telephony service is null.");
                }
            }
        } catch (RemoteException ex) {
            loge("addSubscriptionsIntoGroup RemoteException " + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    private boolean isSystemProcess() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /**
     * Remove a list of subscriptions from their subscription group.
     * See {@link #createSubscriptionGroup(List)} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link #canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't belong
     *             the specified group.
     * @throws IllegalStateException if Telephony service is in bad state.
     *
     * @param subIdList list of subId that need removing from their groups.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void removeSubscriptionsFromGroup(@NonNull List<Integer> subIdList,
            @NonNull ParcelUuid groupUuid) {
        Preconditions.checkNotNull(subIdList, "subIdList can't be null.");
        Preconditions.checkNotNull(groupUuid, "groupUuid can't be null.");
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[removeSubscriptionsFromGroup]");
        }

        int[] subIdArray = subIdList.stream().mapToInt(i->i).toArray();

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.removeSubscriptionsFromGroup(subIdArray, groupUuid, pkgForDebug);
            } else {
                if (!isSystemProcess()) {
                    throw new IllegalStateException("telephony service is null.");
                }
            }
        } catch (RemoteException ex) {
            loge("removeSubscriptionsFromGroup RemoteException " + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Get subscriptionInfo list of subscriptions that are in the same group of given subId.
     * See {@link #createSubscriptionGroup(List)} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#READ_PHONE_STATE}
     * permission or had carrier privilege permission on the subscription.
     * {@link TelephonyManager#hasCarrierPrivileges()}
     *
     * @throws IllegalStateException if Telephony service is in bad state.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     *
     * @param groupUuid of which list of subInfo will be returned.
     * @return list of subscriptionInfo that belong to the same group, including the given
     * subscription itself. It will return an empty list if no subscription belongs to the group.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public @NonNull List<SubscriptionInfo> getSubscriptionsInGroup(@NonNull ParcelUuid groupUuid) {
        Preconditions.checkNotNull(groupUuid, "groupUuid can't be null");
        String contextPkg = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        String contextAttributionTag = mContext != null ? mContext.getAttributionTag() : null;
        if (VDBG) {
            logd("[getSubscriptionsInGroup]+ groupUuid:" + groupUuid);
        }

        List<SubscriptionInfo> result = null;
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getSubscriptionsInGroup(groupUuid, contextPkg,
                        contextAttributionTag);
            } else {
                if (!isSystemProcess()) {
                    throw new IllegalStateException("telephony service is null.");
                }
            }
        } catch (RemoteException ex) {
            loge("removeSubscriptionsFromGroup RemoteException " + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }

        return result;
    }

    /**
     * Whether a subscription is visible to API caller. If it's a bundled opportunistic
     * subscription, it should be hidden anywhere in Settings, dialer, status bar etc.
     * Exception is if caller owns carrier privilege, in which case they will
     * want to see their own hidden subscriptions.
     *
     * @param info the subscriptionInfo to check against.
     * @return true if this subscription should be visible to the API caller.
     *
     * @hide
     */
    public boolean isSubscriptionVisible(SubscriptionInfo info) {
        if (info == null) return false;
        // If subscription is NOT grouped opportunistic subscription, it's visible.
        if (info.getGroupUuid() == null || !info.isOpportunistic()) return true;

        // If the caller is the carrier app and owns the subscription, it should be visible
        // to the caller.
        boolean hasCarrierPrivilegePermission = TelephonyManager.from(mContext)
                .hasCarrierPrivileges(info.getSubscriptionId())
                || canManageSubscription(info);
        return hasCarrierPrivilegePermission;
    }

    /**
     * Return a list of subscriptions that are available and visible to the user.
     * Used by Settings app to show a list of subscriptions for user to pick.
     *
     * <p>
     * Permissions android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE is required
     * for getSelectableSubscriptionInfoList to be invoked.
     * @return list of user selectable subscriptions.
     *
     * @hide
     */
    public @Nullable List<SubscriptionInfo> getSelectableSubscriptionInfoList() {
        List<SubscriptionInfo> availableList = getAvailableSubscriptionInfoList();
        if (availableList == null) {
            return null;
        } else {
            // Multiple subscriptions in a group should only have one representative.
            // It should be the current active primary subscription if any, or any
            // primary subscription.
            List<SubscriptionInfo> selectableList = new ArrayList<>();
            Map<ParcelUuid, SubscriptionInfo> groupMap = new HashMap<>();

            for (SubscriptionInfo info : availableList) {
                // Opportunistic subscriptions are considered invisible
                // to users so they should never be returned.
                if (!isSubscriptionVisible(info)) continue;

                ParcelUuid groupUuid = info.getGroupUuid();
                if (groupUuid == null) {
                    // Doesn't belong to any group. Add in the list.
                    selectableList.add(info);
                } else if (!groupMap.containsKey(groupUuid)
                        || (groupMap.get(groupUuid).getSimSlotIndex() == INVALID_SIM_SLOT_INDEX
                        && info.getSimSlotIndex() != INVALID_SIM_SLOT_INDEX)) {
                    // If it belongs to a group that has never been recorded or it's the current
                    // active subscription, add it in the list.
                    selectableList.remove(groupMap.get(groupUuid));
                    selectableList.add(info);
                    groupMap.put(groupUuid, info);
                }

            }
            return selectableList;
        }
    }

    /**
     * Enables or disables a subscription. This is currently used in the settings page. It will
     * fail and return false if operation is not supported or failed.
     *
     * To disable an active subscription on a physical (non-Euicc) SIM,
     * {@link #canDisablePhysicalSubscription} needs to be true.
     *
     * <p>
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     *
     * @param enable whether user is turning it on or off.
     * @param subscriptionId Subscription to be enabled or disabled.
     *                       It could be a eSIM or pSIM subscription.
     *
     * @return whether the operation is successful.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setSubscriptionEnabled(int subscriptionId, boolean enable) {
        if (VDBG) {
            logd("setSubscriptionActivated subId= " + subscriptionId + " enable " + enable);
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.setSubscriptionEnabled(enable, subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * Set uicc applications being enabled or disabled.
     * The value will be remembered on the subscription and will be applied whenever it's present.
     * If the subscription in currently present, it will also apply the setting to modem
     * immediately.
     *
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     *
     * @param subscriptionId which subscription to operate on.
     * @param enabled whether uicc applications are enabled or disabled.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setUiccApplicationsEnabled(int subscriptionId, boolean enabled) {
        if (VDBG) {
            logd("setUiccApplicationsEnabled subId= " + subscriptionId + " enable " + enabled);
        }
        try {
            ISub iSub = ISub.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSubscriptionServiceRegisterer()
                            .get());
            if (iSub != null) {
                iSub.setUiccApplicationsEnabled(enabled, subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Whether it's supported to disable / re-enable a subscription on a physical (non-euicc) SIM.
     *
     * Physical SIM refers non-euicc, or aka non-programmable SIM.
     *
     * It provides whether a physical SIM card can be disabled without taking it out, which is done
     * via {@link #setSubscriptionEnabled(int, boolean)} API.
     *
     * Requires Permission: READ_PRIVILEGED_PHONE_STATE.
     *
     * @return whether can disable subscriptions on physical SIMs.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean canDisablePhysicalSubscription() {
        if (VDBG) {
            logd("canDisablePhysicalSubscription");
        }
        try {
            ISub iSub = ISub.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSubscriptionServiceRegisterer()
                            .get());
            if (iSub != null) {
                return iSub.canDisablePhysicalSubscription();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * DO NOT USE.
     * This API is designed for features that are not finished at this point. Do not call this API.
     * @hide
     * TODO b/135547512: further clean up
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isSubscriptionEnabled(int subscriptionId) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.isSubscriptionEnabled(subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * DO NOT USE.
     * This API is designed for features that are not finished at this point. Do not call this API.
     * @hide
     * TODO b/135547512: further clean up
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getEnabledSubscriptionId(int slotIndex) {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subId = iSub.getEnabledSubscriptionId(slotIndex);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getEnabledSubscriptionId, subId = " + subId);
        return subId;
    }

    private interface CallISubMethodHelper {
        int callMethod(ISub iSub) throws RemoteException;
    }

    private int setSubscriptionPropertyHelper(int subId, String methodName,
            CallISubMethodHelper helper) {
        if (!isValidSubscriptionId(subId)) {
            logd("[" + methodName + "]" + "- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = helper.callMethod(iSub);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get active data subscription id. Active data subscription refers to the subscription
     * currently chosen to provide cellular internet connection to the user. This may be
     * different from getDefaultDataSubscriptionId(). Eg. Opportunistics data
     *
     * See {@link PhoneStateListener#onActiveDataSubscriptionIdChanged(int)} for the details.
     *
     * @return Active data subscription id if any is chosen, or
     * SubscriptionManager.INVALID_SUBSCRIPTION_ID if not.
     */
    public static int getActiveDataSubscriptionId() {
        return sActiveDataSubIdCache.query(null);
    }

    /**
     * Helper method that puts a subscription id on an intent with the constants:
     * PhoneConstant.SUBSCRIPTION_KEY and SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX.
     * Both constants are used to support backwards compatibility.  Once we know we got all places,
     * we can remove PhoneConstants.SUBSCRIPTION_KEY.
     * @param intent Intent to put sub id on.
     * @param subId SubscriptionId to put on intent.
     *
     * @hide
     */
    public static void putSubscriptionIdExtra(Intent intent, int subId) {
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
    }

    /** @hide */
    public static void invalidateDefaultSubIdCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_DEFAULT_SUB_ID_PROPERTY);
    }

    /** @hide */
    public static void invalidateDefaultDataSubIdCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_DEFAULT_DATA_SUB_ID_PROPERTY);
    }

    /** @hide */
    public static void invalidateDefaultSmsSubIdCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_DEFAULT_SMS_SUB_ID_PROPERTY);
    }

    /** @hide */
    public static void invalidateActiveDataSubIdCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_ACTIVE_DATA_SUB_ID_PROPERTY);
    }

    /** @hide */
    public static void invalidateSlotIndexCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_SLOT_INDEX_PROPERTY);
    }

    /**
     * Allows a test process to disable client-side caching operations.
     *
     * @hide
     */
    public static void disableCaching() {
        sDefaultSubIdCache.disableLocal();
        sDefaultDataSubIdCache.disableLocal();
        sActiveDataSubIdCache.disableLocal();
        sDefaultSmsSubIdCache.disableLocal();
        sSlotIndexCache.disableLocal();
        sPhoneIdCache.disableLocal();
    }

    /**
     * Clears all process-local binder caches.
     *
     * @hide */
    public static void clearCaches() {
        sDefaultSubIdCache.clear();
        sDefaultDataSubIdCache.clear();
        sActiveDataSubIdCache.clear();
        sDefaultSmsSubIdCache.clear();
        sSlotIndexCache.clear();
        sPhoneIdCache.clear();
    }
}
