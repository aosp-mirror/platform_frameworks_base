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
import android.content.pm.PackageManagerInternal;
import android.media.session.MediaSessionManager;
import android.os.BatteryManagerInternal;
import android.os.BatteryStatsInternal;
import android.os.Handler;
import android.os.ServiceManager;
import android.permission.PermissionManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.app.IAppOpsService;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

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

    static final int STATE_TYPE_MEDIA_SESSION = 1;
    static final int STATE_TYPE_FGS_MEDIA_PLAYBACK = 1 << 1;
    static final int STATE_TYPE_FGS_LOCATION = 1 << 2;
    static final int STATE_TYPE_FGS_WITH_NOTIFICATION = 1 << 3;
    static final int STATE_TYPE_PERMISSION = 1 << 4;
    static final int STATE_TYPE_NUM = 5;

    static final int STATE_TYPE_INDEX_MEDIA_SESSION = 0;
    static final int STATE_TYPE_INDEX_FGS_MEDIA_PLAYBACK = 1;
    static final int STATE_TYPE_INDEX_FGS_LOCATION = 2;
    static final int STATE_TYPE_INDEX_FGS_WITH_NOTIFICATION = 3;
    static final int STATE_TYPE_INDEX_PERMISSION = 4;

    protected final AppRestrictionController mAppRestrictionController;
    protected final Injector<T> mInjector;
    protected final Context mContext;
    protected final Handler mBgHandler;
    protected final Object mLock;
    protected final ArrayList<StateListener> mStateListeners = new ArrayList<>();

    interface StateListener {
        void onStateChange(int uid, String packageName, boolean start, long now, int stateType);
    }

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

    static int stateTypeToIndex(int stateType) {
        return Integer.numberOfTrailingZeros(stateType);
    }

    static int stateIndexToType(int stateTypeIndex) {
        return 1 << stateTypeIndex;
    }

    static String stateTypesToString(int stateTypes) {
        final StringBuilder sb = new StringBuilder("[");
        boolean needDelimiter = false;
        for (int stateType = Integer.highestOneBit(stateTypes); stateType != 0;
                stateType = Integer.highestOneBit(stateTypes)) {
            if (needDelimiter) {
                sb.append('|');
            }
            needDelimiter = true;
            switch (stateType) {
                case STATE_TYPE_MEDIA_SESSION:
                    sb.append("MEDIA_SESSION");
                    break;
                case STATE_TYPE_FGS_MEDIA_PLAYBACK:
                    sb.append("FGS_MEDIA_PLAYBACK");
                    break;
                case STATE_TYPE_FGS_LOCATION:
                    sb.append("FGS_LOCATION");
                    break;
                case STATE_TYPE_FGS_WITH_NOTIFICATION:
                    sb.append("FGS_NOTIFICATION");
                    break;
                case STATE_TYPE_PERMISSION:
                    sb.append("PERMISSION");
                    break;
                default:
                    return "[UNKNOWN(" + Integer.toHexString(stateTypes) + ")]";
            }
            stateTypes &= ~stateType;
        }
        sb.append("]");
        return sb.toString();
    }

    void registerStateListener(@NonNull StateListener listener) {
        synchronized (mLock) {
            mStateListeners.add(listener);
        }
    }

    void notifyListenersOnStateChange(int uid, String packageName,
            boolean start, long now, int stateType) {
        synchronized (mLock) {
            for (int i = 0, size = mStateListeners.size(); i < size; i++) {
                mStateListeners.get(i).onStateChange(uid, packageName, start, now, stateType);
            }
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
     * Called when the system sends LOCKED_BOOT_COMPLETED.
     */
    void onLockedBootCompleted() {
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

    void dumpAsProto(ProtoOutputStream proto, int uid) {
    }

    static class Injector<T extends BaseAppStatePolicy> {
        T mAppStatePolicy;

        ActivityManagerInternal mActivityManagerInternal;
        BatteryManagerInternal mBatteryManagerInternal;
        BatteryStatsInternal mBatteryStatsInternal;
        DeviceIdleInternal mDeviceIdleInternal;
        UserManagerInternal mUserManagerInternal;
        PackageManager mPackageManager;
        PackageManagerInternal mPackageManagerInternal;
        PermissionManager mPermissionManager;
        PermissionManagerServiceInternal mPermissionManagerServiceInternal;
        AppOpsManager mAppOpsManager;
        MediaSessionManager mMediaSessionManager;
        RoleManager mRoleManager;
        NotificationManagerInternal mNotificationManagerInternal;
        IAppOpsService mIAppOpsService;

        void setPolicy(T policy) {
            mAppStatePolicy = policy;
        }

        void onSystemReady() {
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
            mBatteryStatsInternal = LocalServices.getService(BatteryStatsInternal.class);
            mDeviceIdleInternal = LocalServices.getService(DeviceIdleInternal.class);
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
            mPermissionManagerServiceInternal = LocalServices.getService(
                    PermissionManagerServiceInternal.class);
            final Context context = mAppStatePolicy.mTracker.mContext;
            mPackageManager = context.getPackageManager();
            mAppOpsManager = context.getSystemService(AppOpsManager.class);
            mMediaSessionManager = context.getSystemService(MediaSessionManager.class);
            mPermissionManager = context.getSystemService(PermissionManager.class);
            mRoleManager = context.getSystemService(RoleManager.class);
            mNotificationManagerInternal = LocalServices.getService(
                    NotificationManagerInternal.class);
            mIAppOpsService = IAppOpsService.Stub.asInterface(
                    ServiceManager.getService(Context.APP_OPS_SERVICE));

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

        PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        PermissionManager getPermissionManager() {
            return mPermissionManager;
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
            return mActivityManagerInternal.getServiceStartForegroundTimeout();
        }

        RoleManager getRoleManager() {
            return mRoleManager;
        }

        NotificationManagerInternal getNotificationManagerInternal() {
            return mNotificationManagerInternal;
        }

        IAppOpsService getIAppOpsService() {
            return mIAppOpsService;
        }
    }
}
