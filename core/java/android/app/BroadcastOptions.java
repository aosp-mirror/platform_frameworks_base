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
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#sendBroadcast(android.content.Intent)
 * Context.sendBroadcast(Intent)} and related methods.
 * {@hide}
 */
@SystemApi
public class BroadcastOptions extends ComponentOptions {
    private long mTemporaryAppAllowlistDuration;
    private @TempAllowListType int mTemporaryAppAllowlistType;
    private @ReasonCode int mTemporaryAppAllowlistReasonCode;
    private @Nullable String mTemporaryAppAllowlistReason;
    private int mMinManifestReceiverApiLevel = 0;
    private int mMaxManifestReceiverApiLevel = Build.VERSION_CODES.CUR_DEVELOPMENT;
    private boolean mDontSendToRestrictedApps = false;
    private boolean mAllowBackgroundActivityStarts;
    private String[] mRequireAllOfPermissions;
    private String[] mRequireNoneOfPermissions;
    private long mRequireCompatChangeId = CHANGE_INVALID;
    private boolean mRequireCompatChangeEnabled = true;
    private boolean mIsAlarmBroadcast = false;
    private boolean mIsDeferUntilActive = false;
    private long mIdForResponseEvent;
    private @Nullable IntentFilter mRemoveMatchingFilter;
    private @DeliveryGroupPolicy int mDeliveryGroupPolicy;
    private @Nullable String mDeliveryGroupMatchingKey;
    private @Nullable BundleMerger mDeliveryGroupExtrasMerger;
    private @Nullable IntentFilter mDeliveryGroupMatchingFilter;

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
     * Corresponds to {@link #setDontSendToRestrictedApps}.
     */
    private static final String KEY_DONT_SEND_TO_RESTRICTED_APPS =
            "android:broadcast.dontSendToRestrictedApps";

    /**
     * Corresponds to {@link #setBackgroundActivityStartsAllowed}.
     */
    private static final String KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS =
            "android:broadcast.allowBackgroundActivityStarts";

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
     * Corresponds to {@link #setRequireCompatChange(long, boolean)}
     */
    private static final String KEY_REQUIRE_COMPAT_CHANGE_ENABLED =
            "android:broadcast.requireCompatChangeEnabled";

    /**
     * Corresponds to {@link #setAlarmBroadcast(boolean)}
     * @hide
     */
    public static final String KEY_ALARM_BROADCAST =
            "android:broadcast.is_alarm";

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
     * Corresponds to {@link #setRemoveMatchingFilter}.
     */
    private static final String KEY_REMOVE_MATCHING_FILTER =
            "android:broadcast.removeMatchingFilter";

    /**
     * Corresponds to {@link #setDeferUntilActive(boolean)}.
     */
    private static final String KEY_DEFER_UNTIL_ACTIVE =
            "android:broadcast.deferuntilactive";

    /**
     * Corresponds to {@link #setDeliveryGroupPolicy(int)}.
     */
    private static final String KEY_DELIVERY_GROUP_POLICY =
            "android:broadcast.deliveryGroupPolicy";

    /**
     * Corresponds to {@link #setDeliveryGroupMatchingKey(String, String)}.
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
     * The list of delivery group policies which specify how multiple broadcasts belonging to
     * the same delivery group has to be handled.
     * @hide
     */
    @IntDef(flag = true, prefix = { "DELIVERY_GROUP_POLICY_" }, value = {
            DELIVERY_GROUP_POLICY_ALL,
            DELIVERY_GROUP_POLICY_MOST_RECENT,
            DELIVERY_GROUP_POLICY_MERGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeliveryGroupPolicy {}

    /**
     * Delivery group policy that indicates that all the broadcasts in the delivery group
     * need to be delivered as is.
     *
     * @hide
     */
    @SystemApi
    public static final int DELIVERY_GROUP_POLICY_ALL = 0;

    /**
     * Delivery group policy that indicates that only the most recent broadcast in the delivery
     * group need to be delivered and the rest can be dropped.
     *
     * @hide
     */
    @SystemApi
    public static final int DELIVERY_GROUP_POLICY_MOST_RECENT = 1;

    /**
     * Delivery group policy that indicates that the extras data from the broadcasts in the
     * delivery group need to be merged into a single broadcast and the rest can be dropped.
     *
     * @hide
     */
    public static final int DELIVERY_GROUP_POLICY_MERGED = 2;

    public static BroadcastOptions makeBasic() {
        BroadcastOptions opts = new BroadcastOptions();
        return opts;
    }

    /**
     * {@hide}
     * @deprecated use {@link #setDeliveryGroupMatchingFilter(IntentFilter)} instead.
     */
    @Deprecated
    public static @NonNull BroadcastOptions makeRemovingMatchingFilter(
            @NonNull IntentFilter removeMatchingFilter) {
        BroadcastOptions opts = new BroadcastOptions();
        opts.setRemoveMatchingFilter(removeMatchingFilter);
        return opts;
    }

    private BroadcastOptions() {
        super();
        resetTemporaryAppAllowlist();
    }

    /** @hide */
    @TestApi
    public BroadcastOptions(@NonNull Bundle opts) {
        super(opts);
        // Match the logic in toBundle().
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
        mDontSendToRestrictedApps = opts.getBoolean(KEY_DONT_SEND_TO_RESTRICTED_APPS, false);
        mAllowBackgroundActivityStarts = opts.getBoolean(KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                false);
        mRequireAllOfPermissions = opts.getStringArray(KEY_REQUIRE_ALL_OF_PERMISSIONS);
        mRequireNoneOfPermissions = opts.getStringArray(KEY_REQUIRE_NONE_OF_PERMISSIONS);
        mRequireCompatChangeId = opts.getLong(KEY_REQUIRE_COMPAT_CHANGE_ID, CHANGE_INVALID);
        mRequireCompatChangeEnabled = opts.getBoolean(KEY_REQUIRE_COMPAT_CHANGE_ENABLED, true);
        mIdForResponseEvent = opts.getLong(KEY_ID_FOR_RESPONSE_EVENT);
        mIsAlarmBroadcast = opts.getBoolean(KEY_ALARM_BROADCAST, false);
        mRemoveMatchingFilter = opts.getParcelable(KEY_REMOVE_MATCHING_FILTER,
                IntentFilter.class);
        mDeliveryGroupPolicy = opts.getInt(KEY_DELIVERY_GROUP_POLICY,
                DELIVERY_GROUP_POLICY_ALL);
        mDeliveryGroupMatchingKey = opts.getString(KEY_DELIVERY_GROUP_KEY);
        mDeliveryGroupExtrasMerger = opts.getParcelable(KEY_DELIVERY_GROUP_EXTRAS_MERGER,
                BundleMerger.class);
        mDeliveryGroupMatchingFilter = opts.getParcelable(KEY_DELIVERY_GROUP_MATCHING_FILTER,
                IntentFilter.class);
        mIsDeferUntilActive = opts.getBoolean(KEY_DEFER_UNTIL_ACTIVE, false);
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power allowlist when this broadcast is being delivered to it.
     * @param duration The duration in milliseconds; 0 means to not place on allowlist.
     * @deprecated use {@link #setTemporaryAppAllowlist(long, int, int,  String)} instead.
     */
    @Deprecated
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
     */
    public void setDontSendToRestrictedApps(boolean dontSendToRestrictedApps) {
        mDontSendToRestrictedApps = dontSendToRestrictedApps;
    }

    /**
     * @hide
     * @return #setDontSendToRestrictedApps
     */
    public boolean isDontSendToRestrictedApps() {
        return mDontSendToRestrictedApps;
    }

    /**
     * Sets the process will be able to start activities from background for the duration of
     * the broadcast dispatch. Default value is {@code false}
     */
    @RequiresPermission(android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND)
    public void setBackgroundActivityStartsAllowed(boolean allowBackgroundActivityStarts) {
        mAllowBackgroundActivityStarts = allowBackgroundActivityStarts;
    }

    /**
     * @hide
     * @return #setAllowBackgroundActivityStarts
     */
    public boolean allowsBackgroundActivityStarts() {
        return mAllowBackgroundActivityStarts;
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
     *
     * @param changeId the {@link ChangeId} to inspect
     * @param enabled the required enabled state of the inspected
     *            {@link ChangeId} for this broadcast to be delivered
     * @see CompatChanges#isChangeEnabled
     * @see #clearRequireCompatChange()
     */
    public void setRequireCompatChange(long changeId, boolean enabled) {
        mRequireCompatChangeId = changeId;
        mRequireCompatChangeEnabled = enabled;
    }

    /**
     * Clear any previously defined requirement for this broadcast requested via
     * {@link #setRequireCompatChange(long, boolean)}.
     */
    public void clearRequireCompatChange() {
        mRequireCompatChangeId = CHANGE_INVALID;
        mRequireCompatChangeEnabled = true;
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
        mIsAlarmBroadcast = senderIsAlarm;
    }

    /**
     * Did this broadcast originate from an alarm triggering?
     * @return true if this broadcast is an alarm message, false otherwise
     * @hide
     */
    public boolean isAlarmBroadcast() {
        return mIsAlarmBroadcast;
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
    public boolean testRequireCompatChange(int uid) {
        if (mRequireCompatChangeId != CHANGE_INVALID) {
            return CompatChanges.isChangeEnabled(mRequireCompatChangeId,
                    uid) == mRequireCompatChangeEnabled;
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
     * Sets whether the broadcast should not run until the process is in an active process state
     * (ie, a process exists for the app and the app is not in a cached process state).
     *
     * Whether an app's process state is considered active is independent of its standby bucket.
     *
     * A broadcast that is deferred until the process is active will not execute until the process
     * is brought to an active state by some other action, like a job, alarm, or service binding. As
     * a result, the broadcast may be delayed indefinitely. This deferral only applies to runtime
     * registered receivers of a broadcast. Any manifest receivers will run immediately, similar to
     * how a manifest receiver would start a new process in order to run a broadcast receiver.
     *
     * Ordered broadcasts, alarm broadcasts, interactive broadcasts, and manifest broadcasts are
     * never deferred.
     *
     * Unordered broadcasts and unordered broadcasts with completion callbacks may be
     * deferred. Completion callbacks for broadcasts deferred until active are
     * best-effort. Completion callbacks will run when all eligible processes have finished
     * executing the broadcast. Processes in inactive process states that defer the broadcast are
     * not considered eligible and may not execute the broadcast prior to the completion callback.
     *
     * @hide
     */
    @SystemApi
    public @NonNull BroadcastOptions setDeferUntilActive(boolean shouldDefer) {
        mIsDeferUntilActive = shouldDefer;
        return this;
    }

    /** @hide */
    @SystemApi
    public boolean isDeferUntilActive() {
        return mIsDeferUntilActive;
    }

    /**
     * When enqueuing this broadcast, remove all pending broadcasts previously
     * sent by this app which match the given filter.
     * <p>
     * For example, sending {@link Intent#ACTION_SCREEN_ON} would typically want
     * to remove any pending {@link Intent#ACTION_SCREEN_OFF} broadcasts.
     *
     * @hide
     * @deprecated use {@link #setDeliveryGroupMatchingFilter(IntentFilter)} instead.
     */
    @Deprecated
    public void setRemoveMatchingFilter(@NonNull IntentFilter removeMatchingFilter) {
        mRemoveMatchingFilter = Objects.requireNonNull(removeMatchingFilter);
    }

    /** @hide */
    public void clearRemoveMatchingFilter() {
        mRemoveMatchingFilter = null;
    }

    /** @hide */
    public @Nullable IntentFilter getRemoveMatchingFilter() {
        return mRemoveMatchingFilter;
    }

    /**
     * Set delivery group policy for this broadcast to specify how multiple broadcasts belonging to
     * the same delivery group has to be handled.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public BroadcastOptions setDeliveryGroupPolicy(@DeliveryGroupPolicy int policy) {
        mDeliveryGroupPolicy = policy;
        return this;
    }

    /**
     * Get the delivery group policy for this broadcast that specifies how multiple broadcasts
     * belonging to the same delivery group has to be handled.
     *
     * @hide
     */
    @SystemApi
    public @DeliveryGroupPolicy int getDeliveryGroupPolicy() {
        return mDeliveryGroupPolicy;
    }

    /**
     * Clears any previously set delivery group policies using
     * {@link #setDeliveryGroupMatchingKey(String, String)} and resets the delivery group policy to
     * the default value ({@link #DELIVERY_GROUP_POLICY_ALL}).
     *
     * @hide
     */
    @SystemApi
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
     * <p> If neither matching key using this API nor matching filter using
     * {@link #setDeliveryGroupMatchingFilter(IntentFilter)} is specified, then by default
     * {@link Intent#filterEquals(Intent)} will be used to identify the delivery group.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public BroadcastOptions setDeliveryGroupMatchingKey(@NonNull String namespace,
            @NonNull String key) {
        Preconditions.checkArgument(!namespace.contains(":"),
                "namespace should not contain ':'");
        Preconditions.checkArgument(!key.contains(":"),
                "key should not contain ':'");
        mDeliveryGroupMatchingKey = namespace + ":" + key;
        return this;
    }

    /**
     * Return the namespace and key that is used to identify the delivery group that this
     * broadcast belongs to.
     *
     * @return the delivery group namespace and key that was previously set using
     *         {@link #setDeliveryGroupMatchingKey(String, String)}, concatenated with a {@code :}.
     * @hide
     */
    @SystemApi
    @Nullable
    public String getDeliveryGroupMatchingKey() {
        return mDeliveryGroupMatchingKey;
    }

    /**
     * Clears the namespace and key that was previously set using
     * {@link #setDeliveryGroupMatchingKey(String, String)}.
     *
     * @hide
     */
    @SystemApi
    public void clearDeliveryGroupMatchingKey() {
        mDeliveryGroupMatchingKey = null;
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
    @SystemApi
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
    @SystemApi
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
    @SystemApi
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
     *
     * @hide
     */
    public void clearDeliveryGroupExtrasMerger() {
        mDeliveryGroupExtrasMerger = null;
    }

    /**
     * Sets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * This is typically used when executing {@link PendingIntent#send(Bundle)} or similar
     * methods. A privileged sender of a PendingIntent should only grant
     * MODE_BACKGROUND_ACTIVITY_START_ALLOWED if the PendingIntent is from a trusted source and/or
     * executed on behalf the user.
     *
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
    public Bundle toBundle() {
        Bundle b = super.toBundle();
        if (isTemporaryAppAllowlistSet()) {
            b.putLong(KEY_TEMPORARY_APP_ALLOWLIST_DURATION, mTemporaryAppAllowlistDuration);
            b.putInt(KEY_TEMPORARY_APP_ALLOWLIST_TYPE, mTemporaryAppAllowlistType);
            b.putInt(KEY_TEMPORARY_APP_ALLOWLIST_REASON_CODE, mTemporaryAppAllowlistReasonCode);
            b.putString(KEY_TEMPORARY_APP_ALLOWLIST_REASON, mTemporaryAppAllowlistReason);
        }
        if (mIsAlarmBroadcast) {
            b.putBoolean(KEY_ALARM_BROADCAST, true);
        }
        if (mMinManifestReceiverApiLevel != 0) {
            b.putInt(KEY_MIN_MANIFEST_RECEIVER_API_LEVEL, mMinManifestReceiverApiLevel);
        }
        if (mMaxManifestReceiverApiLevel != Build.VERSION_CODES.CUR_DEVELOPMENT) {
            b.putInt(KEY_MAX_MANIFEST_RECEIVER_API_LEVEL, mMaxManifestReceiverApiLevel);
        }
        if (mDontSendToRestrictedApps) {
            b.putBoolean(KEY_DONT_SEND_TO_RESTRICTED_APPS, true);
        }
        if (mAllowBackgroundActivityStarts) {
            b.putBoolean(KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS, true);
        }
        if (mRequireAllOfPermissions != null) {
            b.putStringArray(KEY_REQUIRE_ALL_OF_PERMISSIONS, mRequireAllOfPermissions);
        }
        if (mRequireNoneOfPermissions != null) {
            b.putStringArray(KEY_REQUIRE_NONE_OF_PERMISSIONS, mRequireNoneOfPermissions);
        }
        if (mRequireCompatChangeId != CHANGE_INVALID) {
            b.putLong(KEY_REQUIRE_COMPAT_CHANGE_ID, mRequireCompatChangeId);
            b.putBoolean(KEY_REQUIRE_COMPAT_CHANGE_ENABLED, mRequireCompatChangeEnabled);
        }
        if (mIdForResponseEvent != 0) {
            b.putLong(KEY_ID_FOR_RESPONSE_EVENT, mIdForResponseEvent);
        }
        if (mRemoveMatchingFilter != null) {
            b.putParcelable(KEY_REMOVE_MATCHING_FILTER, mRemoveMatchingFilter);
        }
        if (mDeliveryGroupPolicy != DELIVERY_GROUP_POLICY_ALL) {
            b.putInt(KEY_DELIVERY_GROUP_POLICY, mDeliveryGroupPolicy);
        }
        if (mDeliveryGroupMatchingKey != null) {
            b.putString(KEY_DELIVERY_GROUP_KEY, mDeliveryGroupMatchingKey);
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
        if (mIsDeferUntilActive) {
            b.putBoolean(KEY_DEFER_UNTIL_ACTIVE, mIsDeferUntilActive);
        }
        return b.isEmpty() ? null : b;
    }
}
