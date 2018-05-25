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

import static com.android.server.autofill.Helper.bundleToString;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sPartitionMaxCount;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.service.autofill.FillEventHistory;
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
import java.util.List;
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

    public AutofillManagerService(Context context) {
        super(context);
        mContext = context;
        mUi = new AutoFillUI(ActivityThread.currentActivityThread().getSystemUiContext());

        final boolean debug = Build.IS_DEBUGGABLE;
        Slog.i(TAG, "Setting debug to " + debug);
        setDebugLocked(debug);

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
                if (disabled) {
                    Slog.i(TAG, "Disabling Autofill for user " + userId);
                }
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
                        Slog.d(TAG, "Autofill restriction did not change for user " + userId + ": "
                                + bundleToString(newRestrictions));
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
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);
    }

    @Override
    public void onStart() {
        publishBinderService(AUTOFILL_MANAGER_SERVICE, new AutoFillManagerServiceStub());
        publishLocalService(AutofillManagerInternal.class, new LocalService());
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
    @NonNull
    AutofillManagerServiceImpl getServiceForUserLocked(int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        AutofillManagerServiceImpl service = mServicesCache.get(resolvedUserId);
        if (service == null) {
            service = new AutofillManagerServiceImpl(mContext, mLock, mRequestsHistory,
                    mUiLatencyHistory, resolvedUserId, mUi, mDisabledUsers.get(resolvedUserId));
            mServicesCache.put(userId, service);
        }
        return service;
    }

    /**
     * Peeks the service instance for a user.
     *
     * @return service instance or {@code null} if not already present
     */
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

        boolean debug = false;
        boolean verbose = false;
        if (level == AutofillManager.FLAG_ADD_CLIENT_VERBOSE) {
            debug = verbose = true;
        } else if (level == AutofillManager.FLAG_ADD_CLIENT_DEBUG) {
            debug = true;
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
    public int getMaxPartitions() {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            return sPartitionMaxCount;
        }
    }

    // Called by Shell command.
    public void setMaxPartitions(int max) {
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        Slog.i(TAG, "setMaxPartitions(): " + max);
        synchronized (mLock) {
            sPartitionMaxCount = max;
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
    private void removeCachedServiceLocked(int userId) {
        final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            service.destroyLocked();
        }
    }

    /**
     * Updates a cached service for a given user.
     */
    private void updateCachedServiceLocked(int userId) {
        updateCachedServiceLocked(userId, mDisabledUsers.get(userId));
    }

    /**
     * Updates a cached service for a given user.
     */
    private void updateCachedServiceLocked(int userId, boolean disabled) {
        AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
        if (service != null) {
            service.destroySessionsLocked();
            service.updateLocked(disabled);
            if (!service.isEnabled()) {
                removeCachedServiceLocked(userId);
            }
        }
    }

    private final class LocalService extends AutofillManagerInternal {

        @Override
        public void onBackKeyPressed() {
            if (sDebug) Slog.d(TAG, "onBackKeyPressed()");
            mUi.hideAll(null);
        }
    }

    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        @Override
        public int addClient(IAutoFillManagerClient client, int userId) {
            synchronized (mLock) {
                int flags = 0;
                if (getServiceForUserLocked(userId).addClientLocked(client)) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_ENABLED;
                }
                if (sDebug) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_DEBUG;
                }
                if (sVerbose) {
                    flags |= AutofillManager.FLAG_ADD_CLIENT_VERBOSE;
                }
                return flags;
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
        public int startSession(IBinder activityToken, IBinder appCallback, AutofillId autofillId,
                Rect bounds, AutofillValue value, int userId, boolean hasCallback, int flags,
                ComponentName componentName) {

            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");
            autofillId = Preconditions.checkNotNull(autofillId, "autoFillId");
            componentName = Preconditions.checkNotNull(componentName, "componentName");
            final String packageName = Preconditions.checkNotNull(componentName.getPackageName());

            Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()), "userId");

            try {
                mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(componentName + " is not a valid package", e);
            }

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                return service.startSessionLocked(activityToken, getCallingUid(), appCallback,
                        autofillId, bounds, value, hasCallback, flags, componentName);
            }
        }

        @Override
        public FillEventHistory getFillEventHistory() throws RemoteException {
            UserHandle user = getCallingUserHandle();
            int uid = getCallingUid();

            synchronized (mLock) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(user.getIdentifier());
                if (service != null) {
                    return service.getFillEventHistory(uid);
                }
            }

            return null;
        }

        @Override
        public boolean restoreSession(int sessionId, IBinder activityToken, IBinder appCallback)
                throws RemoteException {
            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = mServicesCache.get(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    return service.restoreSession(sessionId, getCallingUid(), activityToken,
                            appCallback);
                }
            }

            return false;
        }

        @Override
        public void updateSession(int sessionId, AutofillId autoFillId, Rect bounds,
                AutofillValue value, int action, int flags, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.updateSessionLocked(sessionId, getCallingUid(), autoFillId, bounds,
                            value, action, flags);
                }
            }
        }

        @Override
        public int updateOrRestartSession(IBinder activityToken, IBinder appCallback,
                AutofillId autoFillId, Rect bounds, AutofillValue value, int userId,
                boolean hasCallback, int flags, ComponentName componentName, int sessionId,
                int action) {
            boolean restart = false;
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    restart = service.updateSessionLocked(sessionId, getCallingUid(), autoFillId,
                            bounds, value, action, flags);
                }
            }
            if (restart) {
                return startSession(activityToken, appCallback, autoFillId, bounds, value, userId,
                        hasCallback, flags, componentName);
            }

            // Nothing changed...
            return sessionId;
        }

        @Override
        public void finishSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.finishSessionLocked(sessionId, getCallingUid());
                }
            }
        }

        @Override
        public void cancelSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.cancelSessionLocked(sessionId, getCallingUid());
                }
            }
        }

        @Override
        public void disableOwnedAutofillServices(int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.disableOwnedAutofillServicesLocked(Binder.getCallingUid());
                }
            }
        }

        @Override
        public boolean isServiceSupported(int userId) {
            synchronized (mLock) {
                return !mDisabledUsers.get(userId);
            }
        }

        @Override
        public boolean isServiceEnabled(int userId, String packageName) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service == null) return false;
                return Objects.equals(packageName, service.getServicePackageName());
            }
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
            try {
                synchronized (mLock) {
                    oldDebug = sDebug;
                    setDebugLocked(true);
                    pw.print("Debug mode: "); pw.println(oldDebug);
                    pw.print("Verbose mode: "); pw.println(sVerbose);
                    pw.print("Disabled users: "); pw.println(mDisabledUsers);
                    pw.print("Max partitions per session: "); pw.println(sPartitionMaxCount);
                    final int size = mServicesCache.size();
                    pw.print("Cached services: ");
                    if (size == 0) {
                        pw.println("none");
                    } else {
                        pw.println(size);
                        for (int i = 0; i < size; i++) {
                            pw.print("\nService at index "); pw.println(i);
                            final AutofillManagerServiceImpl impl = mServicesCache.valueAt(i);
                            impl.dumpLocked("  ", pw);
                        }
                    }
                    mUi.dump(pw);
                }
                if (showHistory) {
                    pw.println("Requests history:");
                    mRequestsHistory.reverseDump(fd, pw, args);
                    pw.println("UI latency history:");
                    mUiLatencyHistory.reverseDump(fd, pw, args);
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
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (sVerbose) Slog.v(TAG, "onChange(): uri=" + uri + ", userId=" + userId);
            synchronized (mLock) {
                updateCachedServiceLocked(userId);
            }
        }
    }
}
