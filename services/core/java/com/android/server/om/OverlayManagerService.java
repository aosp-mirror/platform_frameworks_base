/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static android.app.AppGlobals.getPackageManager;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static android.os.Trace.TRACE_TAG_RRO;
import static android.os.Trace.traceBegin;
import static android.os.Trace.traceEnd;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.FgThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.Installer;
import com.android.server.pm.UserManagerService;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to manage asset overlays.
 *
 * <p>Asset overlays are additional resources that come from apks loaded
 * alongside the system and app apks. This service, the OverlayManagerService
 * (OMS), tracks which installed overlays to use and provides methods to change
 * this. Changes propagate to running applications as part of the Activity
 * lifecycle. This allows Activities to reread their resources at a well
 * defined point.</p>
 *
 * <p>By itself, the OMS will not change what overlays should be active.
 * Instead, it is only responsible for making sure that overlays *can* be used
 * from a technical and security point of view and to activate overlays in
 * response to external requests. The responsibility to toggle overlays on and
 * off lies within components that implement different use-cases such as themes
 * or dynamic customization.</p>
 *
 * <p>The OMS receives input from three sources:</p>
 *
 * <ul>
 *     <li>Callbacks from the SystemService class, specifically when the
 *     Android framework is booting and when the end user switches Android
 *     users.</li>
 *
 *     <li>Intents from the PackageManagerService (PMS). Overlays are regular
 *     apks, and whenever a package is installed (or removed, or has a
 *     component enabled or disabled), the PMS broadcasts this as an intent.
 *     When the OMS receives one of these intents, it updates its internal
 *     representation of the available overlays and, if there was a visible
 *     change, triggers an asset refresh in the affected apps.</li>
 *
 *     <li>External requests via the {@link IOverlayManager AIDL interface}.
 *     The interface allows clients to read information about the currently
 *     available overlays, change whether an overlay should be used or not, and
 *     change the relative order in which overlay packages are loaded.
 *     Read-access is granted if the request targets the same Android user as
 *     the caller runs as, or if the caller holds the
 *     INTERACT_ACROSS_USERS_FULL permission. Write-access is granted if the
 *     caller is granted read-access and additionaly holds the
 *     CHANGE_OVERLAY_PACKAGES permission.</li>
 * </ul>
 *
 * <p>The AIDL interface works with String package names, int user IDs, and
 * {@link OverlayInfo} objects. OverlayInfo instances are used to track a
 * specific pair of target and overlay packages and include information such as
 * the current state of the overlay. OverlayInfo objects are immutable.</p>
 *
 * <p>Internally, OverlayInfo objects are maintained by the
 * OverlayManagerSettings class. The OMS and its helper classes are notified of
 * changes to the settings by the OverlayManagerSettings.ChangeListener
 * callback interface. The file /data/system/overlays.xml is used to persist
 * the settings.</p>
 *
 * <p>Creation and deletion of idmap files are handled by the IdmapManager
 * class.</p>
 *
 * <p>The following is an overview of OMS and its related classes. Note how box
 * (2) does the heavy lifting, box (1) interacts with the Android framework,
 * and box (3) replaces box (1) during unit testing.</p>
 *
 * <pre>
 *         Android framework
 *            |         ^
 *      . . . | . . . . | . . . .
 *     .      |         |       .
 *     .    AIDL,   broadcasts  .
 *     .   intents      |       .
 *     .      |         |       . . . . . . . . . . . .
 *     .      v         |       .                     .
 *     .  OverlayManagerService . OverlayManagerTests .
 *     .                  \     .     /               .
 *     . (1)               \    .    /            (3) .
 *      . . . . . . . . . . \ . . . / . . . . . . . . .
 *     .                     \     /              .
 *     . (2)                  \   /               .
 *     .           OverlayManagerServiceImpl      .
 *     .                  |            |          .
 *     .                  |            |          .
 *     . OverlayManagerSettings     IdmapManager  .
 *     .                                          .
 *     . . . .  . . . . . . . . . . . . . . . . . .
 * </pre>
 *
 * <p>To test the OMS, execute:
 * <code>
 * atest FrameworksServicesTests:com.android.server.om  # internal tests
 * atest OverlayDeviceTests OverlayHostTests            # public API tests
 * </code>
 * </p>
 *
 * <p>Finally, here is a list of keywords used in the OMS context.</p>
 *
 * <ul>
 *     <li><b>target [package]</b> -- A regular apk that may have its resource
 *     pool extended  by zero or more overlay packages.</li>
 *
 *     <li><b>overlay [package]</b> -- An apk that provides additional
 *     resources to another apk.</li>
 *
 *     <li><b>OMS</b> -- The OverlayManagerService, i.e. this class.</li>
 *
 *     <li><b>approved</b> -- An overlay is approved if the OMS has verified
 *     that it can be used technically speaking (its target package is
 *     installed, at least one resource name in both packages match, the
 *     idmap was created, etc) and that it is secure to do so. External
 *     clients can not change this state.</li>
 *
 *     <li><b>not approved</b> -- The opposite of approved.</li>
 *
 *     <li><b>enabled</b> -- An overlay currently in active use and thus part
 *     of resource lookups. This requires the overlay to be approved. Only
 *     external clients can change this state.</li>
 *
 *     <li><b>disabled</b> -- The opposite of enabled.</li>
 *
 *     <li><b>idmap</b> -- A mapping of resource IDs between target and overlay
 *     used during resource lookup. Also the name of the binary that creates
 *     the mapping.</li>
 * </ul>
 */
