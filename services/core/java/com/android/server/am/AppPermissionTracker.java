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
import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.app.AppOpsManager.OPSTR_CAMERA;
import static android.app.AppOpsManager.OPSTR_FINE_LOCATION;
import static android.app.AppOpsManager.OPSTR_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.opToPublicName;
import static android.app.AppOpsManager.strOpToOp;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppBatteryExemptionTracker.DEFAULT_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.STATE_TYPE_PERMISSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.OnPermissionsChangedListener;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.server.am.AppPermissionTracker.AppPermissionPolicy;
import com.android.server.am.AppRestrictionController.TrackerType;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The tracker for monitoring selected permission state of apps.
 */
final class AppPermissionTracker extends BaseAppStateTracker<AppPermissionPolicy>
        implements OnPermissionsChangedListener {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppPermissionTracker" : TAG_AM;

    static final boolean DEBUG_PERMISSION_TRACKER = false;

    private final MyHandler mHandler;

    /**
     * Keep a new instance of callback for each appop we're monitoring,
     * as the AppOpsService doesn't support monitoring multiple appops with single callback
     * instance (except the ALL_OPS case).
     */
    @GuardedBy("mAppOpsCallbacks")
    private final SparseArray<MyAppOpsCallback> mAppOpsCallbacks = new SparseArray<>();

    @GuardedBy("mLock")
    private SparseArray<ArraySet<UidGrantedPermissionState>> mUidGrantedPermissionsInMonitor =
            new SparseArray<>();

    private volatile boolean mLockedBootCompleted = false;

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
    @TrackerType int getType() {
        return AppRestrictionController.TRACKER_TYPE_PERMISSION;
    }

    @Override
    public void onPermissionsChanged(int uid) {
        mHandler.obtainMessage(MyHandler.MSG_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
    }

    private void handleAppOpsInit() {
        final ArrayList<Integer> ops = new ArrayList<>();
        final Pair[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        for (int i = 0; i < permissions.length; i++) {
            final Pair<String, Integer> pair = permissions[i];
            if (pair.second != OP_NONE) {
                ops.add(pair.second);
            }
        }
        startWatchingMode(ops.toArray(new Integer[ops.size()]));
    }

    private void handlePermissionsInit() {
        final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
        final PackageManagerInternal pmi = mInjector.getPackageManagerInternal();
        final PermissionManagerServiceInternal pm = mInjector.getPermissionManagerServiceInternal();
        final Pair[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        final SparseArray<ArraySet<UidGrantedPermissionState>> uidPerms =
                mUidGrantedPermissionsInMonitor;
        for (int userId : allUsers) {
            final List<ApplicationInfo> apps = pmi.getInstalledApplications(0, userId, SYSTEM_UID);
            if (apps == null) {
                continue;
            }
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0, size = apps.size(); i < size; i++) {
                final ApplicationInfo ai = apps.get(i);
                for (Pair<String, Integer> permission : permissions) {
                    final UidGrantedPermissionState state = new UidGrantedPermissionState(
                            ai.uid, permission.first, permission.second);
                    if (!state.isGranted()) {
                        // No need to track it.
                        continue;
                    }
                    synchronized (mLock) {
                        ArraySet<UidGrantedPermissionState> grantedPermissions =
                                uidPerms.get(ai.uid);
                        if (grantedPermissions == null) {
                            grantedPermissions = new ArraySet<UidGrantedPermissionState>();
                            uidPerms.put(ai.uid, grantedPermissions);
                            // This UID has at least one active permission-in-interest now,
                            // let the listeners know.
                            notifyListenersOnStateChange(ai.uid, DEFAULT_NAME, true, now,
                                    STATE_TYPE_PERMISSION);
                        }
                        grantedPermissions.add(state);
                    }
                }
            }
        }
    }

    private void handleAppOpsDestroy() {
        stopWatchingMode();
    }

    private void handlePermissionsDestroy() {
        synchronized (mLock) {
            final SparseArray<ArraySet<UidGrantedPermissionState>> uidPerms =
                    mUidGrantedPermissionsInMonitor;
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0, size = uidPerms.size(); i < size; i++) {
                final int uid = uidPerms.keyAt(i);
                final ArraySet<UidGrantedPermissionState> grantedPermissions = uidPerms.valueAt(i);
                if (grantedPermissions.size() > 0) {
                    notifyListenersOnStateChange(uid, DEFAULT_NAME, false, now,
                            STATE_TYPE_PERMISSION);
                }
            }
            uidPerms.clear();
        }
    }

    private void handleOpChanged(int op, int uid, String packageName) {
        if (DEBUG_PERMISSION_TRACKER) {
            final IAppOpsService appOpsService = mInjector.getIAppOpsService();
            try {
                final int mode = appOpsService.checkOperation(op, uid, packageName);
                Slog.i(TAG, "onOpChanged: " + opToPublicName(op)
                        + " " + UserHandle.formatUid(uid)
                        + " " + packageName + " " + mode);
            } catch (RemoteException e) {
                // Intra-process call, should never happen.
            }
        }
        final Pair[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        if (permissions != null && permissions.length > 0) {
            for (int i = 0; i < permissions.length; i++) {
                final Pair<String, Integer> pair = permissions[i];
                if (pair.second != op) {
                    continue;
                }
                final UidGrantedPermissionState state =
                        new UidGrantedPermissionState(uid, pair.first, op);
                synchronized (mLock) {
                    handlePermissionsChangedLocked(uid, new UidGrantedPermissionState[] {state});
                }
                break;
            }
        }
    }

    private void handlePermissionsChanged(int uid) {
        if (DEBUG_PERMISSION_TRACKER) {
            Slog.i(TAG, "handlePermissionsChanged " + UserHandle.formatUid(uid));
        }
        final Pair[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        if (permissions != null && permissions.length > 0) {
            final PermissionManagerServiceInternal pm =
                    mInjector.getPermissionManagerServiceInternal();
            final UidGrantedPermissionState[] states =
                    new UidGrantedPermissionState[permissions.length];
            for (int i = 0; i < permissions.length; i++) {
                final Pair<String, Integer> pair = permissions[i];
                states[i] = new UidGrantedPermissionState(uid, pair.first, pair.second);
                if (DEBUG_PERMISSION_TRACKER) {
                    Slog.i(TAG, states[i].toString());
                }
            }
            synchronized (mLock) {
                handlePermissionsChangedLocked(uid, states);
            }
        }
    }

    @GuardedBy("mLock")
    private void handlePermissionsChangedLocked(int uid, UidGrantedPermissionState[] states) {
        final int index = mUidGrantedPermissionsInMonitor.indexOfKey(uid);
        ArraySet<UidGrantedPermissionState> grantedPermissions = index >= 0
                ? mUidGrantedPermissionsInMonitor.valueAt(index) : null;
        final long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < states.length; i++) {
            final boolean granted = states[i].isGranted();
            boolean changed = false;
            if (granted) {
                if (grantedPermissions == null) {
                    grantedPermissions = new ArraySet<>();
                    mUidGrantedPermissionsInMonitor.put(uid, grantedPermissions);
                    changed = true;
                }
                grantedPermissions.add(states[i]);
            } else if (grantedPermissions != null && !grantedPermissions.isEmpty()) {
                if (grantedPermissions.remove(states[i]) && grantedPermissions.isEmpty()) {
                    mUidGrantedPermissionsInMonitor.removeAt(index);
                    changed = true;
                }
            }
            if (changed) {
                notifyListenersOnStateChange(uid, DEFAULT_NAME, granted, now,
                        STATE_TYPE_PERMISSION);
            }
        }
    }

    /**
     * Represents the grant state of a permission + appop of the given UID.
     */
    private class UidGrantedPermissionState {
        final int mUid;
        final @Nullable String mPermission;
        final int mAppOp;

        private boolean mPermissionGranted;
        private boolean mAppOpAllowed;

        UidGrantedPermissionState(int uid, @Nullable String permission, int appOp) {
            mUid = uid;
            mPermission = permission;
            mAppOp = appOp;
            updatePermissionState();
            updateAppOps();
        }

        void updatePermissionState() {
            if (TextUtils.isEmpty(mPermission)) {
                mPermissionGranted = true;
                return;
            }
            mPermissionGranted = mInjector.checkPermission(mPermission, Process.INVALID_PID, mUid)
                    == PERMISSION_GRANTED;
        }

        void updateAppOps() {
            if (mAppOp == OP_NONE) {
                mAppOpAllowed = true;
                return;
            }
            final String[] packages = mInjector.getPackageManager().getPackagesForUid(mUid);
            if (packages != null) {
                final IAppOpsService appOpsService = mInjector.getIAppOpsService();
                for (String pkg : packages) {
                    try {
                        final int mode = appOpsService.checkOperation(mAppOp, mUid, pkg);
                        if (mode == AppOpsManager.MODE_ALLOWED) {
                            mAppOpAllowed = true;
                            return;
                        }
                    } catch (RemoteException e) {
                        // Intra-process call, should never happen.
                    }
                }
            }
            mAppOpAllowed = false;
        }

        boolean isGranted() {
            return mPermissionGranted && mAppOpAllowed;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null || !(other instanceof UidGrantedPermissionState)) {
                return false;
            }
            final UidGrantedPermissionState otherState = (UidGrantedPermissionState) other;
            return mUid == otherState.mUid && mAppOp == otherState.mAppOp
                    && Objects.equals(mPermission, otherState.mPermission);
        }

        @Override
        public int hashCode() {
            return (Integer.hashCode(mUid) * 31 + Integer.hashCode(mAppOp)) * 31
                    + (mPermission == null ? 0 : mPermission.hashCode());
        }

        @Override
        public String toString() {
            String s = "UidGrantedPermissionState{"
                    + System.identityHashCode(this) + " "
                    + UserHandle.formatUid(mUid) + ": ";
            final boolean emptyPermissionName = TextUtils.isEmpty(mPermission);
            if (!emptyPermissionName) {
                s += mPermission + "=" + mPermissionGranted;
            }
            if (mAppOp != OP_NONE) {
                if (!emptyPermissionName) {
                    s += ",";
                }
                s += opToPublicName(mAppOp) + "=" + mAppOpAllowed;
            }
            s += "}";
            return s;
        }
    }

    private void startWatchingMode(@NonNull Integer[] ops) {
        synchronized (mAppOpsCallbacks) {
            stopWatchingMode();
            final IAppOpsService appOpsService = mInjector.getIAppOpsService();
            try {
                for (int op: ops) {
                    final MyAppOpsCallback cb = new MyAppOpsCallback();
                    mAppOpsCallbacks.put(op, cb);
                    appOpsService.startWatchingModeWithFlags(op, null,
                            AppOpsManager.WATCH_FOREGROUND_CHANGES, cb);
                }
            } catch (RemoteException e) {
                // Intra-process call, should never happen.
            }
        }
    }

    private void stopWatchingMode() {
        synchronized (mAppOpsCallbacks) {
            final IAppOpsService appOpsService = mInjector.getIAppOpsService();
            for (int i = mAppOpsCallbacks.size() - 1; i >= 0; i--) {
                try {
                    appOpsService.stopWatchingMode(mAppOpsCallbacks.valueAt(i));
                } catch (RemoteException e) {
                    // Intra-process call, should never happen.
                }
            }
            mAppOpsCallbacks.clear();
        }
    }

    private class MyAppOpsCallback extends IAppOpsCallback.Stub {
        @Override
        public void opChanged(int op, int uid, String packageName, String persistentDeviceId) {
            mHandler.obtainMessage(MyHandler.MSG_APPOPS_CHANGED, op, uid, packageName)
                    .sendToTarget();
        }
    }

    private static class MyHandler extends Handler {
        static final int MSG_PERMISSIONS_INIT = 0;
        static final int MSG_PERMISSIONS_DESTROY = 1;
        static final int MSG_PERMISSIONS_CHANGED = 2;
        static final int MSG_APPOPS_CHANGED = 3;

        private @NonNull AppPermissionTracker mTracker;

        MyHandler(@NonNull AppPermissionTracker tracker) {
            super(tracker.mBgHandler.getLooper());
            mTracker = tracker;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERMISSIONS_INIT:
                    mTracker.handleAppOpsInit();
                    mTracker.handlePermissionsInit();
                    break;
                case MSG_PERMISSIONS_DESTROY:
                    mTracker.handlePermissionsDestroy();
                    mTracker.handleAppOpsDestroy();
                    break;
                case MSG_PERMISSIONS_CHANGED:
                    mTracker.handlePermissionsChanged(msg.arg1);
                    break;
                case MSG_APPOPS_CHANGED:
                    mTracker.handleOpChanged(msg.arg1, msg.arg2, (String) msg.obj);
                    break;
            }
        }
    }

    private void onPermissionTrackerEnabled(boolean enabled) {
        if (!mLockedBootCompleted) {
            // Not ready, bail out.
            return;
        }
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
    void onLockedBootCompleted() {
        mLockedBootCompleted = true;
        onPermissionTrackerEnabled(mInjector.getPolicy().isEnabled());
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP PERMISSIONS TRACKER:");
        final Pair[] permissions = mInjector.getPolicy().getBgPermissionsInMonitor();
        final String prefixMore = "  " + prefix;
        final String prefixMoreMore = "  " + prefixMore;
        for (Pair<String, Integer> permission : permissions) {
            pw.print(prefixMore);
            final boolean emptyPermissionName = TextUtils.isEmpty(permission.first);
            if (!emptyPermissionName) {
                pw.print(permission.first);
            }
            if (permission.second != OP_NONE) {
                if (!emptyPermissionName) {
                    pw.print('+');
                }
                pw.print(opToPublicName(permission.second));
            }
            pw.println(':');
            synchronized (mLock) {
                final SparseArray<ArraySet<UidGrantedPermissionState>> uidPerms =
                        mUidGrantedPermissionsInMonitor;
                pw.print(prefixMoreMore);
                pw.print('[');
                boolean needDelimiter = false;
                for (int i = 0, size = uidPerms.size(); i < size; i++) {
                    final ArraySet<UidGrantedPermissionState> uidPerm = uidPerms.valueAt(i);
                    for (int j = uidPerm.size() - 1; j >= 0; j--) {
                        final UidGrantedPermissionState state = uidPerm.valueAt(j);
                        if (state.mAppOp == permission.second
                                && TextUtils.equals(state.mPermission, permission.first)) {
                            if (needDelimiter) {
                                pw.print(',');
                            }
                            needDelimiter = true;
                            pw.print(UserHandle.formatUid(state.mUid));
                            break;
                        }
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
         * Default value to {@link #mBgPermissionsInMonitor}, it comes in pair;
         * the first string strings in the pair is the permission name, and the second string
         * is the appops name, if they are associated.
         */
        static final String[] DEFAULT_BG_PERMISSIONS_IN_MONITOR = new String[] {
            ACCESS_FINE_LOCATION, OPSTR_FINE_LOCATION,
            CAMERA, OPSTR_CAMERA,
            RECORD_AUDIO, OPSTR_RECORD_AUDIO,
        };

        /**
         * @see #KEY_BG_PERMISSIONS_IN_MONITOR.
         */
        volatile @NonNull Pair[] mBgPermissionsInMonitor;

        AppPermissionPolicy(@NonNull Injector injector, @NonNull AppPermissionTracker tracker) {
            super(injector, tracker, KEY_BG_PERMISSION_MONITOR_ENABLED,
                    DEFAULT_BG_PERMISSION_MONITOR_ENABLED);
            mBgPermissionsInMonitor = parsePermissionConfig(DEFAULT_BG_PERMISSIONS_IN_MONITOR);
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

        Pair[] getBgPermissionsInMonitor() {
            return mBgPermissionsInMonitor;
        }

        private @NonNull Pair[] parsePermissionConfig(@NonNull String[] perms) {
            final Pair[] result = new Pair[perms.length / 2];
            for (int i = 0, j = 0; i < perms.length; i += 2, j++) {
                try {
                    result[j] = Pair.create(TextUtils.isEmpty(perms[i]) ? null : perms[i],
                            TextUtils.isEmpty(perms[i + 1]) ? OP_NONE : strOpToOp(perms[i + 1]));
                } catch (Exception e) {
                    // Ignore.
                }
            }
            return result;
        }

        private void updateBgPermissionsInMonitor() {
            final String config = DeviceConfig.getString(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_PERMISSIONS_IN_MONITOR,
                    null);
            final Pair[] newPermsInMonitor = parsePermissionConfig(
                    config != null ? config.split(",") : DEFAULT_BG_PERMISSIONS_IN_MONITOR);
            if (!Arrays.equals(mBgPermissionsInMonitor, newPermsInMonitor)) {
                mBgPermissionsInMonitor = newPermsInMonitor;
                if (isEnabled()) {
                    // Trigger a reload.
                    onTrackerEnabled(false);
                    onTrackerEnabled(true);
                }
            }
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
            pw.print('[');
            for (int i = 0; i < mBgPermissionsInMonitor.length; i++) {
                if (i > 0) {
                    pw.print(',');
                }
                final Pair<String, Integer> pair = mBgPermissionsInMonitor[i];
                if (pair.first != null) {
                    pw.print(pair.first);
                }
                pw.print(',');
                if (pair.second != OP_NONE) {
                    pw.print(opToPublicName(pair.second));
                }
            }
            pw.println(']');
        }
    }
}
