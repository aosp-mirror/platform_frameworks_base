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
import static android.view.autofill.AutofillManager.MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS;
import static android.view.autofill.AutofillManager.getSmartSuggestionModeToString;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sFullScreenMode;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.content.AutofillOptions;
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
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.autofill.FillEventHistory;
import android.service.autofill.UserData;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.SmartSuggestionMode;
import android.view.autofill.AutofillManagerInternal;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

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
public final class AutofillManagerService
        extends AbstractMasterSystemService<AutofillManagerService, AutofillManagerServiceImpl> {

    private static final String TAG = "AutofillManagerService";

    private static final Object sLock = AutofillManagerService.class;

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private static final char COMPAT_PACKAGE_DELIMITER = ':';
    private static final char COMPAT_PACKAGE_URL_IDS_DELIMITER = ',';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_BEGIN = '[';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_END = ']';

    private static final int DEFAULT_AUGMENTED_AUTOFILL_REQUEST_TIMEOUT_MILLIS = 5_000;

    /**
     * Maximum number of partitions that can be allowed in a session.
     *
     * <p>Can be modified using {@code cmd autofill set max_partitions} or through
     * {@link android.provider.Settings.Global#AUTOFILL_MAX_PARTITIONS_SIZE}.
     */
    @GuardedBy("sLock")
    private static int sPartitionMaxCount = AutofillManager.DEFAULT_MAX_PARTITIONS_SIZE;

    /**
     * Maximum number of visible datasets in the dataset picker UI, or {@code 0} to use default
     * value from resources.
     *
     * <p>Can be modified using {@code cmd autofill set max_visible_datasets} or through
     * {@link android.provider.Settings.Global#AUTOFILL_MAX_VISIBLE_DATASETS}.
     */
    @GuardedBy("sLock")
    private static int sVisibleDatasetsMaxCount = 0;

    private final AutoFillUI mUi;

    private final LocalLog mRequestsHistory = new LocalLog(20);
    private final LocalLog mUiLatencyHistory = new LocalLog(20);
    private final LocalLog mWtfHistory = new LocalLog(50);

    private final AutofillCompatState mAutofillCompatState = new AutofillCompatState();
    private final LocalService mLocalService = new LocalService();
    private final ActivityManagerInternal mAm;

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
                    visitServicesLocked((s) -> s.destroyFinishedSessionsLocked());
                }
                mUi.hideAll(null);
            }
        }
    };

    /**
     * Supported modes for Augmented Autofill Smart Suggestions.
     */
    @GuardedBy("mLock")
    private int mSupportedSmartSuggestionModes;

    @GuardedBy("mLock")
    int mAugmentedServiceIdleUnbindTimeoutMs;
    @GuardedBy("mLock")
    int mAugmentedServiceRequestTimeoutMs;

    public AutofillManagerService(Context context) {
        super(context,
                new SecureSettingsServiceNameResolver(context, Settings.Secure.AUTOFILL_SERVICE),
                UserManager.DISALLOW_AUTOFILL);
        mUi = new AutoFillUI(ActivityThread.currentActivityThread().getSystemUiContext());
        mAm = LocalServices.getService(ActivityManagerInternal.class);

        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_AUTOFILL,
                ActivityThread.currentApplication().getMainExecutor(),
                (namespace, key, value) -> onDeviceConfigChange(key, value));

        setLogLevelFromSettings();
        setMaxPartitionsFromSettings();
        setMaxVisibleDatasetsFromSettings();
        setDeviceConfigProperties();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter, null, FgThread.getHandler());

        if (mSupportedSmartSuggestionModes != AutofillManager.FLAG_SMART_SUGGESTION_OFF) {
            // Must eager load the services so they bind to the augmented autofill service
            final UserManager um = getContext().getSystemService(UserManager.class);
            final List<UserInfo> users = um.getUsers();
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                getServiceForUserLocked(userId);
            }
        }
    }

    @Override // from AbstractMasterSystemService
    protected String getServiceSettingsProperty() {
        return Settings.Secure.AUTOFILL_SERVICE;
    }

    @Override // from AbstractMasterSystemService
    protected void registerForExtraSettingsChanges(@NonNull ContentResolver resolver,
            @NonNull ContentObserver observer) {
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_LOGGING_LEVEL), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS), false, observer,
                UserHandle.USER_ALL);
    }

    @Override // from AbstractMasterSystemService
    protected void onSettingsChanged(int userId, @NonNull String property) {
        switch (property) {
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
                Slog.w(TAG, "Unexpected property (" + property + "); updating cache instead");
                // fall through
            case Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES:
                synchronized (mLock) {
                    updateCachedServiceLocked(userId);
                }
        }
    }

    private void onDeviceConfigChange(@NonNull String key, @Nullable String value) {
        switch (key) {
            case AutofillManager.DEVICE_CONFIG_AUTOFILL_SMART_SUGGESTION_SUPPORTED_MODES:
            case AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_IDLE_UNBIND_TIMEOUT:
            case AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_REQUEST_TIMEOUT:
                setDeviceConfigProperties();
                break;
            default:
                Slog.i(mTag, "Ignoring change on " + key);
        }
    }

    @Override // from AbstractMasterSystemService
    protected AutofillManagerServiceImpl newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new AutofillManagerServiceImpl(this, mLock, mUiLatencyHistory,
                mWtfHistory, resolvedUserId, mUi, mAutofillCompatState, disabled);
    }

    @Override // AbstractMasterSystemService
    protected void onServiceRemoved(@NonNull AutofillManagerServiceImpl service,
            @UserIdInt int userId) {
        service.destroyLocked();
        mAutofillCompatState.removeCompatibilityModeRequests(userId);
    }

    @Override // from AbstractMasterSystemService
    protected void onServiceEnabledLocked(@NonNull AutofillManagerServiceImpl service,
            @UserIdInt int userId) {
        addCompatibilityModeRequestsLocked(service, userId);
    }

    @Override // from AbstractMasterSystemService
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(AUTOFILL_MANAGER_SERVICE, new AutoFillManagerServiceStub());
        publishLocalService(AutofillManagerInternal.class, mLocalService);
    }

    @Override // from SystemService
    public void onSwitchUser(int userHandle) {
        if (sDebug) Slog.d(TAG, "Hiding UI when user switched");
        mUi.hideAll(null);
    }

    @SmartSuggestionMode int getSupportedSmartSuggestionModesLocked() {
        return mSupportedSmartSuggestionModes;
    }

    /**
     * Logs a request so it's dumped later...
     */
    void logRequestLocked(@NonNull String historyItem) {
        mRequestsHistory.log(historyItem);
    }

    // Called by AutofillManagerServiceImpl, doesn't need to check permission
    boolean isInstantServiceAllowed() {
        return mAllowInstantService;
    }

    // Called by Shell command.
    void destroySessions(@UserIdInt int userId, IResultReceiver receiver) {
        Slog.i(TAG, "destroySessions() for userId " + userId);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.destroySessionsLocked();
                }
            } else {
                visitServicesLocked((s) -> s.destroySessionsLocked());
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
        enforceCallingPermissionForManagement();

        final Bundle resultData = new Bundle();
        final ArrayList<String> sessions = new ArrayList<>();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.listSessionsLocked(sessions);
                }
            } else {
                visitServicesLocked((s) -> s.listSessionsLocked(sessions));
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
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            visitServicesLocked((s) -> s.destroyLocked());
            clearCacheLocked();
        }
    }

    // Called by Shell command.
    void setLogLevel(int level) {
        Slog.i(TAG, "setLogLevel(): " + level);
        enforceCallingPermissionForManagement();

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(getContext().getContentResolver(),
                    Settings.Global.AUTOFILL_LOGGING_LEVEL, level);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setLogLevelFromSettings() {
        final int level = Settings.Global.getInt(
                getContext().getContentResolver(),
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
            setLoggingLevelsLocked(debug, verbose);
        }
    }

    // Called by Shell command.
    int getLogLevel() {
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            if (sVerbose) return AutofillManager.FLAG_ADD_CLIENT_VERBOSE;
            if (sDebug) return AutofillManager.FLAG_ADD_CLIENT_DEBUG;
            return 0;
        }
    }

    // Called by Shell command.
    int getMaxPartitions() {
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            return sPartitionMaxCount;
        }
    }

    // Called by Shell command.
    void setMaxPartitions(int max) {
        Slog.i(TAG, "setMaxPartitions(): " + max);
        enforceCallingPermissionForManagement();

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(getContext().getContentResolver(),
                    Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE, max);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setMaxPartitionsFromSettings() {
        final int max = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE,
                AutofillManager.DEFAULT_MAX_PARTITIONS_SIZE);
        if (sDebug) Slog.d(TAG, "setMaxPartitionsFromSettings(): " + max);

        synchronized (sLock) {
            sPartitionMaxCount = max;
        }
    }

    // Called by Shell command.
    int getMaxVisibleDatasets() {
        enforceCallingPermissionForManagement();

        synchronized (sLock) {
            return sVisibleDatasetsMaxCount;
        }
    }

    // Called by Shell command.
    void setMaxVisibleDatasets(int max) {
        Slog.i(TAG, "setMaxVisibleDatasets(): " + max);
        enforceCallingPermissionForManagement();

        final long token = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(getContext().getContentResolver(),
                    Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS, max);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setMaxVisibleDatasetsFromSettings() {
        final int max = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS, 0);

        if (sDebug) Slog.d(TAG, "setMaxVisibleDatasetsFromSettings(): " + max);
        synchronized (sLock) {
            sVisibleDatasetsMaxCount = max;
        }
    }

    private void setDeviceConfigProperties() {
        synchronized (mLock) {
            mAugmentedServiceIdleUnbindTimeoutMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_AUTOFILL,
                    AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_IDLE_UNBIND_TIMEOUT,
                    (int) AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS);
            mAugmentedServiceRequestTimeoutMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_AUTOFILL,
                    AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_REQUEST_TIMEOUT,
                    DEFAULT_AUGMENTED_AUTOFILL_REQUEST_TIMEOUT_MILLIS);
            mSupportedSmartSuggestionModes = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_AUTOFILL,
                    AutofillManager.DEVICE_CONFIG_AUTOFILL_SMART_SUGGESTION_SUPPORTED_MODES,
                    AutofillManager.FLAG_SMART_SUGGESTION_SYSTEM);
            if (verbose) {
                Slog.v(mTag, "setDeviceConfigProperties(): "
                        + "augmentedIdleTimeout=" + mAugmentedServiceIdleUnbindTimeoutMs
                        + ", augmentedRequestTimeout=" + mAugmentedServiceRequestTimeoutMs
                        + ", smartSuggestionMode="
                        + getSmartSuggestionModeToString(mSupportedSmartSuggestionModes));
            }
        }
    }

    // Called by Shell command.
    void calculateScore(@Nullable String algorithmName, @NonNull String value1,
            @NonNull String value2, @NonNull RemoteCallback callback) {
        enforceCallingPermissionForManagement();

        final FieldClassificationStrategy strategy =
                new FieldClassificationStrategy(getContext(), UserHandle.USER_CURRENT);

        strategy.calculateScores(callback, Arrays.asList(AutofillValue.forText(value1)),
                new String[] { value2 }, null, algorithmName, null, null, null);
    }

    // Called by Shell command.
    Boolean getFullScreenMode() {
        enforceCallingPermissionForManagement();
        return sFullScreenMode;
    }

    // Called by Shell command.
    void setFullScreenMode(@Nullable Boolean mode) {
        enforceCallingPermissionForManagement();
        sFullScreenMode = mode;
    }

    // Called by Shell command.
    void setTemporaryAugmentedAutofillService(@UserIdInt int userId, @NonNull String serviceName,
            int durationMs) {
        Slog.i(mTag, "setTemporaryAugmentedAutofillService(" + userId + ") to " + serviceName
                + " for " + durationMs + "ms");
        enforceCallingPermissionForManagement();

        Preconditions.checkNotNull(serviceName);
        if (durationMs > MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS) {
            throw new IllegalArgumentException("Max duration is "
                    + MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS + " (called with " + durationMs + ")");
        }

        synchronized (mLock) {
            final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service != null) {
                service.mAugmentedAutofillResolver.setTemporaryService(userId, serviceName,
                        durationMs);
            }
        }
    }

    // Called by Shell command
    void resetTemporaryAugmentedAutofillService(@UserIdInt int userId) {
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service != null) {
                service.mAugmentedAutofillResolver.resetTemporaryService(userId);
            }
        }
    }

    private void setLoggingLevelsLocked(boolean debug, boolean verbose) {
        com.android.server.autofill.Helper.sDebug = debug;
        android.view.autofill.Helper.sDebug = debug;
        this.debug = debug;

        com.android.server.autofill.Helper.sVerbose = verbose;
        android.view.autofill.Helper.sVerbose = verbose;
        this.verbose = verbose;
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
                getContext().getContentResolver(),
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
        send(receiver, SyncResultReceiver.bundleFor(value));
    }

    private void send(@NonNull IResultReceiver receiver, @Nullable String[] value) {
        send(receiver, SyncResultReceiver.bundleFor(value));
    }

    private void send(@NonNull IResultReceiver receiver, @Nullable Parcelable value) {
        send(receiver, SyncResultReceiver.bundleFor(value));
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

    /**
     * Gets the maximum number of partitions / fill requests.
     */
    public static int getPartitionMaxCount() {
        synchronized (sLock) {
            return sPartitionMaxCount;
        }
    }

    /**
     * Gets the maxium number of datasets visible in the UI.
     */
    public static int getVisibleDatasetsMaxCount() {
        synchronized (sLock) {
            return sVisibleDatasetsMaxCount;
        }
    }

    private final class LocalService extends AutofillManagerInternal {
        @Override
        public void onBackKeyPressed() {
            if (sDebug) Slog.d(TAG, "onBackKeyPressed()");
            mUi.hideAll(null);
            synchronized (mLock) {
                final AutofillManagerServiceImpl service =
                        getServiceForUserLocked(UserHandle.getCallingUserId());
                service.onBackKeyPressed();
            }
        }

        @Override
        public AutofillOptions getAutofillOptions(@NonNull String packageName,
                long versionCode, @UserIdInt int userId) {
            final int loggingLevel;
            if (verbose) {
                loggingLevel = AutofillManager.FLAG_ADD_CLIENT_VERBOSE
                        | AutofillManager.FLAG_ADD_CLIENT_DEBUG;
            } else if (debug) {
                loggingLevel = AutofillManager.FLAG_ADD_CLIENT_DEBUG;
            } else {
                loggingLevel = AutofillManager.NO_LOGGING;
            }
            final boolean compatModeEnabled = mAutofillCompatState.isCompatibilityModeRequested(
                    packageName, versionCode, userId);
            final AutofillOptions options = new AutofillOptions(loggingLevel, compatModeEnabled);

            synchronized (mLock) {
                final AutofillManagerServiceImpl service =
                        getServiceForUserLocked(UserHandle.getCallingUserId());
                if (service != null) {
                    service.setAugmentedAutofillWhitelistLocked(options, packageName);
                }
            }

            return options;
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
            synchronized (mLock) {
                if (mUserSpecs == null) {
                    pw.println("N/A");
                    return;
                }
                pw.println();
                final String prefix2 = prefix + "  ";
                for (int i = 0; i < mUserSpecs.size(); i++) {
                    final int user = mUserSpecs.keyAt(i);
                    pw.print(prefix);
                    pw.print("User: ");
                    pw.println(user);
                    final ArrayMap<String, PackageCompatState> perUser = mUserSpecs.valueAt(i);
                    for (int j = 0; j < perUser.size(); j++) {
                        final String packageName = perUser.keyAt(j);
                        final PackageCompatState state = perUser.valueAt(j);
                        pw.print(prefix2); pw.print(packageName); pw.print(": "); pw.println(state);
                    }
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
                getContext().getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(packageName + " is not a valid package", e);
            }

            // TODO(b/113281366): add a callback method on AM to be notified when a task is finished
            // so we can clean up sessions kept alive
            final int taskId = mAm.getTaskIdForActivity(activityToken, false);
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
        public void setAugmentedAutofillWhitelist(@Nullable List<String> packages,
                @Nullable List<ComponentName> activities, @NonNull IResultReceiver receiver)
                throws RemoteException {
            final int userId = UserHandle.getCallingUserId();

            boolean ok;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    ok = service.setAugmentedAutofillWhitelistLocked(packages, activities,
                            getCallingUid());
                } else {
                    if (sVerbose) {
                        Slog.v(TAG, "setAugmentedAutofillWhitelist(): no service for " + userId);
                    }
                    ok = false;
                }
            }
            send(receiver,
                    ok ? AutofillManager.RESULT_OK : AutofillManager.RESULT_CODE_NOT_SERVICE);
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
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
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
                supported = !isDisabledLocked(userId);
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
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

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

            final String prefix = "  ";
            boolean realDebug = sDebug;
            boolean realVerbose = sVerbose;
            try {
                sDebug = sVerbose = true;
                synchronized (mLock) {
                    pw.print("sDebug: "); pw.print(realDebug);
                    pw.print(" sVerbose: "); pw.println(realVerbose);
                    // Dump per-user services
                    dumpLocked("", pw);
                    pw.print("Max partitions per session: "); pw.println(sPartitionMaxCount);
                    pw.print("Max visible datasets: "); pw.println(sVisibleDatasetsMaxCount);
                    if (sFullScreenMode != null) {
                        pw.print("Overridden full-screen mode: "); pw.println(sFullScreenMode);
                    }
                    pw.println("User data constraints: "); UserData.dumpConstraints(prefix, pw);
                    mUi.dump(pw);
                    pw.print("Autofill Compat State: ");
                    mAutofillCompatState.dump(prefix, pw);
                    pw.print("from settings: ");
                    pw.println(getWhitelistedCompatModePackagesFromSettings());
                    if (mSupportedSmartSuggestionModes != 0) {
                        pw.print("Smart Suggestion modes: ");
                        pw.println(getSmartSuggestionModeToString(mSupportedSmartSuggestionModes));
                    }
                    pw.print("Augmented Service Idle Unbind Timeout: ");
                    pw.println(mAugmentedServiceIdleUnbindTimeoutMs);
                    pw.print("Augmented Service Request Timeout: ");
                    pw.println(mAugmentedServiceRequestTimeoutMs);
                    if (showHistory) {
                        pw.println(); pw.println("Requests history:"); pw.println();
                        mRequestsHistory.reverseDump(fd, pw, args);
                        pw.println(); pw.println("UI latency history:"); pw.println();
                        mUiLatencyHistory.reverseDump(fd, pw, args);
                        pw.println(); pw.println("WTF history:"); pw.println();
                        mWtfHistory.reverseDump(fd, pw, args);
                    }
                }
            } finally {
                sDebug = realDebug;
                sVerbose = realVerbose;
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new AutofillManagerServiceShellCommand(AutofillManagerService.this).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }
}
