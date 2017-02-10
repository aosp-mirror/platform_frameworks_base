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
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.autofill.IAutoFillManagerService;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.SomeArgs;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point service for auto-fill management.
 *
 * <p>This service provides the {@link IAutoFillManagerService} implementation and keeps a list of
 * {@link AutoFillManagerServiceImpl} per user; the real work is done by
 * {@link AutoFillManagerServiceImpl} itself.
 */
public final class AutoFillManagerService extends SystemService {

    private static final String TAG = "AutoFillManagerService";

    private static final int MSG_START_SESSION = 1;
    private static final int MSG_UPDATE_SESSION = 2;
    private static final int MSG_FINISH_SESSION = 3;
    private static final int MSG_REQUEST_SAVE_FOR_USER = 4;
    private static final int MSG_LIST_SESSIONS = 5;
    private static final int MSG_RESET = 6;

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private final Context mContext;
    private final AutoFillUI mUi;

    private final Object mLock = new Object();

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_START_SESSION: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final int userId = msg.arg1;
                final IBinder activityToken = (IBinder) args.arg1;
                final IBinder appCallback = (IBinder) args.arg2;
                final AutoFillId autoFillId = (AutoFillId) args.arg3;
                final Rect bounds = (Rect) args.arg4;
                final AutoFillValue value = (AutoFillValue) args.arg5;
                handleStartSession(userId, activityToken, appCallback, autoFillId, bounds, value);
                return;
            } case MSG_FINISH_SESSION: {
                handleFinishSession(msg.arg1, (IBinder) msg.obj);
                return;
            } case MSG_REQUEST_SAVE_FOR_USER: {
                handleSaveForUser(msg.arg1);
                return;
            } case MSG_UPDATE_SESSION: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final IBinder activityToken = (IBinder) args.arg1;
                final AutoFillId autoFillId = (AutoFillId) args.arg2;
                final Rect bounds = (Rect) args.arg3;
                final AutoFillValue value = (AutoFillValue) args.arg4;
                final int userId = args.argi5;
                final int flags = args.argi6;
                handleUpdateSession(userId, activityToken, autoFillId, bounds, value, flags);
                return;
            } case MSG_LIST_SESSIONS: {
                handleListForUser(msg.arg1, (IResultReceiver) msg.obj);
                return;
            } case MSG_RESET: {
                handleReset();
                return;
            } default: {
                Slog.w(TAG, "Invalid message: " + msg);
            }
        }
    };

    private HandlerCaller mHandlerCaller;

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

    public AutoFillManagerService(Context context) {
        super(context);
        mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(), mHandlerCallback, true);
        mContext = context;
        mUi = new AutoFillUI(mContext);
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

    private AutoFillManagerServiceImpl newServiceForUser(int userId) {
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        final String componentName = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.AUTO_FILL_SERVICE, userId);
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 0,
                        userId);
            } catch (RuntimeException | RemoteException e) {
                Slog.e(TAG, "Bad auto-fill service name " + componentName, e);
                return null;
            }
        }

        if (serviceInfo == null) {
            return null;
        }

        try {
            return new AutoFillManagerServiceImpl(mContext, mLock, mRequestsHistory,
                    userId, serviceComponent, mUi);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Auto-fill service not found: " + serviceComponent, e);
        }

        return null;
    }

    /**
     * Gets the service instance for an user.
     *
     * @return service instance or {@code null} if user does not have a service set.
     */
    @Nullable
    AutoFillManagerServiceImpl getServiceForUserLocked(int userId) {
        AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service == null) {
            service = newServiceForUser(userId);
            mServicesCache.put(userId, service);
        }
        return service;
    }

    // Called by Shell command.
    void requestSaveForUser(int userId) {
        Slog.i(TAG, "requestSaveForUser(): " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageI(
                MSG_REQUEST_SAVE_FOR_USER, userId));
    }

    // Called by Shell command.
    void listSessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "listSessions() for userId " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        mHandlerCaller.sendMessage(
                mHandlerCaller.obtainMessageIO(MSG_LIST_SESSIONS, userId, receiver));
    }

    // Called by Shell command.
    void reset() {
        Slog.i(TAG, "reset()");
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_RESET));
    }

    /**
     * Removes a cached service for a given user.
     */
    void removeCachedServiceLocked(int userId) {
        final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            service.destroyLocked();
        }
    }

    private void handleStartSession(int userId, IBinder activityToken, IBinder appCallback,
            AutoFillId autoFillId, Rect bounds, AutoFillValue value) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service == null) {
                return;
            }
           service.startSessionLocked(activityToken, appCallback, autoFillId, bounds, value);
        }
    }

    private void handleFinishSession(int userId, IBinder activityToken) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
            if (service == null) {
                return;
            }
            service.finishSessionLocked(activityToken);
        }
    }

    private void handleUpdateSession(int userId, IBinder activityToken, AutoFillId autoFillId,
            Rect bounds, AutoFillValue value, int flags) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
            if (service == null) {
                return;
            }

            service.updateSessionLocked(activityToken, autoFillId, bounds, value, flags);
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

    private void handleSaveForUser(int userId) {
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

    private void handleListForUser(int userId, IResultReceiver receiver) {
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

    private void handleReset() {
        synchronized (mLock) {
            final int size = mServicesCache.size();
            for (int i = 0; i < size; i++) {
                mServicesCache.valueAt(i).destroyLocked();
            }
            mServicesCache.clear();
        }
    }

    final class AutoFillManagerServiceStub extends IAutoFillManagerService.Stub {

        @Override
        public void startSession(IBinder activityToken, IBinder appCallback, AutoFillId autoFillId,
                Rect bounds, AutoFillValue value) throws RemoteException {
            // TODO(b/33197203): make sure it's called by resumed / focused activity

            final int userId = UserHandle.getCallingUserId();
            if (VERBOSE) {
                Slog.v(TAG, "startSession: autoFillId=" + autoFillId + ", bounds=" + bounds
                        + ", value=" + value);
            }

            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = activityToken;
            args.arg2 = appCallback;
            args.arg3 = autoFillId;
            args.arg4 = bounds;
            args.arg5 = value;

            mHandlerCaller.sendMessage(mHandlerCaller.getHandler().obtainMessage(MSG_START_SESSION,
                    userId, 0, args));
        }

        @Override
        public void updateSession(IBinder activityToken, AutoFillId id, Rect bounds,
                AutoFillValue value, int flags) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "updateSession: flags=" + flags + ", autoFillId=" + id
                        + ", bounds=" + bounds + ", value=" + value);
            }

            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOOII(MSG_UPDATE_SESSION,
                    activityToken, id, bounds, value, UserHandle.getCallingUserId(), flags));
        }

        @Override
        public void finishSession(IBinder activityToken) throws RemoteException {
            if (VERBOSE) Slog.v(TAG, "finishSession(): " + activityToken);

            mHandlerCaller.sendMessage(mHandlerCaller.getHandler().obtainMessage(MSG_FINISH_SESSION,
                    UserHandle.getCallingUserId(), 0, activityToken));
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
                removeCachedServiceLocked(userId);
            }
        }
    }
}
