/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Callback to intercept activity starts and possibly block/redirect them. The callback methods will
 * be called with the WindowManagerGlobalLock held.
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ActivityInterceptorCallback {
    /**
     * Called to allow intercepting activity launching based on the provided launch parameters and
     * intent resolution.
     *
     * <p>If the interceptor decides to change the {@link Intent} or return different {@link
     * ActivityOptions}, it should return a non-{@code null} {@link ActivityInterceptResult} which
     * may redirect to another activity or use new {@link ActivityOptions}. Otherwise, the
     * interceptor should return {@code null} to indicate that passed {@link Intent} should not be
     * changed.
     *
     * @param info the information about the {@link Intent} that is being intercepted to launch an
     *             {@link android.app.Activity}.
     * @return {@code null} if the interceptor decides not to change the existing intent, or a non-
     * {@code null} result which replaces the existing intent and activity options.
     */
    @Nullable
    ActivityInterceptResult onInterceptActivityLaunch(@NonNull ActivityInterceptorInfo info);

    /**
     * Called when an activity is successfully launched.
     *
     * <p>The intent included in the ActivityInterceptorInfo may have changed from the one sent in
     * {@link #onInterceptActivityLaunch(ActivityInterceptorInfo)}, due to the changes might applied
     * during internception.
     *
     * <p>There is no callback in case that the {@link android.app.Activity} is failed to launch,
     * and this is not necessary to be added for the known use-cases.
     *
     * @param taskInfo the information about the @{@link Task} holds the launched
     *                 {@link android.app.Activity}.
     * @param activityInfo the information about the launched {@link android.app.Activity}.
     * @param info the information about the {@link Intent} after calling {@link
     *             #onInterceptActivityLaunch(ActivityInterceptorInfo)}.
     */
    default void onActivityLaunched(@NonNull TaskInfo taskInfo, @NonNull ActivityInfo activityInfo,
            @NonNull ActivityInterceptorInfo info) {}

    /**
     * The unique id of each interceptor registered by a system service which determines the order
     * it will execute in.
     * @hide
     */
    @IntDef(suffix = { "_ORDERED_ID" }, value = {
            // Order Ids for system services
            SYSTEM_FIRST_ORDERED_ID,
            PERMISSION_POLICY_ORDERED_ID,
            VIRTUAL_DEVICE_SERVICE_ORDERED_ID,
            DREAM_MANAGER_ORDERED_ID,
            PRODUCT_ORDERED_ID,
            SYSTEM_LAST_ORDERED_ID, // Update this when adding new ids
            // Order Ids for mainline module services
            MAINLINE_FIRST_ORDERED_ID,
            MAINLINE_SDK_SANDBOX_ORDER_ID,
            MAINLINE_LAST_ORDERED_ID  // Update this when adding new mainline module ids
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface OrderedId {}

    /**
     * The first id, used by the framework to determine the valid range of ids.
     * @hide
     */
    int SYSTEM_FIRST_ORDERED_ID = 0;

    /**
     * The identifier for {@link com.android.server.policy.PermissionPolicyService} interceptor
     * @hide
     */
    int PERMISSION_POLICY_ORDERED_ID = 1;

    /**
     * The identifier for {@link com.android.server.companion.virtual.VirtualDeviceManagerService}
     * interceptor.
     * @hide
     */
    int VIRTUAL_DEVICE_SERVICE_ORDERED_ID = 3;

    /**
     * The identifier for {@link com.android.server.dreams.DreamManagerService} interceptor.
     * @hide
     */
    int DREAM_MANAGER_ORDERED_ID = 4;

    /**
     * The identifier for an interceptor which is specific to the type of android product like
     * automotive, wear, TV etc.
     * @hide
     */
    int PRODUCT_ORDERED_ID = 5;

    /**
     * The final id, used by the framework to determine the valid range of ids. Update this when
     * adding new ids.
     * @hide
     */
    int SYSTEM_LAST_ORDERED_ID = PRODUCT_ORDERED_ID;

    /**
     * The first mainline module id, used by the framework to determine the valid range of ids
     * could be used by mainline modules.
     * @hide
     */
    int MAINLINE_FIRST_ORDERED_ID = 1000;

    /**
     * The identifier for {@link com.android.server.sdksandbox.SdkSandboxManagerService.Lifecycle}
     * interceptor.
     */
    int MAINLINE_SDK_SANDBOX_ORDER_ID = 1001;

    /**
     * The final mainline module id, used by the framework to determine the valid range of ids
     * could be used by mainline modules. Update this when adding new ids for mainline modules.
     * @hide
     */
    int MAINLINE_LAST_ORDERED_ID = MAINLINE_SDK_SANDBOX_ORDER_ID;

    /**
     * Returns {@code true} if the id is in the range of valid system services including mainline
     * module services.
     * @hide
     */
    static boolean isValidOrderId(int id) {
        return isValidMainlineOrderId(id)
                || (id >= SYSTEM_FIRST_ORDERED_ID && id <= SYSTEM_LAST_ORDERED_ID);
    }

    /**
     * Returns {@code true} if the id is in the range of valid mainline module services.
     * @hide
     */
    static boolean isValidMainlineOrderId(int id) {
        return id >= MAINLINE_FIRST_ORDERED_ID && id <= MAINLINE_LAST_ORDERED_ID;
    }

    /**
     * Data class for storing the various arguments needed for activity interception.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    final class ActivityInterceptorInfo {
        private final int mCallingUid;
        private final int mCallingPid;
        private final int mRealCallingUid;
        private final int mRealCallingPid;
        private final int mUserId;
        private final Intent mIntent;
        @NonNull
        private final ResolveInfo mResolveInfo;
        @NonNull
        private final ActivityInfo mActivityInfo;
        @Nullable
        private final String mResolvedType;
        @Nullable
        private final String mCallingPackage;
        @Nullable
        private final String mCallingFeatureId;
        @Nullable
        private final ActivityOptions mCheckedOptions;
        @Nullable
        private final Runnable mClearOptionsAnimation;

        /**
         * @hide
         */
        public ActivityInterceptorInfo(Builder builder) {
            this.mCallingUid = builder.mCallingUid;
            this.mCallingPid = builder.mCallingPid;
            this.mRealCallingUid = builder.mRealCallingUid;
            this.mRealCallingPid = builder.mRealCallingPid;
            this.mUserId = builder.mUserId;
            this.mIntent = builder.mIntent;
            this.mResolveInfo = builder.mResolveInfo;
            this.mActivityInfo = builder.mActivityInfo;
            this.mResolvedType = builder.mResolvedType;
            this.mCallingPackage = builder.mCallingPackage;
            this.mCallingFeatureId = builder.mCallingFeatureId;
            this.mCheckedOptions = builder.mCheckedOptions;
            this.mClearOptionsAnimation = builder.mClearOptionsAnimation;
        }

        /**
         * Builder class to build instances of {@link ActivityInterceptorInfo}.
         */
        public static final class Builder {
            private final int mCallingUid;
            private final int mCallingPid;
            private final int mRealCallingUid;
            private final int mRealCallingPid;
            private final int mUserId;
            private final Intent mIntent;
            @NonNull
            private final ResolveInfo mResolveInfo;
            @NonNull
            private final ActivityInfo mActivityInfo;
            @Nullable
            private String mResolvedType;
            @Nullable
            private String mCallingPackage = null;
            @Nullable
            private String mCallingFeatureId = null;
            @Nullable
            private ActivityOptions mCheckedOptions = null;
            @Nullable
            private Runnable mClearOptionsAnimation = null;

            /**
             * Constructor of {@link ActivityInterceptorInfo.Builder}.
             */
            public Builder(int callingUid, int callingPid, int realCallingUid,
                    int realCallingPid, int userId, @NonNull Intent intent,
                    @NonNull ResolveInfo rInfo, @NonNull ActivityInfo aInfo) {
                this.mCallingUid = callingUid;
                this.mCallingPid = callingPid;
                this.mRealCallingUid = realCallingUid;
                this.mRealCallingPid = realCallingPid;
                this.mUserId = userId;
                this.mIntent = intent;
                this.mResolveInfo = rInfo;
                this.mActivityInfo = aInfo;
            }

            /**
             * Returns a new instance of {@link ActivityInterceptorInfo} based on the {@link
             * Builder} fields.
             *
             * @return a new instance of {@link ActivityInterceptorInfo}.
             */
            @NonNull
            public ActivityInterceptorInfo build() {
                return new ActivityInterceptorInfo(this);
            }

            /**
             * Sets the value for the resolved type.
             * @param resolvedType the resolved type.
             */
            @NonNull
            public Builder setResolvedType(@Nullable String resolvedType) {
                mResolvedType = resolvedType;
                return this;
            }

            /**
             * Sets the value for the calling package.
             * @param callingPackage the calling package.
             */
            @NonNull
            public Builder setCallingPackage(@Nullable String callingPackage) {
                mCallingPackage = callingPackage;
                return this;
            }

            /**
             * Sets the value for the calling feature id.
             * @param callingFeatureId the calling feature id.
             */
            @NonNull
            public Builder setCallingFeatureId(@Nullable String callingFeatureId) {
                mCallingFeatureId = callingFeatureId;
                return this;
            }

            /**
             * Sets the value for the {@link ActivityOptions}.
             * @param checkedOptions the {@link ActivityOptions}.
             */
            @NonNull
            public Builder setCheckedOptions(@Nullable ActivityOptions checkedOptions) {
                mCheckedOptions = checkedOptions;
                return this;
            }

            /**
             * Sets the value for the {@link Runnable} object to clear options Animation.
             * @param clearOptionsAnimationRunnable the calling package.
             */
            @NonNull
            public Builder setClearOptionsAnimationRunnable(@Nullable
                    Runnable clearOptionsAnimationRunnable) {
                mClearOptionsAnimation = clearOptionsAnimationRunnable;
                return this;
            }
        }

        /** Returns the calling uid. */
        public int getCallingUid() {
            return mCallingUid;
        }

        /** Returns the calling pid. */
        public int getCallingPid() {
            return mCallingPid;
        }

        /** Returns the real calling uid. */
        public int getRealCallingUid() {
            return mRealCallingUid;
        }

        /** Returns the real calling pid. */
        public int getRealCallingPid() {
            return mRealCallingPid;
        }

        /** Returns the user id. */
        public int getUserId() {
            return mUserId;
        }

        /** Returns the {@link Intent}. */
        @SuppressWarnings("IntentBuilderName")
        @NonNull
        public Intent getIntent() {
            return mIntent;
        }

        /** Returns the {@link ResolveInfo}. */
        @NonNull
        public ResolveInfo getResolveInfo() {
            return mResolveInfo;
        }

        /** Returns the {@link ActivityInfo}. */
        @NonNull
        public ActivityInfo getActivityInfo() {
            return mActivityInfo;
        }

        /** Returns the real resolved type. */
        @Nullable
        public String getResolvedType() {
            return mResolvedType;
        }

        /** Returns the calling package. */
        @Nullable
        public String getCallingPackage() {
            return mCallingPackage;
        }

        /** Returns the calling feature id. */
        @Nullable
        public String getCallingFeatureId() {
            return mCallingFeatureId;
        }

        /** Returns the {@link ActivityOptions}. */
        @Nullable
        public ActivityOptions getCheckedOptions() {
            return mCheckedOptions;
        }

        /**
         * Returns the {@link Runnable} object to clear options Animation. Note that the runnable
         * should not be executed inside a lock because the implementation of runnable holds window
         * manager's lock.
         */
        @Nullable
        public Runnable getClearOptionsAnimationRunnable() {
            return mClearOptionsAnimation;
        }
    }

    /**
     * Data class for storing the intercept result.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    final class ActivityInterceptResult {
        @NonNull
        private final Intent mIntent;

        @NonNull
        private final ActivityOptions mActivityOptions;

        private final boolean mActivityResolved;

        /**
         * This constructor should only be used if both {@link ActivityInfo} and {@link ResolveInfo}
         * did not get resolved while interception.
         * @hide
         */
        public ActivityInterceptResult(@NonNull Intent intent,
                @NonNull ActivityOptions activityOptions) {
            this(intent, activityOptions, false /* activityResolved */);
        }

        /**
         * Generates the result of intercepting launching the {@link android.app.Activity}
         *
         * <p>Interceptor should return non-{@code null} result when {@link
         * #onInterceptActivityLaunch(ActivityInterceptorInfo)} gets called as an indicator that
         * interception has happened.
         *
         * @param intent is the modified {@link Intent} after interception.
         * @param activityOptions holds the {@link ActivityOptions} after interception.
         * @param activityResolved should be {@code true} only if {@link ActivityInfo} or {@link
         *                         ResolveInfo} gets resolved, otherwise should be {@code false}.
         */
        public ActivityInterceptResult(@NonNull Intent intent,
                @NonNull ActivityOptions activityOptions, boolean activityResolved) {
            this.mIntent = intent;
            this.mActivityOptions = activityOptions;
            this.mActivityResolved = activityResolved;
        }

        /** Returns the intercepted {@link Intent} */
        @SuppressWarnings("IntentBuilderName")
        @NonNull
        public Intent getIntent() {
            return mIntent;
        }

        /** Returns the intercepted {@link ActivityOptions} */
        @NonNull
        public ActivityOptions getActivityOptions() {
            return mActivityOptions;
        }

        /**
         * Returns if the {@link ActivityInfo} or {@link ResolveInfo} gets resolved.
         */
        public boolean isActivityResolved() {
            return mActivityResolved;
        }
    }
}
