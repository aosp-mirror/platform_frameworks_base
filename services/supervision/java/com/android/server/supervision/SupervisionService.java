/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.supervision;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.supervision.ISupervisionManager;
import android.app.supervision.SupervisionManagerInternal;
import android.app.supervision.flags.Flags;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.pm.UserManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/** Service for handling system supervision. */
public class SupervisionService extends ISupervisionManager.Stub {
    private static final String LOG_TAG = "SupervisionService";

    private final Context mContext;

    // TODO(b/362756788): Does this need to be a LockGuard lock?
    private final Object mLockDoNoUseDirectly = new Object();

    @GuardedBy("getLockObject()")
    private final SparseArray<SupervisionUserData> mUserData = new SparseArray<>();

    private final DevicePolicyManagerInternal mDpmInternal;
    private final PackageManager mPackageManager;
    private final UserManagerInternal mUserManagerInternal;

    public SupervisionService(Context context) {
        mContext = context.createAttributionContext(LOG_TAG);
        mDpmInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        mPackageManager = context.getPackageManager();
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mUserManagerInternal.addUserLifecycleListener(new UserLifecycleListener());
    }

    @Override
    public boolean isSupervisionEnabledForUser(@UserIdInt int userId) {
        synchronized (getLockObject()) {
            return getUserDataLocked(userId).supervisionEnabled;
        }
    }

    @Override
    public void onShellCommand(
            @Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver)
            throws RemoteException {
        new SupervisionServiceShellCommand(this)
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter printWriter, @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, printWriter)) return;

        try (var pw = new IndentingPrintWriter(printWriter, "  ")) {
            pw.println("SupervisionService state:");
            pw.increaseIndent();

            List<UserInfo> users = mUserManagerInternal.getUsers(false);
            synchronized (getLockObject()) {
                for (var user : users) {
                    getUserDataLocked(user.id).dump(pw);
                    pw.println();
                }
            }
        }
    }

    private Object getLockObject() {
        return mLockDoNoUseDirectly;
    }

    @NonNull
    @GuardedBy("getLockObject()")
    SupervisionUserData getUserDataLocked(@UserIdInt int userId) {
        SupervisionUserData data = mUserData.get(userId);
        if (data == null) {
            // TODO(b/362790738): Do not create user data for nonexistent users.
            data = new SupervisionUserData(userId);
            mUserData.append(userId, data);
        }
        return data;
    }

    void setSupervisionEnabledForUser(@UserIdInt int userId, boolean enabled) {
        synchronized (getLockObject()) {
            getUserDataLocked(userId).supervisionEnabled = enabled;
        }
    }

    /** Ensures that supervision is enabled when supervision app is the profile owner. */
    private void syncStateWithDevicePolicyManager(@UserIdInt int userId) {
        if (isProfileOwner(userId)) {
            setSupervisionEnabledForUser(userId, true);
        } else {
            // TODO(b/381428475): Avoid disabling supervision when the app is not the profile owner.
            // This might only be possible after introducing specific and public APIs to enable
            // supervision.
            setSupervisionEnabledForUser(userId, false);
        }
    }

    /** Returns whether the supervision app has profile owner status. */
    private boolean isProfileOwner(@UserIdInt int userId) {
        ComponentName profileOwner = mDpmInternal.getProfileOwnerAsUser(userId);
        return profileOwner != null && isSupervisionAppPackage(profileOwner.getPackageName());
    }

    /** Returns whether the given package name belongs to the supervision role holder. */
    private boolean isSupervisionAppPackage(String packageName) {
        return packageName.equals(
                mContext.getResources().getString(R.string.config_systemSupervision));
    }

    public static class Lifecycle extends SystemService {
        private final SupervisionService mSupervisionService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mSupervisionService = new SupervisionService(context);
        }

        @VisibleForTesting
        Lifecycle(Context context, SupervisionService supervisionService) {
            super(context);
            mSupervisionService = supervisionService;
        }

        @Override
        public void onStart() {
            publishLocalService(SupervisionManagerInternal.class, mSupervisionService.mInternal);
            publishBinderService(Context.SUPERVISION_SERVICE, mSupervisionService);
            if (Flags.enableSyncWithDpm()) {
                registerProfileOwnerListener();
            }
        }

        @VisibleForTesting
        void registerProfileOwnerListener() {
            IntentFilter poIntentFilter = new IntentFilter();
            poIntentFilter.addAction(DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED);
            poIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext()
                    .registerReceiverForAllUsers(
                            new ProfileOwnerBroadcastReceiver(),
                            poIntentFilter,
                            /* brodcastPermission= */ null,
                            /* scheduler= */ null);
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            if (Flags.enableSyncWithDpm() && !user.isPreCreated()) {
                mSupervisionService.syncStateWithDevicePolicyManager(user.getUserIdentifier());
            }
        }

        private final class ProfileOwnerBroadcastReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                mSupervisionService.syncStateWithDevicePolicyManager(getSendingUserId());
            }
        }
    }

    final SupervisionManagerInternal mInternal = new SupervisionManagerInternalImpl();

    private final class SupervisionManagerInternalImpl extends SupervisionManagerInternal {
        @Override
        public boolean isActiveSupervisionApp(int uid) {
            String[] packages = mPackageManager.getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (var packageName : packages) {
                if (SupervisionService.this.isSupervisionAppPackage(packageName)) {
                    int userId = UserHandle.getUserId(uid);
                    return SupervisionService.this.isSupervisionEnabledForUser(userId);
                }
            }
            return false;
        }

        @Override
        public boolean isSupervisionEnabledForUser(@UserIdInt int userId) {
            return SupervisionService.this.isSupervisionEnabledForUser(userId);
        }

        @Override
        public void setSupervisionEnabledForUser(@UserIdInt int userId, boolean enabled) {
            SupervisionService.this.setSupervisionEnabledForUser(userId, enabled);
        }

        @Override
        public boolean isSupervisionLockscreenEnabledForUser(@UserIdInt int userId) {
            synchronized (getLockObject()) {
                return getUserDataLocked(userId).supervisionLockScreenEnabled;
            }
        }

        @Override
        public void setSupervisionLockscreenEnabledForUser(
                @UserIdInt int userId, boolean enabled, @Nullable PersistableBundle options) {
            synchronized (getLockObject()) {
                SupervisionUserData data = getUserDataLocked(userId);
                data.supervisionLockScreenEnabled = enabled;
                data.supervisionLockScreenOptions = options;
            }
        }
    }

    private final class UserLifecycleListener implements UserManagerInternal.UserLifecycleListener {
        @Override
        public void onUserRemoved(UserInfo user) {
            synchronized (getLockObject()) {
                mUserData.remove(user.id);
            }
        }
    }
}
