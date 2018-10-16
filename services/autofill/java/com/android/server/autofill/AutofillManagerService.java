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

package com.android.server.autofill;

import static android.Manifest.permission.MANAGE_AUTO_FILL;
import static android.content.Context.AUTOFILL_MANAGER_SERVICE;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sFullScreenMode;
import static com.android.server.autofill.Helper.sPartitionMaxCount;
import static com.android.server.autofill.Helper.sVisibleDatasetsMaxCount;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.service.autofill.FillEventHistory;
import android.service.autofill.UserData;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManagerInternal;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Entry point service for autofill management.
 *
 * <p>This service provides the {@link IAutoFillManager} implementation and keeps a list of
 * {@link AutofillManagerServiceImpl} per user; the real work is done by
 * {@link AutofillManagerServiceImpl} itself.
 */
public final class AutofillManagerService extends SystemService {

    private static final String TAG = "AutofillManagerService";

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private static final char COMPAT_PACKAGE_DELIMITER = ':';
    private static final char COMPAT_PACKAGE_URL_IDS_DELIMITER = ',';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_BEGIN = '[';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_END = ']';

    private final Context mContext;
    private final AutoFillUI mUi;

    private final Object mLock = new Object();

    /**
     * Cache of {@link AutofillManagerServiceImpl} per user id.
     * <p>
     * It has to be mapped by user id because the same current user could have simultaneous sessions
     * associated to different user profiles (for example, in a multi-window environment or when
     * device has work profiles).
     */
    @GuardedBy("mLock")
    private SparseArray<AutofillManagerServiceImpl> mServicesCache = new SparseArray<>();

