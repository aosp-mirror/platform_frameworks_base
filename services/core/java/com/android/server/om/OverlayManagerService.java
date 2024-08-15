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
import static android.content.Intent.ACTION_OVERLAY_CHANGED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_REASON;
import static android.content.Intent.EXTRA_USER_ID;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_REGISTER_FABRICATED;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_SET_DISABLED;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_SET_ENABLED;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_UNREGISTER_FABRICATED;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static android.os.Process.INVALID_UID;
import static android.os.Trace.TRACE_TAG_RRO;
import static android.os.Trace.traceBegin;
import static android.os.Trace.traceEnd;

import static com.android.server.om.OverlayManagerServiceImpl.OperationFailedException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.om.OverlayManagerTransaction.Request;
import android.content.om.OverlayableInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.content.pm.UserPackage;
import android.content.pm.overlay.OverlayPaths;
import android.content.res.ApkAssets;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FabricatedOverlayInternal;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
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
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.KeepForWeakReference;
import com.android.internal.content.PackageMonitor;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.pm.KnownPackages;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.pkg.PackageState;

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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final PackageManagerHelperImpl mPackageManager;

    private final UserManagerService mUserManager;

    private final OverlayManagerSettings mSettings;

    private final OverlayManagerServiceImpl mImpl;

    private final OverlayActorEnforcer mActorEnforcer;

    @KeepForWeakReference
    private final PackageMonitor mPackageMonitor = new OverlayManagerPackageMonitor();

    private int mPrevStartedUserId = -1;

    public OverlayManagerService(@NonNull final Context context) {
        super(context);
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#OverlayManagerService");
            mSettingsFile = new AtomicFile(
                    new File(Environment.getDataSystemDirectory(), "overlays.xml"), "overlays");
            mPackageManager = new PackageManagerHelperImpl(context);
            mUserManager = UserManagerService.getInstance();
            IdmapManager im = new IdmapManager(IdmapDaemon.getInstance(), mPackageManager);
            mSettings = new OverlayManagerSettings();
            mImpl = new OverlayManagerServiceImpl(mPackageManager, im, mSettings,
                    OverlayConfig.getSystemInstance(), getDefaultOverlayPackages());
            mActorEnforcer = new OverlayActorEnforcer(mPackageManager);

            HandlerThread packageMonitorThread = new HandlerThread(TAG);
            packageMonitorThread.start();
            mPackageMonitor.register(
                    context, packageMonitorThread.getLooper(), UserHandle.ALL, true);

            final IntentFilter userFilter = new IntentFilter();
            userFilter.addAction(ACTION_USER_ADDED);
            userFilter.addAction(ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(new UserReceiver(), UserHandle.ALL,
                    userFilter, null, null);

            UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            umi.addUserLifecycleListener(new UserLifecycleListener());

            restoreSettings();

            // Wipe all shell overlays on boot, to recover from a potentially broken device
            String shellPkgName = TextUtils.emptyIfNull(
                    getContext().getString(android.R.string.config_systemShell));
            mSettings.removeIf(overlayInfo -> overlayInfo.isFabricated
                    && shellPkgName.equals(overlayInfo.packageName));

            initIfNeeded();
            onStartUser(UserHandle.USER_SYSTEM);

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
        final List<UserInfo> users = um.getAliveUsers();
        synchronized (mLock) {
            final int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                final UserInfo userInfo = users.get(i);
                if (!userInfo.supportsSwitchTo() && userInfo.id != UserHandle.USER_SYSTEM) {
                    // Initialize any users that can't be switched to, as their state would
                    // never be setup in onStartUser(). We will switch to the system user right
                    // after this, and its state will be setup there.
                    updatePackageManagerLocked(mImpl.updateOverlaysForUser(users.get(i).id));
                }
            }
        }
    }

    @Override
    public void onUserStarting(TargetUser user) {
        onStartUser(user.getUserIdentifier());
    }

    private void onStartUser(@UserIdInt int newUserId) {
        // Do nothing when start a user that is the same as the one started previously.
        if (newUserId == mPrevStartedUserId) {
            return;
        }
        Slog.i(TAG, "Updating overlays for starting user " + newUserId);
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onStartUser " + newUserId);
            // ensure overlays in the settings are up-to-date, and propagate
            // any asset changes to the rest of the system
            synchronized (mLock) {
                updateTargetPackagesLocked(mImpl.updateOverlaysForUser(newUserId));
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
        mPrevStartedUserId = newUserId;
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
        return defaultPackages.toArray(new String[0]);
    }

    private final class OverlayManagerPackageMonitor extends PackageMonitor {

        @Override
        public void onPackageAppearedWithExtras(String packageName, Bundle extras) {
            handlePackageAdd(packageName, extras, getChangingUserId());
        }

        @Override
        public void onPackageChangedWithExtras(String packageName, Bundle extras) {
            handlePackageChange(packageName, extras, getChangingUserId());
        }

        @Override
        public void onPackageDisappearedWithExtras(String packageName, Bundle extras) {
            handlePackageRemove(packageName, extras, getChangingUserId());
        }
    }

    private int[] getUserIds(int uid) {
        final int[] userIds;
        if (uid == INVALID_UID) {
            userIds = mUserManager.getUserIds();
        } else {
            userIds = new int[] { UserHandle.getUserId(uid) };
        }
        return userIds;
    }

    private void handlePackageAdd(String packageName, Bundle extras, int userId) {
        final boolean replacing = extras.getBoolean(Intent.EXTRA_REPLACING, false);
        if (replacing) {
            onPackageReplaced(packageName, userId);
        } else {
            onPackageAdded(packageName, userId);
        }
    }

    private void handlePackageChange(String packageName, Bundle extras, int userId) {
        if (!ACTION_OVERLAY_CHANGED.equals(extras.getString(EXTRA_REASON))) {
            onPackageChanged(packageName, userId);
        }
    }

    private void handlePackageRemove(String packageName, Bundle extras, int userId) {
        final boolean replacing = extras.getBoolean(Intent.EXTRA_REPLACING, false);
        final boolean systemUpdateUninstall =
                extras.getBoolean(Intent.EXTRA_SYSTEM_UPDATE_UNINSTALL, false);

        if (replacing) {
            onPackageReplacing(packageName, systemUpdateUninstall, userId);
        } else {
            onPackageRemoved(packageName, userId);
        }
    }

    private void onPackageAdded(@NonNull final String packageName, final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onPackageAdded " + packageName);
            synchronized (mLock) {
                var packageState = mPackageManager.onPackageAdded(packageName, userId);
                if (packageState != null && !mPackageManager.isInstantApp(packageName,
                        userId)) {
                    try {
                        updateTargetPackagesLocked(
                                mImpl.onPackageAdded(packageName, userId));
                    } catch (OperationFailedException e) {
                        Slog.e(TAG, "onPackageAdded internal error", e);
                    }
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void onPackageChanged(@NonNull final String packageName, final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onPackageChanged " + packageName);
            synchronized (mLock) {
                var packageState = mPackageManager.onPackageUpdated(packageName, userId);
                if (packageState != null && !mPackageManager.isInstantApp(packageName,
                        userId)) {
                    try {
                        updateTargetPackagesLocked(
                                mImpl.onPackageChanged(packageName, userId));
                    } catch (OperationFailedException e) {
                        Slog.e(TAG, "onPackageChanged internal error", e);
                    }
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void onPackageReplacing(@NonNull final String packageName,
                                    boolean systemUpdateUninstall, final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onPackageReplacing " + packageName);
            synchronized (mLock) {
                var packageState = mPackageManager.onPackageUpdated(packageName, userId);
                if (packageState != null && !mPackageManager.isInstantApp(packageName,
                        userId)) {
                    try {
                        updateTargetPackagesLocked(mImpl.onPackageReplacing(packageName,
                                systemUpdateUninstall, userId));
                    } catch (OperationFailedException e) {
                        Slog.e(TAG, "onPackageReplacing internal error", e);
                    }
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void onPackageReplaced(@NonNull final String packageName, final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onPackageReplaced " + packageName);
            synchronized (mLock) {
                var packageState = mPackageManager.onPackageUpdated(packageName, userId);
                if (packageState != null && !mPackageManager.isInstantApp(packageName,
                        userId)) {
                    try {
                        updateTargetPackagesLocked(
                                mImpl.onPackageReplaced(packageName, userId));
                    } catch (OperationFailedException e) {
                        Slog.e(TAG, "onPackageReplaced internal error", e);
                    }
                }
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void onPackageRemoved(@NonNull final String packageName, final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#onPackageRemoved " + packageName);
            synchronized (mLock) {
                mPackageManager.onPackageRemoved(packageName, userId);
                updateTargetPackagesLocked(mImpl.onPackageRemoved(packageName, userId));
            }
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    /**
     * Indicates that the given user is of great importance so that when it is created, we quickly
     * update its overlays by using a Listener mechanism rather than a Broadcast mechanism. This
     * is especially important for {@link UserManager#isHeadlessSystemUserMode() HSUM}'s MainUser,
     * which is created and switched-to immediately on first boot.
     */
    private static boolean isHighPriorityUserCreation(UserInfo user) {
        // TODO: Consider extending this to all created users (guarded behind a flag in that case).
        return user != null && user.isMain();
    }

    private final class UserLifecycleListener implements UserManagerInternal.UserLifecycleListener {
        @Override
        public void onUserCreated(UserInfo user, Object token) {
            if (isHighPriorityUserCreation(user)) {
                final int userId = user.id;
                try {
                    Slog.i(TAG, "Updating overlays for onUserCreated " + userId);
                    traceBegin(TRACE_TAG_RRO, "OMS#onUserCreated " + userId);
                    synchronized (mLock) {
                        updatePackageManagerLocked(mImpl.updateOverlaysForUser(userId));
                    }
                } finally {
                    traceEnd(TRACE_TAG_RRO);
                }
            }
        }
    }

    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            switch (intent.getAction()) {
                case ACTION_USER_ADDED:
                    UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userId != UserHandle.USER_NULL && !isHighPriorityUserCreation(userInfo)) {
                        try {
                            Slog.i(TAG, "Updating overlays for added user " + userId);
                            traceBegin(TRACE_TAG_RRO, "OMS ACTION_USER_ADDED");
                            synchronized (mLock) {
                                updatePackageManagerLocked(mImpl.updateOverlaysForUser(userId));
                            }
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
        public Map<String, List<OverlayInfo>> getAllOverlays(final int userIdArg) {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getAllOverlays " + userIdArg);
                final int realUserId = handleIncomingUser(userIdArg, "getAllOverlays");

                synchronized (mLock) {
                    return mImpl.getOverlaysForUser(realUserId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public List<OverlayInfo> getOverlayInfosForTarget(@Nullable final String targetPackageName,
                final int userIdArg) {
            if (targetPackageName == null) {
                return Collections.emptyList();
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getOverlayInfosForTarget " + targetPackageName);
                final int realUserId = handleIncomingUser(userIdArg, "getOverlayInfosForTarget");

                synchronized (mLock) {
                    return mImpl.getOverlayInfosForTarget(targetPackageName, realUserId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public OverlayInfo getOverlayInfo(@Nullable final String packageName,
                final int userIdArg) {
            return getOverlayInfoByIdentifier(new OverlayIdentifier(packageName), userIdArg);
        }

        @Override
        public OverlayInfo getOverlayInfoByIdentifier(@Nullable final OverlayIdentifier overlay,
                final int userIdArg) {
            if (overlay == null || overlay.getPackageName() == null) {
                return null;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#getOverlayInfo " + overlay);
                final int realUserId = handleIncomingUser(userIdArg, "getOverlayInfo");

                synchronized (mLock) {
                    return mImpl.getOverlayInfo(overlay, realUserId);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setEnabled(@Nullable final String packageName, final boolean enable,
                int userIdArg) {
            if (packageName == null) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabled " + packageName + " " + enable);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final int realUserId = handleIncomingUser(userIdArg, "setEnabled");
                enforceActor(overlay, "setEnabled", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            updateTargetPackagesLocked(
                                    mImpl.setEnabled(overlay, enable, realUserId));
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
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
                int userIdArg) {
            if (packageName == null || !enable) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabledExclusive " + packageName + " " + enable);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final int realUserId = handleIncomingUser(userIdArg, "setEnabledExclusive");
                enforceActor(overlay, "setEnabledExclusive", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            mImpl.setEnabledExclusive(
                                            overlay, false /* withinCategory */, realUserId)
                                    .ifPresent(
                                            OverlayManagerService.this::updateTargetPackagesLocked);
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setEnabledExclusiveInCategory(@Nullable String packageName,
                final int userIdArg) {
            if (packageName == null) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setEnabledExclusiveInCategory " + packageName);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final int realUserId = handleIncomingUser(userIdArg,
                        "setEnabledExclusiveInCategory");
                enforceActor(overlay, "setEnabledExclusiveInCategory", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            mImpl.setEnabledExclusive(overlay,
                                    true /* withinCategory */, realUserId)
                                .ifPresent(OverlayManagerService.this::updateTargetPackagesLocked);
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
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
                @Nullable final String parentPackageName, final int userIdArg) {
            if (packageName == null || parentPackageName == null) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setPriority " + packageName + " "
                        + parentPackageName);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final OverlayIdentifier parentOverlay = new OverlayIdentifier(parentPackageName);
                final int realUserId = handleIncomingUser(userIdArg, "setPriority");
                enforceActor(overlay, "setPriority", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            mImpl.setPriority(overlay, parentOverlay, realUserId)
                                .ifPresent(OverlayManagerService.this::updateTargetPackagesLocked);
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setHighestPriority(@Nullable final String packageName, final int userIdArg) {
            if (packageName == null) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setHighestPriority " + packageName);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final int realUserId = handleIncomingUser(userIdArg, "setHighestPriority");
                enforceActor(overlay, "setHighestPriority", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            updateTargetPackagesLocked(
                                    mImpl.setHighestPriority(overlay, realUserId));
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public boolean setLowestPriority(@Nullable final String packageName, final int userIdArg) {
            if (packageName == null) {
                return false;
            }

            try {
                traceBegin(TRACE_TAG_RRO, "OMS#setLowestPriority " + packageName);

                final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
                final int realUserId = handleIncomingUser(userIdArg, "setLowestPriority");
                enforceActor(overlay, "setLowestPriority", realUserId);

                final long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        try {
                            mImpl.setLowestPriority(overlay, realUserId)
                                .ifPresent(OverlayManagerService.this::updateTargetPackagesLocked);
                            return true;
                        } catch (OperationFailedException e) {
                            return false;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        @Override
        public String[] getDefaultOverlayPackages() {
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
        public void invalidateCachesForOverlay(@Nullable String packageName, final int userIdArg) {
            if (packageName == null) {
                return;
            }

            final OverlayIdentifier overlay = new OverlayIdentifier(packageName);
            final int realUserId = handleIncomingUser(userIdArg, "invalidateCachesForOverlay");
            enforceActor(overlay, "invalidateCachesForOverlay", realUserId);
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        mImpl.removeIdmapForOverlay(overlay, realUserId);
                    } catch (OperationFailedException e) {
                        Slog.w(TAG, "invalidate caches for overlay '" + overlay + "' failed", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void commit(@NonNull final OverlayManagerTransaction transaction)
                throws RemoteException {
            try {
                traceBegin(TRACE_TAG_RRO, "OMS#commit " + transaction);
                try {
                    executeAllRequests(transaction);
                } catch (Exception e) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        restoreSettings();
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                    Slog.d(TAG, "commit failed: " + e.getMessage(), e);
                    throw new SecurityException("commit failed"
                            + (DEBUG || Build.IS_DEBUGGABLE ? ": " + e.getMessage() : ""));
                }
            } finally {
                traceEnd(TRACE_TAG_RRO);
            }
        }

        private Set<UserPackage> executeRequest(
                @NonNull final OverlayManagerTransaction.Request request)
                throws OperationFailedException {
            Objects.requireNonNull(request, "Transaction contains a null request");
            Objects.requireNonNull(request.overlay,
                    "Transaction overlay identifier must be non-null");

            final int callingUid = Binder.getCallingUid();
            final int realUserId;
            if (request.type == TYPE_REGISTER_FABRICATED
                    || request.type == TYPE_UNREGISTER_FABRICATED) {
                if (request.userId != UserHandle.USER_ALL) {
                    throw new IllegalArgumentException(request.typeToString()
                            + " unsupported for user " + request.userId);
                }

                // Normal apps are blocked from accessing OMS via SELinux, so to block non-root,
                // non privileged callers, a simple check against the shell UID is sufficient, since
                // that's the only exception from the other categories. This is enough while OMS
                // is not a public API, but this will have to be changed if it's ever exposed.
                if (callingUid == Process.SHELL_UID) {
                    EventLog.writeEvent(0x534e4554, "202768292", -1, "");
                    throw new IllegalArgumentException("Non-root shell cannot fabricate overlays");
                }

                realUserId = UserHandle.USER_ALL;

                // Enforce that the calling process can only register and unregister fabricated
                // overlays using its package name.
                final String pkgName = request.overlay.getPackageName();
                if (callingUid != Process.ROOT_UID && !ArrayUtils.contains(
                        mPackageManager.getPackagesForUid(callingUid), pkgName)) {
                    throw new IllegalArgumentException("UID " + callingUid + " does own package"
                            + "name " + pkgName);
                }
            } else {
                // Enforce actor requirements for enabling, disabling, and reordering overlays.
                realUserId = handleIncomingUser(request.userId, request.typeToString());
                enforceActor(request.overlay, request.typeToString(), realUserId);
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                switch (request.type) {
                    case TYPE_SET_ENABLED:
                        Set<UserPackage> result = null;
                        result = CollectionUtils.addAll(result,
                                mImpl.setEnabled(request.overlay, true, realUserId));
                        result = CollectionUtils.addAll(result,
                                mImpl.setHighestPriority(request.overlay, realUserId));
                        return CollectionUtils.emptyIfNull(result);

                    case TYPE_SET_DISABLED:
                        return mImpl.setEnabled(request.overlay, false, realUserId);

                    case TYPE_REGISTER_FABRICATED:
                        final FabricatedOverlayInternal fabricated =
                                request.extras.getParcelable(
                                        OverlayManagerTransaction.Request.BUNDLE_FABRICATED_OVERLAY
                                , android.os.FabricatedOverlayInternal.class);
                        Objects.requireNonNull(fabricated,
                                "no fabricated overlay attached to request");
                        return mImpl.registerFabricatedOverlay(fabricated);

                    case TYPE_UNREGISTER_FABRICATED:
                        return mImpl.unregisterFabricatedOverlay(request.overlay);

                    default:
                        throw new IllegalArgumentException("unsupported request: " + request);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void executeAllRequests(@NonNull final OverlayManagerTransaction transaction)
                throws OperationFailedException {
            if (DEBUG) {
                Slog.d(TAG, "commit " + transaction);
            }
            if (transaction == null) {
                throw new IllegalArgumentException("null transaction");
            }

            synchronized (mLock) {
                // execute the requests (as calling user)
                Set<UserPackage> affectedPackagesToUpdate = null;
                for (Iterator<Request> it = transaction.getRequests(); it.hasNext(); ) {
                    Request request = it.next();
                    affectedPackagesToUpdate = CollectionUtils.addAll(affectedPackagesToUpdate,
                            executeRequest(request));
                }

                // past the point of no return: the entire transaction has been
                // processed successfully, we can no longer fail: continue as
                // system_server
                final long ident = Binder.clearCallingIdentity();
                try {
                    updateTargetPackagesLocked(affectedPackagesToUpdate);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
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

                if ("-a".equals(opt)) {
                    // dumpsys will pass in -a; silently ignore it
                } else if ("-h".equals(opt)) {
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
                    case "ismutable":
                    case "priority":
                    case "category":
                        dumpState.setField(arg);
                        break;
                    default:
                        dumpState.setOverlyIdentifier(arg);
                        break;
                }
            }
            if (dumpState.getPackageName() == null && opti < args.length) {
                dumpState.setOverlyIdentifier(args[opti]);
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
         * Enforce that the caller holds the DUMP permission (or is system or root).
         *
         * @param message used as message if SecurityException is thrown
         * @throws SecurityException if the permission check fails
         */
        private void enforceDumpPermission(@NonNull final String message) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, message);
        }

        private void enforceActor(@NonNull OverlayIdentifier overlay, @NonNull String methodName,
                int realUserId) throws SecurityException {
            OverlayInfo overlayInfo = mImpl.getOverlayInfo(overlay, realUserId);

            if (overlayInfo == null) {
                throw new IllegalArgumentException("Unable to retrieve overlay information for "
                        + overlay);
            }

            int callingUid = Binder.getCallingUid();
            mActorEnforcer.enforceActor(overlayInfo, methodName, callingUid, realUserId);
        }

        /**
         * @hide
         */
        public String getPartitionOrder() {
            return mImpl.getOverlayConfig().getPartitionOrder();
        }

        /**
         * @hide
         */
        public boolean isDefaultPartitionOrder() {
            return mImpl.getOverlayConfig().isDefaultPartitionOrder();
        }

    };

    private static final class PackageManagerHelperImpl implements PackageManagerHelper {
        private static class PackageStateUsers {
            private PackageState mPackageState;
            private final Set<Integer> mInstalledUsers = new ArraySet<>();
            private PackageStateUsers(@NonNull PackageState packageState) {
                this.mPackageState = packageState;
            }
        }
        private final Context mContext;
        private final IPackageManager mPackageManager;
        private final PackageManagerInternal mPackageManagerInternal;

        // Use a cache for performance and for consistency within OMS: because
        // additional PACKAGE_* intents may be delivered while we process an
        // intent, querying the PackageManagerService for the actual current
        // state may lead to contradictions within OMS. Better then to lag
        // behind until all pending intents have been processed.
        @GuardedBy("itself")
        private final ArrayMap<String, PackageStateUsers> mCache = new ArrayMap<>();
        private final ArraySet<Integer> mInitializedUsers = new ArraySet<>();

        PackageManagerHelperImpl(Context context) {
            mContext = context;
            mPackageManager = getPackageManager();
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }

        /**
         * Initializes the helper for the user. This only needs to be invoked one time before
         * packages of this user are queried.
         * @param userId the user id to initialize
         * @return a map of package name to all packages installed in the user
         */
        @NonNull
        public ArrayMap<String, PackageState> initializeForUser(final int userId) {
            if (mInitializedUsers.add(userId)) {
                mPackageManagerInternal.forEachPackageState((packageState -> {
                    if (packageState.getPkg() != null
                            && packageState.getUserStateOrDefault(userId).isInstalled()) {
                        addPackageUser(packageState, userId);
                    }
                }));
            }

            final ArrayMap<String, PackageState> userPackages = new ArrayMap<>();
            synchronized (mCache) {
                for (int i = 0, n = mCache.size(); i < n; i++) {
                    final PackageStateUsers pkg = mCache.valueAt(i);
                    if (pkg.mInstalledUsers.contains(userId)) {
                        userPackages.put(mCache.keyAt(i), pkg.mPackageState);
                    }
                }
            }
            return userPackages;
        }

        @Override
        @Nullable
        public PackageState getPackageStateForUser(@NonNull final String packageName,
                final int userId) {
            final PackageStateUsers pkg;

            synchronized (mCache) {
                pkg = mCache.get(packageName);
            }
            if (pkg != null && pkg.mInstalledUsers.contains(userId)) {
                return pkg.mPackageState;
            }
            try {
                if (!mPackageManager.isPackageAvailable(packageName, userId)) {
                    return null;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to check availability of package '" + packageName
                        + "' for user " + userId, e);
                return null;
            }
            return addPackageUser(packageName, userId);
        }

        @NonNull
        private PackageState addPackageUser(@NonNull final String packageName,
                final int user) {
            final PackageState pkg = mPackageManagerInternal.getPackageStateInternal(packageName);
            if (pkg == null) {
                Slog.w(TAG, "Android package for '" + packageName + "' could not be found;"
                        + " continuing as if package was never added", new Throwable());
                return null;
            }
            return addPackageUser(pkg, user);
        }

        @NonNull
        private PackageState addPackageUser(@NonNull final PackageState pkg,
                final int user) {
            PackageStateUsers pkgUsers;
            synchronized (mCache) {
                pkgUsers = mCache.get(pkg.getPackageName());
                if (pkgUsers == null) {
                    pkgUsers = new PackageStateUsers(pkg);
                    mCache.put(pkg.getPackageName(), pkgUsers);
                } else {
                    pkgUsers.mPackageState = pkg;
                }
            }
            pkgUsers.mInstalledUsers.add(user);
            return pkgUsers.mPackageState;
        }


        @NonNull
        private void removePackageUser(@NonNull final String packageName, final int user) {
            // synchronize should include the call to the other removePackageUser() method so that
            // the access and modification happen under the same lock.
            synchronized (mCache) {
                final PackageStateUsers pkgUsers = mCache.get(packageName);
                if (pkgUsers == null) {
                    return;
                }
                removePackageUser(pkgUsers, user);
            }
        }

        @NonNull
        private void removePackageUser(@NonNull final PackageStateUsers pkg, final int user) {
            pkg.mInstalledUsers.remove(user);
            if (pkg.mInstalledUsers.isEmpty()) {
                synchronized (mCache) {
                    mCache.remove(pkg.mPackageState.getPackageName());
                }
            }
        }

        @Nullable
        public PackageState onPackageAdded(@NonNull final String packageName, final int userId) {
            return addPackageUser(packageName, userId);
        }

        @Nullable
        public PackageState onPackageUpdated(@NonNull final String packageName,
                final int userId) {
            return addPackageUser(packageName, userId);
        }

        public void onPackageRemoved(@NonNull final String packageName, final int userId) {
            removePackageUser(packageName, userId);
        }

        @Override
        public boolean isInstantApp(@NonNull final String packageName, final int userId) {
            return mPackageManagerInternal.isInstantApp(packageName, userId);
        }

        @NonNull
        @Override
        public Map<String, Map<String, String>> getNamedActors() {
            return SystemConfig.getInstance().getNamedActors();
        }

        @Override
        public boolean signaturesMatching(@NonNull final String packageName1,
                @NonNull final String packageName2, final int userId) {
            // The package manager does not support different versions of packages
            // to be installed for different users: ignore userId for now.
            try {
                return mPackageManager.checkSignatures(
                        packageName1, packageName2, userId) == SIGNATURE_MATCH;
            } catch (RemoteException e) {
                // Intentionally left blank
            }
            return false;
        }

        @Override
        public String getConfigSignaturePackage() {
            final String[] pkgs = mPackageManagerInternal.getKnownPackageNames(
                    KnownPackages.PACKAGE_OVERLAY_CONFIG_SIGNATURE,
                    UserHandle.USER_SYSTEM);
            return (pkgs.length == 0) ? null : pkgs[0];
        }

        @Nullable
        @Override
        public OverlayableInfo getOverlayableForTarget(@NonNull String packageName,
                @NonNull String targetOverlayableName, int userId)
                throws IOException {
            var packageState = getPackageStateForUser(packageName, userId);
            var pkg = packageState == null ? null : packageState.getAndroidPackage();
            if (pkg == null) {
                throw new IOException("Unable to get target package");
            }

            ApkAssets apkAssets = null;
            try {
                apkAssets = ApkAssets.loadFromPath(pkg.getSplits().get(0).getPath(),
                        ApkAssets.PROPERTY_ONLY_OVERLAYABLES);
                return apkAssets.getOverlayableInfo(targetOverlayableName);
            } finally {
                if (apkAssets != null) {
                    try {
                        apkAssets.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        @Override
        public boolean doesTargetDefineOverlayable(String targetPackageName, int userId)
                throws IOException {
            var packageState = getPackageStateForUser(targetPackageName, userId);
            var pkg = packageState == null ? null : packageState.getAndroidPackage();
            if (pkg == null) {
                throw new IOException("Unable to get target package");
            }

            ApkAssets apkAssets = null;
            try {
                apkAssets = ApkAssets.loadFromPath(pkg.getSplits().get(0).getPath(),
                        ApkAssets.PROPERTY_ONLY_OVERLAYABLES);
                return apkAssets.definesOverlayable();
            } finally {
                if (apkAssets != null) {
                    try {
                        apkAssets.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        @Override
        public void enforcePermission(String permission, String message) throws SecurityException {
            mContext.enforceCallingOrSelfPermission(permission, message);
        }

        public void forgetAllPackageInfos(final int userId) {
            // Iterate in reverse order since removing the package in all users will remove the
            // package from the cache.
            synchronized (mCache) {
                for (int i = mCache.size() - 1; i >= 0; i--) {
                    removePackageUser(mCache.valueAt(i), userId);
                }
            }
        }

        @Nullable
        @Override
        public String[] getPackagesForUid(int uid) {
            try {
                return mPackageManager.getPackagesForUid(uid);
            } catch (RemoteException ignored) {
                return null;
            }
        }

        private static final String TAB1 = "    ";

        public void dump(@NonNull final PrintWriter pw, @NonNull DumpState dumpState) {
            pw.println("AndroidPackage cache");
            synchronized (mCache) {
                if (!dumpState.isVerbose()) {
                    pw.println(TAB1 + mCache.size() + " package(s)");
                    return;
                }

                if (mCache.size() == 0) {
                    pw.println(TAB1 + "<empty>");
                    return;
                }

                for (int i = 0, n = mCache.size(); i < n; i++) {
                    final String packageName = mCache.keyAt(i);
                    final PackageStateUsers pkg = mCache.valueAt(i);
                    pw.print(TAB1 + packageName + ": " + pkg.mPackageState + " users=");
                    pw.println(TextUtils.join(", ", pkg.mInstalledUsers));
                }
            }
        }
    }

    private void updateTargetPackagesLocked(@Nullable UserPackage updatedTarget) {
        if (updatedTarget != null) {
            updateTargetPackagesLocked(Set.of(updatedTarget));
        }
    }

    private void updateTargetPackagesLocked(@Nullable Set<UserPackage> updatedTargets) {
        if (CollectionUtils.isEmpty(updatedTargets)) {
            return;
        }
        persistSettingsLocked();
        final SparseArray<ArraySet<String>> userTargets = groupTargetsByUserId(updatedTargets);
        for (int i = 0, n = userTargets.size(); i < n; i++) {
            final ArraySet<String> targets = userTargets.valueAt(i);
            final int userId = userTargets.keyAt(i);
            final List<String> affectedPackages = updatePackageManagerLocked(targets, userId);
            if (affectedPackages.isEmpty()) {
                // The package manager paths are already up-to-date.
                continue;
            }

            FgThread.getHandler().post(() -> {
                // Send configuration changed events for all target packages that have been affected
                // by overlay state changes.
                updateActivityManager(affectedPackages, userId);

                // Do not send broadcasts for all affected targets. Overlays targeting the framework
                // or shared libraries may cause too many broadcasts to be sent at once.
                broadcastActionOverlayChanged(targets, userId);
            });
        }
    }

    @Nullable
    private static SparseArray<ArraySet<String>> groupTargetsByUserId(
            @Nullable final Set<UserPackage> targetsAndUsers) {
        final SparseArray<ArraySet<String>> userTargets = new SparseArray<>();
        CollectionUtils.forEach(targetsAndUsers, target -> {
            ArraySet<String> targets = userTargets.get(target.userId);
            if (targets == null) {
                targets = new ArraySet<>();
                userTargets.put(target.userId, targets);
            }
            targets.add(target.packageName);
        });
        return userTargets;
    }

    // Helper methods to update other parts of the system or read/write
    // settings: these methods should never call into each other!

    private static void broadcastActionOverlayChanged(@NonNull final Set<String> targetPackages,
            final int userId) {
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        CollectionUtils.forEach(targetPackages, target -> {
            final Intent intent = new Intent(ACTION_OVERLAY_CHANGED,
                    Uri.fromParts("package", target, null));
            intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_PACKAGE_NAME, target);
            intent.putExtra(EXTRA_USER_ID, userId);
            amInternal.broadcastIntent(intent, null /* resultTo */, null /* requiredPermissions */,
                    false /* serialized */, userId, null /* appIdAllowList */,
                    OverlayManagerService::filterReceiverAccess, null /* bOptions */);
        });
    }

    /**
     * A callback from the broadcast queue to determine whether the intent
     * {@link Intent#ACTION_OVERLAY_CHANGED} is visible to the receiver.
     *
     * @param callingUid The receiver's uid.
     * @param extras The extras of intent that contains {@link Intent#EXTRA_PACKAGE_NAME} and
     * {@link Intent#EXTRA_USER_ID} to check.
     * @return {@code null} if the intent is not visible to the receiver.
     */
    @Nullable
    private static Bundle filterReceiverAccess(int callingUid, @NonNull Bundle extras) {
        final String packageName = extras.getString(EXTRA_PACKAGE_NAME);
        final int userId = extras.getInt(EXTRA_USER_ID);
        if (LocalServices.getService(PackageManagerInternal.class).filterAppAccess(
                packageName, callingUid, userId, false /* filterUninstalled */)) {
            return null;
        }
        return extras;
    }

    /**
     * Tell the activity manager to tell a set of packages to reload their
     * resources.
     */
    private void updateActivityManager(@NonNull List<String> targetPackageNames, final int userId) {
        final IActivityManager am = ActivityManager.getService();
        try {
            am.scheduleApplicationInfoChanged(targetPackageNames, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "updateActivityManager remote exception", e);
        }
    }

    @NonNull
    private SparseArray<List<String>> updatePackageManagerLocked(
            @Nullable Set<UserPackage> targets) {
        if (CollectionUtils.isEmpty(targets)) {
            return new SparseArray<>();
        }
        final SparseArray<List<String>> affectedTargets = new SparseArray<>();
        final SparseArray<ArraySet<String>> userTargets = groupTargetsByUserId(targets);
        for (int i = 0, n = userTargets.size(); i < n; i++) {
            final int userId = userTargets.keyAt(i);
            affectedTargets.put(userId, updatePackageManagerLocked(userTargets.valueAt(i), userId));
        }
        return affectedTargets;
    }

    /**
     * Updates the target packages' set of enabled overlays in PackageManager.
     * @return the package names of affected targets (a superset of
     *         targetPackageNames: the target themselves and shared libraries)
     */
    @NonNull
    private List<String> updatePackageManagerLocked(@NonNull Collection<String> targetPackageNames,
            final int userId) {
        try {
            traceBegin(TRACE_TAG_RRO, "OMS#updatePackageManagerLocked " + targetPackageNames);
            if (DEBUG) {
                Slog.d(TAG, "Update package manager about changed overlays");
            }
            final PackageManagerInternal pm =
                    LocalServices.getService(PackageManagerInternal.class);
            final boolean updateFrameworkRes = targetPackageNames.contains("android");
            if (updateFrameworkRes) {
                targetPackageNames = pm.getTargetPackageNames(userId);
            }

            final ArrayMap<String, OverlayPaths> pendingChanges =
                    new ArrayMap<>(targetPackageNames.size());
            synchronized (mLock) {
                final OverlayPaths frameworkOverlays =
                        mImpl.getEnabledOverlayPaths("android", userId, false);
                for (final String targetPackageName : targetPackageNames) {
                    final var list = new OverlayPaths.Builder(frameworkOverlays);
                    if (!"android".equals(targetPackageName)) {
                        list.addAll(mImpl.getEnabledOverlayPaths(targetPackageName, userId, true));
                    }
                    pendingChanges.put(targetPackageName, list.build());
                }
            }

            final HashSet<String> updatedPackages = new HashSet<>();
            final HashSet<String> invalidPackages = new HashSet<>();
            pm.setEnabledOverlayPackages(userId, pendingChanges, updatedPackages, invalidPackages);

            if (DEBUG || !invalidPackages.isEmpty()) {
                for (final String targetPackageName : targetPackageNames) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "-> Updating overlay: target=" + targetPackageName + " overlays=["
                                        + pendingChanges.get(targetPackageName)
                                        + "] userId=" + userId);
                    }

                    if (invalidPackages.contains(targetPackageName)) {
                        Slog.e(TAG, TextUtils.formatSimple(
                                "Failed to change enabled overlays for %s user %d",
                                targetPackageName,
                                userId));
                    }
                }
            }
            return new ArrayList<>(updatedPackages);
        } finally {
            traceEnd(TRACE_TAG_RRO);
        }
    }

    private void persistSettingsLocked() {
        if (DEBUG) {
            Slog.d(TAG, "Writing overlay settings");
        }
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
}
