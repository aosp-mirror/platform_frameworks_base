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
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.app.PropertyInvalidatedCache;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Telephony.SimInfo;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsMmTelManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;

import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.HandlerExecutor;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.telephony.Rlog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Subscription manager provides the mobile subscription information that are associated with the
 * calling user profile {@link UserHandle} for Android SDK 35(V) and above, while Android SDK 34(U)
 * and below can see all subscriptions as it does today.
 *
 * <p>For example, if we have
 * <ul>
 *     <li> Subscription 1 associated with personal profile.
 *     <li> Subscription 2 associated with work profile.
 * </ul>
 * Then for SDK 35+, if the caller identity is personal profile, then
 * {@link #getActiveSubscriptionInfoList} will return subscription 1 only and vice versa.
 *
 */
@SystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
public class SubscriptionManager {
    private static final String LOG_TAG = "SubscriptionManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /** An invalid subscription identifier */
    public static final int INVALID_SUBSCRIPTION_ID = -1;

    /** Base value for placeholder SUBSCRIPTION_ID's. */
    /** @hide */
    public static final int PLACEHOLDER_SUBSCRIPTION_ID_BASE = INVALID_SUBSCRIPTION_ID - 1;

    /** An invalid phone identifier */
    /** @hide */
    public static final int INVALID_PHONE_INDEX = -1;

    /** Indicates invalid sim slot. This can be returned by {@link #getSlotIndex(int)}. */
    public static final int INVALID_SIM_SLOT_INDEX = -1;

    /** Indicates the default subscription ID in Telephony. */
    public static final int DEFAULT_SUBSCRIPTION_ID = Integer.MAX_VALUE;

    /**
     * Indicates the default phone id.
     * @hide
     */
    public static final int DEFAULT_PHONE_INDEX = Integer.MAX_VALUE;

    /** Indicates the default slot index. */
    /** @hide */
    public static final int DEFAULT_SIM_SLOT_INDEX = Integer.MAX_VALUE;

    /** Minimum possible subid that represents a subscription */
    /** @hide */
    public static final int MIN_SUBSCRIPTION_ID_VALUE = 0;

    /** Maximum possible subid that represents a subscription */
    /** @hide */
    public static final int MAX_SUBSCRIPTION_ID_VALUE = DEFAULT_SUBSCRIPTION_ID - 1;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final Uri CONTENT_URI = SimInfo.CONTENT_URI;

    /** The IPC cache key shared by all subscription manager service cacheable properties. */
    private static final String CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY =
            "cache_key.telephony.subscription_manager_service";

    /** @hide */
    public static final String GET_SIM_SPECIFIC_SETTINGS_METHOD_NAME = "getSimSpecificSettings";

    /** @hide */
    public static final String RESTORE_SIM_SPECIFIC_SETTINGS_METHOD_NAME =
            "restoreSimSpecificSettings";

    /**
     * The key of the boolean flag indicating whether restoring subscriptions actually changes
     * the subscription database or not.
     *
     * @hide
     */
    public static final String RESTORE_SIM_SPECIFIC_SETTINGS_DATABASE_UPDATED =
            "restoreSimSpecificSettingsDatabaseUpdated";

    /**
     * Key to the backup & restore data byte array in the Bundle that is returned by {@link
     * #getAllSimSpecificSettingsForBackup()} or to be pass in to {@link
     * #restoreAllSimSpecificSettingsFromBackup(byte[])}.
     *
     * @hide
     */
    public static final String KEY_SIM_SPECIFIC_SETTINGS_DATA = "KEY_SIM_SPECIFIC_SETTINGS_DATA";

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
        public T recompute(Void query) {
            // This always throws on any error.  The exceptions must be handled outside
            // the cache.
            try {
                return mInterfaceMethod.applyOrThrow(TelephonyManager.getSubscriptionService());
            } catch (Exception re) {
                throw new RuntimeException(re);
            }
        }

        @Override
        public T query(Void query) {
            T result = mDefaultValue;

            try {
                ISub iSub = TelephonyManager.getSubscriptionService();
                if (iSub != null) {
                    result = super.query(query);
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
        public T recompute(Integer query) {
            // This always throws on any error.  The exceptions must be handled outside
            // the cache.
            try {
                return mInterfaceMethod.applyOrThrow(
                    TelephonyManager.getSubscriptionService(), query);
            } catch (Exception re) {
                throw new RuntimeException(re);
            }
        }

        @Override
        public T query(Integer query) {
            T result = mDefaultValue;

            try {
                ISub iSub = TelephonyManager.getSubscriptionService();
                if (iSub != null) {
                    result = super.query(query);
                }
            } catch (Exception ex) {
                Rlog.w(LOG_TAG, "Failed to recompute cache key for " + mCacheKeyProperty);
            }

            if (VDBG) logd("recomputing " + mCacheKeyProperty + ", result = " + result);
            return result;
        }
    }

    private static IntegerPropertyInvalidatedCache<Integer> sGetDefaultSubIdCacheAsUser =
            new IntegerPropertyInvalidatedCache<>(ISub::getDefaultSubIdAsUser,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static VoidPropertyInvalidatedCache<Integer> sGetDefaultDataSubIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getDefaultDataSubId,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static IntegerPropertyInvalidatedCache<Integer> sGetDefaultSmsSubIdCacheAsUser =
            new IntegerPropertyInvalidatedCache<>(ISub::getDefaultSmsSubIdAsUser,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static VoidPropertyInvalidatedCache<Integer> sGetActiveDataSubscriptionIdCache =
            new VoidPropertyInvalidatedCache<>(ISub::getActiveDataSubscriptionId,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static IntegerPropertyInvalidatedCache<Integer> sGetSlotIndexCache =
            new IntegerPropertyInvalidatedCache<>(ISub::getSlotIndex,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SIM_SLOT_INDEX);

    private static IntegerPropertyInvalidatedCache<Integer> sGetSubIdCache =
            new IntegerPropertyInvalidatedCache<>(ISub::getSubId,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
                    INVALID_SUBSCRIPTION_ID);

    private static IntegerPropertyInvalidatedCache<Integer> sGetPhoneIdCache =
            new IntegerPropertyInvalidatedCache<>(ISub::getPhoneId,
                    CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY,
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
    public static final Uri WFC_ENABLED_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI, "wfc");

    /**
     * A content {@link Uri} used to receive updates on advanced calling user setting
     *
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
     *
     * @see ImsMmTelManager#isAdvancedCallingSettingEnabled()
     *
     * @hide
     */
    @NonNull
    @SystemApi
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
    public static final Uri WFC_ROAMING_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "wfc_roaming_enabled");


    /**
     * A content {@link uri} used to call the appropriate backup or restore method for sim-specific
     * settings
     * <p>
     * See {@link #GET_SIM_SPECIFIC_SETTINGS_METHOD_NAME} and {@link
     * #RESTORE_SIM_SPECIFIC_SETTINGS_METHOD_NAME} for information on what method to call.
     * @hide
     */
    @NonNull
    public static final Uri SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "backup_and_restore");

    /**
     * A content {@link uri} used to notify contentobservers listening to siminfo restore during
     * SuW.
     * @hide
     */
    @NonNull
    public static final Uri SIM_INFO_SUW_RESTORE_CONTENT_URI = Uri.withAppendedPath(
            SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI, "suw_restore");

    /**
     * A content {@link Uri} used to receive updates on cross sim enabled user setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription cross sim calling enabled
     * {@link ImsMmTelManager#isCrossSimCallingEnabled()}
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
    public static final Uri CROSS_SIM_ENABLED_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI,
            SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED);

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
     * TelephonyProvider column name for last used TP - message Reference
     * <P>Type: INTEGER (int)</P> with -1 as default value
     * TP - Message Reference valid range [0 - 255]
     * @hide
     */
    public static final String TP_MESSAGE_REF = SimInfo.COLUMN_TP_MESSAGE_REF;

    /**
     * TelephonyProvider column name enabled_mobile_data_policies.
     * A list of mobile data policies, each of which represented by an integer and joint by ",".
     *
     * Default value is empty string.
     * @hide
     */
    public static final String ENABLED_MOBILE_DATA_POLICIES =
            SimInfo.COLUMN_ENABLED_MOBILE_DATA_POLICIES;

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
     * The name_source is unknown. (for initialization)
     * @hide
     */
    public static final int NAME_SOURCE_UNKNOWN = SimInfo.NAME_SOURCE_UNKNOWN;

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
                    NAME_SOURCE_UNKNOWN,
                    NAME_SOURCE_CARRIER_ID,
                    NAME_SOURCE_SIM_SPN,
                    NAME_SOURCE_USER_INPUT,
                    NAME_SOURCE_CARRIER,
                    NAME_SOURCE_SIM_PNN
            })
    public @interface SimDisplayNameSource {}

    /**
     * Device status is not shared to a remote party.
     */
    public static final int D2D_SHARING_DISABLED = 0;

    /**
     * Device status is shared with all numbers in the user's contacts.
     */
    public static final int D2D_SHARING_ALL_CONTACTS = 1;

    /**
     * Device status is shared with all selected contacts.
     */
    public static final int D2D_SHARING_SELECTED_CONTACTS = 2;

    /**
     * Device status is shared whenever possible.
     */
    public static final int D2D_SHARING_ALL = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"D2D_SHARING_"},
            value = {
                    D2D_SHARING_DISABLED,
                    D2D_SHARING_ALL_CONTACTS,
                    D2D_SHARING_SELECTED_CONTACTS,
                    D2D_SHARING_ALL
            })
    public @interface DeviceToDeviceStatusSharingPreference {}

    /**
     * TelephonyProvider column name for device to device sharing status.
     * <P>Type: INTEGER (int)</P>
     */
    public static final String D2D_STATUS_SHARING = SimInfo.COLUMN_D2D_STATUS_SHARING;

    /**
     * TelephonyProvider column name for contacts information that allow device to device sharing.
     * <P>Type: TEXT (String)</P>
     */
    public static final String D2D_STATUS_SHARING_SELECTED_CONTACTS =
            SimInfo.COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS;

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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"DATA_ROAMING_"},
            value = {
                    DATA_ROAMING_ENABLE,
                    DATA_ROAMING_DISABLE
            })
    public @interface DataRoamingMode {}

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
     * Determines if the user has enabled cross SIM calling for this subscription.
     *
     * @hide
     */
    public static final String CROSS_SIM_CALLING_ENABLED = SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED;

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
     * TelephonyProvider column name for the port index of the active UICC port.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String PORT_INDEX = SimInfo.COLUMN_PORT_INDEX;

    /**
     * TelephonyProvider column name for VoIMS opt-in status.
     *
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String VOIMS_OPT_IN_STATUS = SimInfo.COLUMN_VOIMS_OPT_IN_STATUS;

    /**
     * TelephonyProvider column name for NR Advanced calling
     * Determines if the user has enabled VoNR settings for this subscription.
     *
     * @hide
     */
    public static final String NR_ADVANCED_CALLING_ENABLED =
            SimInfo.COLUMN_NR_ADVANCED_CALLING_ENABLED;

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
     * Indicate which network type is allowed.
     * @hide
     */
    public static final String ALLOWED_NETWORK_TYPES =
            SimInfo.COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS;

    /**
     * TelephonyProvider column name for user handle associated with a sim.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String USER_HANDLE = SimInfo.COLUMN_USER_HANDLE;

    /**
     * TelephonyProvider column name for satellite enabled.
     * By default, it's disabled.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String SATELLITE_ENABLED = SimInfo.COLUMN_SATELLITE_ENABLED;

    /**
     * TelephonyProvider column name for satellite attach enabled for carrier. The value of this
     * column is set based on user settings.
     * By default, it's enabled.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String SATELLITE_ATTACH_ENABLED_FOR_CARRIER =
            SimInfo.COLUMN_SATELLITE_ATTACH_ENABLED_FOR_CARRIER;

    /**
     * TelephonyProvider column name to identify eSIM profile of a non-terrestrial network.
     * By default, it's disabled.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String IS_NTN = SimInfo.COLUMN_IS_NTN;

    /**
     * TelephonyProvider column name to identify service capabilities.
     * Disabled by default.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String SERVICE_CAPABILITIES = SimInfo.COLUMN_SERVICE_CAPABILITIES;

    /**
     * TelephonyProvider column name to identify eSIM's transfer status.
     * By default, it's disabled.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String TRANSFER_STATUS = SimInfo.COLUMN_TRANSFER_STATUS;

    /**
     * TelephonyProvider column name for satellite entitlement status. The value of this column is
     * set based on entitlement query result for satellite configuration.
     * By default, it's disabled.
     * <P>Type: INTEGER (int)</P>
     *
     * @hide
     */
    public static final String SATELLITE_ENTITLEMENT_STATUS =
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_STATUS;

    /**
     * TelephonyProvider column name for satellite entitlement plmns. The value of this column is
     * set based on entitlement query result for satellite configuration.
     * By default, it's empty.
     * <P>Type: TEXT </P>
     *
     * @hide
     */
    public static final String SATELLITE_ENTITLEMENT_PLMNS =
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_PLMNS;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"USAGE_SETTING_"},
        value = {
            USAGE_SETTING_UNKNOWN,
            USAGE_SETTING_DEFAULT,
            USAGE_SETTING_VOICE_CENTRIC,
            USAGE_SETTING_DATA_CENTRIC})
    public @interface UsageSetting {}

    /**
     * The usage setting is unknown.
     *
     * This will be the usage setting returned on devices that do not support querying the
     * or setting the usage setting.
     *
     * It may also be provided by a carrier that wishes to provide a value to avoid making any
     * settings changes.
     */
    public static final int USAGE_SETTING_UNKNOWN = -1;

    /**
     * Subscription uses the default setting.
     *
     * The value is based upon device capability and the other properties of the subscription.
     *
     * Most subscriptions will default to voice-centric when in a phone.
     *
     * An opportunistic subscription will default to data-centric.
     *
     * @see SubscriptionInfo#isOpportunistic
     */
    public static final int USAGE_SETTING_DEFAULT = 0;

    /**
     * This subscription is forced to voice-centric mode
     *
     * <p>Refer to voice-centric mode in 3gpp 24.301 sec 4.3 and 3gpp 24.501 sec 4.3.
     * Also refer to "UE's usage setting" as defined in 3gpp 24.301 section 3.1 and 3gpp 23.221
     * Annex A.
     *
     * <p>Devices that support {@link PackageManager#FEATURE_TELEPHONY_CALLING} and support usage
     * setting configuration must support setting this value via
     * {@link CarrierConfigManager#KEY_CELLULAR_USAGE_SETTING_INT}.
     */
    public static final int USAGE_SETTING_VOICE_CENTRIC = 1;

    /**
     * This subscription is forced to data-centric mode
     *
     * <p>Refer to data-centric mode in 3gpp 24.301 sec 4.3 and 3gpp 24.501 sec 4.3.
     * Also refer to "UE's usage setting" as defined in 3gpp 24.301 section 3.1 and 3gpp 23.221
     * Annex A.
     *
     * <p>Devices that support {@link PackageManager#FEATURE_TELEPHONY_DATA} and support usage
     * setting configuration must support setting this value via.
     * {@link CarrierConfigManager#KEY_CELLULAR_USAGE_SETTING_INT}.
     */
    public static final int USAGE_SETTING_DATA_CENTRIC = 2;

    /**
     * Indicate the preferred usage setting for the subscription.
     *
     * 0 - Default - If the value has not been explicitly set, it will be "default"
     * 1 - Voice-centric
     * 2 - Data-centric
     *
     * @hide
     */
    public static final String USAGE_SETTING = SimInfo.COLUMN_USAGE_SETTING;

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

    /**
     * A source of phone number: the EF-MSISDN (see 3GPP TS 31.102),
     * or EF-MDN for CDMA (see 3GPP2 C.P0065-B), from UICC application.
     *
     * <p>The availability and accuracy of the number depends on the carrier.
     * The number may be updated by over-the-air update to UICC applications
     * from the carrier, or by other means with physical access to the SIM.
     */
    public static final int PHONE_NUMBER_SOURCE_UICC = 1;

    /**
     * A source of phone number: provided by an app that has carrier privilege.
     *
     * <p>The number is intended to be set by a carrier app knowing the correct number
     * which is, for example, different from the number in {@link #PHONE_NUMBER_SOURCE_UICC UICC}
     * for some reason.
     * The number is not available until a carrier app sets one via
     * {@link #setCarrierPhoneNumber(int, String)}.
     * The app can update the number with the same API should the number change.
     */
    public static final int PHONE_NUMBER_SOURCE_CARRIER = 2;

    /**
     * A source of phone number: provided by IMS (IP Multimedia Subsystem) implementation.
     * When IMS service is registered (as indicated by
     * {@link android.telephony.ims.RegistrationManager.RegistrationCallback#onRegistered(int)})
     * the IMS implementation may return P-Associated-Uri SIP headers (RFC 3455). The URIs
     * are the user’s public user identities known to the network (see 3GPP TS 24.229 5.4.1.2),
     * and the phone number is typically one of them (see “global number” in 3GPP TS 23.003 13.4).
     *
     * <p>This source provides the phone number from the last IMS registration.
     * IMS registration may happen on every device reboot or other network condition changes.
     * The number will be updated should the associated URI change after an IMS registration.
     */
    public static final int PHONE_NUMBER_SOURCE_IMS = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PHONE_NUMBER_SOURCE"},
            value = {
                    PHONE_NUMBER_SOURCE_UICC,
                    PHONE_NUMBER_SOURCE_CARRIER,
                    PHONE_NUMBER_SOURCE_IMS,
            })
    public @interface PhoneNumberSource {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SERVICE_CAPABILITY"},
            value = {
                    SERVICE_CAPABILITY_VOICE,
                    SERVICE_CAPABILITY_SMS,
                    SERVICE_CAPABILITY_DATA,
            })
    public @interface ServiceCapability {
    }

    /**
     * Represents a value indicating the voice calling capabilities of a subscription.
     *
     * <p>This value identifies whether the subscription supports various voice calling services.
     * These services can include circuit-switched (CS) calling, packet-switched (PS) IMS (IP
     * Multimedia Subsystem) calling, and over-the-top (OTT) calling options.
     *
     * <p>Note: The availability of emergency calling services is not solely dependent on this
     * voice capability. Emergency services may be accessible even if the subscription lacks
     * standard voice capabilities. However, the device's ability to support emergency calls
     * can be influenced by its inherent voice capabilities, as determined by
     * {@link TelephonyManager#isDeviceVoiceCapable()}.
     *
     * @see TelephonyManager#isDeviceVoiceCapable()
     */
    @FlaggedApi(Flags.FLAG_DATA_ONLY_CELLULAR_SERVICE)
    public static final int SERVICE_CAPABILITY_VOICE = 1;

    /**
     * Represents a value indicating the SMS capabilities of a subscription.
     *
     * <p>This value identifies whether the subscription supports various sms services.
     * These services can include circuit-switched (CS) SMS, packet-switched (PS) IMS (IP
     * Multimedia Subsystem) SMS, and over-the-top (OTT) SMS options.
     *
     * <p>Note: The availability of emergency SMS services is not solely dependent on this
     * sms capability. Emergency services may be accessible even if the subscription lacks
     * standard sms capabilities. However, the device's ability to support emergency sms
     * can be influenced by its inherent sms capabilities, as determined by
     * {@link TelephonyManager#isDeviceSmsCapable()}.
     *
     * @see TelephonyManager#isDeviceSmsCapable()
     */
    @FlaggedApi(Flags.FLAG_DATA_ONLY_CELLULAR_SERVICE)
    public static final int SERVICE_CAPABILITY_SMS = 2;

    /**
     * Represents a value indicating the data calling capabilities of a subscription.
     */
    @FlaggedApi(Flags.FLAG_DATA_ONLY_CELLULAR_SERVICE)
    public static final int SERVICE_CAPABILITY_DATA = 3;

    /**
     * Maximum value of service capabilities supported so far.
     * @hide
     */
    public static final int SERVICE_CAPABILITY_MAX = SERVICE_CAPABILITY_DATA;

    /**
     * Bitmask for {@code SERVICE_CAPABILITY_VOICE}.
     * @hide
     */
    public static final int SERVICE_CAPABILITY_VOICE_BITMASK =
            serviceCapabilityToBitmask(SERVICE_CAPABILITY_VOICE);

    /**
     * Bitmask for {@code SERVICE_CAPABILITY_SMS}.
     * @hide
     */
    public static final int SERVICE_CAPABILITY_SMS_BITMASK =
            serviceCapabilityToBitmask(SERVICE_CAPABILITY_SMS);

    /**
     * Bitmask for {@code SERVICE_CAPABILITY_DATA}.
     * @hide
     */
    public static final int SERVICE_CAPABILITY_DATA_BITMASK =
            serviceCapabilityToBitmask(SERVICE_CAPABILITY_DATA);

    private final Context mContext;

    /**
     * In order to prevent the overflow of the heap size due to an indiscriminate increase in the
     * cache, the heap size of the resource cache is set sufficiently large.
     */
    private static final int MAX_RESOURCE_CACHE_ENTRY_COUNT = 1_000;

    /**
     * Cache of Resources that has been created in getResourcesForSubId. Key contains package name,
     * and Configuration of Resources. If more than the maximum number of resources are stored in
     * this cache, the least recently used Resources will be removed to maintain the maximum size.
     */
    private static final LruCache<Pair<String, Configuration>, Resources> sResourcesCache =
            new LruCache<>(MAX_RESOURCE_CACHE_ENTRY_COUNT);


    /**
     * The profile has not been transferred or converted to an eSIM.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    public static final int TRANSFER_STATUS_NONE = 0;

    /**
     * The existing profile of the old device has been transferred to an eSIM of the new device.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    public static final int TRANSFER_STATUS_TRANSFERRED_OUT = 1;

    /**
     * The existing profile of the same device has been converted to an eSIM of the same device
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    public static final int TRANSFER_STATUS_CONVERTED = 2;
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TRANSFER_STATUS"},
            value = {
                    TRANSFER_STATUS_NONE,
                    TRANSFER_STATUS_TRANSFERRED_OUT,
                    TRANSFER_STATUS_CONVERTED,
            })
    public @interface TransferStatus {}


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

        /**
         * After {@link Build.VERSION_CODES#Q}, it is no longer necessary to instantiate a
         * Handler inside of the OnSubscriptionsChangedListener in all cases, so it will only
         * be done for callers that do not supply an Executor.
         */
        @ChangeId
        @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
        private static final long LAZY_INITIALIZE_SUBSCRIPTIONS_CHANGED_HANDLER = 278814050L;

        /**
         * For backwards compatibility reasons, stashes the Looper associated with the thread
         * context in which this listener was created.
         */
        private final Looper mCreatorLooper;

        /**
         * @hide
         */
        public Looper getCreatorLooper() {
            return mCreatorLooper;
        }

        /**
         * Create an OnSubscriptionsChangedListener.
         *
         * For callers targeting {@link Build.VERSION_CODES#P} or earlier, this can only be called
         * on a thread that already has a prepared Looper. Callers targeting Q or later should
         * subsequently use {@link SubscriptionManager#addOnSubscriptionsChangedListener(
         * Executor, OnSubscriptionsChangedListener)}.
         *
         * On OS versions prior to {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} callers should
         * assume that this call will fail if invoked on a thread that does not already have a
         * prepared looper.
         */
        public OnSubscriptionsChangedListener() {
            mCreatorLooper = Looper.myLooper();
            if (mCreatorLooper == null
                    && !Compatibility.isChangeEnabled(
                            LAZY_INITIALIZE_SUBSCRIPTIONS_CHANGED_HANDLER)) {
                // matches the implementation of Handler
                throw new RuntimeException(
                        "Can't create handler inside thread "
                        + Thread.currentThread()
                        + " that has not called Looper.prepare()");
            }
        }

        /**
         * Allow a listener to be created with a custom looper
         * @param looper the non-null Looper that the underlining handler should run on
         * @hide
         */
        public OnSubscriptionsChangedListener(@NonNull Looper looper) {
            Objects.requireNonNull(looper);
            mCreatorLooper = looper;
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

    /**
     * {@code true} if the SubscriptionManager instance should see all subscriptions regardless its
     * association with particular user profile.
     *
     * <p> It only applies to Android SDK 35(V) and above. For Android SDK 34(U) and below, the
     * caller can see all subscription across user profiles as it does today today even if it's
     * {@code false}.
     */
    private final boolean mIsForAllUserProfiles;

    /** @hide */
    @UnsupportedAppUsage
    public SubscriptionManager(Context context) {
        this(context, false /*isForAllUserProfiles*/);
    }

    /**  Constructor */
    private SubscriptionManager(Context context, boolean isForAllUserProfiles) {
        if (DBG) {
            logd("SubscriptionManager created "
                    + (isForAllUserProfiles ? "for all user profile" : ""));
        }
        mIsForAllUserProfiles = isForAllUserProfiles;
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
     * function. The callback will be invoked on the looper specified in the listener's constructor.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     *                 onSubscriptionsChanged overridden.
     *
     * @deprecated Will get exception if the parameter listener is not initialized with a Looper.
     * Use {@link #addOnSubscriptionsChangedListener(Executor, OnSubscriptionsChangedListener)}.
     */
    @Deprecated
    public void addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        if (listener == null) return;

        Looper looper = listener.getCreatorLooper();
        if (looper == null) {
            throw new RuntimeException(
                    "Can't create handler inside thread " + Thread.currentThread()
                    + " that has not called Looper.prepare()");
        }

        addOnSubscriptionsChangedListener(new HandlerExecutor(new Handler(looper)), listener);
    }

    /**
     * Register for changes to the list of {@link SubscriptionInfo} records or to the
     * individual records (active or inactive) themselves. When a change occurs, the
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged()} method of
     * the listener will be invoked immediately. The
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged()} method will also be invoked
     * once initially when calling this method.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged()} overridden.
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
        // available.
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
        // available.
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
        // available.
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * Get all subscription info records from SIMs that are inserted now or previously inserted.
     *
     * <p>
     * If the caller does not have {@link Manifest.permission#READ_PHONE_NUMBERS} permission,
     * {@link SubscriptionInfo#getNumber()} will return empty string.
     * If the caller does not have {@link Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER},
     * {@link SubscriptionInfo#getIccId()} will return an empty string, and
     * {@link SubscriptionInfo#getGroupUuid()} will return {@code null}.
     *
     * <p>
     * The carrier app will only get the list of subscriptions that it has carrier privilege on,
     * but will have non-stripped {@link SubscriptionInfo} in the list.
     *
     * @return List of all {@link SubscriptionInfo} records from SIMs that are inserted or
     * previously inserted. Sorted by {@link SubscriptionInfo#getSimSlotIndex()}, then
     * {@link SubscriptionInfo#getSubscriptionId()}.
     *
     * @throws SecurityException if callers do not hold the required permission.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            "carrier privileges",
    })
    public List<SubscriptionInfo> getAllSubscriptionInfoList() {
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
            result = Collections.emptyList();
        }
        return result;
    }

    /**
     * Get the SubscriptionInfo(s) of the currently active SIM(s) associated with the current caller
     * user profile {@link UserHandle} for Android SDK 35(V) and above, while Android SDK 34(U)
     * and below can see all subscriptions as it does today.
     *
     * <p>For example, if we have
     * <ul>
     *     <li> Subscription 1 associated with personal profile.
     *     <li> Subscription 2 associated with work profile.
     * </ul>
     * Then for SDK 35+, if the caller identity is personal profile, then this will return
     * subscription 1 only and vice versa.
     *
     * <p> Returned records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}. Beginning with Android SDK 35, this method will
     * never return null.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @return a list of the active {@link SubscriptionInfo} that is visible to the caller. If
     *         an empty list or null is returned, then there are no active subscriptions that
     *         are visible to the caller. If the number of active subscriptions available to
     *         any caller changes, then this change will be indicated by
     *         {@link OnSubscriptionsChangedListener#onSubscriptionsChanged}.
     *
     * @throws UnsupportedOperationException If the device does not have
     *         {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @Nullable List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        List<SubscriptionInfo> activeList = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                activeList = iSub.getActiveSubscriptionInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag(), mIsForAllUserProfiles);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (activeList != null) {
            activeList = activeList.stream().filter(subInfo -> isSubscriptionVisible(subInfo))
                    .collect(Collectors.toList());
        } else {
            activeList = Collections.emptyList();
        }
        return activeList;
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
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    public @NonNull List<SubscriptionInfo> getCompleteActiveSubscriptionInfoList() {
        return getActiveSubscriptionInfoList(/* userVisibleonly */ false);
    }

    /**
     * Create a new subscription manager instance that can see all subscriptions across
     * user profiles.
     *
     * The permission check for accessing all subscriptions will be enforced upon calling the
     * individual APIs linked below.
     *
     * @return a SubscriptionManager that can see all subscriptions regardless its user profile
     * association.
     *
     * @see #getActiveSubscriptionInfoList
     * @see #getActiveSubscriptionInfoCount
     * @see UserHandle
     */
    @FlaggedApi(Flags.FLAG_ENFORCE_SUBSCRIPTION_USER_FILTER)
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_PROFILES)
    @NonNull public SubscriptionManager createForAllUserProfiles() {
        return new SubscriptionManager(mContext, true/*isForAllUserProfiles*/);
    }

    /**
    * This is similar to {@link #getActiveSubscriptionInfoList()}, but if userVisibleOnly
    * is true, it will filter out the hidden subscriptions.
    *
    * @hide
    */
    public @NonNull List<SubscriptionInfo> getActiveSubscriptionInfoList(boolean userVisibleOnly) {
        List<SubscriptionInfo> activeList = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                activeList = iSub.getActiveSubscriptionInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag(), true /*isForAllUserProfiles*/);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (activeList == null || activeList.isEmpty()) {
            return Collections.emptyList();
        } else if (userVisibleOnly) {
            return activeList.stream().filter(subInfo -> isSubscriptionVisible(subInfo))
                    .collect(Collectors.toList());
        } else {
            return activeList;
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    public @Nullable List<SubscriptionInfo> getAvailableSubscriptionInfoList() {
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
        return (result == null) ? Collections.emptyList() : result;
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    public @Nullable List<SubscriptionInfo> getAccessibleSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getAccessibleSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return (result == null) ? Collections.emptyList() : result;
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
     * @see TelephonyManager#getCardIdForDefaultEuicc() for more information on the card ID.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
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
     * @see TelephonyManager#getCardIdForDefaultEuicc() for more information on the card ID.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
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
     * Get the active subscription count associated with the current caller user profile for
     * Android SDK 35(V) and above, while Android SDK 34(U) and below can see all subscriptions as
     * it does today.
     *
     * @return The current number of active subscriptions.
     *
     * @see #getActiveSubscriptionInfoList()
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getActiveSubscriptionInfoCount() {
        int result = 0;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                result = iSub.getActiveSubInfoCount(mContext.getOpPackageName(),
                        mContext.getAttributionTag(), mIsForAllUserProfiles);
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void addSubscriptionInfoRecord(@NonNull String uniqueId, @Nullable String displayName,
            int slotIndex, @SubscriptionType int subscriptionType) {
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
     * Remove subscription info record from the subscription database.
     *
     * @param uniqueId This is the unique identifier for the subscription within the specific
     * subscription type.
     * @param subscriptionType the type of subscription to be removed.
     *
     * @throws NullPointerException if {@code uniqueId} is {@code null}.
     * @throws SecurityException if callers do not hold the required permission.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void removeSubscriptionInfoRecord(@NonNull String uniqueId,
            @SubscriptionType int subscriptionType) {
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
            boolean result = iSub.removeSubInfo(uniqueId, subscriptionType);
            if (!result) {
                Log.e(LOG_TAG, "Removal of subscription didn't succeed");
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
     * @param subId the unique subscription ID in database
     * @return the number of records updated
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setIconTint(@ColorInt int tint, int subId) {
        if (VDBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setIconTint",
                (iSub)-> iSub.setIconTint(subId, tint)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        return sGetSlotIndexCache.query(subscriptionId);
    }

    /**
     * Get an array of subscription ids for the specified logical SIM slot Index. The maximum size
     * of the array is 1. This API was mistakenly designed to return multiple subscription ids,
     * which is not possible in the current Android telephony architecture.
     *
     * @param slotIndex The logical SIM slot index.
     *
     * @return Subscription id of the active subscription on the specified logical SIM slot index.
     * If SIM is absent on the slot, a single element array of {@link #INVALID_SUBSCRIPTION_ID} will
     * be returned. {@code null} if the provided {@code slotIndex} is not valid.
     *
     * @deprecated Use {@link #getSubscriptionId(int)} instead.
     */
    @Deprecated
    @Nullable
    public int[] getSubscriptionIds(int slotIndex) {
        if (!isValidSlotIndex(slotIndex)) {
            return null;
        }
        return new int[]{getSubscriptionId(slotIndex)};
    }

    /**
     * Get an array of subscription ids for the specified logical SIM slot Index. The maximum size
     * of the array is 1. This API was mistakenly designed to return multiple subscription ids,
     * which is not possible in the current Android telephony architecture.
     *
     * @param slotIndex The logical SIM slot index.
     *
     * @return Subscription id of the active subscription on the specified logical SIM slot index.
     * If SIM is absent on the slot, a single element array of {@link #INVALID_SUBSCRIPTION_ID} will
     * be returned. {@code null} if the provided {@code slotIndex} is not valid.
     *
     * @deprecated Use {@link #getSubscriptionId(int)} instead.
     * @hide
     */
    @Deprecated
    public static int[] getSubId(int slotIndex) {
        if (!isValidSlotIndex(slotIndex)) {
            return null;
        }
        return new int[]{getSubscriptionId(slotIndex)};
    }

    /**
     * Get the subscription id for specified logical SIM slot index.
     *
     * @param slotIndex The logical SIM slot index.
     * @return The subscription id. {@link #INVALID_SUBSCRIPTION_ID} if SIM is absent.
     */
    public static int getSubscriptionId(int slotIndex) {
        if (!isValidSlotIndex(slotIndex)) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }

        return sGetSubIdCache.query(slotIndex);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static int getPhoneId(int subId) {
        return sGetPhoneIdCache.query(subId);
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
        return sGetDefaultSubIdCacheAsUser.query(Process.myUserHandle().getIdentifier());
    }

    /**
     * Returns the system's default voice subscription id.
     *
     * On a data only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default voice subscription Id.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    public static int getDefaultVoiceSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubIdAsUser(Process.myUserHandle().getIdentifier());
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public SubscriptionInfo getDefaultVoiceSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultVoiceSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        return sGetDefaultSmsSubIdCacheAsUser.query(Process.myUserHandle().getIdentifier());
    }

    /**
     * Set the subscription which will be used by default for SMS, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * Returns the system's default data subscription id.
     *
     * On a voice only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default data subscription Id.
     */
    public static int getDefaultDataSubscriptionId() {
        return sGetDefaultDataSubIdCache.query(null);
    }

    /**
     * Set the subscription which will be used by default for data, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @UnsupportedAppUsage
    public SubscriptionInfo getDefaultDataSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultDataSubscriptionId());
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < TelephonyManager.getDefault().getActiveModemCount();
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId) {
        int subId = SubscriptionManager.getSubscriptionId(phoneId);
        if (isValidSubscriptionId(subId)) {
            putPhoneIdAndSubIdExtra(intent, phoneId, subId);
        } else {
            logd("putPhoneIdAndSubIdExtra: no valid subs");
            intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
            intent.putExtra(EXTRA_SLOT_INDEX, phoneId);
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * Set a field in the subscription database. Note not all fields are supported.
     *
     * @param subscriptionId Subscription Id of Subscription.
     * @param columnName Column name in the database. Note not all fields are supported.
     * @param value Value to store in the database.
     *
     * @throws IllegalArgumentException if {@code subscriptionId} is invalid, or the field is not
     * exposed.
     * @throws SecurityException if callers do not hold the required permission.
     *
     * @see android.provider.Telephony.SimInfo for all the columns.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public static void setSubscriptionProperty(int subscriptionId, @NonNull String columnName,
            @NonNull String value) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setSubscriptionProperty(subscriptionId, columnName, value);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Serialize list of contacts uri to string
     * @hide
     */
    public static String serializeUriLists(List<Uri> uris) {
        List<String> contacts = new ArrayList<>();
        for (Uri uri : uris) {
            contacts.add(uri.toString());
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(contacts);
            oos.flush();
            return Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT);
        } catch (IOException e) {
            logd("serializeUriLists IO exception");
        }
        return "";
    }

    /**
     * Get specific field in string format from the subscription info database.
     *
     * @param context The calling context.
     * @param subscriptionId Subscription id of the subscription.
     * @param columnName Column name in subscription database.
     *
     * @return Value in string format associated with {@code subscriptionId} and {@code columnName}
     * from the database. Empty string if the {@code subscriptionId} is invalid (for backward
     * compatible).
     *
     * @throws IllegalArgumentException if the field is not exposed.
     *
     * @see android.provider.Telephony.SimInfo for all the columns.
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    private static String getStringSubscriptionProperty(@NonNull Context context,
            int subscriptionId, @NonNull String columnName) {
        String resultValue = null;
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                resultValue = iSub.getSubscriptionProperty(subscriptionId, columnName,
                        context.getOpPackageName(), context.getAttributionTag());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return TextUtils.emptyIfNull(resultValue);
    }

    /**
     * Get specific field in {@code boolean} format from the subscription info database.
     *
     * @param subscriptionId Subscription id of the subscription.
     * @param columnName Column name in subscription database.
     * @param defaultValue Default value in case not found or error.
     * @param context The calling context.
     *
     * @return Value in {@code boolean} format associated with {@code subscriptionId} and
     * {@code columnName} from the database, or {@code defaultValue} if not found or error.
     *
     * @throws IllegalArgumentException if {@code subscriptionId} is invalid, or the field is not
     * exposed.
     *
     * @see android.provider.Telephony.SimInfo for all the columns.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public static boolean getBooleanSubscriptionProperty(int subscriptionId,
            @NonNull String columnName, boolean defaultValue, @NonNull Context context) {
        String result = getStringSubscriptionProperty(context, subscriptionId, columnName);
        if (!result.isEmpty()) {
            try {
                return Integer.parseInt(result) == 1;
            } catch (NumberFormatException err) {
                logd("getBooleanSubscriptionProperty NumberFormat exception");
            }
        }
        return defaultValue;
    }

    /**
     * Get specific field in {@code integer} format from the subscription info database.
     *
     * @param subscriptionId Subscription id of the subscription.
     * @param columnName Column name in subscription database.
     * @param defaultValue Default value in case not found or error.
     * @param context The calling context.
     *
     * @return Value in {@code integer} format associated with {@code subscriptionId} and
     * {@code columnName} from the database, or {@code defaultValue} if not found or error.
     *
     * @throws IllegalArgumentException if {@code subscriptionId} is invalid, or the field is not
     * exposed.
     *
     * @see android.provider.Telephony.SimInfo for all the columns.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public static int getIntegerSubscriptionProperty(int subscriptionId, @NonNull String columnName,
            int defaultValue, @NonNull Context context) {
        String result = getStringSubscriptionProperty(context, subscriptionId, columnName);
        if (!result.isEmpty()) {
            try {
                return Integer.parseInt(result);
            } catch (NumberFormatException err) {
                logd("getIntegerSubscriptionProperty NumberFormat exception");
            }
        }
        return defaultValue;
    }

    /**
     * Get specific field in {@code long} format from the subscription info database.
     *
     * @param subscriptionId Subscription id of the subscription.
     * @param columnName Column name in subscription database.
     * @param defaultValue Default value in case not found or error.
     * @param context The calling context.
     *
     * @return Value in {@code long} format associated with {@code subscriptionId} and
     * {@code columnName} from the database, or {@code defaultValue} if not found or error.
     *
     * @throws IllegalArgumentException if {@code subscriptionId} is invalid, or the field is not
     * exposed.
     *
     * @see android.provider.Telephony.SimInfo for all the columns.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public static long getLongSubscriptionProperty(int subscriptionId, @NonNull String columnName,
            long defaultValue, @NonNull Context context) {
        String result = getStringSubscriptionProperty(context, subscriptionId, columnName);
        if (!result.isEmpty()) {
            try {
                return Long.parseLong(result);
            } catch (NumberFormatException err) {
                logd("getLongSubscriptionProperty NumberFormat exception");
            }
        }
        return defaultValue;
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
        // Check if the Resources already exists in the cache based on the given context. Find a
        // Resource that match Configuration.
        Pair<String, Configuration> cacheKey = null;
        if (isValidSubscriptionId(subId)) {
            Configuration configurationKey =
                    new Configuration(context.getResources().getConfiguration());
            if (useRootLocale) {
                configurationKey.setLocale(Locale.ROOT);
            }
            cacheKey = Pair.create(context.getPackageName() + ", subid=" + subId, configurationKey);
            synchronized (sResourcesCache) {
                Resources cached = sResourcesCache.get(cacheKey);
                if (cached != null) {
                    // Cache hit. Use cached Resources.
                    return cached;
                }
            }
        }

        final SubscriptionInfo subInfo =
                SubscriptionManager.from(context).getActiveSubscriptionInfo(subId);

        Configuration overrideConfig = new Configuration();
        if (subInfo != null) {
            overrideConfig.mcc = subInfo.getMcc();
            overrideConfig.mnc = subInfo.getMnc();
            if (overrideConfig.mnc == 0) {
                overrideConfig.mnc = Configuration.MNC_ZERO;
                cacheKey = null;
            }
        } else {
            cacheKey = null;
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
            synchronized (sResourcesCache) {
                // Save the newly created Resources in the resource cache.
                sResourcesCache.put(cacheKey, res);
            }
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
     *i
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * @deprecated use {@link #setSubscriptionPlans(int, List, long)} instead.
     */
    @Deprecated
    public void setSubscriptionPlans(int subId, @NonNull List<SubscriptionPlan> plans) {
        setSubscriptionPlans(subId, plans, 0);
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
     * @param expirationDurationMillis the duration after which the subscription plans
     *            will be automatically cleared, or {@code 0} to leave the plans until
     *            explicitly cleared, or the next reboot, whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if plans don't meet the requirements
     *             defined in {@link SubscriptionPlan}.
     */
    public void setSubscriptionPlans(int subId, @NonNull List<SubscriptionPlan> plans,
            @DurationMillisLong long expirationDurationMillis) {
        getNetworkPolicyManager().setSubscriptionPlans(subId,
                plans.toArray(new SubscriptionPlan[0]), expirationDurationMillis,
                mContext.getOpPackageName());
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
     * @param expirationDurationMillis the duration after which the requested override
     *            will be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *            outlined above.
     */
    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            @DurationMillisLong long expirationDurationMillis) {
        setSubscriptionOverrideUnmetered(subId, overrideUnmetered,
                TelephonyManager.getAllNetworkTypes(), expirationDurationMillis);
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
     * @param networkTypes the network types this override applies to. If no
     *            network types are specified, override values will be ignored.
     * @param expirationDurationMillis the duration after which the requested override
     *            will be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *            outlined above.
     */
    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            @NonNull @Annotation.NetworkType int[] networkTypes,
            @DurationMillisLong long expirationDurationMillis) {
        final int overrideValue = overrideUnmetered ? SUBSCRIPTION_OVERRIDE_UNMETERED : 0;
        getNetworkPolicyManager().setSubscriptionOverride(subId, SUBSCRIPTION_OVERRIDE_UNMETERED,
                overrideValue, networkTypes, expirationDurationMillis, mContext.getOpPackageName());
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
     * @param expirationDurationMillis the duration after which the requested override
     *            will be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *            outlined above.
     */
    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            @DurationMillisLong long expirationDurationMillis) {
        setSubscriptionOverrideCongested(subId, overrideCongested,
                TelephonyManager.getAllNetworkTypes(), expirationDurationMillis);
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
     * @param overrideCongested set if the subscription should be considered congested.
     * @param networkTypes the network types this override applies to. If no network types are
     * specified, override values will be ignored.
     * @param expirationDurationMillis the duration after which the requested override
     * will be automatically cleared, or {@code 0} to leave in the requested state until explicitly
     * cleared, or the next reboot, whichever happens first.
     *
     * @throws SecurityException if the caller doesn't meet the requirements outlined above.
     */
    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            @NonNull @Annotation.NetworkType int[] networkTypes,
            @DurationMillisLong long expirationDurationMillis) {
        final int overrideValue = overrideCongested ? SUBSCRIPTION_OVERRIDE_CONGESTED : 0;
        getNetworkPolicyManager().setSubscriptionOverride(subId, SUBSCRIPTION_OVERRIDE_CONGESTED,
                overrideValue, networkTypes, expirationDurationMillis, mContext.getOpPackageName());
    }

    /**
     * Checks whether the app with the given context is authorized to manage the given subscription
     * according to its metadata.
     *
     * Only supported for embedded subscriptions (if {@link SubscriptionInfo#isEmbedded} returns
     * true). To check for permissions for non-embedded subscription as well,
     * see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}.
     *
     * @param info The subscription to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
     * @see android.telephony.TelephonyManager#hasCarrierPrivileges
     */
    public boolean canManageSubscription(SubscriptionInfo info) {
        return canManageSubscription(info, mContext.getPackageName());
    }

    /**
     * Checks whether the given app is authorized to manage the given subscription. An app can only
     * be authorized if it is included in the {@link android.telephony.UiccAccessRule} of the
     * {@link android.telephony.SubscriptionInfo} with the access status.
     *
     * Only supported for embedded subscriptions (if {@link SubscriptionInfo#isEmbedded} returns
     * true). To check for permissions for non-embedded subscription as well,
     * see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}.
     *
     * @param info The subscription to check.
     * @param packageName Package name of the app to check.
     *
     * @return whether the app is authorized to manage this subscription per its access rules.
     * @see android.telephony.TelephonyManager#hasCarrierPrivileges
     * @hide
     */
    @SystemApi
    public boolean canManageSubscription(@NonNull SubscriptionInfo info,
            @NonNull String packageName) {
        if (info == null || info.getAccessRules() == null || packageName == null) {
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
        for (UiccAccessRule rule : info.getAccessRules()) {
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
     * @throws IllegalStateException when subscription manager service is not available.
     * @throws SecurityException when clients do not have MODIFY_PHONE_STATE permission.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setPreferredDataSubscriptionId(int subId, boolean needValidation,
            @Nullable @CallbackExecutor Executor executor, @Nullable
            @TelephonyManager.SetOpportunisticSubscriptionResult Consumer<Integer> callback) {
        if (VDBG) logd("[setPreferredDataSubscriptionId]+ subId:" + subId);
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub == null) {
                throw new IllegalStateException("subscription manager service is null.");
            }

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
            loge("setPreferredDataSubscriptionId RemoteException=" + ex);
            ex.rethrowFromSystemServer();
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     *
     *  @deprecated this API is a duplicate of {@link EuiccManager#switchToSubscription(int,
     *  PendingIntent)} and does not support Multiple Enabled Profile(MEP). Apps should use
     *  {@link EuiccManager#switchToSubscription(int, PendingIntent)} or
     *  {@link EuiccManager#switchToSubscription(int, int, PendingIntent)} instead.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    @RequiresFeature(PackageManager.FEATURE_TELEPHONY_EUICC)
    @Deprecated
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
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("createSubscriptionGroup RemoteException " + ex);
            ex.rethrowAsRuntimeException();
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
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
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
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("addSubscriptionsIntoGroup RemoteException " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    private boolean isSystemProcess() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /**
     * Remove a list of subscriptions from their subscription group.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or has carrier privilege permission on all of the subscriptions provided in
     * {@code subIdList}.
     *
     * @param subIdList list of subId that need removing from their groups.
     * @param groupUuid The UUID of the subscription group.
     *
     * @throws SecurityException if the caller doesn't meet the requirements outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't belong the
     * specified group.
     * @throws IllegalStateException if Telephony service is in bad state.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     *
     * @see #createSubscriptionGroup(List)
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void removeSubscriptionsFromGroup(@NonNull List<Integer> subIdList,
            @NonNull ParcelUuid groupUuid) {
        Preconditions.checkNotNull(subIdList, "subIdList can't be null.");
        Preconditions.checkNotNull(groupUuid, "groupUuid can't be null.");
        String callingPackage = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[removeSubscriptionsFromGroup]");
        }

        int[] subIdArray = subIdList.stream().mapToInt(i->i).toArray();

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.removeSubscriptionsFromGroup(subIdArray, groupUuid, callingPackage);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("removeSubscriptionsFromGroup RemoteException " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Get subscriptionInfo list of subscriptions that are in the same group of given subId.
     *
     * Caller must have {@link android.Manifest.permission#READ_PHONE_STATE}
     * or carrier privilege permission on the subscription.
     * {@link TelephonyManager#hasCarrierPrivileges()}
     *
     * <p>Starting with API level 33, the caller also needs permission to access device identifiers
     * to get the list of subscriptions associated with a group UUID.
     * This method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the app has carrier privilege permission.
     *     {@link TelephonyManager#hasCarrierPrivileges()}
     *     <li>If the app has {@link android.Manifest.permission#READ_PHONE_STATE} permission and
     *     access to device identifiers.
     * </ul>
     *
     * @throws IllegalStateException if Telephony service is in bad state.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     *
     * @param groupUuid of which list of subInfo will be returned.
     * @return list of subscriptionInfo that belong to the same group, including the given
     * subscription itself. It will return an empty list if no subscription belongs to the group.
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

        // TODO(b/296125268) Really this method should throw, but it's common enough that for
        // system callers it's worth having a little magic for the system process until it's
        // made safer.
        if (result == null) result = Collections.emptyList();

        return result;
    }

    /**
     * Whether a subscription is visible to API caller. If it's a bundled opportunistic
     * subscription, it should be hidden anywhere in Settings, dialer, status bar etc.
     * Exception is if caller owns carrier privilege, in which case they will
     * want to see their own hidden subscriptions.
     *
     * @param info the subscriptionInfo to check against.
     *
     * @return {@code true} if this subscription should be visible to the API caller.
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
                // Grouped opportunistic subscriptions are considered invisible
                // to users so they should never be returned.
                if (info.getGroupUuid() != null && info.isOpportunistic()) continue;

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
     * Enable or disable a subscription. This method is same as
     * {@link #setUiccApplicationsEnabled(int, boolean)}.
     *
     * @param subscriptionId Subscription to be enabled or disabled.
     * @param enable whether user is turning it on or off.
     *
     * @return whether the operation is successful.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setSubscriptionEnabled(int subscriptionId, boolean enable) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setUiccApplicationsEnabled(enable, subscriptionId);
            }
        } catch (RemoteException ex) {
            return false;
        }
        return true;
    }

    /**
     * Set uicc applications being enabled or disabled.
     * The value will be remembered on the subscription and will be applied whenever it's present.
     * If the subscription in currently present, it will also apply the setting to modem
     * immediately (the setting in the modem will not change until the modem receives and responds
     * to the request, but typically this should only take a few seconds. The user visible setting
     * available from SubscriptionInfo.areUiccApplicationsEnabled() will be updated
     * immediately.)
     *
     * @param subscriptionId which subscription to operate on.
     * @param enabled whether uicc applications are enabled or disabled.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setUiccApplicationsEnabled(int subscriptionId, boolean enabled) {
        if (VDBG) {
            logd("setUiccApplicationsEnabled subId= " + subscriptionId + " enable " + enabled);
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
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
     * @return whether can disable subscriptions on physical SIMs.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean canDisablePhysicalSubscription() {
        if (VDBG) {
            logd("canDisablePhysicalSubscription");
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.canDisablePhysicalSubscription();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * Check if the subscription is currently active in any slot.
     *
     * @param subscriptionId The subscription id.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
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
     * Set the device to device status sharing user preference for a subscription id. The setting
     * app uses this method to indicate with whom they wish to share device to device status
     * information.
     *
     * @param subscriptionId The subscription id.
     * @param sharing The status sharing preference.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDeviceToDeviceStatusSharingPreference(int subscriptionId,
            @DeviceToDeviceStatusSharingPreference int sharing) {
        if (VDBG) {
            logd("[setDeviceToDeviceStatusSharing] + sharing: " + sharing + " subId: "
                    + subscriptionId);
        }
        setSubscriptionPropertyHelper(subscriptionId, "setDeviceToDeviceSharingStatus",
                (iSub)->iSub.setDeviceToDeviceStatusSharing(sharing, subscriptionId));
    }

    /**
     * Returns the user-chosen device to device status sharing preference
     * @param subscriptionId Subscription id of subscription
     * @return The device to device status sharing preference
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    public @DeviceToDeviceStatusSharingPreference int getDeviceToDeviceStatusSharingPreference(
            int subscriptionId) {
        if (VDBG) {
            logd("[getDeviceToDeviceStatusSharing] + subId: " + subscriptionId);
        }
        return getIntegerSubscriptionProperty(subscriptionId, D2D_STATUS_SHARING,
                D2D_SHARING_DISABLED, mContext);
    }

    /**
     * Set the list of contacts that allow device to device status sharing for a subscription id.
     * The setting app uses this method to indicate with whom they wish to share device to device
     * status information.
     *
     * @param subscriptionId The subscription id.
     * @param contacts The list of contacts that allow device to device status sharing.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDeviceToDeviceStatusSharingContacts(int subscriptionId,
            @NonNull List<Uri> contacts) {
        String contactString = serializeUriLists(contacts);
        if (VDBG) {
            logd("[setDeviceToDeviceStatusSharingContacts] + contacts: " + contactString
                    + " subId: " + subscriptionId);
        }
        setSubscriptionPropertyHelper(subscriptionId, "setDeviceToDeviceSharingStatus",
                (iSub)->iSub.setDeviceToDeviceStatusSharingContacts(serializeUriLists(contacts),
                        subscriptionId));
    }

    /**
     * Get the list of contacts that allow device to device status sharing.
     *
     * @param subscriptionId Subscription id.
     *
     * @return The list of contacts that allow device to device status sharing.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    public @NonNull List<Uri> getDeviceToDeviceStatusSharingContacts(int subscriptionId) {
        String result = getStringSubscriptionProperty(mContext, subscriptionId,
                D2D_STATUS_SHARING_SELECTED_CONTACTS);
        if (result != null) {
            try {
                byte[] b = Base64.decode(result, Base64.DEFAULT);
                ByteArrayInputStream bis = new ByteArrayInputStream(b);
                ObjectInputStream ois = new ObjectInputStream(bis);
                List<String> contacts = ArrayList.class.cast(ois.readObject());
                List<Uri> uris = new ArrayList<>();
                for (String contact : contacts) {
                    uris.add(Uri.parse(contact));
                }
                return uris;
            } catch (IOException e) {
                logd("getDeviceToDeviceStatusSharingContacts IO exception");
            } catch (ClassNotFoundException e) {
                logd("getDeviceToDeviceStatusSharingContacts ClassNotFound exception");
            }
        }
        return new ArrayList<>();
    }

    /**
     * Get the active subscription id by logical SIM slot index.
     *
     * @param slotIndex The logical SIM slot index.
     * @return The active subscription id.
     *
     * @throws IllegalArgumentException if the provided slot index is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     *
     * @hide
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
     * different from {@link #getDefaultDataSubscriptionId()}.
     *
     * @return Active data subscription id if any is chosen, or {@link #INVALID_SUBSCRIPTION_ID} if
     * not.
     *
     * @see TelephonyCallback.ActiveDataSubscriptionIdListener
     */
    public static int getActiveDataSubscriptionId() {
        return sGetActiveDataSubscriptionIdCache.query(null);
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
    public static void invalidateSubscriptionManagerServiceCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_SUBSCRIPTION_MANAGER_SERVICE_PROPERTY);
    }

    /**
     * Allows a test process to disable client-side caching operations.
     *
     * @hide
     */
    public static void disableCaching() {
        sGetDefaultSubIdCacheAsUser.disableLocal();
        sGetDefaultDataSubIdCache.disableLocal();
        sGetActiveDataSubscriptionIdCache.disableLocal();
        sGetDefaultSmsSubIdCacheAsUser.disableLocal();
        sGetSlotIndexCache.disableLocal();
        sGetSubIdCache.disableLocal();
        sGetPhoneIdCache.disableLocal();
    }

    /**
     * Clears all process-local binder caches.
     *
     * @hide */
    public static void clearCaches() {
        sGetDefaultSubIdCacheAsUser.clear();
        sGetDefaultDataSubIdCache.clear();
        sGetActiveDataSubscriptionIdCache.clear();
        sGetDefaultSmsSubIdCacheAsUser.clear();
        sGetSlotIndexCache.clear();
        sGetSubIdCache.clear();
        sGetPhoneIdCache.clear();
    }

    /**
     * Called to retrieve SIM-specific settings data to be backed up.
     *
     * @return data in byte[] to be backed up.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public byte[] getAllSimSpecificSettingsForBackup() {
        Bundle bundle =  mContext.getContentResolver().call(
                SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI,
                GET_SIM_SPECIFIC_SETTINGS_METHOD_NAME, null, null);
        return bundle.getByteArray(SubscriptionManager.KEY_SIM_SPECIFIC_SETTINGS_DATA);
    }

    /**
     * Called during setup wizard restore flow to attempt to restore the backed up sim-specific
     * configs to device for all existing SIMs in the subscription database {@link SimInfo}.
     * Internally, it will store the backup data in an internal file. This file will persist on
     * device for device's lifetime and will be used later on when a SIM is inserted to restore that
     * specific SIM's settings. End result is subscription database is modified to match any backed
     * up configs for the appropriate inserted SIMs.
     *
     * <p>
     * The {@link Uri} {@link #SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI} is notified if any
     * {@link SimInfo} entry is updated as the result of this method call.
     *
     * @param data with the sim specific configs to be backed up.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void restoreAllSimSpecificSettingsFromBackup(@NonNull byte[] data) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.restoreAllSimSpecificSettingsFromBackup(data);
            } else {
                throw new IllegalStateException("subscription service unavailable.");
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Returns the phone number for the given {@code subscriptionId} and {@code source},
     * or an empty string if not available.
     *
     * <p>General apps that need to know the phone number should use {@link #getPhoneNumber(int)}
     * instead. This API may be suitable specific apps that needs to know the phone number from
     * a specific source. For example, a carrier app needs to know exactly what's on
     * {@link #PHONE_NUMBER_SOURCE_UICC UICC} and decide if the previously set phone number
     * of source {@link #PHONE_NUMBER_SOURCE_CARRIER carrier} should be updated.
     *
     * <p>The API provides no guarantees of what format the number is in: the format can vary
     * depending on the {@code source} and the network etc. Programmatic parsing should be done
     * cautiously, for example, after formatting the number to a consistent format with
     * {@link android.telephony.PhoneNumberUtils#formatNumberToE164(String, String)}.
     *
     * <p>Note the assumption is that one subscription (which usually means one SIM) has
     * only one phone number. The multiple sources backup each other so hopefully at least one
     * is available. For example, for a carrier that doesn't typically set phone numbers
     * on {@link #PHONE_NUMBER_SOURCE_UICC UICC}, the source {@link #PHONE_NUMBER_SOURCE_IMS IMS}
     * may provide one. Or, a carrier may decide to provide the phone number via source
     * {@link #PHONE_NUMBER_SOURCE_CARRIER carrier} if neither source UICC nor IMS is available.
     *
     * <p>The availability and correctness of the phone number depends on the underlying source
     * and the network etc. Additional verification is needed to use this number for
     * security-related or other sensitive scenarios.
     *
     * @param subscriptionId the subscription ID, or {@link #DEFAULT_SUBSCRIPTION_ID}
     * for the default one.
     * @param source the source of the phone number, one of the PHONE_NUMBER_SOURCE_* constants.
     *
     * @return the phone number, or an empty string if not available.
     *
     * @throws IllegalArgumentException if {@code source} is invalid.
     * @throws IllegalStateException if the telephony process is not currently available.
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     *
     * @see #PHONE_NUMBER_SOURCE_UICC
     * @see #PHONE_NUMBER_SOURCE_CARRIER
     * @see #PHONE_NUMBER_SOURCE_IMS
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    @NonNull
    public String getPhoneNumber(int subscriptionId, @PhoneNumberSource int source) {
        if (subscriptionId == DEFAULT_SUBSCRIPTION_ID) {
            subscriptionId = getDefaultSubscriptionId();
        }
        if (source != PHONE_NUMBER_SOURCE_UICC
                && source != PHONE_NUMBER_SOURCE_CARRIER
                && source != PHONE_NUMBER_SOURCE_IMS) {
            throw new IllegalArgumentException("invalid source " + source);
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.getPhoneNumber(subscriptionId, source,
                        mContext.getOpPackageName(), mContext.getAttributionTag());
            } else {
                throw new IllegalStateException("subscription service unavailable.");
            }
        } catch (RemoteException ex) {
            throw ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns the phone number for the given {@code subId}, or an empty string if
     * not available.
     *
     * <p>This API is suitable for general apps that needs to know the phone number.
     * For specific apps that needs to know the phone number provided by a specific source,
     * {@link #getPhoneNumber(int, int)} may be suitable.
     *
     * <p>This API is built up on {@link #getPhoneNumber(int, int)}, but picks
     * from available sources in the following order: {@link #PHONE_NUMBER_SOURCE_CARRIER}
     * > {@link #PHONE_NUMBER_SOURCE_UICC} > {@link #PHONE_NUMBER_SOURCE_IMS}.
     *
     * <p>The API provides no guarantees of what format the number is in: the format can vary
     * depending on the underlying source and the network etc. Programmatic parsing should be done
     * cautiously, for example, after formatting the number to a consistent format with
     * {@link android.telephony.PhoneNumberUtils#formatNumberToE164(String, String)}.
     *
     * <p>The availability and correctness of the phone number depends on the underlying source
     * and the network etc. Additional verification is needed to use this number for
     * security-related or other sensitive scenarios.
     *
     * @param subscriptionId the subscription ID, or {@link #DEFAULT_SUBSCRIPTION_ID}
     *                       for the default one.
     * @return the phone number, or an empty string if not available.
     *
     * @throws IllegalStateException if the telephony process is not currently available.
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     *
     * @see #getPhoneNumber(int, int)
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    @NonNull
    public String getPhoneNumber(int subscriptionId) {
        if (subscriptionId == DEFAULT_SUBSCRIPTION_ID) {
            subscriptionId = getDefaultSubscriptionId();
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.getPhoneNumberFromFirstAvailableSource(subscriptionId,
                        mContext.getOpPackageName(), mContext.getAttributionTag());
            } else {
                throw new IllegalStateException("subscription service unavailable.");
            }
        } catch (RemoteException ex) {
            throw ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Sets the phone number for the given {@code subId} for source
     * {@link #PHONE_NUMBER_SOURCE_CARRIER carrier}.
     * Sets an empty string to remove the previously set phone number.
     *
     * <p>The API is suitable for carrier apps to provide a phone number, for example when
     * it's not possible to update {@link #PHONE_NUMBER_SOURCE_UICC UICC} directly.
     *
     * <p>It's recommended that the phone number is formatted to well-known formats,
     * for example, by {@link PhoneNumberUtils} {@code formatNumber*} methods.
     *
     * @param subscriptionId the subscription ID, or {@link #DEFAULT_SUBSCRIPTION_ID}
     *                       for the default one.
     * @param number the phone number, or an empty string to remove the previously set number.
     * @throws IllegalStateException if the telephony process is not currently available.
     * @throws NullPointerException if {@code number} is {@code null}.
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     */
    @RequiresPermission("carrier privileges")
    public void setCarrierPhoneNumber(int subscriptionId, @NonNull String number) {
        if (subscriptionId == DEFAULT_SUBSCRIPTION_ID) {
            subscriptionId = getDefaultSubscriptionId();
        }
        if (number == null) {
            throw new NullPointerException("invalid number null");
        }
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setPhoneNumber(subscriptionId, PHONE_NUMBER_SOURCE_CARRIER, number,
                        mContext.getOpPackageName(), mContext.getAttributionTag());
            } else {
                throw new IllegalStateException("subscription service unavailable.");
            }
        } catch (RemoteException ex) {
            throw ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the preferred usage setting.
     *
     * The cellular usage setting is a switch which controls the mode of operation for the cellular
     * radio to either require or not require voice service. It is not managed via Android’s
     * Settings.
     *
     * @param subscriptionId the subId of the subscription.
     * @param usageSetting the requested usage setting.
     *
     * @throws IllegalStateException if a specific mode or setting the mode is not supported on a
     * particular device.
     *
     * <p>Requires {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * or that the calling app has CarrierPrivileges for the given subscription.
     *
     * Note: This method will not allow the setting of USAGE_SETTING_UNKNOWN.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    void setUsageSetting(int subscriptionId, @UsageSetting int usageSetting) {
        if (VDBG) logd("[setUsageSetting]+ setting:" + usageSetting + " subId:" + subscriptionId);
        setSubscriptionPropertyHelper(subscriptionId, "setUsageSetting",
                (iSub)-> iSub.setUsageSetting(
                        usageSetting, subscriptionId, mContext.getOpPackageName()));
    }

    /**
     * Convert phone number source to string.
     *
     * @param source The phone name source.
     *
     * @return The phone name source in string format.
     *
     * @hide
     */
    @NonNull
    public static String phoneNumberSourceToString(@PhoneNumberSource int source) {
        switch (source) {
            case SubscriptionManager.PHONE_NUMBER_SOURCE_UICC: return "UICC";
            case SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER: return "CARRIER";
            case SubscriptionManager.PHONE_NUMBER_SOURCE_IMS: return "IMS";
            default:
                return "UNKNOWN(" + source + ")";
        }
    }

    /**
     * Convert display name source to string.
     *
     * @param source The display name source.
     * @return The display name source in string format.
     *
     * @hide
     */
    @NonNull
    public static String displayNameSourceToString(
            @SubscriptionManager.SimDisplayNameSource int source) {
        switch (source) {
            case SubscriptionManager.NAME_SOURCE_UNKNOWN: return "UNKNOWN";
            case SubscriptionManager.NAME_SOURCE_CARRIER_ID: return "CARRIER_ID";
            case SubscriptionManager.NAME_SOURCE_SIM_SPN: return "SIM_SPN";
            case SubscriptionManager.NAME_SOURCE_USER_INPUT: return "USER_INPUT";
            case SubscriptionManager.NAME_SOURCE_CARRIER: return "CARRIER";
            case SubscriptionManager.NAME_SOURCE_SIM_PNN: return "SIM_PNN";
            default:
                return "UNKNOWN(" + source + ")";
        }
    }

    /**
     * Convert subscription type to string.
     *
     * @param type The subscription type.
     * @return The subscription type in string format.
     *
     * @hide
     */
    @NonNull
    public static String subscriptionTypeToString(@SubscriptionManager.SubscriptionType int type) {
        switch (type) {
            case SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM: return "LOCAL_SIM";
            case SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM: return "REMOTE_SIM";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * Convert usage setting to string.
     *
     * @param usageSetting Usage setting.
     * @return The usage setting in string format.
     *
     * @hide
     */
    @NonNull
    public static String usageSettingToString(@SubscriptionManager.UsageSetting int usageSetting) {
        switch (usageSetting) {
            case SubscriptionManager.USAGE_SETTING_UNKNOWN: return "UNKNOWN";
            case SubscriptionManager.USAGE_SETTING_DEFAULT: return "DEFAULT";
            case SubscriptionManager.USAGE_SETTING_VOICE_CENTRIC: return "VOICE_CENTRIC";
            case SubscriptionManager.USAGE_SETTING_DATA_CENTRIC: return "DATA_CENTRIC";
            default:
                return "UNKNOWN(" + usageSetting + ")";
        }
    }

    /**
     * Set userHandle for a subscription.
     *
     * Used to set an association between a subscription and a user on the device so that voice
     * calling and SMS from that subscription can be associated with that user.
     * Data services are always shared between users on the device.
     *
     * @param subscriptionId the subId of the subscription.
     * @param userHandle the userHandle associated with the subscription.
     * Pass {@code null} user handle to clear the association.
     *
     * @throws IllegalArgumentException if subscription is invalid.
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws IllegalStateException if subscription service is not available.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public void setSubscriptionUserHandle(int subscriptionId, @Nullable UserHandle userHandle) {
        if (!isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("[setSubscriptionUserHandle]: "
                    + "Invalid subscriptionId: " + subscriptionId);
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setSubscriptionUserHandle(userHandle, subscriptionId);
            } else {
                throw new IllegalStateException("[setSubscriptionUserHandle]: "
                        + "subscription service unavailable");
            }
        } catch (RemoteException ex) {
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Get UserHandle of this subscription.
     *
     * Used to get user handle associated with this subscription.
     *
     * @param subscriptionId the subId of the subscription.
     * @return userHandle associated with this subscription
     * or {@code null} if subscription is not associated with any user.
     *
     * @throws IllegalArgumentException if subscription is invalid.
     * @throws SecurityException if the caller doesn't have permissions required.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public @Nullable UserHandle getSubscriptionUserHandle(int subscriptionId) {
        if (!isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("[getSubscriptionUserHandle]: "
                    + "Invalid subscriptionId: " + subscriptionId);
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.getSubscriptionUserHandle(subscriptionId);
            } else {
                Log.e(LOG_TAG, "[getSubscriptionUserHandle]: subscription service unavailable");
            }
        } catch (RemoteException ex) {
            ex.rethrowAsRuntimeException();
        }
        return null;
    }

    /**
     * Check if subscription and user are associated with each other.
     *
     * @param subscriptionId the subId of the subscription
     * @param userHandle user handle of the user
     * @return {@code true} if subscription is associated with user
     * else {@code false} if subscription is not associated with user.
     *
     * @throws IllegalArgumentException if subscription doesn't exist.
     * @throws SecurityException if the caller doesn't have permissions required.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public boolean isSubscriptionAssociatedWithUser(int subscriptionId,
            @NonNull UserHandle userHandle) {
        if (!isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("[isSubscriptionAssociatedWithUser]: "
                    + "Invalid subscriptionId: " + subscriptionId);
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.isSubscriptionAssociatedWithUser(subscriptionId, userHandle);
            } else {
                Log.e(LOG_TAG, "[isSubscriptionAssociatedWithUser]: subscription service "
                        + "unavailable");
            }
        } catch (RemoteException ex) {
            ex.rethrowAsRuntimeException();
        }
        return false;
    }

    /**
     * Returns whether the given subscription is associated with the calling user.
     *
     * @param subscriptionId the subscription ID of the subscription
     * @return {@code true} if the subscription is associated with the user that the current process
     *         is running in; {@code false} otherwise.
     *
     * @throws IllegalArgumentException if subscription doesn't exist.
     * @throws SecurityException if the caller doesn't have permissions required.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_USER_ASSOCIATION_QUERY)
    public boolean isSubscriptionAssociatedWithUser(int subscriptionId) {
        if (!isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("[isSubscriptionAssociatedWithCallingUser]: "
                    + "Invalid subscriptionId: " + subscriptionId);
        }

        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.isSubscriptionAssociatedWithCallingUser(subscriptionId);
            } else {
                throw new IllegalStateException("subscription service unavailable.");
            }
        } catch (RemoteException ex) {
            ex.rethrowAsRuntimeException();
        }
        return false;
    }

    /**
     * Get list of subscriptions associated with user.
     *
     * @param userHandle user handle of the user
     * @return list of subscriptionInfo associated with the user.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws IllegalStateException if subscription service is not available.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public @NonNull List<SubscriptionInfo> getSubscriptionInfoListAssociatedWithUser(
            @NonNull UserHandle userHandle) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                return iSub.getSubscriptionInfoListAssociatedWithUser(userHandle);
            } else {
                Log.e(LOG_TAG, "[getSubscriptionInfoListAssociatedWithUser]: "
                        + "subscription service unavailable");
            }
        } catch (RemoteException ex) {
            ex.rethrowAsRuntimeException();
        }
        return new ArrayList<>();
    }

    /**
     * @return the bitmasks combination of all service capabilities.
     * @hide
     */
    public static int getAllServiceCapabilityBitmasks() {
        return SERVICE_CAPABILITY_VOICE_BITMASK | SERVICE_CAPABILITY_SMS_BITMASK
                | SERVICE_CAPABILITY_DATA_BITMASK;
    }

    /**
     * @return The set of service capability from a bitmask combined one.
     * @hide
     */
    @NonNull
    @ServiceCapability
    public static Set<Integer> getServiceCapabilitiesSet(int combinedServiceCapabilities) {
        Set<Integer> capabilities = new HashSet<>();
        for (int i = SERVICE_CAPABILITY_VOICE; i <= SERVICE_CAPABILITY_MAX; i++) {
            final int capabilityBitmask = serviceCapabilityToBitmask(i);
            if ((combinedServiceCapabilities & capabilityBitmask) == capabilityBitmask) {
                capabilities.add(i);
            }
        }
        return Collections.unmodifiableSet(capabilities);
    }

    /**
     * @return The service capability bitmask from a {@link ServiceCapability} value.
     * @hide
     */
    public static int serviceCapabilityToBitmask(@ServiceCapability int capability) {
        return 1 << (capability - 1);
    }

    /**
     * Set the transfer status of the subscriptionInfo of the subId.
     * @param subscriptionId The unique SubscriptionInfo key in database.
     * @param status The transfer status to change.
     *
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void setTransferStatus(int subscriptionId, @TransferStatus int status) {
        try {
            ISub iSub = TelephonyManager.getSubscriptionService();
            if (iSub != null) {
                iSub.setTransferStatus(subscriptionId, status);
            }
        } catch (RemoteException ex) {
            logd("setTransferStatus for subId = " + subscriptionId + " failed.");
            throw ex.rethrowFromSystemServer();
        }
    }
}