    /**
     * Users disabled due to {@link UserManager} restrictions.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mDisabledUsers = new SparseBooleanArray();

    private final LocalLog mRequestsHistory = new LocalLog(20);
    private final LocalLog mUiLatencyHistory = new LocalLog(20);
    private final LocalLog mWtfHistory = new LocalLog(50);

    private final AutofillCompatState mAutofillCompatState = new AutofillCompatState();
    private final LocalService mLocalService = new LocalService();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                if (sDebug) Slog.d(TAG, "Close system dialogs");

                // TODO(b/64940307): we need to destroy all sessions that are finished but showing
                // Save UI because there is no way to show the Save UI back when the activity
                // beneath it is brought back to top. Ideally, we should just hide the UI and
                // bring it back when the activity resumes.
                synchronized (mLock) {
                    for (int i = 0; i < mServicesCache.size(); i++) {
                        mServicesCache.valueAt(i).destroyFinishedSessionsLocked();
                    }
                }

                mUi.hideAll(null);
            }
        }
    };

    @GuardedBy("mLock")
    private boolean mAllowInstantService;

    public AutofillManagerService(Context context) {
        super(context);
        mContext = context;
        mUi = new AutoFillUI(ActivityThread.currentActivityThread().getSystemUiContext());

        setLogLevelFromSettings();
        setMaxPartitionsFromSettings();
        setMaxVisibleDatasetsFromSettings();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, FgThread.getHandler());

        // Hookup with UserManager to disable service when necessary.
        final UserManager um = context.getSystemService(UserManager.class);
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final List<UserInfo> users = um.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            final boolean disabled = umi.getUserRestriction(userId, UserManager.DISALLOW_AUTOFILL);
            if (disabled) {
                Slog.i(TAG, "Disabling Autofill for user " + userId);
                mDisabledUsers.put(userId, disabled);
            }
        }
        umi.addUserRestrictionsListener((userId, newRestrictions, prevRestrictions) -> {
            final boolean disabledNow =
                    newRestrictions.getBoolean(UserManager.DISALLOW_AUTOFILL, false);
            synchronized (mLock) {
                final boolean disabledBefore = mDisabledUsers.get(userId);
                if (disabledBefore == disabledNow) {
                    // Nothing changed, do nothing.
                    if (sDebug) {
                        Slog.d(TAG, "Autofill restriction did not change for user " + userId);
                        return;
                    }
                }
                Slog.i(TAG, "Updating Autofill for user " + userId + ": disabled=" + disabledNow);
                mDisabledUsers.put(userId, disabledNow);
                updateCachedServiceLocked(userId, disabledNow);
            }
        });
        startTrackingPackageChanges();
    }

    private void startTrackingPackageChanges() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    updateCachedServiceLocked(getChangingUserId());
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                synchronized (mLock) {
                    final String activePackageName = getActiveAutofillServicePackageName();
                    if (packageName.equals(activePackageName)) {
                        removeCachedServiceLocked(getChangingUserId());
                    } else {
                        handlePackageUpdateLocked(packageName);
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    final AutofillManagerServiceImpl userState = peekServiceForUserLocked(userId);
                    if (userState != null) {
                        final ComponentName componentName = userState.getServiceComponentName();
                        if (componentName != null) {
                            if (packageName.equals(componentName.getPackageName())) {
                                handleActiveAutofillServiceRemoved(userId);
                            }
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    final String activePackageName = getActiveAutofillServicePackageName();
                    for (String pkg : packages) {
                        if (pkg.equals(activePackageName)) {
                            if (!doit) {
                                return true;
                            }
                            removeCachedServiceLocked(getChangingUserId());
                        } else {
                          handlePackageUpdateLocked(pkg);
                        }
                    }
                }
                return false;
            }

            private void handleActiveAutofillServiceRemoved(int userId) {
                removeCachedServiceLocked(userId);
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.AUTOFILL_SERVICE, null, userId);
            }

            private String getActiveAutofillServicePackageName() {
                final int userId = getChangingUserId();
                final AutofillManagerServiceImpl userState = peekServiceForUserLocked(userId);
                if (userState == null) {
                    return null;
                }
                final ComponentName serviceComponent = userState.getServiceComponentName();
                if (serviceComponent == null) {
                    return null;
                }
                return serviceComponent.getPackageName();
            }

            @GuardedBy("mLock")
            private void handlePackageUpdateLocked(String packageName) {
                final int size = mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    mServicesCache.valueAt(i).handlePackageUpdateLocked(packageName);
                }
            }
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);
    }

    @Override
    public void onStart() {
        publishBinderService(AUTOFILL_MANAGER_SERVICE, new AutoFillManagerServiceStub());
        publishLocalService(AutofillManagerInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            new SettingsObserver(BackgroundThread.getHandler());
        }
    }

    @Override
    public void onUnlockUser(int userId) {
        synchronized (mLock) {
            updateCachedServiceLocked(userId);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        if (sDebug) Slog.d(TAG, "Hiding UI when user switched");
        mUi.hideAll(null);
    }

    @Override
    public void onCleanupUser(int userId) {
        synchronized (mLock) {
            removeCachedServiceLocked(userId);
        }
    }

    /**
     * Gets the service instance for an user.
     *
     * @return service instance.
     */
    @GuardedBy("mLock")
    @NonNull
    AutofillManagerServiceImpl getServiceForUserLocked(int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        AutofillManagerServiceImpl service = mServicesCache.get(resolvedUserId);
        if (service == null) {
            service = new AutofillManagerServiceImpl(mContext, mLock, mRequestsHistory,
                    mUiLatencyHistory, mWtfHistory, resolvedUserId, mUi,
                    mAutofillCompatState, mDisabledUsers.get(resolvedUserId));
            mServicesCache.put(userId, service);
            addCompatibilityModeRequestsLocked(service, userId);
        }
        return service;
    }

    /**
     * Peeks the service instance for a user.
     *
     * @return service instance or {@code null} if not already present
     */
    @GuardedBy("mLock")
    @Nullable
    AutofillManagerServiceImpl peekServiceForUserLocked(int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        return mServicesCache.get(resolvedUserId);
    }

