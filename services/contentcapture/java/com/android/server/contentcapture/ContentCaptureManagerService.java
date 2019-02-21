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
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.contentcapture.ContentCaptureHelper;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.IContentCaptureManager;
import android.view.contentcapture.UserDataRemovalRequest;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;
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
     * Users disabled by {@link android.provider.Settings.Secure#CONTENT_CAPTURE_ENABLED}.
     */
    @GuardedBy("mLock")
    @Nullable
    private SparseBooleanArray mDisabledUsers;

    /**
     * Global kill-switch based on value defined by
     * {@link ContentCaptureManager#DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED}.
     */
    @GuardedBy("mLock")
    @Nullable
    private boolean mDisabledByDeviceConfig;

    // Device-config settings that are cached and passed back to apps
    public int mDevCfgLoggingLevel;
    public int mDevCfgMaxBufferSize;
    public int mDevCfgIdleFlushingFrequencyMs;
    public int mDevCfgTextChangeFlushingFrequencyMs;
    public int mDevCfgLogHistorySize;

    public ContentCaptureManagerService(@NonNull Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                com.android.internal.R.string.config_defaultContentCaptureService),
                UserManager.DISALLOW_CONTENT_CAPTURE);
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ActivityThread.currentApplication().getMainExecutor(),
                (namespace, key, value) -> onDeviceConfigChange(key, value));
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

        // Sets which services are disabled
        final UserManager um = getContext().getSystemService(UserManager.class);
        final List<UserInfo> users = um.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            final boolean disabled = mDisabledByDeviceConfig || isDisabledBySettings(userId);
            if (disabled) {
                Slog.i(mTag, "user " + userId + " disabled by settings or device config");
                if (mDisabledUsers == null) {
                    mDisabledUsers = new SparseBooleanArray(1);
                }
                mDisabledUsers.put(userId, true);
            }
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
                setContentCaptureFeatureEnabledFromSettings(userId);
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
        return mDisabledUsers != null && mDisabledUsers.get(userId);
    }

    private void setContentCaptureFeatureEnabledFromSettings(@UserIdInt int userId) {
        setContentCaptureFeatureEnabledForUser(userId, !isDisabledBySettings(userId));
    }

    private boolean isDisabledBySettings(@UserIdInt int userId) {
        final String property = Settings.Secure.CONTENT_CAPTURE_ENABLED;
        final String value = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                property, userId);
        if (value == null) {
            if (verbose) {
                Slog.v(mTag, "isDisabledBySettings(): assuming false as '" + property
                        + "' is not set");
            }
            return false;
        }

        try {
            return !Boolean.valueOf(value);
        } catch (Exception e) {
            Slog.w(mTag, "Invalid value for property " + property + ": " + value);
        }
        return false;
    }

    private void onDeviceConfigChange(@NonNull String key, @Nullable String value) {
        switch (key) {
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED:
                setDisabledByDeviceConfig(value);
                return;
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL:
                setLoggingLevelFromDeviceConfig();
                return;
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE:
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY:
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE:
            case ContentCaptureManager.DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY:
                setFineTuneParamsFromDeviceConfig();
                return;
            default:
                Slog.i(mTag, "Ignoring change on " + key);
        }
    }

    private void setFineTuneParamsFromDeviceConfig() {
        mDevCfgMaxBufferSize = ContentCaptureHelper.getIntDeviceConfigProperty(
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE,
                ContentCaptureManager.DEFAULT_MAX_BUFFER_SIZE);
        mDevCfgIdleFlushingFrequencyMs = ContentCaptureHelper.getIntDeviceConfigProperty(
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY,
                ContentCaptureManager.DEFAULT_IDLE_FLUSHING_FREQUENCY_MS);
        mDevCfgTextChangeFlushingFrequencyMs = ContentCaptureHelper.getIntDeviceConfigProperty(
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY,
                ContentCaptureManager.DEFAULT_TEXT_CHANGE_FLUSHING_FREQUENCY_MS);
        mDevCfgLogHistorySize = ContentCaptureHelper.getIntDeviceConfigProperty(
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE, 20);
        if (verbose) {
            Slog.v(mTag, "setFineTuneParamsFromDeviceConfig(): bufferSize=" + mDevCfgMaxBufferSize
                    + ", idleFlush=" + mDevCfgIdleFlushingFrequencyMs
                    + ", textFluxh=" + mDevCfgTextChangeFlushingFrequencyMs
                    + ", logHistory=" + mDevCfgLogHistorySize);
        }
    }

    private void setLoggingLevelFromDeviceConfig() {
        mDevCfgLoggingLevel = ContentCaptureHelper.getIntDeviceConfigProperty(
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

    private void setContentCaptureFeatureEnabledForUser(@UserIdInt int userId, boolean enabled) {
        synchronized (mLock) {
            if (mDisabledUsers == null) {
                mDisabledUsers = new SparseBooleanArray();
            }
            final boolean alreadyEnabled = !mDisabledUsers.get(userId);
            if (!(enabled ^ alreadyEnabled)) {
                if (debug) {
                    Slog.d(mTag, "setContentCaptureFeatureEnabledForUser(): already " + enabled);
                }
                return;
            }
            if (enabled) {
                Slog.i(mTag, "setContentCaptureFeatureEnabled(): enabling service for user "
                        + userId);
                mDisabledUsers.delete(userId);
            } else {
                Slog.i(mTag, "setContentCaptureFeatureEnabled(): disabling service for user "
                        + userId);
                mDisabledUsers.put(userId, true);
            }
            updateCachedServiceLocked(userId, !enabled);
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
    private boolean assertCalledByServiceLocked(@NonNull String methodName, @UserIdInt int userId,
            int callingUid, @NonNull IResultReceiver result) {
        final boolean isService = isCalledByServiceLocked(methodName, userId, callingUid);
        if (isService) return true;

        try {
            result.send(ContentCaptureManager.RESULT_CODE_NOT_SERVICE,
                    /* resultData= */ null);
        } catch (RemoteException e) {
            Slog.w(mTag, "Unable to send isContentCaptureFeatureEnabled(): " + e);
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean isCalledByServiceLocked(@NonNull String methodName, @UserIdInt int userId,
            int callingUid) {

        final String serviceName = mServiceNameResolver.getServiceName(userId);
        if (serviceName == null) {
            Slog.e(mTag, methodName + ": called by UID " + callingUid
                    + ", but there's no service set for user " + userId);
            return false;
        }

        final ComponentName serviceComponent  = ComponentName.unflattenFromString(serviceName);
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

    @Override // from AbstractMasterSystemService
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);

        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("Disabled users: "); pw.println(mDisabledUsers);
        pw.print(prefix); pw.println("DeviceConfig Settings: ");
        pw.print(prefix2); pw.print("disabled: "); pw.println(mDisabledByDeviceConfig);
        pw.print(prefix2); pw.print("loggingLevel: "); pw.println(mDevCfgLoggingLevel);
        pw.print(prefix2); pw.print("maxBufferSize: "); pw.println(mDevCfgMaxBufferSize);
        pw.print(prefix2); pw.print("idleFlushingFrequencyMs: ");
        pw.println(mDevCfgIdleFlushingFrequencyMs);
        pw.print(prefix2); pw.print("textChangeFlushingFrequencyMs: ");
        pw.println(mDevCfgTextChangeFlushingFrequencyMs);
        pw.print(prefix2); pw.print("logHistorySize: "); pw.println(mDevCfgLogHistorySize);
    }

    final class ContentCaptureManagerServiceStub extends IContentCaptureManager.Stub {

        @Override
        public void startSession(@NonNull IBinder activityToken,
                @NonNull ComponentName componentName, @NonNull String sessionId, int flags,
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
        public void finishSession(@NonNull String sessionId) {
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
                result.send(/* resultCode= */ 0,
                        SyncResultReceiver.bundleFor(connectedServiceComponentName));
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send service component name: " + e);
            }
        }

        @Override
        public void removeUserData(@NonNull UserDataRemovalRequest request) {
            Preconditions.checkNotNull(request);
            final int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                final ContentCapturePerUserService service = getServiceForUserLocked(userId);
                service.removeUserDataLocked(request);
            }
        }

        @Override
        public void isContentCaptureFeatureEnabled(@NonNull IResultReceiver result) {
            final int userId = UserHandle.getCallingUserId();
            boolean enabled;
            synchronized (mLock) {
                final boolean isService = assertCalledByServiceLocked(
                        "isContentCaptureFeatureEnabled()", userId, Binder.getCallingUid(), result);
                if (!isService) return;

                enabled = !mDisabledByDeviceConfig && !isDisabledBySettingsLocked(userId);
            }
            try {
                result.send(enabled ? ContentCaptureManager.RESULT_CODE_TRUE
                        : ContentCaptureManager.RESULT_CODE_FALSE, /* resultData= */null);
            } catch (RemoteException e) {
                Slog.w(mTag, "Unable to send isContentCaptureFeatureEnabled(): " + e);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), mTag, pw)) return;

            boolean showHistory = true;
            if (args != null) {
                for (String arg : args) {
                    switch(arg) {
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
        public ContentCaptureOptions getOptionsForPackage(int userId, String packageName) {
            synchronized (mLock) {
                final ContentCapturePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getOptionsForPackageLocked(packageName);
                }
            }
            return null;
        }
    }
}
