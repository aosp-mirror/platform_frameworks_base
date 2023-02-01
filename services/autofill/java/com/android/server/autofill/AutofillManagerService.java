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

import static java.util.Objects.requireNonNull;

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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
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
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.AutofillCommitReason;
import android.view.autofill.AutofillManager.SmartSuggestionMode;
import android.view.autofill.AutofillManagerInternal;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.GlobalWhitelistState;
import com.android.internal.infra.WhitelistHelper;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /**
     * Object used to set the name of the augmented autofill service.
     */
    @NonNull
    final FrameworkResourcesServiceNameResolver mAugmentedAutofillResolver;

    private final AutoFillUI mUi;

    private final LocalLog mRequestsHistory = new LocalLog(20);
    private final LocalLog mUiLatencyHistory = new LocalLog(20);
    private final LocalLog mWtfHistory = new LocalLog(50);

    private final AutofillCompatState mAutofillCompatState = new AutofillCompatState();
    private final DisabledInfoCache mDisabledInfoCache = new DisabledInfoCache();

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
                    visitServicesLocked((s) -> s.forceRemoveFinishedSessionsLocked());
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

    final AugmentedAutofillState mAugmentedAutofillState = new AugmentedAutofillState();

    public AutofillManagerService(Context context) {
        super(context,
                new SecureSettingsServiceNameResolver(context, Settings.Secure.AUTOFILL_SERVICE),
                UserManager.DISALLOW_AUTOFILL, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
        mUi = new AutoFillUI(ActivityThread.currentActivityThread().getSystemUiContext());
        mAm = LocalServices.getService(ActivityManagerInternal.class);

        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_AUTOFILL,
                ActivityThread.currentApplication().getMainExecutor(),
                (properties) -> onDeviceConfigChange(properties.getKeyset()));

        setLogLevelFromSettings();
        setMaxPartitionsFromSettings();
        setMaxVisibleDatasetsFromSettings();
        setDeviceConfigProperties();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter, null, FgThread.getHandler(),
                Context.RECEIVER_EXPORTED);

        mAugmentedAutofillResolver = new FrameworkResourcesServiceNameResolver(getContext(),
                com.android.internal.R.string.config_defaultAugmentedAutofillService);
        mAugmentedAutofillResolver.setOnTemporaryServiceNameChangedCallback(
                (u, s, t) -> onAugmentedServiceNameChanged(u, s, t));

        if (mSupportedSmartSuggestionModes != AutofillManager.FLAG_SMART_SUGGESTION_OFF) {
            final List<UserInfo> users = getSupportedUsers();
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                // Must eager load the services so they bind to the augmented autofill service
                getServiceForUserLocked(userId);

                // And also set the global state
                mAugmentedAutofillState.setServiceInfo(userId,
                        mAugmentedAutofillResolver.getServiceName(userId),
                        mAugmentedAutofillResolver.isTemporary(userId));
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
                Settings.Global.AUTOFILL_LOGGING_LEVEL), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE), false, observer,
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
            case Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE:
                handleInputMethodSwitch(userId);
                break;
            default:
                Slog.w(TAG, "Unexpected property (" + property + "); updating cache instead");
                synchronized (mLock) {
                    updateCachedServiceLocked(userId);
                }
        }
    }

    private void handleInputMethodSwitch(@UserIdInt int userId) {
        // TODO(b/156903336): Used the SettingsObserver with a background thread maybe slow to
        // respond to the IME switch in certain situations.
        // See: services/core/java/com/android/server/FgThread.java
        // In particular, the shared background thread could be doing relatively long-running
        // operations like saving state to disk (in addition to simply being a background priority),
        // which can cause operations scheduled on it to be delayed for a user-noticeable amount
        // of time.

        synchronized (mLock) {
            final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
            if (service != null) {
                service.onSwitchInputMethod();
            }
        }
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        for (String key : keys) {
            switch (key) {
                case AutofillManager.DEVICE_CONFIG_AUTOFILL_SMART_SUGGESTION_SUPPORTED_MODES:
                case AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_IDLE_UNBIND_TIMEOUT:
                case AutofillManager.DEVICE_CONFIG_AUGMENTED_SERVICE_REQUEST_TIMEOUT:
                    setDeviceConfigProperties();
                    break;
                case AutofillManager.DEVICE_CONFIG_AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES:
                    updateCachedServices();
                    break;
                default:
                    Slog.i(mTag, "Ignoring change on " + key);
            }
        }
    }

    private void onAugmentedServiceNameChanged(@UserIdInt int userId, @Nullable String serviceName,
            boolean isTemporary) {
        mAugmentedAutofillState.setServiceInfo(userId, serviceName, isTemporary);
        synchronized (mLock) {
            final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
            if (service == null) {
                // If we cannot get the service from the services cache, it will call
                // updateRemoteAugmentedAutofillService() finally. Skip call this update again.
                getServiceForUserLocked(userId);
            } else {
                service.updateRemoteAugmentedAutofillService();
            }
        }
    }

    @Override // from AbstractMasterSystemService
    protected AutofillManagerServiceImpl newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new AutofillManagerServiceImpl(this, mLock, mUiLatencyHistory, mWtfHistory,
                resolvedUserId, mUi, mAutofillCompatState, disabled, mDisabledInfoCache);
    }

    @Override // AbstractMasterSystemService
    protected void onServiceRemoved(@NonNull AutofillManagerServiceImpl service,
            @UserIdInt int userId) {
        service.destroyLocked();
        mDisabledInfoCache.remove(userId);
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
    public boolean isUserSupported(TargetUser user) {
        return user.isFull() || user.isManagedProfile();
    }

    @Override // from SystemService
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
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
    void removeAllSessions(@UserIdInt int userId, IResultReceiver receiver) {
        Slog.i(TAG, "removeAllSessions() for userId " + userId);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.forceRemoveAllSessionsLocked();
                }
            } else {
                visitServicesLocked((s) -> s.forceRemoveAllSessionsLocked());
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

    private void updateCachedServices() {
        List<UserInfo> supportedUsers = getSupportedUsers();
        for (UserInfo userInfo : supportedUsers) {
            synchronized (mLock) {
                updateCachedServiceLocked(userInfo.id);
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
                new String[] { value2 }, new String[] { null }, algorithmName, null, null, null);
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

        Objects.requireNonNull(serviceName);
        if (durationMs > MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS) {
            throw new IllegalArgumentException("Max duration is "
                    + MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS + " (called with " + durationMs + ")");
        }

        mAugmentedAutofillResolver.setTemporaryService(userId, serviceName, durationMs);
    }

    // Called by Shell command
    void resetTemporaryAugmentedAutofillService(@UserIdInt int userId) {
        enforceCallingPermissionForManagement();
        mAugmentedAutofillResolver.resetTemporaryService(userId);
    }

    // Called by Shell command
    boolean isDefaultAugmentedServiceEnabled(@UserIdInt int userId) {
        enforceCallingPermissionForManagement();
        return mAugmentedAutofillResolver.isDefaultServiceEnabled(userId);
    }

    // Called by Shell command
    boolean setDefaultAugmentedServiceEnabled(@UserIdInt int userId, boolean enabled) {
        Slog.i(mTag, "setDefaultAugmentedServiceEnabled() for userId " + userId + ": " + enabled);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service != null) {
                final boolean changed = mAugmentedAutofillResolver
                        .setDefaultServiceEnabled(userId, enabled);
                if (changed) {
                    service.updateRemoteAugmentedAutofillService();
                    return true;
                } else {
                    if (debug) {
                        Slog.d(TAG, "setDefaultAugmentedServiceEnabled(): already " + enabled);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Requests a count of saved passwords from the current service.
     *
     * @return {@code true} if the request succeeded
     */
    // Called by Shell command
    boolean requestSavedPasswordCount(@UserIdInt int userId, @NonNull IResultReceiver receiver) {
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
            if (service != null) {
                service.requestSavedPasswordCount(receiver);
                return true;
            } else if (sVerbose) {
                Slog.v(TAG, "requestSavedPasswordCount(): no service for " + userId);
            }
        }
        return false;
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

        final Map<String, String[]> allowedPackages = getAllowedCompatModePackages();
        final int compatPackageCount = compatPackages.size();
        for (int i = 0; i < compatPackageCount; i++) {
            final String packageName = compatPackages.keyAt(i);
            if (allowedPackages == null || !allowedPackages.containsKey(packageName)) {
                Slog.w(TAG, "Ignoring not allowed compat package " + packageName);
                continue;
            }
            final Long maxVersionCode = compatPackages.valueAt(i);
            if (maxVersionCode != null) {
                mAutofillCompatState.addCompatibilityModeRequest(packageName,
                        maxVersionCode, allowedPackages.get(packageName), userId);
            }
        }
    }

    private String getAllowedCompatModePackagesFromDeviceConfig() {
        String config = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_AUTOFILL,
                AutofillManager.DEVICE_CONFIG_AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES,
                /* defaultValue */ null);
        if (!TextUtils.isEmpty(config)) {
            return config;
        }
        // Fallback to Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES if
        // the device config is null.
        return getAllowedCompatModePackagesFromSettings();
    }

    private String getAllowedCompatModePackagesFromSettings() {
        return Settings.Global.getString(
                getContext().getContentResolver(),
                Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES);
    }

    @Nullable
    private Map<String, String[]> getAllowedCompatModePackages() {
        return getAllowedCompatModePackages(getAllowedCompatModePackagesFromDeviceConfig());
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

    private void send(@NonNull IResultReceiver receiver, int value1, int value2) {
        try {
            receiver.send(value1, SyncResultReceiver.bundleFor(value2));
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }

    @Nullable
    @VisibleForTesting
    static Map<String, String[]> getAllowedCompatModePackages(String setting) {
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
            mAugmentedAutofillState.injectAugmentedAutofillInfo(options, userId, packageName);
            injectDisableAppInfo(options, userId, packageName);
            return options;
        }

        @Override
        public boolean isAugmentedAutofillServiceForUser(int callingUid, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.isAugmentedAutofillServiceForUserLocked(callingUid);
                }
            }
            return false;
        }

        private void injectDisableAppInfo(@NonNull AutofillOptions options, int userId,
                String packageName) {
            options.appDisabledExpiration =
                    mDisabledInfoCache.getAppDisabledExpiration(userId, packageName);
            options.disabledActivities =
                    mDisabledInfoCache.getAppDisabledActivities(userId, packageName);
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
     * Stores autofill disable information, i.e. {@link AutofillDisabledInfo},  keyed by user id.
     * The information is cleaned up when the service is removed.
     */
    static final class DisabledInfoCache {

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final SparseArray<AutofillDisabledInfo> mCache = new SparseArray<>();

        void remove(@UserIdInt int userId) {
            synchronized (mLock) {
                mCache.remove(userId);
            }
        }

        void addDisabledAppLocked(@UserIdInt int userId, @NonNull String packageName,
                long expiration) {
            Objects.requireNonNull(packageName);
            synchronized (mLock) {
                AutofillDisabledInfo info =
                        getOrCreateAutofillDisabledInfoByUserIdLocked(userId);
                info.putDisableAppsLocked(packageName, expiration);
            }
        }

        void addDisabledActivityLocked(@UserIdInt int userId, @NonNull ComponentName componentName,
                long expiration) {
            Objects.requireNonNull(componentName);
            synchronized (mLock) {
                AutofillDisabledInfo info =
                        getOrCreateAutofillDisabledInfoByUserIdLocked(userId);
                info.putDisableActivityLocked(componentName, expiration);
            }
        }

        boolean isAutofillDisabledLocked(@UserIdInt int userId,
                @NonNull ComponentName componentName) {
            Objects.requireNonNull(componentName);
            final boolean disabled;
            synchronized (mLock) {
                final AutofillDisabledInfo info = mCache.get(userId);
                disabled = info != null ? info.isAutofillDisabledLocked(componentName) : false;
            }
            return disabled;
        }

        long getAppDisabledExpiration(@UserIdInt int userId, @NonNull String packageName) {
            Objects.requireNonNull(packageName);
            final Long expiration;
            synchronized (mLock) {
                final AutofillDisabledInfo info = mCache.get(userId);
                expiration = info != null ? info.getAppDisabledExpirationLocked(packageName) : 0;
            }
            return expiration;
        }

        @Nullable
        ArrayMap<String, Long> getAppDisabledActivities(@UserIdInt int userId,
                @NonNull String packageName) {
            Objects.requireNonNull(packageName);
            final ArrayMap<String, Long> disabledList;
            synchronized (mLock) {
                final AutofillDisabledInfo info = mCache.get(userId);
                disabledList =
                        info != null ? info.getAppDisabledActivitiesLocked(packageName) : null;
            }
            return disabledList;
        }

        void dump(@UserIdInt int userId, String prefix, PrintWriter pw) {
            synchronized (mLock) {
                final AutofillDisabledInfo info = mCache.get(userId);
                if (info != null) {
                    info.dumpLocked(prefix, pw);
                }
            }
        }

        @NonNull
        private AutofillDisabledInfo getOrCreateAutofillDisabledInfoByUserIdLocked(
                @UserIdInt int userId) {
            AutofillDisabledInfo info = mCache.get(userId);
            if (info == null) {
                info = new AutofillDisabledInfo();
                mCache.put(userId, info);
            }
            return info;
        }
    }

    /**
     * The autofill disable information.
     * <p>
     * This contains disable information set by the AutofillService, e.g. disabled application
     * expiration, disable activity expiration.
     */
    private static final class AutofillDisabledInfo {
        /**
         * Apps disabled by the service; key is package name, value is when they will be enabled
         * again.
         */
        private ArrayMap<String, Long> mDisabledApps;
        /**
         * Activities disabled by the service; key is component name, value is when they will be
         * enabled again.
         */
        private ArrayMap<ComponentName, Long> mDisabledActivities;

        void putDisableAppsLocked(@NonNull String packageName, long expiration) {
            if (mDisabledApps == null) {
                mDisabledApps = new ArrayMap<>(1);
            }
            mDisabledApps.put(packageName, expiration);
        }

        void putDisableActivityLocked(@NonNull ComponentName componentName, long expiration) {
            if (mDisabledActivities == null) {
                mDisabledActivities = new ArrayMap<>(1);
            }
            mDisabledActivities.put(componentName, expiration);
        }

        long getAppDisabledExpirationLocked(@NonNull String packageName) {
            if (mDisabledApps == null) {
                return 0;
            }
            final Long expiration = mDisabledApps.get(packageName);
            return expiration != null ? expiration : 0;
        }

        ArrayMap<String, Long> getAppDisabledActivitiesLocked(@NonNull String packageName) {
            if (mDisabledActivities != null) {
                final int size = mDisabledActivities.size();
                ArrayMap<String, Long> disabledList = null;
                for (int i = 0; i < size; i++) {
                    final ComponentName component = mDisabledActivities.keyAt(i);
                    if (packageName.equals(component.getPackageName())) {
                        if (disabledList == null) {
                            disabledList = new ArrayMap<>();
                        }
                        final long expiration = mDisabledActivities.valueAt(i);
                        disabledList.put(component.flattenToShortString(), expiration);
                    }
                }
                return disabledList;
            }
            return null;
        }

        boolean isAutofillDisabledLocked(@NonNull ComponentName componentName) {
            // Check activities first.
            long elapsedTime = 0;
            if (mDisabledActivities != null) {
                elapsedTime = SystemClock.elapsedRealtime();
                final Long expiration = mDisabledActivities.get(componentName);
                if (expiration != null) {
                    if (expiration >= elapsedTime) return true;
                    // Restriction expired - clean it up.
                    if (sVerbose) {
                        Slog.v(TAG, "Removing " + componentName.toShortString()
                                + " from disabled list");
                    }
                    mDisabledActivities.remove(componentName);
                }
            }

            // Then check apps.
            final String packageName = componentName.getPackageName();
            if (mDisabledApps == null) return false;

            final Long expiration = mDisabledApps.get(packageName);
            if (expiration == null) return false;

            if (elapsedTime == 0) {
                elapsedTime = SystemClock.elapsedRealtime();
            }

            if (expiration >= elapsedTime) return true;

            // Restriction expired - clean it up.
            if (sVerbose)  Slog.v(TAG, "Removing " + packageName + " from disabled list");
            mDisabledApps.remove(packageName);
            return false;
        }

        void dumpLocked(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("Disabled apps: ");
            if (mDisabledApps == null) {
                pw.println("N/A");
            } else {
                final int size = mDisabledApps.size();
                pw.println(size);
                final StringBuilder builder = new StringBuilder();
                final long now = SystemClock.elapsedRealtime();
                for (int i = 0; i < size; i++) {
                    final String packageName = mDisabledApps.keyAt(i);
                    final long expiration = mDisabledApps.valueAt(i);
                    builder.append(prefix).append(prefix)
                            .append(i).append(". ").append(packageName).append(": ");
                    TimeUtils.formatDuration((expiration - now), builder);
                    builder.append('\n');
                }
                pw.println(builder);
            }

            pw.print(prefix); pw.print("Disabled activities: ");
            if (mDisabledActivities == null) {
                pw.println("N/A");
            } else {
                final int size = mDisabledActivities.size();
                pw.println(size);
                final StringBuilder builder = new StringBuilder();
                final long now = SystemClock.elapsedRealtime();
                for (int i = 0; i < size; i++) {
                    final ComponentName component = mDisabledActivities.keyAt(i);
                    final long expiration = mDisabledActivities.valueAt(i);
                    builder.append(prefix).append(prefix)
                            .append(i).append(". ").append(component).append(": ");
                    TimeUtils.formatDuration((expiration - now), builder);
                    builder.append('\n');
                }
                pw.println(builder);
            }
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

    /**
     * Augmented autofill metadata associated with all services.
     *
     * <p>This object is defined here instead of on each {@link AutofillManagerServiceImpl} because
     * it cannot hold a lock on the main lock when
     * {@link AugmentedAutofillState#injectAugmentedAutofillInfo(AutofillOptions, int, String)}
     * is called by external services.
     */
    static final class AugmentedAutofillState extends GlobalWhitelistState {

        @GuardedBy("mGlobalWhitelistStateLock")
        private final SparseArray<String> mServicePackages = new SparseArray<>();
        @GuardedBy("mGlobalWhitelistStateLock")
        private final SparseBooleanArray mTemporaryServices = new SparseBooleanArray();

        private void setServiceInfo(@UserIdInt int userId, @Nullable String serviceName,
                boolean isTemporary) {
            synchronized (mGlobalWhitelistStateLock) {
                if (isTemporary) {
                    mTemporaryServices.put(userId, true);
                } else {
                    mTemporaryServices.delete(userId);
                }
                if (serviceName != null) {
                    final ComponentName componentName =
                            ComponentName.unflattenFromString(serviceName);
                    if (componentName == null) {
                        Slog.w(TAG, "setServiceInfo(): invalid name: " + serviceName);
                        mServicePackages.remove(userId);
                    } else {
                        mServicePackages.put(userId, componentName.getPackageName());
                    }
                } else {
                    mServicePackages.remove(userId);
                }
            }
        }

        public void injectAugmentedAutofillInfo(@NonNull AutofillOptions options,
                @UserIdInt int userId, @NonNull String packageName) {
            synchronized (mGlobalWhitelistStateLock) {
                if (mWhitelisterHelpers == null) return;
                final WhitelistHelper helper = mWhitelisterHelpers.get(userId);
                if (helper != null) {
                    options.augmentedAutofillEnabled = helper.isWhitelisted(packageName);
                    options.whitelistedActivitiesForAugmentedAutofill = helper
                            .getWhitelistedComponents(packageName);
                }
            }
        }

        @Override
        public boolean isWhitelisted(@UserIdInt int userId, @NonNull ComponentName componentName) {
            synchronized (mGlobalWhitelistStateLock) {
                if (!super.isWhitelisted(userId, componentName)) return false;

                if (Build.IS_USER && mTemporaryServices.get(userId)) {
                    final String packageName = componentName.getPackageName();
                    if (!packageName.equals(mServicePackages.get(userId))) {
                        Slog.w(TAG, "Ignoring package " + packageName + " for augmented autofill "
                                + "while using temporary service " + mServicePackages.get(userId));
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            super.dump(prefix, pw);

            synchronized (mGlobalWhitelistStateLock) {
                if (mServicePackages.size() > 0) {
                    pw.print(prefix); pw.print("Service packages: "); pw.println(mServicePackages);
                }
                if (mTemporaryServices.size() > 0) {
                    pw.print(prefix); pw.print("Temp services: "); pw.println(mTemporaryServices);
                }
            }
        }
    }

    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        @Override
        public void addClient(IAutoFillManagerClient client, ComponentName componentName,
                int userId, IResultReceiver receiver) {
            int flags = 0;
            synchronized (mLock) {
                final int enabledFlags = getServiceForUserLocked(userId).addClientLocked(client,
                        componentName);
                if (enabledFlags != 0) {
                    flags |= enabledFlags;
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
        public void startSession(IBinder activityToken, IBinder clientCallback,
                AutofillId autofillId, Rect bounds, AutofillValue value, int userId,
                boolean hasCallback, int flags, ComponentName clientActivity,
                boolean compatMode, IResultReceiver receiver) {

            requireNonNull(activityToken, "activityToken");
            requireNonNull(clientCallback, "clientCallback");
            requireNonNull(autofillId, "autofillId");
            requireNonNull(clientActivity, "clientActivity");
            final String packageName = requireNonNull(clientActivity.getPackageName());

            Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()), "userId");

            try {
                getContext().getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(packageName + " is not a valid package", e);
            }

            // TODO(b/113281366): add a callback method on AM to be notified when a task is finished
            // so we can clean up sessions kept alive
            final int taskId = mAm.getTaskIdForActivity(activityToken, false);
            final long result;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                result = service.startSessionLocked(activityToken, taskId, getCallingUid(),
                        clientCallback, autofillId, bounds, value, hasCallback, clientActivity,
                        compatMode, mAllowInstantService, flags);
            }
            final int sessionId = (int) result;
            final int resultFlags = (int) (result >> 32);
            if (resultFlags != 0) {
                send(receiver, sessionId, resultFlags);
            } else {
                send(receiver, sessionId);
            }
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
            Objects.requireNonNull(activityToken, "activityToken");
            Objects.requireNonNull(appCallback, "appCallback");

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
        public void finishSession(int sessionId, int userId,
                @AutofillCommitReason int commitReason) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.finishSessionLocked(sessionId, getCallingUid(), commitReason);
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
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                enabled = Objects.equals(packageName, service.getServicePackageName());
            }
            send(receiver, enabled);
        }

        @Override
        public void onPendingSaveUi(int operation, IBinder token) {
            Objects.requireNonNull(token, "token");
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
                    mAugmentedAutofillResolver.dumpShort(pw); pw.println();
                    pw.print("Max partitions per session: "); pw.println(sPartitionMaxCount);
                    pw.print("Max visible datasets: "); pw.println(sVisibleDatasetsMaxCount);
                    if (sFullScreenMode != null) {
                        pw.print("Overridden full-screen mode: "); pw.println(sFullScreenMode);
                    }
                    pw.println("User data constraints: "); UserData.dumpConstraints(prefix, pw);
                    mUi.dump(pw);
                    pw.print("Autofill Compat State: ");
                    mAutofillCompatState.dump(prefix, pw);
                    pw.print("from device config: ");
                    pw.println(getAllowedCompatModePackagesFromDeviceConfig());
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
                    pw.println("Augmented Autofill State: ");
                    mAugmentedAutofillState.dump(prefix, pw);
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
