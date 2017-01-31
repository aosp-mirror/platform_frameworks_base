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
import static android.view.View.AUTO_FILL_FLAG_TYPE_FILL;

import android.Manifest;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.autofill.IAutoFillManagerService;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutoFillId;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    static final boolean DEBUG = true; // TODO(b/33197203): change to false once stable

    private static final long SERVICE_BINDING_LIFETIME_MS = 5 * DateUtils.MINUTE_IN_MILLIS;

    protected static final int MSG_UNBIND = 1;
    protected static final int MSG_REQUEST_AUTO_FILL_FOR_USER = 2;
    protected static final int MSG_REQUEST_AUTO_FILL = 3;

    private final AutoFillManagerServiceStub mServiceStub;
    private final Context mContext;
    private final ContentResolver mResolver;

    private final Object mLock = new Object();

    private final HandlerCaller.Callback mHandlerCallback = new HandlerCaller.Callback() {

        @Override
        public void executeMessage(Message msg) {
            switch (msg.what) {
                case MSG_UNBIND: {
                    synchronized (mLock) {
                        removeCachedServiceLocked(msg.arg1);
                    }
                    return;
                } case MSG_REQUEST_AUTO_FILL_FOR_USER: {
                    final int userId = msg.arg1;
                    final int flags = msg.arg2;
                    handleAutoFillForUser(userId, flags);
                    return;
                } case MSG_REQUEST_AUTO_FILL: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final int userId = msg.arg1;
                    final int flags = msg.arg2;
                    final IBinder activityToken = (IBinder) args.arg1;
                    final AutoFillId autoFillId = (AutoFillId) args.arg2;
                    final Rect bounds = (Rect) args.arg3;
                    handleAutoFill(activityToken, userId, autoFillId, bounds, flags);
                    return;
                } default: {
                    Slog.w(TAG, "Invalid message: " + msg);
                }
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
     *   <li>The {@link android.provider.Settings.Secure#AUTO_FILL_SERVICE} for an user change.
     *   <li>It has not been interacted with for {@link #SERVICE_BINDING_LIFETIME_MS} ms.
     * </ol>
     */
    @GuardedBy("mLock")
    private SparseArray<AutoFillManagerServiceImpl> mServicesCache = new SparseArray<>();

    // TODO(b/33197203): set a different max (or disable it) on low-memory devices.
    private final LocalLog mRequestsHistory = new LocalLog(100);

    public AutoFillManagerService(Context context) {
        super(context);

        mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(), mHandlerCallback, true);

        mContext = context;

        mResolver = context.getContentResolver();
        mServiceStub = new AutoFillManagerServiceStub();
    }

    @Override
    public void onStart() {
        if (DEBUG) Slog.d(TAG, "onStart(): binding as " + AUTO_FILL_MANAGER_SERVICE);
        publishBinderService(AUTO_FILL_MANAGER_SERVICE, mServiceStub);
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
                mResolver, Settings.Secure.AUTO_FILL_SERVICE, userId);
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo =
                        AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 0, userId);
            } catch (RuntimeException | RemoteException e) {
                Slog.wtf(TAG, "Bad auto-fill service name " + componentName, e);
                return null;
            }
        }

        if (DEBUG) Slog.d(TAG, "getServiceComponentForUser(" + userId + "): component="
                + serviceComponent + ", info: " + serviceInfo);
        if (serviceInfo == null) {
            if (DEBUG) Slog.d(TAG, "no service info for " + serviceComponent);
            return null;
        }
        return new AutoFillManagerServiceImpl(this, mContext, mLock, mRequestsHistory,
                FgThread.getHandler(), userId, serviceInfo.applicationInfo.uid, serviceComponent,
                SERVICE_BINDING_LIFETIME_MS);
    }

    /**
     * Gets the service instance for an user.
     *
     * <p>First it tries to return the existing instance from the cache; if it's not cached, it
     * creates a new instance and caches it.
     */
    // TODO(b/33197203): make private once AutoFillUi does not uses notifications
    AutoFillManagerServiceImpl getServiceForUserLocked(int userId) {
        AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service != null) {
            if (DEBUG) Log.d(TAG, "reusing cached service for userId " + userId);
            service.setLifeExpectancy(SERVICE_BINDING_LIFETIME_MS);
        } else {
            service = newServiceForUser(userId);
            if (service == null) {
                // Already logged
                return null;
            }
            if (DEBUG) Log.d(TAG, "creating new cached service for userId " + userId);
            service.startLocked();
            mServicesCache.put(userId, service);
        }
        // Keep service connection alive for a while, in case user needs to interact with it
        // (for example, to save the data that was inputted in)
        if (mHandlerCaller.hasMessages(MSG_UNBIND)) {
            mHandlerCaller.removeMessages(MSG_UNBIND);
        }
        mHandlerCaller.sendMessageDelayed(mHandlerCaller.obtainMessageI(MSG_UNBIND, userId),
                SERVICE_BINDING_LIFETIME_MS);
        return service;
    }

    /**
     * Removes a cached service for a given user.
     */
    void removeCachedServiceLocked(int userId) {
        if (DEBUG) Log.d(TAG, "removing cached service for userId " + userId);
        final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service == null) {
            if (DEBUG) {
                Log.d(TAG, "removeCachedServiceForUser(): no cached service for userId " + userId);
            }
            return;
        }
        mServicesCache.delete(userId);
        service.stopLocked();
    }

    private void handleAutoFill(IBinder activityToken, int userId, AutoFillId autoFillId,
            Rect bounds, int flags) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service != null) {
                // TODO(b/33197203): must pass AUTO_FILL_FLAG_TYPE_FILL because AM is expecting
                // either that flag or AUTO_FILL_FLAG_TYPE_SAVE; should go away once save is
                // refactored
                flags |= AUTO_FILL_FLAG_TYPE_FILL;
                service.requestAutoFillLocked(activityToken, autoFillId, bounds, flags);
            }
        }
    }

    private void handleAutoFillForUser(int userId, int flags) {
        if (DEBUG) {
            Slog.d(TAG, "handler.requestAutoFillForUser(): id=" + userId + ", flags=" + flags);
        }
        final List<IBinder> topActivities = LocalServices
                .getService(ActivityManagerInternal.class).getTopVisibleActivities();
        if (DEBUG)
            Slog.d(TAG, "Top activities (" + topActivities.size() + "): " + topActivities);
        if (topActivities.isEmpty()) {
            Slog.w(TAG, "Could not get top activity");
            return;
        }
        final IBinder activityToken = topActivities.get(0);
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
            if (service == null) {
                Slog.w(TAG, "no service for user " + userId);
                return;
            }
            service.requestAutoFillLocked(activityToken, null, null, flags);
        }
    }

    final class AutoFillManagerServiceStub extends IAutoFillManagerService.Stub {

        @Override
        public void requestAutoFill(AutoFillId id, Rect bounds, int flags) {
            if (DEBUG) Slog.d(TAG, "requestAutoFill: flags=" + flags + ", autoFillId=" + id
                    + ", bounds=" + bounds);

            // Make sure its called by the top activity.
            final int uid = Binder.getCallingUid();
            final IBinder activityToken = LocalServices.getService(ActivityManagerInternal.class)
                        .getTopVisibleActivity(uid);
            if (activityToken == null) {
                throw new SecurityException("uid " + uid + " does not own the top activity");
            }

            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIIOOO(MSG_REQUEST_AUTO_FILL,
                    UserHandle.getCallingUserId(), flags, activityToken, id, bounds));
        }

        @Override
        public void requestAutoFillForUser(int userId, int flags) {
            mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageII(
                    MSG_REQUEST_AUTO_FILL_FOR_USER, userId, flags));
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
            }
            pw.println("Requests history:");
            mRequestsHistory.reverseDump(fd, pw, args);
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
                    Settings.Secure.AUTO_FILL_SERVICE), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (DEBUG) Slog.d(TAG, "settings (" + uri + " changed for " + userId);
            synchronized (mLock) {
                removeCachedServiceLocked(userId);
            }
        }
    }
}
