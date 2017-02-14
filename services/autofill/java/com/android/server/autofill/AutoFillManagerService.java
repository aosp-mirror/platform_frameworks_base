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
import static android.content.Context.AUTO_FILL_MANAGER_SERVICE;
import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;

import android.Manifest;
import android.annotation.NonNull;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;

import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.IResultReceiver;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point service for auto-fill management.
 *
 * <p>This service provides the {@link IAutoFillManager} implementation and keeps a list of
 * {@link AutoFillManagerServiceImpl} per user; the real work is done by
 * {@link AutoFillManagerServiceImpl} itself.
 */
// TODO(b/33197203): Handle removing of packages
public final class AutoFillManagerService extends SystemService {

    private static final String TAG = "AutoFillManagerService";

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private final Context mContext;
    private final AutoFillUI mUi;

    private final Object mLock = new Object();

    /**
     * Cache of {@link AutoFillManagerServiceImpl} per user id.
     * <p>
     * It has to be mapped by user id because the same current user could have simultaneous sessions
     * associated to different user profiles (for example, in a multi-window environment or when
     * device has work profiles).
     * <p>
     * Entries on this cache are added on demand and removed when:
     * <ol>
     *   <li>An auto-fill service app is removed.
     *   <li>The {@link android.provider.Settings.Secure#AUTO_FILL_SERVICE} for an user change.\
     * </ol>
     */
    // TODO(b/33197203): Update the above comment
    @GuardedBy("mLock")
    private SparseArray<AutoFillManagerServiceImpl> mServicesCache = new SparseArray<>();

    // TODO(b/33197203): set a different max (or disable it) on low-memory devices.
    private final LocalLog mRequestsHistory = new LocalLog(100);

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                final String reason = intent.getStringExtra("reason");
                if (DEBUG) Slog.d(TAG, "close system dialogs: " + reason);
                mUi.hideAll();
            }
        }
    };

    public AutoFillManagerService(Context context) {
        super(context);
        mContext = context;
        mUi = new AutoFillUI(mContext);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null,
                FgThread.getHandler());
    }

    @Override
    public void onStart() {
        publishBinderService(AUTO_FILL_MANAGER_SERVICE, new AutoFillManagerServiceStub());
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
    public void onStopUser(int userId) {
        synchronized (mLock) {
            removeCachedServiceLocked(userId);
        }
    }

    /**
     * Gets the service instance for an user.
     *
     * @return service instance.
     */
    @NonNull AutoFillManagerServiceImpl getServiceForUserLocked(int userId) {
        AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service == null) {
            service = new AutoFillManagerServiceImpl(mContext, mLock,
                    mRequestsHistory, userId, mUi);
            mServicesCache.put(userId, service);
        }
        return service;
    }

    // Called by Shell command.
    void requestSaveForUser(int userId) {
        Slog.i(TAG, "requestSaveForUser(): " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        final IBinder activityToken = getTopActivityForUser();
        if (activityToken != null) {
            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
                if (service == null) {
                    Log.w(TAG, "handleSaveForUser(): no cached service for userId " + userId);
                    return;
                }

                service.requestSaveForUserLocked(activityToken);
            }
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
                mServicesCache.get(userId).listSessionsLocked(sessions);
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

    /**
     * Removes a cached service for a given user.
     */
    private void removeCachedServiceLocked(int userId) {
        final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            service.destroyLocked();
        }
    }

    /**
     * Updates a cached service for a given user.
     */
    private void updateCachedServiceLocked(int userId) {
        AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service != null) {
            service.updateLocked();
        }
    }

    private IBinder getTopActivityForUser() {
        final List<IBinder> topActivities = LocalServices
                .getService(ActivityManagerInternal.class).getTopVisibleActivities();
        if (DEBUG) Slog.d(TAG, "Top activities (" + topActivities.size() + "): " + topActivities);
        if (topActivities.isEmpty()) {
            Slog.w(TAG, "Could not get top activity");
            return null;
        }
        return topActivities.get(0);
    }

    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        @Override
        public boolean addClient(IAutoFillManagerClient client, int userId) {
            synchronized (mLock) {
                return getServiceForUserLocked(userId).addClientLocked(client);
            }
        }

        @Override
        public void setAuthenticationResult(Bundle data, IBinder activityToken, int userId) {
            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.setAuthenticationResultLocked(data, activityToken);
            }
        }

        @Override
        public void startSession(IBinder activityToken, IBinder appCallback, AutoFillId autoFillId,
                Rect bounds, AutoFillValue value, int userId) {
            // TODO(b/33197203): make sure it's called by resumed / focused activity

            if (VERBOSE) {
                Slog.v(TAG, "startSession: autoFillId=" + autoFillId + ", bounds=" + bounds
                        + ", value=" + value);
            }

            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.startSessionLocked(activityToken, appCallback, autoFillId, bounds, value);
            }
        }

        @Override
        public void updateSession(IBinder activityToken, AutoFillId id, Rect bounds,
                AutoFillValue value, int flags, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "updateSession: flags=" + flags + ", autoFillId=" + id
                        + ", bounds=" + bounds + ", value=" + value);
            }

            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = mServicesCache.get(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    service.updateSessionLocked(activityToken, id, bounds, value, flags);
                }
            }
        }

        @Override
        public void finishSession(IBinder activityToken, int userId) {
            if (VERBOSE) Slog.v(TAG, "finishSession(): " + activityToken);

            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = mServicesCache.get(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    service.finishSessionLocked(activityToken);
                }
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingPermission(
                    Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump autofill from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }
            synchronized (mLock) {
                final int size = mServicesCache.size();
                pw.print("Cached services: ");
                if (size == 0) {
                    pw.println("none");
                } else {
                    pw.println(size);
                    for (int i = 0; i < size; i++) {
                        pw.print("\nService at index "); pw.println(i);
                        final AutoFillManagerServiceImpl impl = mServicesCache.valueAt(i);
                        impl.dumpLocked("  ", pw);
                    }
                }
                mUi.dump(pw);
            }
            pw.println("Requests history:");
            mRequestsHistory.reverseDump(fd, pw, args);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new AutoFillManagerServiceShellCommand(AutoFillManagerService.this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.AUTO_FILL_SERVICE), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                updateCachedServiceLocked(userId);
            }
        }
    }
}
