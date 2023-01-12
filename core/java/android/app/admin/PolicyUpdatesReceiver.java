/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import android.annotation.BroadcastBehavior;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Base class for implementing a policy update receiver. This class provides a convenience for
 * interpreting the raw intent actions ({@link #ACTION_DEVICE_POLICY_SET_RESULT} and
 * {@link #ACTION_DEVICE_POLICY_CHANGED}) that are sent by the system.
 *
 * <p>The callback methods happen on the main thread of the process. Thus, long-running
 * operations must be done on another thread.
 *
 * <p>When publishing your {@code PolicyUpdatesReceiver} subclass as a receiver, it must
 * require the {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission.
 *
 * <p>Admins can implement {@link DeviceAdminService} to ensure they receive all policy updates
 * (for policies they have set) via {@link #onPolicyChanged} by constantly being bound to by the
 * system. For more information see {@link DeviceAdminService}.
 */
public abstract class PolicyUpdatesReceiver extends BroadcastReceiver {
    private static String TAG = "PolicyUpdatesReceiver";

    /**
     * Result code passed in to {@link #onPolicySetResult} to indicate that the policy has been
     * set successfully.
     */
    public static final int POLICY_SET_RESULT_SUCCESS = 0;

    /**
     * Result code passed in to {@link #onPolicySetResult} to indicate that the policy has NOT been
     * set, a {@link PolicyUpdateReason} will be passed in to {@link #onPolicySetResult} to indicate
     * the reason.
     */
    public static final int POLICY_SET_RESULT_FAILURE = -1;

    /**
     * Result codes passed in to {@link #onPolicySetResult}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "POLICY_SET_RESULT_" }, value = {
            POLICY_SET_RESULT_SUCCESS,
            POLICY_SET_RESULT_FAILURE,
    })
    public @interface ResultCode {}

    /**
     * Action for a broadcast sent to admins to communicate back the result of setting a policy in
     * {@link DevicePolicyManager}.
     *
     * <p>Admins wishing to receive these updates (via {@link #onPolicySetResult}) should include
     * this action in the intent filter for their receiver in the manifest, the receiver
     * must be protected by {@link android.Manifest.permission#BIND_DEVICE_ADMIN} to ensure that
     * only the system can send updates.
     *
     * <p>Admins shouldn't implement {@link #onReceive} and should instead implement
     * {@link #onPolicySetResult}.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_DEVICE_POLICY_SET_RESULT =
            "android.app.admin.action.DEVICE_POLICY_SET_RESULT";

    /**
     * Action for a broadcast sent to admins to communicate back a change in a policy they have
     * previously set.
     *
     * <p>Admins wishing to receive these updates should include this action in the intent filter
     * for their receiver in the manifest, the receiver must be protected by
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} to ensure that only the system can
     * send updates.
     *
     * <p>Admins shouldn't implement {@link #onReceive} and should instead implement
     * {@link #onPolicyChanged}.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_DEVICE_POLICY_CHANGED =
            "android.app.admin.action.DEVICE_POLICY_CHANGED";

    /**
     * A string extra holding the package name the policy applies to, (see
     * {@link PolicyUpdatesReceiver#onPolicyChanged} and
     * {@link PolicyUpdatesReceiver#onPolicySetResult})
     */
    public static final String EXTRA_PACKAGE_NAME =
            "android.app.admin.extra.PACKAGE_NAME";

    /**
     * A string extra holding the permission name the policy applies to, (see
     * {@link PolicyUpdatesReceiver#onPolicyChanged} and
     * {@link PolicyUpdatesReceiver#onPolicySetResult})
     */
    public static final String EXTRA_PERMISSION_NAME =
            "android.app.admin.extra.PERMISSION_NAME";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_CHANGED_KEY =
            "android.app.admin.extra.POLICY_CHANGED_KEY";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_KEY = "android.app.admin.extra.POLICY_KEY";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_BUNDLE_KEY =
            "android.app.admin.extra.POLICY_BUNDLE_KEY";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_SET_RESULT_KEY =
            "android.app.admin.extra.POLICY_SET_RESULT_KEY";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_UPDATE_REASON_KEY =
            "android.app.admin.extra.POLICY_UPDATE_REASON_KEY";

    /**
     * @hide
     */
    public static final String EXTRA_POLICY_TARGET_USER_ID =
            "android.app.admin.extra.POLICY_TARGET_USER_ID";

    /**
     * Intercept standard policy update broadcasts. Implementations should not override this
     * method and rely on the callbacks instead.
     *
     * @hide
     */
    @Override
    public final void onReceive(Context context, Intent intent) {
        Objects.requireNonNull(intent.getAction());
        switch (intent.getAction()) {
            case ACTION_DEVICE_POLICY_SET_RESULT:
                Log.i(TAG, "Received ACTION_DEVICE_POLICY_SET_RESULT");
                onPolicySetResult(context, getPolicyKey(intent), getPolicyExtraBundle(intent),
                        getTargetUser(intent), getPolicyResult(intent), getFailureReason(intent));
                break;
            case ACTION_DEVICE_POLICY_CHANGED:
                Log.i(TAG, "Received ACTION_DEVICE_POLICY_CHANGED");
                onPolicyChanged(context, getPolicyKey(intent), getPolicyExtraBundle(intent),
                        getTargetUser(intent), getPolicyChangedReason(intent));
                break;
            default:
                Log.e(TAG, "Unknown action received: " + intent.getAction());
        }
    }

    /**
     * @hide
     */
    static String getPolicyKey(Intent intent) {
        if (!intent.hasExtra(EXTRA_POLICY_KEY)) {
            throw new IllegalArgumentException("PolicyKey has to be provided.");
        }
        return intent.getStringExtra(EXTRA_POLICY_KEY);
    }

    /**
     * @hide
     */
    @ResultCode
    static int getPolicyResult(Intent intent) {
        if (!intent.hasExtra(EXTRA_POLICY_SET_RESULT_KEY)) {
            throw new IllegalArgumentException("Result has to be provided.");
        }
        return intent.getIntExtra(EXTRA_POLICY_SET_RESULT_KEY, POLICY_SET_RESULT_FAILURE);
    }

    /**
     * @hide
     */
    @NonNull
    static Bundle getPolicyExtraBundle(Intent intent) {
        Bundle bundle = intent.getBundleExtra(EXTRA_POLICY_BUNDLE_KEY);
        return bundle == null ? new Bundle() : bundle;
    }

    /**
     * @hide
     */
    @Nullable
    static PolicyUpdateReason getFailureReason(Intent intent) {
        if (getPolicyResult(intent) != POLICY_SET_RESULT_FAILURE) {
            return null;
        }
        return getPolicyChangedReason(intent);
    }

    /**
     * @hide
     */
    @NonNull
    static PolicyUpdateReason getPolicyChangedReason(Intent intent) {
        int reasonCode = intent.getIntExtra(
                EXTRA_POLICY_UPDATE_REASON_KEY, PolicyUpdateReason.REASON_UNKNOWN);
        return new PolicyUpdateReason(reasonCode);
    }

    /**
     * @hide
     */
    @NonNull
    static TargetUser getTargetUser(Intent intent) {
        if (!intent.hasExtra(EXTRA_POLICY_TARGET_USER_ID)) {
            throw new IllegalArgumentException("TargetUser has to be provided.");
        }
        int targetUserId = intent.getIntExtra(
                EXTRA_POLICY_TARGET_USER_ID, TargetUser.LOCAL_USER_ID);
        return new TargetUser(targetUserId);
    }

    // TODO(b/260847505): Add javadocs to explain which DPM APIs are supported
    /**
     * Callback triggered after an admin has set a policy using one of the APIs in
     * {@link DevicePolicyManager} to notify the admin whether it has been successful or not.
     *
     * <p>Admins wishing to receive this callback should include
     * {@link PolicyUpdatesReceiver#ACTION_DEVICE_POLICY_SET_RESULT} in the intent filter for their
     * receiver in the manifest, the receiver must be protected by
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} to ensure that only the system can
     * send updates.
     *
     * @param context the running context as per {@link #onReceive}
     * @param policyKey Key to identify which policy this callback relates to.
     * @param additionalPolicyParams Bundle containing additional params that may be required to
     *                               identify some of the policy
     *                               (e.g. {@link PolicyUpdatesReceiver#EXTRA_PACKAGE_NAME}
     *                               and {@link PolicyUpdatesReceiver#EXTRA_PERMISSION_NAME}).
     *                               Each policy will document the required additional params if
     *                               needed.
     * @param targetUser The {@link TargetUser} which this policy relates to.
     * @param result Indicates whether the policy has been set successfully,
     *               (see {@link PolicyUpdatesReceiver#POLICY_SET_RESULT_SUCCESS} and
     *               {@link PolicyUpdatesReceiver#POLICY_SET_RESULT_FAILURE}).
     * @param reason Indicates the reason the policy failed to apply, {@code null} if the policy was
     *               applied successfully.
     */
    public void onPolicySetResult(
            @NonNull Context context,
            @NonNull String policyKey,
            @NonNull Bundle additionalPolicyParams,
            @NonNull TargetUser targetUser,
            @ResultCode int result,
            @Nullable PolicyUpdateReason reason) {}

    // TODO(b/260847505): Add javadocs to explain which DPM APIs are supported
    // TODO(b/261430877): Add javadocs to explain when will this get triggered
    /**
     * Callback triggered when a policy previously set by the admin has changed.
     *
     * <p>Admins wishing to receive this callback should include
     * {@link PolicyUpdatesReceiver#ACTION_DEVICE_POLICY_CHANGED} in the intent filter for their
     * receiver in the manifest, the receiver must be protected by
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} to ensure that only the system can
     * send updates.
     *
     * @param context the running context as per {@link #onReceive}
     * @param policyKey Key to identify which policy this callback relates to.
     * @param additionalPolicyParams Bundle containing additional params that may be required to
     *                               identify some of the policy
     *                               (e.g. {@link PolicyUpdatesReceiver#EXTRA_PACKAGE_NAME}
     *                               and {@link PolicyUpdatesReceiver#EXTRA_PERMISSION_NAME}).
     *                               Each policy will document the required additional params if
     *                               needed.
     * @param targetUser The {@link TargetUser} which this policy relates to.
     * @param reason Indicates the reason the policy value has changed.
     */
    public void onPolicyChanged(
            @NonNull Context context,
            @NonNull String policyKey,
            @NonNull Bundle additionalPolicyParams,
            @NonNull TargetUser targetUser,
            @NonNull PolicyUpdateReason reason) {}
}