    // Called by Shell command.
    void destroySessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "destroySessions() for userId " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.destroySessionsLocked();
                }
            } else {
                final int size = mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    mServicesCache.valueAt(i).destroySessionsLocked();
                }
            }
        }

        try {
            receiver.send(0, new Bundle());
        } catch (RemoteException e) {
            // Just ignore it...
        }
    }

    // Called by Shell command.
    void listSessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "listSessions() for userId " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        final Bundle resultData = new Bundle();
        final ArrayList<String> sessions = new ArrayList<>();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.listSessionsLocked(sessions);
                }
            } else {
                final int size = mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    mServicesCache.valueAt(i).listSessionsLocked(sessions);
                }
            }
        }

        resultData.putStringArrayList(RECEIVER_BUNDLE_EXTRA_SESSIONS, sessions);
        try {
            receiver.send(0, resultData);
        } catch (RemoteException e) {
            // Just ignore it...
        }
    }

    // Called by Shell command.
    void reset() {
        Slog.i(TAG, "reset()");
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            final int size = mServicesCache.size();
            for (int i = 0; i < size; i++) {
                mServicesCache.valueAt(i).destroyLocked();
            }
            mServicesCache.clear();
        }
    }

    // Called by Shell command.
    void setLogLevel(int level) {
        Slog.i(TAG, "setLogLevel(): " + level);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AUTOFILL_LOGGING_LEVEL, level);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setLogLevelFromSettings() {
        final int level = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AUTOFILL_LOGGING_LEVEL, AutofillManager.DEFAULT_LOGGING_LEVEL);
        boolean debug = false;
        boolean verbose = false;
        if (level != AutofillManager.NO_LOGGING) {
            if (level == AutofillManager.FLAG_ADD_CLIENT_VERBOSE) {
                debug = verbose = true;
            } else if (level == AutofillManager.FLAG_ADD_CLIENT_DEBUG) {
                debug = true;
            } else {
                Slog.w(TAG,  "setLogLevelFromSettings(): invalid level: " + level);
            }
        }
        if (debug || sDebug) {
            Slog.d(TAG, "setLogLevelFromSettings(): level=" + level + ", debug=" + debug
                    + ", verbose=" + verbose);
        }
        synchronized (mLock) {
            setDebugLocked(debug);
            setVerboseLocked(verbose);
        }
    }

    // Called by Shell command.
    int getLogLevel() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            if (sVerbose) return AutofillManager.FLAG_ADD_CLIENT_VERBOSE;
            if (sDebug) return AutofillManager.FLAG_ADD_CLIENT_DEBUG;
            return 0;
        }
    }

    // Called by Shell command.
    int getMaxPartitions() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            return sPartitionMaxCount;
        }
    }

    // Called by Shell command.
    void setMaxPartitions(int max) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        Slog.i(TAG, "setMaxPartitions(): " + max);

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE, max);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setMaxPartitionsFromSettings() {
        final int max = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE,
                AutofillManager.DEFAULT_MAX_PARTITIONS_SIZE);
        if (sDebug) Slog.d(TAG, "setMaxPartitionsFromSettings(): " + max);

        synchronized (mLock) {
            sPartitionMaxCount = max;
        }
    }

    // Called by Shell command.
    int getMaxVisibleDatasets() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            return sVisibleDatasetsMaxCount;
        }
    }

    // Called by Shell command.
    void setMaxVisibleDatasets(int max) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        Slog.i(TAG, "setMaxVisibleDatasets(): " + max);

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS, max);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setMaxVisibleDatasetsFromSettings() {
        final int max = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS, 0);

        if (sDebug) Slog.d(TAG, "setMaxVisibleDatasetsFromSettings(): " + max);
        synchronized (mLock) {
            sVisibleDatasetsMaxCount = max;
        }
    }

    // Called by Shell command.
    void getScore(@Nullable String algorithmName, @NonNull String value1,
            @NonNull String value2, @NonNull RemoteCallback callback) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        final FieldClassificationStrategy strategy =
                new FieldClassificationStrategy(mContext, UserHandle.USER_CURRENT);

        strategy.getScores(callback, algorithmName, null,
                Arrays.asList(AutofillValue.forText(value1)), new String[] { value2 });
    }

    // Called by Shell command.
    Boolean getFullScreenMode() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        return sFullScreenMode;
    }

    // Called by Shell command.
    void setFullScreenMode(@Nullable Boolean mode) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        sFullScreenMode = mode;
    }

    // Called by Shell command.
    boolean getAllowInstantService() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        synchronized (mLock) {
            return mAllowInstantService;
        }
    }

    // Called by Shell command.
    void setAllowInstantService(boolean mode) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        Slog.i(TAG, "setAllowInstantService(): " + mode);
        synchronized (mLock) {
            mAllowInstantService = mode;
        }
    }

    private void setDebugLocked(boolean debug) {
        com.android.server.autofill.Helper.sDebug = debug;
        android.view.autofill.Helper.sDebug = debug;
    }

    private void setVerboseLocked(boolean verbose) {
        com.android.server.autofill.Helper.sVerbose = verbose;
        android.view.autofill.Helper.sVerbose = verbose;
    }

    /**
     * Removes a cached service for a given user.
     */
    @GuardedBy("mLock")
    private void removeCachedServiceLocked(int userId) {
        final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            service.destroyLocked();
            mAutofillCompatState.removeCompatibilityModeRequests(userId);
        }
    }

    /**
     * Updates a cached service for a given user.
     */
    @GuardedBy("mLock")
    private void updateCachedServiceLocked(int userId) {
        updateCachedServiceLocked(userId, mDisabledUsers.get(userId));
    }

    /**
     * Updates a cached service for a given user.
     */
    @GuardedBy("mLock")
    private void updateCachedServiceLocked(int userId, boolean disabled) {
        AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
        if (service != null) {
            service.destroySessionsLocked();
            service.updateLocked(disabled);
            if (!service.isEnabledLocked()) {
                removeCachedServiceLocked(userId);
            } else {
                addCompatibilityModeRequestsLocked(service, userId);
            }
        }
    }

    private void addCompatibilityModeRequestsLocked(@NonNull AutofillManagerServiceImpl service
            , int userId) {
        mAutofillCompatState.reset(userId);
        final ArrayMap<String, Long> compatPackages =
                service.getCompatibilityPackagesLocked();
        if (compatPackages == null || compatPackages.isEmpty()) {
            return;
        }

        final Map<String, String[]> whiteListedPackages = getWhitelistedCompatModePackages();
        final int compatPackageCount = compatPackages.size();
        for (int i = 0; i < compatPackageCount; i++) {
            final String packageName = compatPackages.keyAt(i);
            if (whiteListedPackages == null || !whiteListedPackages.containsKey(packageName)) {
                Slog.w(TAG, "Ignoring not whitelisted compat package " + packageName);
                continue;
            }
            final Long maxVersionCode = compatPackages.valueAt(i);
            if (maxVersionCode != null) {
                mAutofillCompatState.addCompatibilityModeRequest(packageName,
                        maxVersionCode, whiteListedPackages.get(packageName), userId);
            }
        }
    }

    private String getWhitelistedCompatModePackagesFromSettings() {
        return Settings.Global.getString(
                mContext.getContentResolver(),
                Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES);
    }

    @Nullable
    private Map<String, String[]> getWhitelistedCompatModePackages() {
        return getWhitelistedCompatModePackages(getWhitelistedCompatModePackagesFromSettings());
    }

    private void send(@NonNull IResultReceiver receiver, int value) {
        try {
            receiver.send(value, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }

    private void send(@NonNull IResultReceiver receiver, @NonNull Bundle value) {
        try {
            receiver.send(0, value);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }

    private void send(@NonNull IResultReceiver receiver, @Nullable String value) {
        send(receiver, AutofillManager.SyncResultReceiver.bundleFor(value));
    }

    private void send(@NonNull IResultReceiver receiver, @Nullable String[] value) {
        send(receiver, AutofillManager.SyncResultReceiver.bundleFor(value));
    }

    private void send(@NonNull IResultReceiver receiver, @Nullable Parcelable value) {
        send(receiver, AutofillManager.SyncResultReceiver.bundleFor(value));
    }

    private void send(@NonNull IResultReceiver receiver, boolean value) {
        send(receiver, value ? 1 : 0);
    }

    @Nullable
    @VisibleForTesting
    static Map<String, String[]> getWhitelistedCompatModePackages(String setting) {
        if (TextUtils.isEmpty(setting)) {
            return null;
        }

        final ArrayMap<String, String[]> compatPackages = new ArrayMap<>();
        final SimpleStringSplitter splitter = new SimpleStringSplitter(COMPAT_PACKAGE_DELIMITER);
        splitter.setString(setting);
        while (splitter.hasNext()) {
            final String packageBlock = splitter.next();
            final int urlBlockIndex = packageBlock.indexOf(COMPAT_PACKAGE_URL_IDS_BLOCK_BEGIN);
            final String packageName;
            final List<String> urlBarIds;
            if (urlBlockIndex == -1) {
                packageName = packageBlock;
                urlBarIds = null;
            } else {
                if (packageBlock.charAt(packageBlock.length() - 1)
                        != COMPAT_PACKAGE_URL_IDS_BLOCK_END) {
                    Slog.w(TAG, "Ignoring entry '" + packageBlock + "' on '" + setting
                            + "'because it does not end on '" + COMPAT_PACKAGE_URL_IDS_BLOCK_END +
                            "'");
                    continue;
                }
                packageName = packageBlock.substring(0, urlBlockIndex);
                urlBarIds = new ArrayList<>();
                final String urlBarIdsBlock =
                        packageBlock.substring(urlBlockIndex + 1, packageBlock.length() - 1);
                if (sVerbose) {
                    Slog.v(TAG, "pkg:" + packageName + ": block:" + packageBlock + ": urls:"
                            + urlBarIds + ": block:" + urlBarIdsBlock + ":");
                }
                final SimpleStringSplitter splitter2 =
                        new SimpleStringSplitter(COMPAT_PACKAGE_URL_IDS_DELIMITER);
                splitter2.setString(urlBarIdsBlock);
                while (splitter2.hasNext()) {
                    final String urlBarId = splitter2.next();
                    urlBarIds.add(urlBarId);
                }
            }
            if (urlBarIds == null) {
                compatPackages.put(packageName, null);
            } else {
                final String[] urlBarIdsArray = new String[urlBarIds.size()];
                urlBarIds.toArray(urlBarIdsArray);
                compatPackages.put(packageName, urlBarIdsArray);
            }
        }
        return compatPackages;
    }

    private final class LocalService extends AutofillManagerInternal {
        @Override
        public void onBackKeyPressed() {
            if (sDebug) Slog.d(TAG, "onBackKeyPressed()");
            mUi.hideAll(null);
        }

        @Override
        public boolean isCompatibilityModeRequested(@NonNull String packageName,
                long versionCode, @UserIdInt int userId) {
            return mAutofillCompatState.isCompatibilityModeRequested(
                    packageName, versionCode, userId);
        }

    }

    /**
     * Compatibility mode metadata per package.
     */
    static final class PackageCompatState {
        private final long maxVersionCode;
        private final String[] urlBarResourceIds;

        PackageCompatState(long maxVersionCode, String[] urlBarResourceIds) {
            this.maxVersionCode = maxVersionCode;
            this.urlBarResourceIds = urlBarResourceIds;
        }

        @Override
        public String toString() {
            return "maxVersionCode=" + maxVersionCode
                    + ", urlBarResourceIds=" + Arrays.toString(urlBarResourceIds);
        }
    }

    /**
     * Compatibility mode metadata associated with all services.
     *
     * <p>This object is defined here instead of on each {@link AutofillManagerServiceImpl} because
     * it cannot hold a lock on the main lock when
     * {@link AutofillCompatState#isCompatibilityModeRequested(String, long, int)} is called by
     * external services.
     */
    static final class AutofillCompatState {
        private final Object mLock = new Object();

        /**
         * Map of app->compat_state per user.
         */
        @GuardedBy("mLock")
        private SparseArray<ArrayMap<String, PackageCompatState>> mUserSpecs;

        boolean isCompatibilityModeRequested(@NonNull String packageName,
                long versionCode, @UserIdInt int userId) {
            synchronized (mLock) {
                if (mUserSpecs == null) {
                    return false;
                }
                final ArrayMap<String, PackageCompatState> userSpec = mUserSpecs.get(userId);
                if (userSpec == null) {
                    return false;
                }
                final PackageCompatState metadata = userSpec.get(packageName);
                if (metadata == null) {
                    return false;
                }
                return versionCode <= metadata.maxVersionCode;
            }
        }

        @Nullable
        String[] getUrlBarResourceIds(@NonNull String packageName, @UserIdInt int userId) {
            synchronized (mLock) {
                if (mUserSpecs == null) {
                    return null;
                }
                final ArrayMap<String, PackageCompatState> userSpec = mUserSpecs.get(userId);
                if (userSpec == null) {
                    return null;
                }
                final PackageCompatState metadata = userSpec.get(packageName);
                if (metadata == null) {
                    return null;
                }
                return metadata.urlBarResourceIds;
            }
        }

        void addCompatibilityModeRequest(@NonNull String packageName,
                long versionCode, @Nullable String[] urlBarResourceIds, @UserIdInt int userId) {
            synchronized (mLock) {
                if (mUserSpecs == null) {
                    mUserSpecs = new SparseArray<>();
                }
                ArrayMap<String, PackageCompatState> userSpec = mUserSpecs.get(userId);
                if (userSpec == null) {
                    userSpec = new ArrayMap<>();
                    mUserSpecs.put(userId, userSpec);
                }
                userSpec.put(packageName,
                        new PackageCompatState(versionCode, urlBarResourceIds));
            }
        }

        void removeCompatibilityModeRequests(@UserIdInt int userId) {
            synchronized (mLock) {
                if (mUserSpecs != null) {
                    mUserSpecs.remove(userId);
                    if (mUserSpecs.size() <= 0) {
                        mUserSpecs = null;
                    }
                }
            }
        }

        void reset(int userId) {
            synchronized (mLock) {
                if (mUserSpecs != null) {
                    mUserSpecs.delete(userId);
                    final int newSize = mUserSpecs.size();
                    if (newSize == 0) {
                        if (sVerbose) Slog.v(TAG, "reseting mUserSpecs");
                        mUserSpecs = null;
                    } else {
                        if (sVerbose) Slog.v(TAG, "mUserSpecs down to " + newSize);
                    }
                }
            }
        }

        private void dump(String prefix, PrintWriter pw) {
             if (mUserSpecs == null) {
                 pw.println("N/A");
                 return;
             }
             pw.println();
             final String prefix2 = prefix + "  ";
             for (int i = 0; i < mUserSpecs.size(); i++) {
                 final int user = mUserSpecs.keyAt(i);
                 pw.print(prefix); pw.print("User: "); pw.println(user);
                 final ArrayMap<String, PackageCompatState> perUser = mUserSpecs.valueAt(i);
                 for (int j = 0; j < perUser.size(); j++) {
                     final String packageName = perUser.keyAt(j);
                     final PackageCompatState state = perUser.valueAt(j);
                     pw.print(prefix2); pw.print(packageName); pw.print(": "); pw.println(state);
                }
            }
        }
    }

    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        @Override
        public void addClient(IAutoFillManagerClient client, int userId,
                @NonNull IResultReceiver receiver) {
            int flags = 0;
            synchronized (mLock) {
                if (getServiceForUserLocked(userId).addClientLocked(client)) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_ENABLED;
                }
                if (sDebug) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_DEBUG;
                }
                if (sVerbose) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_VERBOSE;
                }
            }
            send(receiver, flags);
        }

        @Override
        public void removeClient(IAutoFillManagerClient client, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.removeClientLocked(client);
                } else if (sVerbose) {
                    Slog.v(TAG, "removeClient(): no service for " + userId);
                }
            }
        }

        @Override
        public void setAuthenticationResult(Bundle data, int sessionId, int authenticationId,
                int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.setAuthenticationResultLocked(data, sessionId, authenticationId,
                        getCallingUid());
            }
        }

        @Override
        public void setHasCallback(int sessionId, int userId, boolean hasIt) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.setHasCallback(sessionId, getCallingUid(), hasIt);
            }
        }

        @Override
        public void startSession(IBinder activityToken, IBinder appCallback, AutofillId autofillId,
                Rect bounds, AutofillValue value, int userId, boolean hasCallback, int flags,
                ComponentName componentName, boolean compatMode, IResultReceiver receiver) {

            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");
            autofillId = Preconditions.checkNotNull(autofillId, "autoFillId");
            componentName = Preconditions.checkNotNull(componentName, "componentName");
            final String packageName = Preconditions.checkNotNull(componentName.getPackageName());

            Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()), "userId");

            try {
                mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(packageName + " is not a valid package", e);
            }

            // TODO(b/113281366): rather than always call AM here, call it on demand on
            // getPreviousSessionsLocked()? That way we save space / time here, and don't set
            // a callback on AM unnecessarily (see TODO below :-)
            final ActivityManagerInternal am = LocalServices
                    .getService(ActivityManagerInternal.class);
            // TODO(b/113281366): add a callback method on AM to be notified when a task is finished
            // so we can clean up sessions kept alive
            final int taskId = am.getTaskIdForActivity(activityToken, false);
            final int sessionId;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                sessionId = service.startSessionLocked(activityToken, taskId, getCallingUid(),
                        appCallback, autofillId, bounds, value, hasCallback, componentName,
                        compatMode, mAllowInstantService, flags);
            }
            send(receiver, sessionId);
        }

        @Override
        public void getFillEventHistory(@NonNull IResultReceiver receiver) throws RemoteException {
            final int userId = UserHandle.getCallingUserId();

            FillEventHistory fillEventHistory = null;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    fillEventHistory = service.getFillEventHistory(getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "getFillEventHistory(): no service for " + userId);
                }
            }
            send(receiver, fillEventHistory);
        }

        @Override
        public void getUserData(@NonNull IResultReceiver receiver) throws RemoteException {
            final int userId = UserHandle.getCallingUserId();

            UserData userData = null;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    userData = service.getUserData(getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "getUserData(): no service for " + userId);
                }
            }
            send(receiver, userData);
        }

        @Override
        public void getUserDataId(@NonNull IResultReceiver receiver) throws RemoteException {
            final int userId = UserHandle.getCallingUserId();
            UserData userData = null;

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    userData = service.getUserData(getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "getUserDataId(): no service for " + userId);
                }
            }
            final String userDataId = userData == null ? null : userData.getId();
            send(receiver, userDataId);
        }

        @Override
        public void setUserData(UserData userData) throws RemoteException {
            final int userId = UserHandle.getCallingUserId();

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.setUserData(getCallingUid(), userData);
                } else if (sVerbose) {
                    Slog.v(TAG, "setUserData(): no service for " + userId);
                }
            }
        }

        @Override
        public void isFieldClassificationEnabled(@NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();
            boolean enabled = false;

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    enabled = service.isFieldClassificationEnabled(getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "isFieldClassificationEnabled(): no service for " + userId);
                }
            }
            send(receiver, enabled);
        }

        @Override
        public void getDefaultFieldClassificationAlgorithm(@NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();
            String algorithm = null;

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    algorithm = service.getDefaultFieldClassificationAlgorithm(getCallingUid());
                } else {
                    if (sVerbose) {
                        Slog.v(TAG, "getDefaultFcAlgorithm(): no service for " + userId);
                    }
               }
            }
            send(receiver, algorithm);
        }

        @Override
        public void getAvailableFieldClassificationAlgorithms(@NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();
            String[] algorithms = null;

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    algorithms = service.getAvailableFieldClassificationAlgorithms(getCallingUid());
                } else {
                    if (sVerbose) {
                        Slog.v(TAG, "getAvailableFcAlgorithms(): no service for " + userId);
                    }
                }
            }
            send(receiver, algorithms);
        }

        @Override
        public void getAutofillServiceComponentName(@NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();

            ComponentName componentName = null;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    componentName = service.getServiceComponentName();
                } else if (sVerbose) {
                    Slog.v(TAG, "getAutofillServiceComponentName(): no service for " + userId);
                }
            }
            send(receiver, componentName);
        }

        @Override
        public void restoreSession(int sessionId, @NonNull IBinder activityToken,
                @NonNull IBinder appCallback, @NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();
            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");

            boolean restored = false;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = mServicesCache.get(userId);
                if (service != null) {
                    restored = service.restoreSession(sessionId, getCallingUid(), activityToken,
                            appCallback);
                } else if (sVerbose) {
                    Slog.v(TAG, "restoreSession(): no service for " + userId);
                }
            }
            send(receiver, restored);
        }

        @Override
        public void updateSession(int sessionId, AutofillId autoFillId, Rect bounds,
                AutofillValue value, int action, int flags, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.updateSessionLocked(sessionId, getCallingUid(), autoFillId, bounds,
                            value, action, flags);
                } else if (sVerbose) {
                    Slog.v(TAG, "updateSession(): no service for " + userId);
                }
            }
        }

        @Override
        public void setAutofillFailure(int sessionId, @NonNull List<AutofillId> ids, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.setAutofillFailureLocked(sessionId, getCallingUid(), ids);
                } else if (sVerbose) {
                    Slog.v(TAG, "setAutofillFailure(): no service for " + userId);
                }
            }
        }

        @Override
        public void finishSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.finishSessionLocked(sessionId, getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "finishSession(): no service for " + userId);
                }
            }
        }

        @Override
        public void cancelSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.cancelSessionLocked(sessionId, getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "cancelSession(): no service for " + userId);
                }
            }
        }

        @Override
        public void disableOwnedAutofillServices(int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.disableOwnedAutofillServicesLocked(Binder.getCallingUid());
                } else if (sVerbose) {
                    Slog.v(TAG, "cancelSession(): no service for " + userId);
                }
            }
        }

        @Override
        public void isServiceSupported(int userId, @NonNull IResultReceiver receiver) {
            boolean supported = false;
            synchronized (mLock) {
                supported = !mDisabledUsers.get(userId);
            }
            send(receiver, supported);
        }

        @Override
        public void isServiceEnabled(int userId, @NonNull String packageName,
                @NonNull IResultReceiver receiver) {
            boolean enabled = false;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    enabled = Objects.equals(packageName, service.getServicePackageName());
                } else if (sVerbose) {
                    Slog.v(TAG, "isServiceEnabled(): no service for " + userId);
                }
            }
            send(receiver, enabled);
        }

        @Override
        public void onPendingSaveUi(int operation, IBinder token) {
            Preconditions.checkNotNull(token, "token");
            Preconditions.checkArgument(operation == AutofillManager.PENDING_UI_OPERATION_CANCEL
                    || operation == AutofillManager.PENDING_UI_OPERATION_RESTORE,
                    "invalid operation: %d", operation);
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    service.onPendingSaveUi(operation, token);
                }
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            boolean showHistory = true;
            boolean uiOnly = false;
            if (args != null) {
                for (String arg : args) {
                    switch(arg) {
                        case "--no-history":
                            showHistory = false;
                            break;
                        case "--ui-only":
                            uiOnly = true;
                            break;
                        case "--help":
                            pw.println("Usage: dumpsys autofill [--ui-only|--no-history]");
                            return;
                        default:
                            Slog.w(TAG, "Ignoring invalid dump arg: " + arg);
                    }
                }
            }

            if (uiOnly) {
                mUi.dump(pw);
                return;
            }

            boolean oldDebug = sDebug;
            final String prefix = "  ";
            final String prefix2 = "    ";
            try {
                synchronized (mLock) {
                    oldDebug = sDebug;
                    setDebugLocked(true);
                    pw.print("Debug mode: "); pw.println(oldDebug);
                    pw.print("Verbose mode: "); pw.println(sVerbose);
                    pw.print("Disabled users: "); pw.println(mDisabledUsers);
                    pw.print("Max partitions per session: "); pw.println(sPartitionMaxCount);
                    pw.print("Max visible datasets: "); pw.println(sVisibleDatasetsMaxCount);
                    if (sFullScreenMode != null) {
                        pw.print("Overridden full-screen mode: "); pw.println(sFullScreenMode);
                    }
                    pw.println("User data constraints: "); UserData.dumpConstraints(prefix, pw);
                    final int size = mServicesCache.size();
                    pw.print("Cached services: ");
                    if (size == 0) {
                        pw.println("none");
                    } else {
                        pw.println(size);
                        for (int i = 0; i < size; i++) {
                            pw.print("\nService at index "); pw.println(i);
                            final AutofillManagerServiceImpl impl = mServicesCache.valueAt(i);
                            impl.dumpLocked(prefix, pw);
                        }
                    }
                    mUi.dump(pw);
                    pw.print("Autofill Compat State: ");
                    mAutofillCompatState.dump(prefix2, pw);
                    pw.print(prefix2); pw.print("from settings: ");
                    pw.println(getWhitelistedCompatModePackagesFromSettings());
                    pw.print("Allow instant service: "); pw.println(mAllowInstantService);
                }
                if (showHistory) {
                    pw.println(); pw.println("Requests history:"); pw.println();
                    mRequestsHistory.reverseDump(fd, pw, args);
                    pw.println(); pw.println("UI latency history:"); pw.println();
                    mUiLatencyHistory.reverseDump(fd, pw, args);
                    pw.println(); pw.println("WTF history:"); pw.println();
                    mWtfHistory.reverseDump(fd, pw, args);
                }
            } finally {
                setDebugLocked(oldDebug);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new AutofillManagerServiceShellCommand(AutofillManagerService.this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.AUTOFILL_SERVICE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USER_SETUP_COMPLETE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.AUTOFILL_LOGGING_LEVEL), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (sVerbose) Slog.v(TAG, "onChange(): uri=" + uri + ", userId=" + userId);
            switch (uri.getLastPathSegment()) {
                case Settings.Global.AUTOFILL_LOGGING_LEVEL:
                    setLogLevelFromSettings();
                    break;
                case Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE:
                    setMaxPartitionsFromSettings();
                    break;
                case Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS:
                    setMaxVisibleDatasetsFromSettings();
                    break;
                default:
                synchronized (mLock) {
                    updateCachedServiceLocked(userId);
                }
            }
        }
    }
}
