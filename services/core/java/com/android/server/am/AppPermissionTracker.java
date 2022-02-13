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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppBatteryExemptionTracker.DEFAULT_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.STATE_TYPE_PERMISSION;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.OnPermissionsChangedListener;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.am.AppPermissionTracker.AppPermissionPolicy;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

/**
 * The tracker for monitoring selected permission state of apps.
 */
final class AppPermissionTracker extends BaseAppStateTracker<AppPermissionPolicy>
        implements OnPermissionsChangedListener {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppPermissionTracker" : TAG_AM;

    static final boolean DEBUG_PERMISSION_TRACKER = false;

    private final MyHandler mHandler;

    @GuardedBy("mLock")
    private SparseArray<ArraySet<String>> mUidGrantedPermissionsInMonitor = new SparseArray<>();

    AppPermissionTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppPermissionTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppPermissionPolicy>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
        mHandler = new MyHandler(this);
        mInjector.setPolicy(new AppPermissionPolicy(mInjector, this));
    }

    @Override
    public void onPermissionsChanged(int uid) {
        mHandler.obtainMessage(MyHandler.MSG_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
    }

    private void handlePermissionsInit() {
        final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
        final PackageManagerInternal pmi = mInjector.getPackageManagerInternal();
        final PermissionManagerServiceInternal pm = mInjector.getPermissionManagerServiceInternal();
        final String[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        for (int userId : allUsers) {
            final List<ApplicationInfo> apps = pmi.getInstalledApplications(0, userId, SYSTEM_UID);
            if (apps == null) {
                continue;
            }
            synchronized (mLock) {
                final SparseArray<ArraySet<String>> uidPerms = mUidGrantedPermissionsInMonitor;
                final long now = SystemClock.elapsedRealtime();
                for (int i = 0, size = apps.size(); i < size; i++) {
                    final ApplicationInfo ai = apps.get(i);
                    for (String permission : permissions) {
                        if (pm.checkUidPermission(ai.uid, permission) != PERMISSION_GRANTED) {
                            continue;
                        }
                        ArraySet<String> grantedPermissions = uidPerms.get(ai.uid);
                        if (grantedPermissions == null) {
                            grantedPermissions = new ArraySet<String>();
                            uidPerms.put(ai.uid, grantedPermissions);
                        }
                        grantedPermissions.add(permission);
                        notifyListenersOnStateChange(ai.uid, DEFAULT_NAME, true, now,
                                STATE_TYPE_PERMISSION);
                    }
                }
            }
        }
    }

    private void handlePermissionsDestroy() {
        synchronized (mLock) {
            final SparseArray<ArraySet<String>> uidPerms = mUidGrantedPermissionsInMonitor;
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0, size = uidPerms.size(); i < size; i++) {
                final int uid = uidPerms.keyAt(i);
                final ArraySet<String> grantedPermissions = uidPerms.valueAt(i);
                for (int j = 0, numOfPerms = grantedPermissions.size(); j < numOfPerms; j++) {
                    notifyListenersOnStateChange(uid, DEFAULT_NAME, false, now,
                            STATE_TYPE_PERMISSION);
                }
            }
            uidPerms.clear();
        }
    }

    private void handlePermissionsChanged(int uid) {
        final String[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        if (permissions != null && permissions.length > 0) {
            synchronized (mLock) {
                handlePermissionsChangedLocked(uid);
            }
        }
    }

    @GuardedBy("mLock")
    private void handlePermissionsChangedLocked(int uid) {
        final PermissionManagerServiceInternal pm = mInjector.getPermissionManagerServiceInternal();
        final int index = mUidGrantedPermissionsInMonitor.indexOfKey(uid);
        ArraySet<String> grantedPermissions = index >= 0
                ? mUidGrantedPermissionsInMonitor.valueAt(index) : null;
        final String[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        final long now = SystemClock.elapsedRealtime();
        for (String permission: permissions) {
            boolean granted = pm.checkUidPermission(uid, permission) == PERMISSION_GRANTED;
            if (DEBUG_PERMISSION_TRACKER) {
                Slog.i(TAG, UserHandle.formatUid(uid) + " " + permission + "=" + granted);
            }
            boolean changed = false;
            if (granted) {
                if (grantedPermissions == null) {
                    grantedPermissions = new ArraySet<>();
                    mUidGrantedPermissionsInMonitor.put(uid, grantedPermissions);
                }
                changed = grantedPermissions.add(permission);
            } else if (grantedPermissions != null) {
                changed = grantedPermissions.remove(permission);
                if (grantedPermissions.isEmpty()) {
                    mUidGrantedPermissionsInMonitor.removeAt(index);
                }
            }
            if (changed) {
                notifyListenersOnStateChange(uid, DEFAULT_NAME, granted, now,
                        STATE_TYPE_PERMISSION);
            }
        }
    }

    private static class MyHandler extends Handler {
        static final int MSG_PERMISSIONS_INIT = 0;
        static final int MSG_PERMISSIONS_DESTROY = 1;
        static final int MSG_PERMISSIONS_CHANGED = 2;

        private @NonNull AppPermissionTracker mTracker;

        MyHandler(@NonNull AppPermissionTracker tracker) {
            super(tracker.mBgHandler.getLooper());
            mTracker = tracker;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERMISSIONS_INIT:
                    mTracker.handlePermissionsInit();
                    break;
                case MSG_PERMISSIONS_DESTROY:
                    mTracker.handlePermissionsDestroy();
                    break;
                case MSG_PERMISSIONS_CHANGED:
                    mTracker.handlePermissionsChanged(msg.arg1);
                    break;
            }
        }
    }

    private void onPermissionTrackerEnabled(boolean enabled) {
        final PermissionManager pm = mInjector.getPermissionManager();
        if (enabled) {
            pm.addOnPermissionsChangeListener(this);
            mHandler.obtainMessage(MyHandler.MSG_PERMISSIONS_INIT).sendToTarget();
        } else {
            pm.removeOnPermissionsChangeListener(this);
            mHandler.obtainMessage(MyHandler.MSG_PERMISSIONS_DESTROY).sendToTarget();
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP PERMISSIONS TRACKER:");
        final String[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        final String prefixMore = "  " + prefix;
        final String prefixMoreMore = "  " + prefixMore;
        for (String permission : permissions) {
            pw.print(prefixMore);
            pw.print(permission);
            pw.println(':');
            synchronized (mLock) {
                final SparseArray<ArraySet<String>> uidPerms = mUidGrantedPermissionsInMonitor;
                pw.print(prefixMoreMore);
                pw.print('[');
                boolean needDelimiter = false;
                for (int i = 0, size = uidPerms.size(); i < size; i++) {
                    if (uidPerms.valueAt(i).contains(permission)) {
                        if (needDelimiter) {
                            pw.print(',');
                        }
                        needDelimiter = true;
                        pw.print(UserHandle.formatUid(uidPerms.keyAt(i)));
                    }
                }
                pw.println(']');
            }
        }
        super.dump(pw, prefix);
    }

    static final class AppPermissionPolicy extends BaseAppStatePolicy<AppPermissionTracker> {
        /**
         * Whether or not we should enable the monitoring on app permissions.
         */
        static final String KEY_BG_PERMISSION_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "permission_monitor_enabled";

        /**
         * The names of the permissions we're monitoring its changes.
         */
        static final String KEY_BG_PERMISSIONS_IN_MONITOR =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "permission_in_monitor";

        /**
         * Default value to {@link #mTrackerEnabled}.
         */
        static final boolean DEFAULT_BG_PERMISSION_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mBgPermissionsInMonitor}.
         */
        static final String[] DEFAULT_BG_PERMISSIONS_IN_MONITOR = new String[] {
            ACCESS_FINE_LOCATION,
        };

        /**
         * @see #KEY_BG_PERMISSIONS_IN_MONITOR.
         */
        volatile String[] mBgPermissionsInMonitor = DEFAULT_BG_PERMISSIONS_IN_MONITOR;

        AppPermissionPolicy(@NonNull Injector injector, @NonNull AppPermissionTracker tracker) {
            super(injector, tracker, KEY_BG_PERMISSION_MONITOR_ENABLED,
                    DEFAULT_BG_PERMISSION_MONITOR_ENABLED);
        }

        @Override
        public void onSystemReady() {
            super.onSystemReady();
            updateBgPermissionsInMonitor();
        }

        @Override
        public void onPropertiesChanged(String name) {
            switch (name) {
                case KEY_BG_PERMISSIONS_IN_MONITOR:
                    updateBgPermissionsInMonitor();
                    break;
                default:
                    super.onPropertiesChanged(name);
                    break;
            }
        }

        String[] getBgPermissionsInMonitor() {
            return mBgPermissionsInMonitor;
        }

        private void updateBgPermissionsInMonitor() {
            final String config = DeviceConfig.getString(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_PERMISSIONS_IN_MONITOR,
                    null);
            mBgPermissionsInMonitor = config != null
                    ? config.split(",") : DEFAULT_BG_PERMISSIONS_IN_MONITOR;
        }

        @Override
        public void onTrackerEnabled(boolean enabled) {
            mTracker.onPermissionTrackerEnabled(enabled);
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP PERMISSION TRACKER POLICY SETTINGS:");
            prefix = "  " + prefix;
            super.dump(pw, prefix);
            pw.print(prefix);
            pw.print(KEY_BG_PERMISSIONS_IN_MONITOR);
            pw.print('=');
            pw.println(Arrays.toString(mBgPermissionsInMonitor));
        }
    }
}