public final class OverlayManagerService extends SystemService {
    static final String TAG = "OverlayManager";

    static final boolean DEBUG = false;

    /**
     * The system property that specifies the default overlays to apply.
     * This is a semicolon separated list of package names.
     *
     * Ex: com.android.vendor.overlay_one;com.android.vendor.overlay_two
     */
    private static final String DEFAULT_OVERLAYS_PROP = "ro.boot.vendor.overlay.theme";

    private final Object mLock = new Object();

    private final AtomicFile mSettingsFile;

    private final PackageManagerHelper mPackageManager;

    private final UserManagerService mUserManager;

    private final OverlayManagerSettings mSettings;

    private final OverlayManagerServiceImpl mImpl;

    private final AtomicBoolean mPersistSettingsScheduled = new AtomicBoolean(false);

    public OverlayManagerService(@NonNull final Context context,
            @NonNull final Installer installer) {
        super(context);
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#OverlayManagerService");
            mSettingsFile = new AtomicFile(
                    new File(Environment.getDataSystemDirectory(), "overlays.xml"), "overlays");
            mPackageManager = new PackageManagerHelper();
            mUserManager = UserManagerService.getInstance();
            IdmapManager im = new IdmapManager(installer, mPackageManager);
            mSettings = new OverlayManagerSettings();
            mImpl = new OverlayManagerServiceImpl(mPackageManager, im, mSettings,
                    getDefaultOverlayPackages(), new OverlayChangeListener());

            final IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(ACTION_PACKAGE_ADDED);
            packageFilter.addAction(ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL,
                    packageFilter, null, null);

            final IntentFilter userFilter = new IntentFilter();
            userFilter.addAction(ACTION_USER_ADDED);
            userFilter.addAction(ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(new UserReceiver(), UserHandle.ALL,
                    userFilter, null, null);

            restoreSettings();

            initIfNeeded();
            onSwitchUser(UserHandle.USER_SYSTEM);

            publishBinderService(Context.OVERLAY_SERVICE, mService);
            publishLocalService(OverlayManagerService.class, this);
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    @Override
    public void onStart() {
        // Intentionally left empty.
    }

    private void initIfNeeded() {
        final UserManager um = getContext().getSystemService(UserManager.class);
        final List<UserInfo> users = um.getUsers(true /*excludeDying*/);
        synchronized (mLock) {
            final int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                final UserInfo userInfo = users.get(i);
                if (!userInfo.supportsSwitchTo() && userInfo.id != UserHandle.USER_SYSTEM) {
                    // Initialize any users that can't be switched to, as there state would
                    // never be setup in onSwitchUser(). We will switch to the system user right
                    // after this, and its state will be setup there.
                    final List<String> targets = mImpl.updateOverlaysForUser(users.get(i).id);
                    updateOverlayPaths(users.get(i).id, targets);
                }
            }
        }
    }

    @Override
    public void onSwitchUser(final int newUserId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onSwitchUser " + newUserId);
            // ensure overlays in the settings are up-to-date, and propagate
            // any asset changes to the rest of the system
            synchronized (mLock) {
                final List<String> targets = mImpl.updateOverlaysForUser(newUserId);
                updateAssets(newUserId, targets);
            }
            schedulePersistSettings();
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private static String[] getDefaultOverlayPackages() {
        final String str = SystemProperties.get(DEFAULT_OVERLAYS_PROP);
        if (TextUtils.isEmpty(str)) {
            return EmptyArray.STRING;
        }

        final ArraySet<String> defaultPackages = new ArraySet<>();
        for (String packageName : str.split(";")) {
            if (!TextUtils.isEmpty(packageName)) {
                defaultPackages.add(packageName);
            }
        }
        return defaultPackages.toArray(new String[defaultPackages.size()]);
    }

    private final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                Slog.e(TAG, "Cannot handle package broadcast with null action");
                return;
            }
            final Uri data = intent.getData();
            if (data == null) {
                Slog.e(TAG, "Cannot handle package broadcast with null data");
                return;
            }
            final String packageName = data.getSchemeSpecificPart();

            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

            final int[] userIds;
            final int extraUid = intent.getIntExtra(Intent.EXTRA_UID, UserHandle.USER_NULL);
            if (extraUid == UserHandle.USER_NULL) {
                userIds = mUserManager.getUserIds();
            } else {
                userIds = new int[] { UserHandle.getUserId(extraUid) };
            }

            switch (action) {
                case ACTION_PACKAGE_ADDED:
                    if (replacing) {
                        onPackageReplaced(packageName, userIds);
                    } else {
                        onPackageAdded(packageName, userIds);
                    }
                    break;
                case ACTION_PACKAGE_CHANGED:
                    onPackageChanged(packageName, userIds);
                    break;
                case ACTION_PACKAGE_REMOVED:
                    if (replacing) {
                        onPackageReplacing(packageName, userIds);
                    } else {
                        onPackageRemoved(packageName, userIds);
                    }
                    break;
                default:
                    // do nothing
                    break;
            }
        }

