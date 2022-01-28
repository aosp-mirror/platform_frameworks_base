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

package com.android.server.am;

import static com.android.server.am.ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.MediaSessionManager;
import android.os.BatteryManagerInternal;
import android.os.BatteryStatsInternal;
import android.os.Handler;
import android.util.Slog;

import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;

/**
 * Base class to track certain state of the app, could be used to determine the restriction level.
 *
 * @param <T> A class derived from BaseAppStatePolicy.
 */
public abstract class BaseAppStateTracker<T extends BaseAppStatePolicy> {
    protected static final String TAG = TAG_WITH_CLASS_NAME ? "BaseAppStatePolicy" : TAG_AM;

    static final long ONE_MINUTE = 60 * 1_000L;
    static final long ONE_HOUR = 60 * ONE_MINUTE;
    static final long ONE_DAY = 24 * ONE_HOUR;

    protected final AppRestrictionController mAppRestrictionController;
    protected final Injector<T> mInjector;
    protected final Context mContext;
    protected final Handler mBgHandler;
    protected final Object mLock;

    BaseAppStateTracker(Context context, AppRestrictionController controller,
            @Nullable Constructor<? extends Injector<T>> injector, Object outerContext) {
        mContext = context;
        mAppRestrictionController = controller;
        mBgHandler = controller.getBackgroundHandler();
        mLock = controller.getLock();
        if (injector == null) {
            mInjector = new Injector<>();
        } else {
            Injector<T> localInjector = null;
            try {
                localInjector = injector.newInstance(outerContext);
            } catch (Exception e) {
                Slog.w(TAG, "Unable to instantiate " + injector, e);
            }
            mInjector = (localInjector == null) ? new Injector<>() : localInjector;
        }
    }

    /**
     * Return the policy holder of this tracker.
     */
    T getPolicy() {
        return mInjector.getPolicy();
    }

    /**
     * Called when the system is ready to rock.
     */
    void onSystemReady() {
        mInjector.onSystemReady();
    }

    /**
     * Called when a user with the given uid is added.
     */
    void onUidAdded(final int uid) {
    }

    /**
     * Called when a user with the given uid is removed.
     */
    void onUidRemoved(final int uid) {
    }

    /**
     * Called when a user with the given userId is added.
     */
    void onUserAdded(final @UserIdInt int userId) {
    }

    /**
     * Called when a user with the given userId is started.
     */
    void onUserStarted(final @UserIdInt int userId) {
    }

    /**
     * Called when a user with the given userId is stopped.
     */
    void onUserStopped(final @UserIdInt int userId) {
    }

    /**
     * Called when a user with the given userId is removed.
     */
    void onUserRemoved(final @UserIdInt int userId) {
    }

    /**
     * Called when a device config property in the activity manager namespace
     * has changed.
     */
    void onPropertiesChanged(@NonNull String name) {
        getPolicy().onPropertiesChanged(name);
    }

    /**
     * Called when an app has transitioned into an active state due to user interaction.
     */
    void onUserInteractionStarted(String packageName, int uid) {
    }

    /**
     * Called when the background restriction settings of the given app is changed.
     */
    void onBackgroundRestrictionChanged(int uid, String pkgName, boolean restricted) {
    }

    /**
     * Called when the process state of the given UID has been changed.
     *
     * <p>Note: as of now, for simplification, we're tracking the TOP state changes only.</p>
     */
    void onUidProcStateChanged(int uid, int procState) {
    }

    /**
     * Called when all the processes in the given UID have died.
     */
    void onUidGone(int uid) {
    }

    /**
     * Dump to the given printer writer.
     */
    void dump(PrintWriter pw, String prefix) {
        mInjector.getPolicy().dump(pw, "  " + prefix);
    }

    static class Injector<T extends BaseAppStatePolicy> {
        T mAppStatePolicy;

        ActivityManagerInternal mActivityManagerInternal;
        BatteryManagerInternal mBatteryManagerInternal;
        BatteryStatsInternal mBatteryStatsInternal;
        DeviceIdleInternal mDeviceIdleInternal;
        UserManagerInternal mUserManagerInternal;
        PackageManager mPackageManager;
        PermissionManagerServiceInternal mPermissionManagerServiceInternal;
        AppOpsManager mAppOpsManager;
        MediaSessionManager mMediaSessionManager;
        RoleManager mRoleManager;

        void setPolicy(T policy) {
            mAppStatePolicy = policy;
        }

        void onSystemReady() {
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
            mBatteryStatsInternal = LocalServices.getService(BatteryStatsInternal.class);
            mDeviceIdleInternal = LocalServices.getService(DeviceIdleInternal.class);
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mPermissionManagerServiceInternal = LocalServices.getService(
                    PermissionManagerServiceInternal.class);
            final Context context = mAppStatePolicy.mTracker.mContext;
            mPackageManager = context.getPackageManager();
            mAppOpsManager = context.getSystemService(AppOpsManager.class);
            mMediaSessionManager = context.getSystemService(MediaSessionManager.class);
            mRoleManager = context.getSystemService(RoleManager.class);

            getPolicy().onSystemReady();
        }

        ActivityManagerInternal getActivityManagerInternal() {
            return mActivityManagerInternal;
        }

        BatteryManagerInternal getBatteryManagerInternal() {
            return mBatteryManagerInternal;
        }

        BatteryStatsInternal getBatteryStatsInternal() {
            return mBatteryStatsInternal;
        }

        T getPolicy() {
            return mAppStatePolicy;
        }

        DeviceIdleInternal getDeviceIdleInternal() {
            return mDeviceIdleInternal;
        }

        UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternal;
        }

        /**
         * Equivalent to {@link java.lang.System#currentTimeMillis}.
         */
        long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        PackageManager getPackageManager() {
            return mPackageManager;
        }

        PermissionManagerServiceInternal getPermissionManagerServiceInternal() {
            return mPermissionManagerServiceInternal;
        }

        AppOpsManager getAppOpsManager() {
            return mAppOpsManager;
        }

        MediaSessionManager getMediaSessionManager() {
            return mMediaSessionManager;
        }

        long getServiceStartForegroundTimeout() {
            return SERVICE_START_FOREGROUND_TIMEOUT;
        }

        RoleManager getRoleManager() {
            return mRoleManager;
        }
    }
}
