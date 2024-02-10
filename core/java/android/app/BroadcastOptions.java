/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#sendBroadcast(android.content.Intent)
 * Context.sendBroadcast(Intent)} and related methods.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BroadcastOptions extends ComponentOptions {
    private @Flags int mFlags;
    private long mTemporaryAppAllowlistDuration;
    private @TempAllowListType int mTemporaryAppAllowlistType;
    private @ReasonCode int mTemporaryAppAllowlistReasonCode;
    private @Nullable String mTemporaryAppAllowlistReason;
    private int mMinManifestReceiverApiLevel = 0;
    private int mMaxManifestReceiverApiLevel = Build.VERSION_CODES.CUR_DEVELOPMENT;
    private String[] mRequireAllOfPermissions;
    private String[] mRequireNoneOfPermissions;
    private long mRequireCompatChangeId = CHANGE_INVALID;
    private long mIdForResponseEvent;
    private @DeliveryGroupPolicy int mDeliveryGroupPolicy;
    private @Nullable String mDeliveryGroupMatchingNamespaceFragment;
    private @Nullable String mDeliveryGroupMatchingKeyFragment;
    private @Nullable BundleMerger mDeliveryGroupExtrasMerger;
    private @Nullable IntentFilter mDeliveryGroupMatchingFilter;
    private @DeferralPolicy int mDeferralPolicy;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_DONT_SEND_TO_RESTRICTED_APPS,
            FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS,
            FLAG_REQUIRE_COMPAT_CHANGE_ENABLED,
            FLAG_IS_ALARM_BROADCAST,
            FLAG_SHARE_IDENTITY,
            FLAG_INTERACTIVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    private static final int FLAG_DONT_SEND_TO_RESTRICTED_APPS = 1 << 0;
    private static final int FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS = 1 << 1;
    private static final int FLAG_REQUIRE_COMPAT_CHANGE_ENABLED = 1 << 2;
    private static final int FLAG_IS_ALARM_BROADCAST = 1 << 3;
    private static final int FLAG_SHARE_IDENTITY = 1 << 4;
    private static final int FLAG_INTERACTIVE = 1 << 5;

    /**
     * Change ID which is invalid.
     *
     * @hide
     */
    public static final long CHANGE_INVALID = Long.MIN_VALUE;

    /**
     * Change ID which is always enabled, for testing purposes.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.BASE)
    public static final long CHANGE_ALWAYS_ENABLED = 209888056L;

    /**
     * Change ID which is always disabled, for testing purposes.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @Disabled
    public static final long CHANGE_ALWAYS_DISABLED = 210856463L;

    /**
     * Corresponds to {@link #mFlags}.
     */
    private static final String KEY_FLAGS = "android:broadcast.flags";

    /**
     * How long to temporarily put an app on the power allowlist when executing this broadcast
     * to it.
     */
    private static final String KEY_TEMPORARY_APP_ALLOWLIST_DURATION
            = "android:broadcast.temporaryAppAllowlistDuration";

    private static final String KEY_TEMPORARY_APP_ALLOWLIST_TYPE
            = "android:broadcast.temporaryAppAllowlistType";

    private static final String KEY_TEMPORARY_APP_ALLOWLIST_REASON_CODE =
            "android:broadcast.temporaryAppAllowlistReasonCode";

    private static final String KEY_TEMPORARY_APP_ALLOWLIST_REASON =
            "android:broadcast.temporaryAppAllowlistReason";

    /**
     * Corresponds to {@link #setMinManifestReceiverApiLevel}.
     */
    private static final String KEY_MIN_MANIFEST_RECEIVER_API_LEVEL
            = "android:broadcast.minManifestReceiverApiLevel";

    /**
     * Corresponds to {@link #setMaxManifestReceiverApiLevel}.
     */
    private static final String KEY_MAX_MANIFEST_RECEIVER_API_LEVEL
            = "android:broadcast.maxManifestReceiverApiLevel";

    /**
     * Corresponds to {@link #setRequireAllOfPermissions}
     * @hide
     */
    public static final String KEY_REQUIRE_ALL_OF_PERMISSIONS =
            "android:broadcast.requireAllOfPermissions";

    /**
     * Corresponds to {@link #setRequireNoneOfPermissions}
     * @hide
     */
    public static final String KEY_REQUIRE_NONE_OF_PERMISSIONS =
            "android:broadcast.requireNoneOfPermissions";

    /**
     * Corresponds to {@link #setRequireCompatChange(long, boolean)}
     */
    private static final String KEY_REQUIRE_COMPAT_CHANGE_ID =
            "android:broadcast.requireCompatChangeId";

    /**
     * @hide
     * @deprecated Use {@link android.os.PowerExemptionManager#
     * TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED} instead.
     */
    @Deprecated
    public static final int TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_ALLOWED =
            PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

    /**
     * @hide
     * @deprecated Use {@link android.os.PowerExemptionManager#
     * TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED} instead.
     */
    @Deprecated
    public static final int TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED =
            PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;

    /**
     * Corresponds to {@link #recordResponseEventWhileInBackground(long)}.
     */
    private static final String KEY_ID_FOR_RESPONSE_EVENT =
            "android:broadcast.idForResponseEvent";

    /**
     * Corresponds to {@link #setDeliveryGroupPolicy(int)}.
     */
    private static final String KEY_DELIVERY_GROUP_POLICY =
            "android:broadcast.deliveryGroupPolicy";

    /**
     * Corresponds to namespace fragment of {@link #setDeliveryGroupMatchingKey(String, String)}.
     */
    private static final String KEY_DELIVERY_GROUP_NAMESPACE =
            "android:broadcast.deliveryGroupMatchingNamespace";

    /**
     * Corresponds to key fragment of {@link #setDeliveryGroupMatchingKey(String, String)}.
     */
    private static final String KEY_DELIVERY_GROUP_KEY =
            "android:broadcast.deliveryGroupMatchingKey";

    /**
     * Corresponds to {@link #setDeliveryGroupExtrasMerger(BundleMerger)}.
     */
    private static final String KEY_DELIVERY_GROUP_EXTRAS_MERGER =
            "android:broadcast.deliveryGroupExtrasMerger";

    /**
     * Corresponds to {@link #setDeliveryGroupMatchingFilter(IntentFilter)}.
     */
    private static final String KEY_DELIVERY_GROUP_MATCHING_FILTER =
            "android:broadcast.deliveryGroupMatchingFilter";

    /**
     * Corresponds to {@link #setDeferralPolicy(int)}
     */
    private static final String KEY_DEFERRAL_POLICY =
            "android:broadcast.deferralPolicy";

    /**
     * The list of delivery group policies which specify how multiple broadcasts belonging to
     * the same delivery group has to be handled.
     * @hide
     */
    @IntDef(prefix = { "DELIVERY_GROUP_POLICY_" }, value = {
            DELIVERY_GROUP_POLICY_ALL,
            DELIVERY_GROUP_POLICY_MOST_RECENT,
            DELIVERY_GROUP_POLICY_MERGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeliveryGroupPolicy {}

    /**
     * Delivery group policy that indicates that all the broadcasts in the delivery group
     * need to be delivered as is.
     */
    public static final int DELIVERY_GROUP_POLICY_ALL = 0;

    /**
     * Delivery group policy that indicates that only the most recent broadcast in the delivery
     * group need to be delivered and the rest can be dropped.
     */
    public static final int DELIVERY_GROUP_POLICY_MOST_RECENT = 1;

    /**
     * Delivery group policy that indicates that the extras data from the broadcasts in the
     * delivery group need to be merged into a single broadcast and the rest can be dropped.
     *
     * @hide
     */
    public static final int DELIVERY_GROUP_POLICY_MERGED = 2;

    /** {@hide} */
    @IntDef(prefix = { "DEFERRAL_POLICY_" }, value = {
            DEFERRAL_POLICY_DEFAULT,
            DEFERRAL_POLICY_NONE,
            DEFERRAL_POLICY_UNTIL_ACTIVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeferralPolicy {}

    /**
     * Deferral policy that indicates no desire has been expressed, and that the
     * system should use a reasonable default behavior.
     */
    public static final int DEFERRAL_POLICY_DEFAULT = 0;

    /**
     * Deferral policy that indicates a strong desire that no receiver of this
     * broadcast should be deferred.
     */
    public static final int DEFERRAL_POLICY_NONE = 1;

    /**
     * Deferral policy that indicates a strong desire that each receiver of this
     * broadcast should be deferred until that receiver's process is in an
     * active (non-cached) state. Whether an app's process state is considered
     * active is independent of its standby bucket.
     * <p>
     * This policy only applies to runtime registered receivers of a broadcast,
     * and does not apply to ordered broadcasts, alarm broadcasts, interactive
     * broadcasts, or manifest broadcasts.
     * <p>
     * This policy means that a runtime registered receiver will not typically
     * execute until that receiver's process is brought to an active state by
     * some other action, such as a job, alarm, or service binding. As a result,
     * the receiver may be delayed indefinitely.
     * <p>
     * When this policy is set on an unordered broadcast with a completion
     * callback, the completion callback will run once all eligible processes
     * have finished receiving the broadcast. Processes in inactive process
     * state are not considered eligible and may not receive the broadcast prior
     * to the completion callback.
     */
    public static final int DEFERRAL_POLICY_UNTIL_ACTIVE = 2;

    /**
     * Creates a basic {@link BroadcastOptions} with no options initially set.
     *
     * @return an instance of {@code BroadcastOptions} against which options can be set
     */
    public static @NonNull BroadcastOptions makeBasic() {
        BroadcastOptions opts = new BroadcastOptions();
        return opts;
    }

    /** @hide */
    @TestApi
    public BroadcastOptions() {
        super();
        resetTemporaryAppAllowlist();
    }

    /** @hide */
    @TestApi
    public BroadcastOptions(@NonNull Bundle opts) {
        super(opts);
        // Match the logic in toBundle().
        mFlags = opts.getInt(KEY_FLAGS, 0);
        if (opts.containsKey(KEY_TEMPORARY_APP_ALLOWLIST_DURATION)) {
            mTemporaryAppAllowlistDuration = opts.getLong(KEY_TEMPORARY_APP_ALLOWLIST_DURATION);
            mTemporaryAppAllowlistType = opts.getInt(KEY_TEMPORARY_APP_ALLOWLIST_TYPE);
            mTemporaryAppAllowlistReasonCode = opts.getInt(KEY_TEMPORARY_APP_ALLOWLIST_REASON_CODE,
                    PowerExemptionManager.REASON_UNKNOWN);
            mTemporaryAppAllowlistReason = opts.getString(KEY_TEMPORARY_APP_ALLOWLIST_REASON);
        } else {
            resetTemporaryAppAllowlist();
        }
        mMinManifestReceiverApiLevel = opts.getInt(KEY_MIN_MANIFEST_RECEIVER_API_LEVEL, 0);
        mMaxManifestReceiverApiLevel = opts.getInt(KEY_MAX_MANIFEST_RECEIVER_API_LEVEL,
                Build.VERSION_CODES.CUR_DEVELOPMENT);
        mRequireAllOfPermissions = opts.getStringArray(KEY_REQUIRE_ALL_OF_PERMISSIONS);
        mRequireNoneOfPermissions = opts.getStringArray(KEY_REQUIRE_NONE_OF_PERMISSIONS);
        mRequireCompatChangeId = opts.getLong(KEY_REQUIRE_COMPAT_CHANGE_ID, CHANGE_INVALID);
        mIdForResponseEvent = opts.getLong(KEY_ID_FOR_RESPONSE_EVENT);
        mDeliveryGroupPolicy = opts.getInt(KEY_DELIVERY_GROUP_POLICY,
                DELIVERY_GROUP_POLICY_ALL);
        mDeliveryGroupMatchingNamespaceFragment = opts.getString(KEY_DELIVERY_GROUP_NAMESPACE);
        mDeliveryGroupMatchingKeyFragment = opts.getString(KEY_DELIVERY_GROUP_KEY);
        mDeliveryGroupExtrasMerger = opts.getParcelable(KEY_DELIVERY_GROUP_EXTRAS_MERGER,
                BundleMerger.class);
        mDeliveryGroupMatchingFilter = opts.getParcelable(KEY_DELIVERY_GROUP_MATCHING_FILTER,
                IntentFilter.class);
        mDeferralPolicy = opts.getInt(KEY_DEFERRAL_POLICY, DEFERRAL_POLICY_DEFAULT);
    }

    /** @hide */
    @NonNull
    public static BroadcastOptions makeWithDeferUntilActive(boolean deferUntilActive) {
        final BroadcastOptions opts = BroadcastOptions.makeBasic();
        if (deferUntilActive) {
            opts.setDeferralPolicy(DEFERRAL_POLICY_UNTIL_ACTIVE);
        }
        return opts;
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power allowlist when this broadcast is being delivered to it.
     * @param duration The duration in milliseconds; 0 means to not place on allowlist.
     * @deprecated use {@link #setTemporaryAppAllowlist(long, int, int,  String)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
            android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
            android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND})
    public void setTemporaryAppWhitelistDuration(long duration) {
        setTemporaryAppAllowlist(duration,
                PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_UNKNOWN, null);
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power allowlist when this broadcast is being delivered to it, specify the temp allowlist
     * type.
     * @hide
     *
     * @param duration the duration in milliseconds.
     *                 0 means to not place on allowlist, and clears previous call to this method.
     * @param type one of {@link TempAllowListType}.
     *             {@link PowerExemptionManager#TEMPORARY_ALLOW_LIST_TYPE_NONE} means
     *             to not place on allowlist, and clears previous call to this method.
     * @param reasonCode one of {@link ReasonCode}, use
     *                  {@link PowerExemptionManager#REASON_UNKNOWN} if not sure.
     * @param reason A human-readable reason explaining why the app is temp allowlisted. Only
     *               used for logging purposes. Could be null or empty string.
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
            android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
            android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND})
    public void setTemporaryAppAllowlist(long duration, @TempAllowListType int type,
            @ReasonCode int reasonCode, @Nullable String reason) {
        mTemporaryAppAllowlistDuration = duration;
        mTemporaryAppAllowlistType = type;
        mTemporaryAppAllowlistReasonCode = reasonCode;
        mTemporaryAppAllowlistReason = reason;

        if (!isTemporaryAppAllowlistSet()) {
            resetTemporaryAppAllowlist();
        }
    }

    private boolean isTemporaryAppAllowlistSet() {
        return mTemporaryAppAllowlistDuration > 0
                && mTemporaryAppAllowlistType
                    != PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;
    }

    private void resetTemporaryAppAllowlist() {
        mTemporaryAppAllowlistDuration = 0;
        mTemporaryAppAllowlistType = PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;
        mTemporaryAppAllowlistReasonCode = PowerExemptionManager.REASON_UNKNOWN;
        mTemporaryAppAllowlistReason = null;
    }

    /**
     * Return {@link #setTemporaryAppAllowlist}.
     * @hide
     */
    @TestApi
    public long getTemporaryAppAllowlistDuration() {
        return mTemporaryAppAllowlistDuration;
    }

    /**
     * Return {@link #mTemporaryAppAllowlistType}.
     * @hide
     */
    @TestApi
    public @TempAllowListType int getTemporaryAppAllowlistType() {
        return mTemporaryAppAllowlistType;
    }

    /**
     * Return {@link #mTemporaryAppAllowlistReasonCode}.
     * @hide
     */
    @TestApi
    public @ReasonCode int getTemporaryAppAllowlistReasonCode() {
        return mTemporaryAppAllowlistReasonCode;
    }

    /**
     * Return {@link #mTemporaryAppAllowlistReason}.
     * @hide
     */
    @TestApi
    public @Nullable String getTemporaryAppAllowlistReason() {
        return mTemporaryAppAllowlistReason;
    }

    /**
     * Set the minimum target API level of receivers of the broadcast.  If an application
     * is targeting an API level less than this, the broadcast will not be delivered to
     * them.  This only applies to receivers declared in the app's AndroidManifest.xml.
     *
     * @deprecated to give developers the most flexibility during beta releases,
     *             we strongly encourage using {@link ChangeId} instead of
     *             target SDK checks; callers should use
     *             {@link #setRequireCompatChange(long, boolean)} instead,
     *             possibly combined with
     *             {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY}.
     * @hide
     */
    @Deprecated
    public void setMinManifestReceiverApiLevel(int apiLevel) {
        mMinManifestReceiverApiLevel = apiLevel;
    }

    /**
     * Return {@link #setMinManifestReceiverApiLevel}.
     *
     * @deprecated to give developers the most flexibility during beta releases,
     *             we strongly encourage using {@link ChangeId} instead of
     *             target SDK checks; callers should use
     *             {@link #setRequireCompatChange(long, boolean)} instead,
     *             possibly combined with
     *             {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY}.
     * @hide
     */
    @Deprecated
    public int getMinManifestReceiverApiLevel() {
        return mMinManifestReceiverApiLevel;
    }

    /**
     * Set the maximum target API level of receivers of the broadcast.  If an application
     * is targeting an API level greater than this, the broadcast will not be delivered to
     * them.  This only applies to receivers declared in the app's AndroidManifest.xml.
     *
     * @deprecated to give developers the most flexibility during beta releases,
     *             we strongly encourage using {@link ChangeId} instead of
     *             target SDK checks; callers should use
     *             {@link #setRequireCompatChange(long, boolean)} instead,
     *             possibly combined with
     *             {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY}.
     * @hide
     */
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @Deprecated
    public void setMaxManifestReceiverApiLevel(int apiLevel) {
        mMaxManifestReceiverApiLevel = apiLevel;
    }

    /**
     * Return {@link #setMaxManifestReceiverApiLevel}.
     *
     * @deprecated to give developers the most flexibility during beta releases,
     *             we strongly encourage using {@link ChangeId} instead of
     *             target SDK checks; callers should use
     *             {@link #setRequireCompatChange(long, boolean)} instead,
     *             possibly combined with
     *             {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY}.
     * @hide
     */
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @Deprecated
    public int getMaxManifestReceiverApiLevel() {
        return mMaxManifestReceiverApiLevel;
    }

    /**
     * Sets whether pending intent can be sent for an application with background restrictions
     * @param dontSendToRestrictedApps if true, pending intent will not be sent for an application
     * with background restrictions. Default value is {@code false}
     * @hide
     */
    @SystemApi
    public void setDontSendToRestrictedApps(boolean dontSendToRestrictedApps) {
        if (dontSendToRestrictedApps) {
            mFlags |= FLAG_DONT_SEND_TO_RESTRICTED_APPS;
        } else {
            mFlags &= ~FLAG_DONT_SEND_TO_RESTRICTED_APPS;
        }
    }

    /**
     * @hide
     * @return #setDontSendToRestrictedApps
     */
    public boolean isDontSendToRestrictedApps() {
        return (mFlags & FLAG_DONT_SEND_TO_RESTRICTED_APPS) != 0;
    }

    /**
     * Sets the process will be able to start activities from background for the duration of
     * the broadcast dispatch. Default value is {@code false}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND)
    public void setBackgroundActivityStartsAllowed(boolean allowBackgroundActivityStarts) {
        if (allowBackgroundActivityStarts) {
            mFlags |= FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS;
        } else {
            mFlags &= ~FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS;
        }
    }

    /**
     * @hide
     * @return #setAllowBackgroundActivityStarts
     */
    @Deprecated
    public boolean allowsBackgroundActivityStarts() {
        return (mFlags & FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS) != 0;
    }

    /**
     * Use this to configure a broadcast to be sent to apps that hold all permissions in
     * the list. This is only for use with the {@link Context#sendBroadcast(Intent intent,
     * @Nullable String receiverPermission, @Nullable Bundle options)}.
     *
     * <p> If both {@link #setRequireAllOfPermissions(String[])} and
     * {@link #setRequireNoneOfPermissions(String[])} are used, then receivers must have all of the
     * permissions set by {@link #setRequireAllOfPermissions(String[])}, and none of the
     * permissions set by {@link #setRequireNoneOfPermissions(String[])} to get the broadcast.
     *
     * @param requiredPermissions a list of Strings of permission the receiver must have. Set to
     *                            null or an empty array to clear any previously set value.
     * @hide
     */
    @SystemApi
    public void setRequireAllOfPermissions(@Nullable String[] requiredPermissions) {
        mRequireAllOfPermissions = requiredPermissions;
    }

    /**
     * Use this to configure a broadcast to be sent to apps that don't hold any permissions in
     * list. This is only for use with the {@link Context#sendBroadcast(Intent intent,
     * @Nullable String receiverPermission, @Nullable Bundle options)}.
     *
     * <p> If both {@link #setRequireAllOfPermissions(String[])} and
     * {@link #setRequireNoneOfPermissions(String[])} are used, then receivers must have all of the
     * permissions set by {@link #setRequireAllOfPermissions(String[])}, and none of the
     * permissions set by {@link #setRequireNoneOfPermissions(String[])} to get the broadcast.
     *
     * @param excludedPermissions a list of Strings of permission the receiver must not have. Set to
     *                            null or an empty array to clear any previously set value.
     * @hide
     */
    @SystemApi
    public void setRequireNoneOfPermissions(@Nullable String[] excludedPermissions) {
        mRequireNoneOfPermissions = excludedPermissions;
    }

    /**
     * When set, this broadcast will only be delivered to apps which have the
     * given {@link ChangeId} in the given state.
     * <p>
     * Each {@link BroadcastOptions} instance supports only a single
     * {@link ChangeId} requirement, so any subsequent calls will override any
     * previously defined requirement.
     * <p>
     * This requirement applies to both manifest registered and runtime
     * registered receivers.
     * @hide
     *
     * @param changeId the {@link ChangeId} to inspect
     * @param enabled the required enabled state of the inspected
     *            {@link ChangeId} for this broadcast to be delivered
     * @see CompatChanges#isChangeEnabled
     * @see #clearRequireCompatChange()
     */
    @SystemApi
    public void setRequireCompatChange(long changeId, boolean enabled) {
        mRequireCompatChangeId = changeId;
        if (enabled) {
            mFlags |= FLAG_REQUIRE_COMPAT_CHANGE_ENABLED;
        } else {
            mFlags &= ~FLAG_REQUIRE_COMPAT_CHANGE_ENABLED;
        }
    }

    /**
     * Clear any previously defined requirement for this broadcast requested via
     * {@link #setRequireCompatChange(long, boolean)}.
     * @hide
     */
    @SystemApi
    public void clearRequireCompatChange() {
        setRequireCompatChange(CHANGE_INVALID, true);
    }

    /**
     * When set, this broadcast will be understood as having originated from an
     * alarm going off.  Only the OS itself can use this option; uses by other
     * senders will be ignored.
     * @hide
     *
     * @param senderIsAlarm Whether the broadcast is alarm-triggered.
     */
    public void setAlarmBroadcast(boolean senderIsAlarm) {
        if (senderIsAlarm) {
            mFlags |= FLAG_IS_ALARM_BROADCAST;
        } else {
            mFlags &= ~FLAG_IS_ALARM_BROADCAST;
        }
    }

    /**
     * Did this broadcast originate from an alarm triggering?
     * @return true if this broadcast is an alarm message, false otherwise
     * @hide
     */
    public boolean isAlarmBroadcast() {
        return (mFlags & FLAG_IS_ALARM_BROADCAST) != 0;
    }

    /**
     * Sets whether the identity of the broadcasting app should be shared with all receivers
     * that will receive this broadcast.
     *
     * <p>Use this option when broadcasting to a receiver that needs to know the identity of the
     * broadcaster; with this set to {@code true}, the receiver will have access to the broadcasting
     * app's package name and uid.
     *
     * <p>Defaults to {@code false} if not set.
     *
     * @param shareIdentityEnabled whether the broadcasting app's identity should be shared with the
     *                             receiver
     * @return {@code this} {@link BroadcastOptions} instance
     * @see BroadcastReceiver#getSentFromUid()
     * @see BroadcastReceiver#getSentFromPackage()
     */
    public @NonNull BroadcastOptions setShareIdentityEnabled(boolean shareIdentityEnabled) {
        if (shareIdentityEnabled) {
            mFlags |= FLAG_SHARE_IDENTITY;
        } else {
            mFlags &= ~FLAG_SHARE_IDENTITY;
        }
        return this;
    }

    /**
     * Returns whether the broadcasting app has opted-in to sharing its identity with the receiver.
     *
     * @return {@code true} if the broadcasting app has opted in to sharing its identity
     * @see #setShareIdentityEnabled(boolean)
     * @see BroadcastReceiver#getSentFromUid()
     * @see BroadcastReceiver#getSentFromPackage()
     */
    public boolean isShareIdentityEnabled() {
        return (mFlags & FLAG_SHARE_IDENTITY) != 0;
    }

    /**
     * Did this broadcast originate from a push message from the server?
     *
     * @return true if this broadcast is a push message, false otherwise.
     * @hide
     */
    public boolean isPushMessagingBroadcast() {
        return mTemporaryAppAllowlistReasonCode == PowerExemptionManager.REASON_PUSH_MESSAGING;
    }

    /**
     * Did this broadcast originate from a push message from the server which was over the allowed
     * quota?
     *
     * @return true if this broadcast is a push message over quota, false otherwise.
     * @hide
     */
    public boolean isPushMessagingOverQuotaBroadcast() {
        return mTemporaryAppAllowlistReasonCode
                == PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA;
    }

    /** {@hide} */
    public long getRequireCompatChangeId() {
        return mRequireCompatChangeId;
    }

    /**
     * Test if the given app meets the {@link ChangeId} state required by this
     * broadcast, if any.
     *
     * @hide
     */
    @TestApi
    @android.ravenwood.annotation.RavenwoodThrow
    public boolean testRequireCompatChange(int uid) {
        if (mRequireCompatChangeId != CHANGE_INVALID) {
            final boolean requireEnabled = (mFlags & FLAG_REQUIRE_COMPAT_CHANGE_ENABLED) != 0;
            return CompatChanges.isChangeEnabled(mRequireCompatChangeId, uid) == requireEnabled;
        } else {
            return true;
        }
    }

    /**
     * Sets whether events (such as posting a notification) originating from an app after it
     * receives the broadcast while in background should be recorded as responses to the broadcast.
     *
     * <p> Note that this will only be considered when sending explicit broadcast intents.
     *
     * @param id ID to be used for the response events corresponding to this broadcast. If the
     *           value is {@code 0} (default), then response events will not be recorded. Otherwise,
     *           they will be recorded with the ID provided.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS)
    public void recordResponseEventWhileInBackground(@IntRange(from = 0) long id) {
        mIdForResponseEvent = id;
    }

    /** @hide */
    @IntRange(from = 0)
    public long getIdForResponseEvent() {
        return mIdForResponseEvent;
    }

    /**
     * Sets deferral policy for this broadcast that specifies how this broadcast
     * can be deferred for delivery at some future point.
     */
    public @NonNull BroadcastOptions setDeferralPolicy(@DeferralPolicy int deferralPolicy) {
        mDeferralPolicy = deferralPolicy;
        return this;
    }

    /**
     * Gets deferral policy for this broadcast that specifies how this broadcast
     * can be deferred for delivery at some future point.
     */
    public @DeferralPolicy int getDeferralPolicy() {
        return mDeferralPolicy;
    }

    /**
     * Clears any deferral policy for this broadcast that specifies how this
     * broadcast can be deferred for delivery at some future point.
     */
    public void clearDeferralPolicy() {
        mDeferralPolicy = DEFERRAL_POLICY_DEFAULT;
    }

    /**
     * Set delivery group policy for this broadcast to specify how multiple broadcasts belonging to
     * the same delivery group has to be handled.
     */
    @NonNull
    public BroadcastOptions setDeliveryGroupPolicy(@DeliveryGroupPolicy int policy) {
        mDeliveryGroupPolicy = policy;
        return this;
    }

    /**
     * Get the delivery group policy for this broadcast that specifies how multiple broadcasts
     * belonging to the same delivery group has to be handled.
     */
    public @DeliveryGroupPolicy int getDeliveryGroupPolicy() {
        return mDeliveryGroupPolicy;
    }

    /**
     * Clears any previously set delivery group policies using
     * {@link #setDeliveryGroupMatchingKey(String, String)} and resets the delivery group policy to
     * the default value ({@link #DELIVERY_GROUP_POLICY_ALL}).
     */
    public void clearDeliveryGroupPolicy() {
        mDeliveryGroupPolicy = DELIVERY_GROUP_POLICY_ALL;
    }

    /**
     * Set namespace and key to identify the delivery group that this broadcast belongs to.
     *
     * <p> If {@code namespace} and {@code key} are specified, then another broadcast will be
     * considered to be in the same delivery group as this iff it has the same {@code namespace}
     * and {@code key}.
     *
     * <p> If not matching key using this API then by default
     * {@link Intent#filterEquals(Intent)} will be used to identify the delivery group.
     */
    @NonNull
    public BroadcastOptions setDeliveryGroupMatchingKey(@NonNull String namespace,
            @NonNull String key) {
        mDeliveryGroupMatchingNamespaceFragment = Objects.requireNonNull(namespace);
        mDeliveryGroupMatchingKeyFragment = Objects.requireNonNull(key);
        return this;
    }

    /**
     * Return the namespace and key that is used to identify the delivery group that this
     * broadcast belongs to.
     *
     * @return the delivery group namespace and key that was previously set using
     *         {@link #setDeliveryGroupMatchingKey(String, String)}, concatenated with a {@code :}.
     */
    @Nullable
    public String getDeliveryGroupMatchingKey() {
        if (mDeliveryGroupMatchingNamespaceFragment == null
                || mDeliveryGroupMatchingKeyFragment == null) {
            return null;
        }
        return String.join(":", mDeliveryGroupMatchingNamespaceFragment,
                mDeliveryGroupMatchingKeyFragment);
    }

    /**
     * Return the namespace fragment that is used to identify the delivery group that this
     * broadcast belongs to.
     *
     * @return the delivery group namespace fragment that was previously set using
     *         {@link #setDeliveryGroupMatchingKey(String, String)}.
     * @hide
     */
    @Nullable
    public String getDeliveryGroupMatchingNamespaceFragment() {
        return mDeliveryGroupMatchingNamespaceFragment;
    }

    /**
     * Return the key fragment that is used to identify the delivery group that this
     * broadcast belongs to.
     *
     * @return the delivery group key fragment that was previously set using
     *         {@link #setDeliveryGroupMatchingKey(String, String)}.
     * @hide
     */
    @Nullable
    public String getDeliveryGroupMatchingKeyFragment() {
        return mDeliveryGroupMatchingKeyFragment;
    }

    /**
     * Clears the namespace and key that was previously set using
     * {@link #setDeliveryGroupMatchingKey(String, String)}.
     */
    public void clearDeliveryGroupMatchingKey() {
        mDeliveryGroupMatchingNamespaceFragment = null;
        mDeliveryGroupMatchingKeyFragment = null;
    }

    /**
     * Set the {@link IntentFilter} object to identify the delivery group that this broadcast
     * belongs to.
     *
     * <p> If a {@code matchingFilter} is specified, then another broadcast will be considered
     * to be in the same delivery group as this iff the {@code matchingFilter} matches it's intent.
     *
     * <p> If neither matching key using {@link #setDeliveryGroupMatchingKey(String, String)} nor
     * matching filter using this API is specified, then by default
     * {@link Intent#filterEquals(Intent)} will be used to identify the delivery group.
     *
     * @hide
     */
    @NonNull
    public BroadcastOptions setDeliveryGroupMatchingFilter(@NonNull IntentFilter matchingFilter) {
        mDeliveryGroupMatchingFilter = Objects.requireNonNull(matchingFilter);
        return this;
    }

    /**
     * Return the {@link IntentFilter} object that is used to identify the delivery group
     * that this broadcast belongs to.
     *
     * @return the {@link IntentFilter} object that was previously set using
     *         {@link #setDeliveryGroupMatchingFilter(IntentFilter)}.
     * @hide
     */
    @Nullable
    public IntentFilter getDeliveryGroupMatchingFilter() {
        return mDeliveryGroupMatchingFilter;
    }

    /**
     * Clears the {@link IntentFilter} object that was previously set using
     * {@link #setDeliveryGroupMatchingFilter(IntentFilter)}.
     *
     * @hide
     */
    public void clearDeliveryGroupMatchingFilter() {
        mDeliveryGroupMatchingFilter = null;
    }

    /**
     * Set the {@link BundleMerger} that specifies how to merge the extras data from
     * broadcasts in a delivery group.
     *
     * <p>Note that this value will be ignored if the delivery group policy is not set as
     * {@link #DELIVERY_GROUP_POLICY_MERGED}.
     *
     * @hide
     */
    @NonNull
    public BroadcastOptions setDeliveryGroupExtrasMerger(@NonNull BundleMerger extrasMerger) {
        mDeliveryGroupExtrasMerger = Objects.requireNonNull(extrasMerger);
        return this;
    }

    /**
     * Return the {@link BundleMerger} that specifies how to merge the extras data from
     * broadcasts in a delivery group.
     *
     * @return the {@link BundleMerger} object that was previously set using
     *         {@link #setDeliveryGroupExtrasMerger(BundleMerger)}.
     * @hide
     */
    @Nullable
    public BundleMerger getDeliveryGroupExtrasMerger() {
        return mDeliveryGroupExtrasMerger;
    }

    /**
     * Clear the {@link BundleMerger} object that was previously set using
     * {@link #setDeliveryGroupExtrasMerger(BundleMerger)}.
     * @hide
     */
    public void clearDeliveryGroupExtrasMerger() {
        mDeliveryGroupExtrasMerger = null;
    }

    /**
     * Sets whether the broadcast should be considered as having originated from
     * some direct interaction by the user such as a notification tap or button
     * press. This signal is used internally to ensure the broadcast is
     * delivered quickly with low latency.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BROADCAST_OPTION_INTERACTIVE)
    public @NonNull BroadcastOptions setInteractive(boolean interactive) {
        if (interactive) {
            mFlags |= FLAG_INTERACTIVE;
        } else {
            mFlags &= ~FLAG_INTERACTIVE;
        }
        return this;
    }

    /**
     * Returns whether the broadcast should be considered as having originated
     * from some direct interaction by the user such as a notification tap or
     * button press.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BROADCAST_OPTION_INTERACTIVE)
    public boolean isInteractive() {
        return (mFlags & FLAG_INTERACTIVE) != 0;
    }

    /**
     * Set PendingIntent activity is allowed to be started in the background if the caller
     * can start background activities.
     *
     * @deprecated use #setPendingIntentBackgroundActivityStartMode(int) to set the full range
     * of states
     * @hide
     */
    @SystemApi
    @Override
    @Deprecated public void setPendingIntentBackgroundActivityLaunchAllowed(boolean allowed) {
        super.setPendingIntentBackgroundActivityLaunchAllowed(allowed);
    }

    /**
     * Get PendingIntent activity is allowed to be started in the background if the caller can start
     * background activities.
     *
     * @deprecated use {@link #getPendingIntentBackgroundActivityStartMode()} since for apps
     * targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or higher this value might
     * not match the actual behavior if the value was not explicitly set.
     * @hide
     */
    @SystemApi
    @Override
    @Deprecated public boolean isPendingIntentBackgroundActivityLaunchAllowed() {
        return super.isPendingIntentBackgroundActivityLaunchAllowed();
    }


    /**
     * Sets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * This is typically used when executing {@link PendingIntent#send(Bundle)} or similar
     * methods. A privileged sender of a PendingIntent should only grant
     * MODE_BACKGROUND_ACTIVITY_START_ALLOWED if the PendingIntent is from a trusted source and/or
     * executed on behalf the user.
     * @hide
     */
    @SystemApi
    @NonNull
    @Override // to narrow down the return type
    public BroadcastOptions setPendingIntentBackgroundActivityStartMode(int state) {
        super.setPendingIntentBackgroundActivityStartMode(state);
        return this;
    }

    /**
     * Gets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * @see #setPendingIntentBackgroundActivityStartMode(int)
     * @hide
     */
    @SystemApi
    @Override // to narrow down the return type
    public @BackgroundActivityStartMode int getPendingIntentBackgroundActivityStartMode() {
        return super.getPendingIntentBackgroundActivityStartMode();
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#sendBroadcast(android.content.Intent)
     * Context.sendBroadcast(Intent)} and related methods.
     * Note that the returned Bundle is still owned by the BroadcastOptions
     * object; you must not modify it, but can supply it to the sendBroadcast
     * methods that take an options Bundle.
     *
     * @throws IllegalStateException if the broadcast option values are inconsistent. For example,
     *                               if the delivery group policy is specified as "MERGED" but no
     *                               extras merger is supplied.
     */
    @Override
    public @NonNull Bundle toBundle() {
        Bundle b = super.toBundle();
        if (mFlags != 0) {
            b.putInt(KEY_FLAGS, mFlags);
        }
        if (isTemporaryAppAllowlistSet()) {
            b.putLong(KEY_TEMPORARY_APP_ALLOWLIST_DURATION, mTemporaryAppAllowlistDuration);
            b.putInt(KEY_TEMPORARY_APP_ALLOWLIST_TYPE, mTemporaryAppAllowlistType);
            b.putInt(KEY_TEMPORARY_APP_ALLOWLIST_REASON_CODE, mTemporaryAppAllowlistReasonCode);
            b.putString(KEY_TEMPORARY_APP_ALLOWLIST_REASON, mTemporaryAppAllowlistReason);
        }
        if (mMinManifestReceiverApiLevel != 0) {
            b.putInt(KEY_MIN_MANIFEST_RECEIVER_API_LEVEL, mMinManifestReceiverApiLevel);
        }
        if (mMaxManifestReceiverApiLevel != Build.VERSION_CODES.CUR_DEVELOPMENT) {
            b.putInt(KEY_MAX_MANIFEST_RECEIVER_API_LEVEL, mMaxManifestReceiverApiLevel);
        }
        if (mRequireAllOfPermissions != null) {
            b.putStringArray(KEY_REQUIRE_ALL_OF_PERMISSIONS, mRequireAllOfPermissions);
        }
        if (mRequireNoneOfPermissions != null) {
            b.putStringArray(KEY_REQUIRE_NONE_OF_PERMISSIONS, mRequireNoneOfPermissions);
        }
        if (mRequireCompatChangeId != CHANGE_INVALID) {
            b.putLong(KEY_REQUIRE_COMPAT_CHANGE_ID, mRequireCompatChangeId);
        }
        if (mIdForResponseEvent != 0) {
            b.putLong(KEY_ID_FOR_RESPONSE_EVENT, mIdForResponseEvent);
        }
        if (mDeliveryGroupPolicy != DELIVERY_GROUP_POLICY_ALL) {
            b.putInt(KEY_DELIVERY_GROUP_POLICY, mDeliveryGroupPolicy);
        }
        if (mDeliveryGroupMatchingNamespaceFragment != null) {
            b.putString(KEY_DELIVERY_GROUP_NAMESPACE, mDeliveryGroupMatchingNamespaceFragment);
        }
        if (mDeliveryGroupMatchingKeyFragment != null) {
            b.putString(KEY_DELIVERY_GROUP_KEY, mDeliveryGroupMatchingKeyFragment);
        }
        if (mDeliveryGroupPolicy == DELIVERY_GROUP_POLICY_MERGED) {
            if (mDeliveryGroupExtrasMerger != null) {
                b.putParcelable(KEY_DELIVERY_GROUP_EXTRAS_MERGER,
                        mDeliveryGroupExtrasMerger);
            } else {
                throw new IllegalStateException("Extras merger cannot be empty "
                        + "when delivery group policy is 'MERGED'");
            }
        }
        if (mDeliveryGroupMatchingFilter != null) {
            b.putParcelable(KEY_DELIVERY_GROUP_MATCHING_FILTER, mDeliveryGroupMatchingFilter);
        }
        if (mDeferralPolicy != DEFERRAL_POLICY_DEFAULT) {
            b.putInt(KEY_DEFERRAL_POLICY, mDeferralPolicy);
        }
        return b;
    }

    /**
     * Returns a {@link BroadcastOptions} parsed from the given {@link Bundle},
     * typically generated from {@link #toBundle()}.
     */
    public static @NonNull BroadcastOptions fromBundle(@NonNull Bundle options) {
        return new BroadcastOptions(options);
    }

    /** {@hide} */
    public static @Nullable BroadcastOptions fromBundleNullable(@Nullable Bundle options) {
        return (options != null) ? new BroadcastOptions(options) : null;
    }
}