        private void onPackageAdded(@NonNull final String packageName,
                @NonNull final int[] userIds) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#onPackageAdded " + packageName);
                for (final int userId : userIds) {
                    synchronized (mLock) {
                        final PackageInfo pi = mPackageManager.getPackageInfo(packageName, userId,
                                false);
                        if (pi != null && !pi.applicationInfo.isInstantApp()) {
                            mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                mImpl.onOverlayPackageAdded(packageName, userId);
                            } else {
                                mImpl.onTargetPackageAdded(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        private void onPackageChanged(@NonNull final String packageName,
                @NonNull final int[] userIds) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#onPackageChanged " + packageName);
                for (int userId : userIds) {
                    synchronized (mLock) {
                        final PackageInfo pi = mPackageManager.getPackageInfo(packageName, userId,
                                false);
                        if (pi != null && pi.applicationInfo.isInstantApp()) {
                            mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                mImpl.onOverlayPackageChanged(packageName, userId);
                            }  else {
                                mImpl.onTargetPackageChanged(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        private void onPackageReplacing(@NonNull final String packageName,
                @NonNull final int[] userIds) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#onPackageReplacing " + packageName);
                for (int userId : userIds) {
                    synchronized (mLock) {
                        mPackageManager.forgetPackageInfo(packageName, userId);
                        final OverlayInfo oi = mImpl.getOverlayInfo(packageName, userId);
                        if (oi != null) {
                            mImpl.onOverlayPackageReplacing(packageName, userId);
                        }
                    }
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        private void onPackageReplaced(@NonNull final String packageName,
                @NonNull final int[] userIds) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#onPackageReplaced " + packageName);
                for (int userId : userIds) {
                    synchronized (mLock) {
                        final PackageInfo pi = mPackageManager.getPackageInfo(packageName, userId,
                                false);
                        if (pi != null && !pi.applicationInfo.isInstantApp()) {
                            mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                mImpl.onOverlayPackageReplaced(packageName, userId);
                            } else {
                                mImpl.onTargetPackageReplaced(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        private void onPackageRemoved(@NonNull final String packageName,
                @NonNull final int[] userIds) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#onPackageRemoved " + packageName);
                for (int userId : userIds) {
                    synchronized (mLock) {
                        mPackageManager.forgetPackageInfo(packageName, userId);
                        final OverlayInfo oi = mImpl.getOverlayInfo(packageName, userId);
                        if (oi != null) {
                            mImpl.onOverlayPackageRemoved(packageName, userId);
                        } else {
                            mImpl.onTargetPackageRemoved(packageName, userId);
                        }
                    }
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }
    }

    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            switch (intent.getAction()) {
                case ACTION_USER_ADDED:
                    if (userId != UserHandle.USER_NULL) {
                        try {
                            traceBegin(TRACE_TAG_RRO, "OMS ACTION_USER_ADDED");
                            final ArrayList<String> targets;
                            synchronized (mLock) {
                                targets = mImpl.updateOverlaysForUser(userId);
                            }
                            updateOverlayPaths(userId, targets);
                        } finally {
                            traceEnd(TRACE_TAG_RRO);
                        }
                    }
                    break;

                case ACTION_USER_REMOVED:
                    if (userId != UserHandle.USER_NULL) {
                        try {
                            traceBegin(TRACE_TAG_RRO, "OMS ACTION_USER_REMOVED");
                            synchronized (mLock) {
                                mImpl.onUserRemoved(userId);
                                mPackageManager.forgetAllPackageInfos(userId);
                            }
                        } finally {
                            traceEnd(TRACE_TAG_RRO);
                        }
                    }
                    break;
                default:
                    // do nothing
                    break;
            }
        }
    }

    private final IBinder mService = new IOverlayManager.Stub() {
        @Override
        public Map<String, List<OverlayInfo>> getAllOverlays(int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getAllOverlays " + userId);
                userId = handleIncomingUser(userId, "getAllOverlays");

                synchronized (mLock) {
                    return mImpl.getOverlaysForUser(userId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public List<OverlayInfo> getOverlayInfosForTarget(@Nullable final String targetPackageName,
                int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getOverlayInfosForTarget " + targetPackageName);
                userId = handleIncomingUser(userId, "getOverlayInfosForTarget");
                if (targetPackageName == null) {
                    return Collections.emptyList();
                }

                synchronized (mLock) {
                    return mImpl.getOverlayInfosForTarget(targetPackageName, userId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public OverlayInfo getOverlayInfo(@Nullable final String packageName,
                int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getOverlayInfo " + packageName);
                userId = handleIncomingUser(userId, "getOverlayInfo");
                if (packageName == null) {
                    return null;
                }

                synchronized (mLock) {
                    return mImpl.getOverlayInfo(packageName, userId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setEnabled(@Nullable final String packageName, final boolean enable,
                int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabled " + packageName + " " + enable);
                enforceChangeOverlayPackagesPermission("setEnabled");
                userId = handleIncomingUser(userId, "setEnabled");
                if (packageName == null) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setEnabled(packageName, enable, userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setEnabledExclusive(@Nullable final String packageName, final boolean enable,
                int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabledExclusive " + packageName + " " + enable);
                enforceChangeOverlayPackagesPermission("setEnabledExclusive");
                userId = handleIncomingUser(userId, "setEnabledExclusive");
                if (packageName == null || !enable) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setEnabledExclusive(packageName, false /* withinCategory */,
                                userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setEnabledExclusiveInCategory(@Nullable String packageName, int userId)
                throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabledExclusiveInCategory " + packageName);
                enforceChangeOverlayPackagesPermission("setEnabledExclusiveInCategory");
                userId = handleIncomingUser(userId, "setEnabledExclusiveInCategory");
                if (packageName == null) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setEnabledExclusive(packageName, true /* withinCategory */,
                                userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setPriority(@Nullable final String packageName,
                @Nullable final String parentPackageName, int userId) throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setPriority " + packageName + " "
                        + parentPackageName);
                enforceChangeOverlayPackagesPermission("setPriority");
                userId = handleIncomingUser(userId, "setPriority");
                if (packageName == null || parentPackageName == null) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setPriority(packageName, parentPackageName, userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setHighestPriority(@Nullable final String packageName, int userId)
                throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setHighestPriority " + packageName);
                enforceChangeOverlayPackagesPermission("setHighestPriority");
                userId = handleIncomingUser(userId, "setHighestPriority");
                if (packageName == null) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setHighestPriority(packageName, userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setLowestPriority(@Nullable final String packageName, int userId)
                throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setLowestPriority " + packageName);
                enforceChangeOverlayPackagesPermission("setLowestPriority");
                userId = handleIncomingUser(userId, "setLowestPriority");
                if (packageName == null) {
                    return false;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.setLowestPriority(packageName, userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public String[] getDefaultOverlayPackages() throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getDefaultOverlayPackages");
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_THEME_OVERLAY, null);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        return mImpl.getDefaultOverlayPackages();
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public void onShellCommand(@NonNull final FileDescriptor in,
                @NonNull final FileDescriptor out, @NonNull final FileDescriptor err,
                @NonNull final String[] args, @NonNull final ShellCallback callback,
                @NonNull final ResultReceiver resultReceiver) {
            (new OverlayManagerShellCommand(getContext(), this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final DumpState dumpState = new DumpState();
            dumpState.setUserId(UserHandle.USER_ALL);

            int opti = 0;
            while (opti < args.length) {
                final String opt = args[opti];
                if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                    break;
                }
                opti++;

                if ("-h".equals(opt)) {
                    pw.println("dump [-h] [--verbose] [--user USER_ID] [[FIELD] PACKAGE]");
                    pw.println("  Print debugging information about the overlay manager.");
                    pw.println("  With optional parameter PACKAGE, limit output to the specified");
                    pw.println("  package. With optional parameter FIELD, limit output to");
                    pw.println("  the value of that SettingsItem field. Field names are");
                    pw.println("  case insensitive and out.println the m prefix can be omitted,");
                    pw.println("  so the following are equivalent: mState, mstate, State, state.");
                    return;
                } else if ("--user".equals(opt)) {
                    if (opti >= args.length) {
                        pw.println("Error: user missing argument");
                        return;
                    }
                    try {
                        dumpState.setUserId(Integer.parseInt(args[opti]));
                        opti++;
                    } catch (NumberFormatException e) {
                        pw.println("Error: user argument is not a number: " + args[opti]);
                        return;
                    }
                } else if ("--verbose".equals(opt)) {
                    dumpState.setVerbose(true);
                } else {
                    pw.println("Unknown argument: " + opt + "; use -h for help");
                }
            }
            if (opti < args.length) {
                final String arg = args[opti];
                opti++;
                switch (arg) {
                    case "packagename":
                    case "userid":
                    case "targetpackagename":
                    case "targetoverlayablename":
                    case "basecodepath":
                    case "state":
                    case "isenabled":
                    case "isstatic":
                    case "priority":
                    case "category":
                        dumpState.setField(arg);
                        break;
                    default:
                        dumpState.setPackageName(arg);
                        break;
                }
            }
            if (dumpState.getPackageName() == null && opti < args.length) {
                dumpState.setPackageName(args[opti]);
                opti++;
            }

            enforceDumpPermission("dump");
            synchronized (mLock) {
                mImpl.dump(pw, dumpState);
                if (dumpState.getPackageName() == null) {
                    mPackageManager.dump(pw, dumpState);
                }
            }
        }

        /**
         * Ensure that the caller has permission to interact with the given userId.
         * If the calling user is not the same as the provided user, the caller needs
         * to hold the INTERACT_ACROSS_USERS_FULL permission (or be system uid or
         * root).
         *
         * @param userId the user to interact with
         * @param message message for any SecurityException
         */
        private int handleIncomingUser(final int userId, @NonNull final String message) {
            return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, message, null);
        }

        /**
         * Enforce that the caller holds the CHANGE_OVERLAY_PACKAGES permission (or is
         * system or root).
         *
         * @param message used as message if SecurityException is thrown
         * @throws SecurityException if the permission check fails
         */
        private void enforceChangeOverlayPackagesPermission(@NonNull final String message) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, message);
        }

        /**
         * Enforce that the caller holds the DUMP permission (or is system or root).
         *
         * @param message used as message if SecurityException is thrown
         * @throws SecurityException if the permission check fails
         */
        private void enforceDumpPermission(@NonNull final String message) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, message);
        }
    };

    private final class OverlayChangeListener
            implements OverlayManagerServiceImpl.OverlayChangeListener {
        @Override
        public void onOverlaysChanged(@NonNull final String targetPackageName, final int userId) {
            schedulePersistSettings();
            FgThread.getHandler().post(() -> {
                updateAssets(userId, targetPackageName);

                final Intent intent = new Intent(Intent.ACTION_OVERLAY_CHANGED,
                        Uri.fromParts("package", targetPackageName, null));
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

                if (DEBUG) {
                    Slog.d(TAG, "send broadcast " + intent);
                }

                try {
                    ActivityManager.getService().broadcastIntent(null, intent, null, null, 0,
                            null, null, null, android.app.AppOpsManager.OP_NONE, null, false, false,
                            userId);
                } catch (RemoteException e) {
                    // Intentionally left empty.
                }
            });
        }
    }

    /**
     * Updates the target packages' set of enabled overlays in PackageManager.
     */
    private void updateOverlayPaths(int userId, List<String> targetPackageNames) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#updateOverlayPaths " + targetPackageNames);
            if (DEBUG) {
                Slog.d(TAG, "Updating overlay assets");
            }
            final PackageManagerInternal pm =
                    LocalServices.getService(PackageManagerInternal.class);
            final boolean updateFrameworkRes = targetPackageNames.contains("android");
            if (updateFrameworkRes) {
                targetPackageNames = pm.getTargetPackageNames(userId);
            }

            final Map<String, List<String>> pendingChanges =
                    new ArrayMap<>(targetPackageNames.size());
            synchronized (mLock) {
                final List<String> frameworkOverlays =
                        mImpl.getEnabledOverlayPackageNames("android", userId);
                final int n = targetPackageNames.size();
                for (int i = 0; i < n; i++) {
                    final String targetPackageName = targetPackageNames.get(i);
                    List<String> list = new ArrayList<>();
                    if (!"android".equals(targetPackageName)) {
                        list.addAll(frameworkOverlays);
                    }
                    list.addAll(mImpl.getEnabledOverlayPackageNames(targetPackageName, userId));
                    pendingChanges.put(targetPackageName, list);
                }
            }

            final int n = targetPackageNames.size();
            for (int i = 0; i < n; i++) {
                final String targetPackageName = targetPackageNames.get(i);
                if (DEBUG) {
                    Slog.d(TAG, "-> Updating overlay: target=" + targetPackageName + " overlays=["
                            + TextUtils.join(",", pendingChanges.get(targetPackageName))
                            + "] userId=" + userId);
                }

                if (!pm.setEnabledOverlayPackages(
                        userId, targetPackageName, pendingChanges.get(targetPackageName))) {
                    Slog.e(TAG, String.format("Failed to change enabled overlays for %s user %d",
                            targetPackageName, userId));
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void updateAssets(final int userId, final String targetPackageName) {
        updateAssets(userId, Collections.singletonList(targetPackageName));
    }

    private void updateAssets(final int userId, List<String> targetPackageNames) {
        updateOverlayPaths(userId, targetPackageNames);
        final IActivityManager am = ActivityManager.getService();
        try {
            am.scheduleApplicationInfoChanged(targetPackageNames, userId);
        } catch (RemoteException e) {
            // Intentionally left empty.
        }
    }

    private void schedulePersistSettings() {
        if (mPersistSettingsScheduled.getAndSet(true)) {
            return;
        }
        IoThread.getHandler().post(() -> {
            mPersistSettingsScheduled.set(false);
            if (DEBUG) {
                Slog.d(TAG, "Writing overlay settings");
            }
            synchronized (mLock) {
                FileOutputStream stream = null;
                try {
                    stream = mSettingsFile.startWrite();
                    mSettings.persist(stream);
                    mSettingsFile.finishWrite(stream);
                } catch (IOException | XmlPullParserException e) {
                    mSettingsFile.failWrite(stream);
                    Slog.e(TAG, "failed to persist overlay state", e);
                }
            }
        });
    }

    private void restoreSettings() {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#restoreSettings");
            synchronized (mLock) {
                if (!mSettingsFile.getBaseFile().exists()) {
                    return;
                }
                try (FileInputStream stream = mSettingsFile.openRead()) {
                    mSettings.restore(stream);

                    // We might have data for dying users if the device was
                    // restarted before we received USER_REMOVED. Remove data for
                    // users that will not exist after the system is ready.

                    final List<UserInfo> liveUsers = mUserManager.getUsers(true /*excludeDying*/);
                    final int[] liveUserIds = new int[liveUsers.size()];
                    for (int i = 0; i < liveUsers.size(); i++) {
                        liveUserIds[i] = liveUsers.get(i).getUserHandle().getIdentifier();
                    }
                    Arrays.sort(liveUserIds);

                    for (int userId : mSettings.getUsers()) {
                        if (Arrays.binarySearch(liveUserIds, userId) < 0) {
                            mSettings.removeUser(userId);
                        }
                    }
                } catch (IOException | XmlPullParserException e) {
                    Slog.e(TAG, "failed to restore overlay state", e);
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private static final class PackageManagerHelper implements
            OverlayManagerServiceImpl.PackageManagerHelper {

        private final IPackageManager mPackageManager;
        private final PackageManagerInternal mPackageManagerInternal;

        // Use a cache for performance and for consistency within OMS: because
        // additional PACKAGE_* intents may be delivered while we process an
        // intent, querying the PackageManagerService for the actual current
        // state may lead to contradictions within OMS. Better then to lag
        // behind until all pending intents have been processed.
        private final SparseArray<HashMap<String, PackageInfo>> mCache = new SparseArray<>();

        PackageManagerHelper() {
            mPackageManager = getPackageManager();
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }

        public PackageInfo getPackageInfo(@NonNull final String packageName, final int userId,
                final boolean useCache) {
            if (useCache) {
                final PackageInfo cachedPi = getCachedPackageInfo(packageName, userId);
                if (cachedPi != null) {
                    return cachedPi;
                }
            }
            try {
                final PackageInfo pi = mPackageManager.getPackageInfo(packageName, 0, userId);
                if (useCache && pi != null) {
                    cachePackageInfo(packageName, userId, pi);
                }
                return pi;
            } catch (RemoteException e) {
                // Intentionally left empty.
            }
            return null;
        }

        @Override
        public PackageInfo getPackageInfo(@NonNull final String packageName, final int userId) {
            return getPackageInfo(packageName, userId, true);
        }

        @Override
        public boolean signaturesMatching(@NonNull final String packageName1,
                @NonNull final String packageName2, final int userId) {
            // The package manager does not support different versions of packages
            // to be installed for different users: ignore userId for now.
            try {
                return mPackageManager.checkSignatures(
                        packageName1, packageName2) == SIGNATURE_MATCH;
            } catch (RemoteException e) {
                // Intentionally left blank
            }
            return false;
        }

        @Override
        public List<PackageInfo> getOverlayPackages(final int userId) {
            return mPackageManagerInternal.getOverlayPackages(userId);
        }

        public PackageInfo getCachedPackageInfo(@NonNull final String packageName,
                final int userId) {
            final HashMap<String, PackageInfo> map = mCache.get(userId);
            return map == null ? null : map.get(packageName);
        }

        public void cachePackageInfo(@NonNull final String packageName, final int userId,
                @NonNull final PackageInfo pi) {
            HashMap<String, PackageInfo> map = mCache.get(userId);
            if (map == null) {
                map = new HashMap<>();
                mCache.put(userId, map);
            }
            map.put(packageName, pi);
        }

        public void forgetPackageInfo(@NonNull final String packageName, final int userId) {
            final HashMap<String, PackageInfo> map = mCache.get(userId);
            if (map == null) {
                return;
            }
            map.remove(packageName);
            if (map.isEmpty()) {
                mCache.delete(userId);
            }
        }

        public void forgetAllPackageInfos(final int userId) {
            mCache.delete(userId);
        }

        private static final String TAB1 = "    ";
        private static final String TAB2 = TAB1 + TAB1;

        public void dump(@NonNull final PrintWriter pw, @NonNull DumpState dumpState) {
            pw.println("PackageInfo cache");

            if (!dumpState.isVerbose()) {
                int count = 0;
                final int n = mCache.size();
                for (int i = 0; i < n; i++) {
                    final int userId = mCache.keyAt(i);
                    count += mCache.get(userId).size();
                }
                pw.println(TAB1 + count + " package(s)");
                return;
            }

            if (mCache.size() == 0) {
                pw.println(TAB1 + "<empty>");
                return;
            }

            final int n = mCache.size();
            for (int i = 0; i < n; i++) {
                final int userId = mCache.keyAt(i);
                pw.println(TAB1 + "User " + userId);
                final HashMap<String, PackageInfo> map = mCache.get(userId);
                for (Map.Entry<String, PackageInfo> entry : map.entrySet()) {
                    pw.println(TAB2 + entry.getKey() + ": " + entry.getValue());
                }
            }
        }
    }
}
