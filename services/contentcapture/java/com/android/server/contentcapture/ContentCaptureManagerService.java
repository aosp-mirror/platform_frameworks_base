/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.contentcapture;

import static android.Manifest.permission.MANAGE_CONTENT_CAPTURE;
import static android.content.Context.CONTENT_CAPTURE_MANAGER_SERVICE;
import static android.view.contentcapture.ContentCaptureHelper.toList;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_FALSE;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_OK;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_SECURITY_EXCEPTION;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_TRUE;

import static com.android.internal.util.SyncResultReceiver.bundleFor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.service.contentcapture.ActivityEvent.ActivityEventType;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.contentcapture.ContentCaptureCondition;
import android.view.contentcapture.ContentCaptureHelper;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.DataRemovalRequest;
import android.view.contentcapture.IContentCaptureManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.GlobalWhitelistState;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A service used to observe the contents of the screen.
 *
 * <p>The data collected by this service can be analyzed on-device and combined
 * with other sources to provide contextual data in other areas of the system
 * such as Autofill.
 */
public final class ContentCaptureManagerService extends
        AbstractMasterSystemService<ContentCaptureManagerService, ContentCapturePerUserService> {

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private final LocalService mLocalService = new LocalService();

    @Nullable
    final LocalLog mRequestsHistory;

    @GuardedBy("mLock")
    private ActivityManagerInternal mAm;

    /**
     * Users disabled by {@link android.provider.Settings.Secure#CONTENT_CAPTURE_ENABLED}
     */
    @GuardedBy("mLock")
    @Nullable
    private SparseBooleanArray mDisabledBySettings;

    /**
     * Global kill-switch based on value defined by
     * {@link ContentCaptureManager#DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED}.
     */
    @GuardedBy("mLock")
    @Nullable
    private boolean mDisabledByDeviceConfig;

    // Device-config settings that are cached and passed back to apps
    @GuardedBy("mLock") int mDevCfgLoggingLevel;
    @GuardedBy("mLock") int mDevCfgMaxBufferSize;
    @GuardedBy("mLock") int mDevCfgIdleFlushingFrequencyMs;
    @GuardedBy("mLock") int mDevCfgTextChangeFlushingFrequencyMs;
    @GuardedBy("mLock") int mDevCfgLogHistorySize;
    @GuardedBy("mLock") int mDevCfgIdleUnbindTimeoutMs;

    final GlobalContentCaptureOptions mGlobalContentCaptureOptions =
            new GlobalContentCaptureOptions();

    public ContentCaptureManagerService(@NonNull Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                com.android.internal.R.string.config_defaultContentCaptureService),
                UserManager.DISALLOW_CONTENT_CAPTURE,
                /*packageUpdatePolicy=*/ PACKAGE_UPDATE_POLICY_NO_REFRESH);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ActivityThread.currentApplication().getMainExecutor(),
                (properties) -> onDeviceConfigChange(properties));
        setDeviceConfigProperties();

        if (mDevCfgLogHistorySize > 0) {
            if (debug) Slog.d(mTag, "log history size: " + mDevCfgLogHistorySize);
            mRequestsHistory = new LocalLog(mDevCfgLogHistorySize);
        } else {
            if (debug) {
                Slog.d(mTag, "disabled log history because size is " + mDevCfgLogHistorySize);
            }
            mRequestsHistory = null;
        }

        final UserManager um = getContext().getSystemService(UserManager.class);
        final List<UserInfo> users = um.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            final boolean disabled = !isEnabledBySettings(userId);
            // Sets which services are disabled by settings
            if (disabled) {
                Slog.i(mTag, "user " + userId + " disabled by settings");
                if (mDisabledBySettings == null) {
                    mDisabledBySettings = new SparseBooleanArray(1);
                }
                mDisabledBySettings.put(userId, true);
            }
            // Sets the global options for the service.
            mGlobalContentCaptureOptions.setServiceInfo(userId,
                    mServiceNameResolver.getServiceName(userId),
                    mServiceNameResolver.isTemporary(userId));
        }
    }

    @Override // from AbstractMasterSystemService
    protected ContentCapturePerUserService newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new ContentCapturePerUserService(this, mLock, disabled, resolvedUserId);
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(CONTENT_CAPTURE_MANAGER_SERVICE,
                new ContentCaptureManagerServiceStub());
        publishLocalService(ContentCaptureManagerInternal.class, mLocalService);
    }

    @Override // from AbstractMasterSystemService
    protected void onServiceRemoved(@NonNull ContentCapturePerUserService service,
            @UserIdInt int userId) {
        service.destroyLocked();
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatingLocked(int userId) {
        final ContentCapturePerUserService service = getServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatingLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final ContentCapturePerUserService service = getServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServiceNameChanged(@UserIdInt int userId, @NonNull String serviceName,
            boolean isTemporary) {
        mGlobalContentCaptureOptions.setServiceInfo(userId, serviceName, isTemporary);

        super.onServiceNameChanged(userId, serviceName, isTemporary);
    }

    @Override // from AbstractMasterSystemService
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_CONTENT_CAPTURE, mTag);
    }

    @Override // from AbstractMasterSystemService
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    @Override // from AbstractMasterSystemService
    protected void registerForExtraSettingsChanges(@NonNull ContentResolver resolver,
            @NonNull ContentObserver observer) {
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.CONTENT_CAPTURE_ENABLED), false, observer,
                UserHandle.USER_ALL);
    }

    @Override // from AbstractMasterSystemService
    protected void onSettingsChanged(@UserIdInt int userId, @NonNull String property) {
        switch (property) {
            case Settings.Secure.CONTENT_CAPTURE_ENABLED:
                setContentCaptureFeatureEnabledBySettingsForUser(userId,
                        isEnabledBySettings(userId));
                return;
            default:
                Slog.w(mTag, "Unexpected property (" + property + "); updating cache instead");
        }
    }

    @Override // from AbstractMasterSystemService
    protected boolean isDisabledLocked(@UserIdInt int userId) {
        return mDisabledByDeviceConfig || isDisabledBySettingsLocked(userId)
                || super.isDisabledLocked(userId);
    }

    private boolean isDisabledBySettingsLocked(@UserIdInt int userId) {
        return mDisabledBySettings != null && mDisabledBySettings.get(userId);
    }

    private boolean isEnabledBySettings(@UserIdInt int userId) {
        final boolean enabled = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.CONTENT_CAPTURE_ENABLED, 1, userId) == 1 ? true : false;
        return enabled;
    }

    private void onDeviceConfigChange(@NonNull Properties properties) {
        for (String key : properties.getKeyset()) {
            switch (key) {
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED:
                    setDisabledByDeviceConfig(properties.getString(key, null));
                    return;
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL:
                    setLoggingLevelFromDeviceConfig();
                    return;
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE:
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY:
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE:
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY:
                case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_UNBIND_TIMEOUT:
                    setFineTuneParamsFromDeviceConfig();
                    return;
                default:
                    Slog.i(mTag, "Ignoring change on " + key);
            }
        }
    }

    private void setFineTuneParamsFromDeviceConfig() {
        synchronized (mLock) {
            mDevCfgMaxBufferSize = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                    ContentCaptureManager.DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE,
                    ContentCaptureManager.DEFAULT_MAX_BUFFER_SIZE);
            mDevCfgIdleFlushingFrequencyMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                    ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY,
                    ContentCaptureManager.DEFAULT_IDLE_FLUSHING_FREQUENCY_MS);
            mDevCfgTextChangeFlushingFrequencyMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                    ContentCaptureManager.DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY,
                    ContentCaptureManager.DEFAULT_TEXT_CHANGE_FLUSHING_FREQUENCY_MS);
            mDevCfgLogHistorySize = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                    ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE, 20);
            mDevCfgIdleUnbindTimeoutMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                    ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_UNBIND_TIMEOUT,
                    (int) AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS);
            if (verbose) {
                Slog.v(mTag, "setFineTuneParamsFromDeviceConfig(): "
                        + "bufferSize=" + mDevCfgMaxBufferSize
                        + ", idleFlush=" + mDevCfgIdleFlushingFrequencyMs
                        + ", textFluxh=" + mDevCfgTextChangeFlushingFrequencyMs
                        + ", logHistory=" + mDevCfgLogHistorySize
                        + ", idleUnbindTimeoutMs=" + mDevCfgIdleUnbindTimeoutMs);
            }
        }
    }

    private void setLoggingLevelFromDeviceConfig() {
        mDevCfgLoggingLevel = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL,
                ContentCaptureHelper.getDefaultLoggingLevel());
        ContentCaptureHelper.setLoggingLevel(mDevCfgLoggingLevel);
        verbose = ContentCaptureHelper.sVerbose;
        debug = ContentCaptureHelper.sDebug;
        if (verbose) {
            Slog.v(mTag, "setLoggingLevelFromDeviceConfig(): level=" + mDevCfgLoggingLevel
                    + ", debug=" + debug + ", verbose=" + verbose);
        }
    }

    private void setDeviceConfigProperties() {
        setLoggingLevelFromDeviceConfig();
        setFineTuneParamsFromDeviceConfig();
        final String enabled = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED);
        setDisabledByDeviceConfig(enabled);
    }

    private void setDisabledByDeviceConfig(@Nullable String explicitlyEnabled) {
        if (verbose) {
            Slog.v(mTag, "setDisabledByDeviceConfig(): explicitlyEnabled=" + explicitlyEnabled);
        }
        final UserManager um = getContext().getSystemService(UserManager.class);
        final List<UserInfo> users = um.getUsers();

        final boolean newDisabledValue;

        if (explicitlyEnabled != null && explicitlyEnabled.equalsIgnoreCase("false")) {
            newDisabledValue = true;
        } else {
            newDisabledValue = false;
        }

        synchronized (mLock) {
            if (mDisabledByDeviceConfig == newDisabledValue) {
                if (verbose) {
                    Slog.v(mTag, "setDisabledByDeviceConfig(): already " + newDisabledValue);
                }
                return;
            }
            mDisabledByDeviceConfig = newDisabledValue;

            Slog.i(mTag, "setDisabledByDeviceConfig(): set to " + mDisabledByDeviceConfig);
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                boolean disabled = mDisabledByDeviceConfig || isDisabledBySettingsLocked(userId);
                Slog.i(mTag, "setDisabledByDeviceConfig(): updating service for user "
                        + userId + " to " + (disabled ? "'disabled'" : "'enabled'"));
                updateCachedServiceLocked(userId, disabled);
            }
        }
    }

    private void setContentCaptureFeatureEnabledBySettingsForUser(@UserIdInt int userId,
            boolean enabled) {
        synchronized (mLock) {
            if (mDisabledBySettings == null) {
                mDisabledBySettings = new SparseBooleanArray();
            }
            final boolean alreadyEnabled = !mDisabledBySettings.get(userId);
            if (!(enabled ^ alreadyEnabled)) {
                if (debug) {
                    Slog.d(mTag, "setContentCaptureFeatureEnabledForUser(): already " + enabled);
                }
                return;
            }
            if (enabled) {
                Slog.i(mTag, "setContentCaptureFeatureEnabled(): enabling service for user "
                        + userId);
                mDisabledBySettings.delete(userId);
            } else {
                Slog.i(mTag, "setContentCaptureFeatureEnabled(): disabling service for user "
                        + userId);
                mDisabledBySettings.put(userId, true);
            }
            final boolean disabled = !enabled || mDisabledByDeviceConfig;
            updateCachedServiceLocked(userId, disabled);
        }
    }

    // Called by Shell command.
    void destroySessions(@UserIdInt int userId, @NonNull IResultReceiver receiver) {
        Slog.i(mTag, "destroySessions() for userId " + userId);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
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
        Slog.i(mTag, "listSessions() for userId " + userId);
        enforceCallingPermissionForManagement();

        final Bundle resultData = new Bundle();
        final ArrayList<String> sessions = new ArrayList<>();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
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

    private ActivityManagerInternal getAmInternal() {
        synchronized (mLock) {
            if (mAm == null) {
                mAm = LocalServices.getService(ActivityManagerInternal.class);
            }
        }
        return mAm;
    }

    @GuardedBy("mLock")
    private void assertCalledByServiceLocked(@NonNull String methodName) {
        if (!isCalledByServiceLocked(methodName)) {
            throw new SecurityException("caller is not user's ContentCapture service");
        }
    }

    @GuardedBy("mLock")
    private boolean isCalledByServiceLocked(@NonNull String methodName) {
        final int userId = UserHandle.getCallingUserId();
        final int callingUid = Binder.getCallingUid();
        final String serviceName = mServiceNameResolver.getServiceName(userId);
        if (serviceName == null) {
            Slog.e(mTag, methodName + ": called by UID " + callingUid
                    + ", but there's no service set for user " + userId);
            return false;
        }

        final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
        if (serviceComponent == null) {
            Slog.w(mTag, methodName + ": invalid service name: " + serviceName);
            return false;
        }

        final String servicePackageName = serviceComponent.getPackageName();

        final PackageManager pm = getContext().getPackageManager();
        final int serviceUid;
        try {
            serviceUid = pm.getPackageUidAsUser(servicePackageName, UserHandle.getCallingUserId());
        } catch (NameNotFoundException e) {
            Slog.w(mTag, methodName + ": could not verify UID for " + serviceName);
            return false;
        }
        if (callingUid != serviceUid) {
            Slog.e(mTag, methodName + ": called by UID " + callingUid + ", but service UID is "
                    + serviceUid);
            return false;
        }

        return true;
    }

    /**
     * Executes the given {@code runnable} and if it throws a {@link SecurityException},
     * send it back to the receiver.
     *
     * @return whether the exception was thrown or not.
     */
    private boolean throwsSecurityException(@NonNull IResultReceiver result,
            @NonNull Runnable runable) {
        try {
            runable.run();
            return false;
        } catch (SecurityException e) {
            try {
                result.send(RESULT_CODE_SECURITY_EXCEPTION, bundleFor(e.getMessage()));
            } catch (RemoteException e2) {
                Slog.w(mTag, "Unable to send security exception (" + e + "): ", e2);
            }
        }
        return true;
    }

    @Override // from AbstractMasterSystemService
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);

        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("Users disabled by Settings: "); pw.println(mDisabledBySettings);
        pw.print(prefix); pw.println("DeviceConfig Settings: ");
        pw.print(prefix2); pw.print("disabled: "); pw.println(mDisabledByDeviceConfig);
        pw.print(prefix2); pw.print("loggingLevel: "); pw.println(mDevCfgLoggingLevel);
        pw.print(prefix2); pw.print("maxBufferSize: "); pw.println(mDevCfgMaxBufferSize);
        pw.print(prefix2); pw.print("idleFlushingFrequencyMs: ");
        pw.println(mDevCfgIdleFlushingFrequencyMs);
        pw.print(prefix2); pw.print("textChangeFlushingFrequencyMs: ");
        pw.println(mDevCfgTextChangeFlushingFrequencyMs);
        pw.print(prefix2); pw.print("logHistorySize: "); pw.println(mDevCfgLogHistorySize);
        pw.print(prefix2); pw.print("idleUnbindTimeoutMs: ");
        pw.println(mDevCfgIdleUnbindTimeoutMs);
        pw.print(prefix); pw.println("Global Options:");
        mGlobalContentCaptureOptions.dump(prefix2, pw);
    }

    final class ContentCaptureManagerServiceStub extends IContentCaptureManager.Stub {

        @Override
        public void startSession(@NonNull IBinder activityToken,
                @NonNull ComponentName componentName, int sessionId, int flags,
                @NonNull IResultReceiver result) {
            Preconditions.checkNotNull(activityToken);
            Preconditions.checkNotNull(sessionId);
            final int userId = UserHandle.getCallingUserId();

            final ActivityPresentationInfo activityPresentationInfo = getAmInternal()
                    .getActivityPresentationInfo(activityToken);

            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                service.startSessionLocked(activityToken, activityPresentationInfo, sessionId,
                        Binder.getCallingUid(), flags, result);
            }
        }

        @Override
        public void finishSession(int sessionId) {
            Preconditions.checkNotNull(sessionId);
            final int userId = UserHandle.getCallingUserId();

            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                service.finishSessionLocked(sessionId);
            }
        }

        @Override
        public void getServiceComponentName(@NonNull IResultReceiver result) {
            final int userId = UserHandle.getCallingUserId();
            ComponentName connectedServiceComponentName;
            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                connectedServiceComponentName = service.getServiceComponentName();
            }
            try {
                result.send(RESULT_CODE_OK, bundleFor(connectedServiceComponentName));
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send service component name: " + e);
            }
        }

        @Override
        public void removeData(@NonNull DataRemovalRequest request) {
            Preconditions.checkNotNull(request);
            assertCalledByPackageOwner(request.getPackageName());

            final int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                service.removeDataLocked(request);
            }
        }

        @Override
        public void isContentCaptureFeatureEnabled(@NonNull IResultReceiver result) {
            boolean enabled;
            synchronized (mLock) {
                if (throwsSecurityException(result,
                        () -> assertCalledByServiceLocked("isContentCaptureFeatureEnabled()"))) {
                    return;
                }

                final int userId = UserHandle.getCallingUserId();
                enabled = !mDisabledByDeviceConfig && !isDisabledBySettingsLocked(userId);
            }
            try {
                result.send(enabled ? RESULT_CODE_TRUE : RESULT_CODE_FALSE, /* resultData= */null);
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send isContentCaptureFeatureEnabled(): " + e);
            }
        }

        @Override
        public void getServiceSettingsActivity(@NonNull IResultReceiver result) {
            if (throwsSecurityException(result, () -> enforceCallingPermissionForManagement())) {
                return;
            }

            final int userId = UserHandle.getCallingUserId();
            final ComponentName componentName;
            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                if (service == null) return;
                componentName = service.getServiceSettingsActivityLocked();
            }
            try {
                result.send(RESULT_CODE_OK, bundleFor(componentName));
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send getServiceSettingsIntent(): " + e);
            }
        }

        @Override
        public void getContentCaptureConditions(@NonNull String packageName,
                @NonNull IResultReceiver result) {
            if (throwsSecurityException(result, () -> assertCalledByPackageOwner(packageName))) {
                return;
            }

            final int userId = UserHandle.getCallingUserId();
            final ArrayList<ContentCaptureCondition> conditions;
            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                conditions = service == null ? null
                        : toList(service.getContentCaptureConditionsLocked(packageName));
            }
            try {
                result.send(RESULT_CODE_OK, bundleFor(conditions));
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send getServiceComponentName(): " + e);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), mTag, pw)) return;

            boolean showHistory = true;
            if (args != null) {
                for (String arg : args) {
                    switch (arg) {
                        case "--no-history":
                            showHistory = false;
                            break;
                        case "--help":
                            pw.println("Usage: dumpsys content_capture [--no-history]");
                            return;
                        default:
                            Slog.w(mTag, "Ignoring invalid dump arg: " + arg);
                    }
                }
            }

            synchronized (mLock) {
                dumpLocked("", pw);
            }
            pw.print("Requests history: ");
            if (mRequestsHistory == null) {
                pw.println("disabled by device config");
            } else if (showHistory) {
                pw.println();
                mRequestsHistory.reverseDump(fd, pw, args);
                pw.println();
            } else {
                pw.println();
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            new ContentCaptureManagerServiceShellCommand(ContentCaptureManagerService.this).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class LocalService extends ContentCaptureManagerInternal {

        @Override
        public boolean isContentCaptureServiceForUser(int uid, @UserIdInt int userId) {
            synchronized (mLock) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.isContentCaptureServiceForUserLocked(uid);
                }
            }
            return false;
        }

        @Override
        public boolean sendActivityAssistData(@UserIdInt int userId, @NonNull IBinder activityToken,
                @NonNull Bundle data) {
            synchronized (mLock) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.sendActivityAssistDataLocked(activityToken, data);
                }
            }
            return false;
        }

        @Override
        public ContentCaptureOptions getOptionsForPackage(int userId, @NonNull String packageName) {
            return mGlobalContentCaptureOptions.getOptions(userId, packageName);
        }

        @Override
        public void notifyActivityEvent(int userId, @NonNull ComponentName activityComponent,
                @ActivityEventType int eventType) {
            synchronized (mLock) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.onActivityEventLocked(activityComponent, eventType);
                }
            }
        }
    }

    /**
     * Content capture options associated with all services.
     *
     * <p>This object is defined here instead of on each {@link ContentCapturePerUserService}
     * because it cannot hold a lock on the main lock when
     * {@link GlobalContentCaptureOptions#getOptions(int, String)} is called by external services.
     */
    final class GlobalContentCaptureOptions extends GlobalWhitelistState {

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
                        Slog.w(mTag, "setServiceInfo(): invalid name: " + serviceName);
                        mServicePackages.remove(userId);
                    } else {
                        mServicePackages.put(userId, componentName.getPackageName());
                    }
                } else {
                    mServicePackages.remove(userId);
                }
            }
        }

        @Nullable
        @GuardedBy("mGlobalWhitelistStateLock")
        public ContentCaptureOptions getOptions(@UserIdInt int userId,
                @NonNull String packageName) {
            boolean packageWhitelisted;
            ArraySet<ComponentName> whitelistedComponents = null;
            synchronized (mGlobalWhitelistStateLock) {
                packageWhitelisted = isWhitelisted(userId, packageName);
                if (!packageWhitelisted) {
                    // Full package is not whitelisted: check individual components first
                    whitelistedComponents = getWhitelistedComponents(userId, packageName);
                    if (whitelistedComponents == null
                            && packageName.equals(mServicePackages.get(userId))) {
                        // No components whitelisted either, but let it go because it's the
                        // service's own package
                        if (verbose) Slog.v(mTag, "getOptionsForPackage() lite for " + packageName);
                        return new ContentCaptureOptions(mDevCfgLoggingLevel);
                    }
                }
            } // synchronized

            // Restrict what temporary services can whitelist
            if (Build.IS_USER && mServiceNameResolver.isTemporary(userId)) {
                if (!packageName.equals(mServicePackages.get(userId))) {
                    Slog.w(mTag, "Ignoring package " + packageName + " while using temporary "
                            + "service " + mServicePackages.get(userId));
                    return null;
                }
            }

            if (!packageWhitelisted && whitelistedComponents == null) {
                // No can do!
                if (verbose) {
                    Slog.v(mTag, "getOptionsForPackage(" + packageName + "): not whitelisted");
                }
                return null;
            }

            final ContentCaptureOptions options = new ContentCaptureOptions(mDevCfgLoggingLevel,
                    mDevCfgMaxBufferSize, mDevCfgIdleFlushingFrequencyMs,
                    mDevCfgTextChangeFlushingFrequencyMs, mDevCfgLogHistorySize,
                    whitelistedComponents);
            if (verbose) Slog.v(mTag, "getOptionsForPackage(" + packageName + "): " + options);
            return options;
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
}
