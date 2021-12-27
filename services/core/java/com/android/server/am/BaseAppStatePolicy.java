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

import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
import static android.os.PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;
import static android.os.PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.PowerExemptionManager.REASON_DEVICE_DEMO_MODE;
import static android.os.PowerExemptionManager.REASON_DEVICE_OWNER;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_PLATFORM_VPN;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_VPN;
import static android.os.PowerExemptionManager.REASON_OTHER;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
import static android.os.PowerExemptionManager.REASON_PROFILE_OWNER;
import static android.os.PowerExemptionManager.REASON_SYSTEM_UID;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.app.ActivityManager.RestrictionLevel;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;

import com.android.server.am.BaseAppStateTracker.Injector;

import java.io.PrintWriter;
import java.util.List;

/**
 * Base class to track the policy for certain state of the app.
 *
 * @param <T> A class derived from BaseAppStateTracker.
 */
public abstract class BaseAppStatePolicy<T extends BaseAppStateTracker> {

    protected final Injector<?> mInjector;
    protected final T mTracker;

    /**
     * The key to the device config, on whether or not we should enable the tracker.
     */
    protected final @NonNull String mKeyTrackerEnabled;

    /**
     * The default settings on whether or not we should enable the tracker.
     */
    protected final boolean mDefaultTrackerEnabled;

    /**
     * Whether or not we should enable the tracker.
     */
    volatile boolean mTrackerEnabled;

    BaseAppStatePolicy(@NonNull Injector<?> injector, @NonNull T tracker,
            @NonNull String keyTrackerEnabled, boolean defaultTrackerEnabled) {
        mInjector = injector;
        mTracker = tracker;
        mKeyTrackerEnabled = keyTrackerEnabled;
        mDefaultTrackerEnabled = defaultTrackerEnabled;
    }

    void updateTrackerEnabled() {
        final boolean enabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                mKeyTrackerEnabled, mDefaultTrackerEnabled);
        if (enabled != mTrackerEnabled) {
            mTrackerEnabled = enabled;
            onTrackerEnabled(enabled);
        }
    }

    /**
     * Called when the tracker enable flag flips.
     */
    public abstract void onTrackerEnabled(boolean enabled);

    /**
     * Called when a device config property in the activity manager namespace
     * has changed.
     */
    public void onPropertiesChanged(@NonNull String name) {
        if (mKeyTrackerEnabled.equals(name)) {
            updateTrackerEnabled();
        }
    }

    /**
     * @return The proposed background restriction policy for the given package/uid.
     */
    public @RestrictionLevel int getProposedRestrictionLevel(String packageName, int uid) {
        return RESTRICTION_LEVEL_UNKNOWN;
    }

    /**
     * Called when the system is ready to rock.
     */
    public void onSystemReady() {
        updateTrackerEnabled();
    }

    /**
     * @return If this tracker is enabled or not.
     */
    public boolean isEnabled() {
        return mTrackerEnabled;
    }

    /**
     * @return If the given UID should be exempted.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     */
    @CallSuper
    @ReasonCode
    public int shouldExemptUid(int uid) {
        if (UserHandle.isCore(uid)) {
            return REASON_SYSTEM_UID;
        }
        if (mTracker.mAppRestrictionController.isOnDeviceIdleAllowlist(uid, false)) {
            return REASON_ALLOWLISTED_PACKAGE;
        }
        final ActivityManagerInternal am = mInjector.getActivityManagerInternal();
        if (am.isAssociatedCompanionApp(UserHandle.getUserId(uid), uid)) {
            return REASON_COMPANION_DEVICE_MANAGER;
        }
        if (UserManager.isDeviceInDemoMode(mTracker.mContext)) {
            return REASON_DEVICE_DEMO_MODE;
        }
        if (am.isDeviceOwner(uid)) {
            return REASON_DEVICE_OWNER;
        }
        if (am.isProfileOwner(uid)) {
            return REASON_PROFILE_OWNER;
        }
        final int uidProcState = am.getUidProcessState(uid);
        if (uidProcState <= PROCESS_STATE_PERSISTENT) {
            return REASON_PROC_STATE_PERSISTENT;
        } else if (uidProcState <= PROCESS_STATE_PERSISTENT_UI) {
            return REASON_PROC_STATE_PERSISTENT_UI;
        }
        final String[] packages = mInjector.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            final AppOpsManager appOpsManager = mInjector.getAppOpsManager();
            for (String pkg : packages) {
                if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_VPN,
                        uid, pkg) == AppOpsManager.MODE_ALLOWED) {
                    return REASON_OP_ACTIVATE_VPN;
                } else if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN,
                        uid, pkg) == AppOpsManager.MODE_ALLOWED) {
                    return REASON_OP_ACTIVATE_PLATFORM_VPN;
                }
            }
            if (isRoleHeldByUid(RoleManager.ROLE_DIALER, uid, packages)
                    || isRoleHeldByUid(RoleManager.ROLE_EMERGENCY, uid, packages)) {
                return REASON_OTHER;
            }
        }
        return REASON_DENIED;
    }

    private boolean isRoleHeldByUid(@NonNull String role, int uid, String[] uidPackages) {
        final List<String> rolePkgs = mInjector.getRoleManager().getRoleHoldersAsUser(role,
                UserHandle.of(UserHandle.getUserId(uid)));
        if (rolePkgs == null) {
            return false;
        }
        for (String pkg: uidPackages) {
            if (rolePkgs.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dump to the given printer writer.
     */
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print(mKeyTrackerEnabled);
        pw.print('=');
        pw.println(mTrackerEnabled);
    }
}
