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

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.autofill.IAutoFillManagerService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Entry point service for auto-fill management.
 *
 * <p>This service provides the {@link IAutoFillManagerService} implementation and keeps a list of
 * {@link AutoFillManagerServiceImpl} per user; the real work is done by
 * {@link AutoFillManagerServiceImpl} itself.
 */
public final class AutoFillManagerService extends SystemService {

    private static final String TAG = "AutoFillManagerService";
    private static final boolean DEBUG = true; // TODO: change to false once stable

    private final AutoFillManagerServiceStub mServiceStub;
    private final Context mContext;
    private final ContentResolver mResolver;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mSafeMode;

    /**
     * Map of {@link AutoFillManagerServiceImpl} per user id.
     * <p>
     * It has to be mapped by user id because the same current user could have simultaneous sessions
     * associated to different user profiles (for example, in a multi-window environment).
     * <p>
     * This map is filled on demand in the following scenarios:
     * <ol>
     *   <li>On start, it sets the value for the default user.
     *   <li>When an auto-fill service app is removed, its entries are removed.
     *   <li>When the current user changes.
     *   <li>When the {@link android.provider.Settings.Secure#AUTO_FILL_SERVICE} changes.
     * </ol>
     */
    // TODO: make sure all cases listed above are handled
    // TODO: should entries be removed when there is no section and have not be used for a while?
    @GuardedBy("mLock")
    private SparseArray<AutoFillManagerServiceImpl> mImplByUser = new SparseArray<>();

    // TODO: should disable it on low-memory devices? if not, this attribute should be removed...
    private final boolean mEnableService = true;

    public AutoFillManagerService(Context context) {
        super(context);

        mContext = context;
        mResolver = context.getContentResolver();
        mServiceStub = new AutoFillManagerServiceStub();
    }

    @Override
    public void onStart() {
        if (DEBUG)
            Slog.d(TAG, "onStart(): binding as " + AUTO_FILL_MANAGER_SERVICE);
        publishBinderService(AUTO_FILL_MANAGER_SERVICE, mServiceStub);
    }

    // TODO: refactor so it's bound on demand, in which case it can use isSafeMode() from PM.
    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            systemRunning(isSafeMode());
        }
    }

    // TODO: refactor so it's bound on demand, in which case it can use isSafeMode() from PM.
    @Override
    public void onStartUser(int userHandle) {
        if (DEBUG) Slog.d(TAG, "onStartUser(): userHandle=" + userHandle);

        updateImplementationIfNeeded(userHandle, false);
    }

    @Override
    public void onUnlockUser(int userHandle) {
        if (DEBUG) Slog.d(TAG, "onUnlockUser(): userHandle=" + userHandle);

        updateImplementationIfNeeded(userHandle, false);
    }

    @Override
    public void onSwitchUser(int userHandle) {
        if (DEBUG) Slog.d(TAG, "onSwitchUser(): userHandle=" + userHandle);

        updateImplementationIfNeeded(userHandle, false);
    }

    private void systemRunning(boolean safeMode) {
        if (DEBUG) Slog.d(TAG, "systemRunning(): safeMode=" + safeMode);

        // TODO: register a PackageMonitor
        new SettingsObserver(BackgroundThread.getHandler());

        synchronized (mLock) {
            mSafeMode = safeMode;
            updateImplementationIfNeededLocked(ActivityManager.getCurrentUser(), false);
        }
    }

    private void updateImplementationIfNeeded(int user, boolean force) {
        synchronized (mLock) {
            updateImplementationIfNeededLocked(user, force);
        }
    }

    private void updateImplementationIfNeededLocked(int user, boolean force) {
        if (DEBUG)
            Slog.d(TAG, "updateImplementationIfNeededLocked(" + user + ", " + force + ")");

        if (mSafeMode) {
            if (DEBUG) Slog.d(TAG, "skipping on safe mode");
            return;
        }

        final String curService = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.AUTO_FILL_SERVICE, user);
        if (DEBUG)
            Slog.d(TAG, "Current service settings for user " + user + ": " + curService);
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        if (!TextUtils.isEmpty(curService)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(curService);
                serviceInfo =
                        AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 0, user);
            } catch (RuntimeException | RemoteException e) {
                Slog.wtf(TAG, "Bad auto-fill service name " + curService, e);
                serviceComponent = null;
                serviceInfo = null;
            }
        }

        final AutoFillManagerServiceImpl impl = mImplByUser.get(user);
        if (DEBUG) Slog.d(TAG, "Current impl: " + impl + " component: " + serviceComponent
                + " info: " + serviceInfo);

        if (force || impl == null || !impl.mComponent.equals(serviceComponent)) {
            if (impl != null) {
                impl.shutdownLocked();
            }
            if (serviceInfo != null) {
                final AutoFillManagerServiceImpl newImpl = new AutoFillManagerServiceImpl(mContext,
                        mLock, mServiceStub, FgThread.getHandler(), user, serviceComponent);
                if (DEBUG) Slog.d(TAG, "Setting impl for user " + user + " as: " + newImpl);
                mImplByUser.put(user, newImpl);
                newImpl.startLocked();
            } else {
                if (DEBUG) Slog.d(TAG, "Removing impl for user " + user + ": " + impl);
                mImplByUser.remove(user);
            }
        }
    }

    // TODO: might need to return null instead of throw exception
    private AutoFillManagerServiceImpl getImplOrThrowLocked(int userId) {
        final AutoFillManagerServiceImpl impl = mImplByUser.get(userId);
        if (impl == null) {
            throw new IllegalStateException("no auto-fill service for user " + userId);
        }
        return impl;
    }

    final class AutoFillManagerServiceStub extends IAutoFillManagerService.Stub {

        @Override
        public String startSession(int userId, Bundle args, int flags, IBinder activityToken) {
            mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

            synchronized (mLock) {
                return getImplOrThrowLocked(userId).startSession(args, flags, activityToken);
            }
        }

        @Override
        public boolean finishSession(int userId, String token) {
            mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

            synchronized (mLock) {
                return getImplOrThrowLocked(userId).finishSessionLocked(token);
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
            if (args.length > 0) {
                if ("--sessions".equals(args[0])) {
                    dumpSessions(pw);
                    return;
                }
            }
            synchronized (mLock) {
                pw.print("mEnableService: "); pw.println(mEnableService);
                pw.print("mSafeMode: "); pw.println(mSafeMode);
                final int size = mImplByUser.size();
                pw.print("Number of implementations: ");
                if (size == 0) {
                    pw.println("none");
                } else {
                    pw.println(size);
                    for (int i = 0; i < size; i++) {
                        pw.print("\nImplementation at index "); pw.println(i);
                        final AutoFillManagerServiceImpl impl = mImplByUser.valueAt(i);
                        impl.dumpLocked("  ", pw);
                    }
                }
            }
        }

        private void dumpSessions(PrintWriter pw) {
            boolean foundOne = false;
            synchronized (mLock) {
                final int size = mImplByUser.size();
                for (int i = 0; i < size; i++) {
                    final AutoFillManagerServiceImpl impl = mImplByUser.valueAt(i);
                    foundOne |= impl.dumpSessionsLocked("", pw);
                }
            }
            if (!foundOne) {
                pw.println("No active sessions");
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new AutoFillManagerServiceShellCommand(this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }

    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.AUTO_FILL_SERVICE), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                updateImplementationIfNeededLocked(userId, false);
            }
        }
    }
}
