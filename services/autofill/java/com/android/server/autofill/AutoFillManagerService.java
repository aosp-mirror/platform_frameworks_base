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
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.autofill.IAutoFillManagerService;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.server.FgThread;
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
    static final boolean DEBUG = true; // TODO: change to false once stable

    private static final long SERVICE_BINDING_LIFETIME_MS = 5 * DateUtils.MINUTE_IN_MILLIS;

    private static final int ARG_NOT_USED = 0;

    protected static final int MSG_UNBIND = 1;

    private final AutoFillManagerServiceStub mServiceStub;
    private final Context mContext;
    private final ContentResolver mResolver;

    private final Object mLock = new Object();

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UNBIND:
                    removeStaleServiceForUser(msg.arg1);
                    return;
                case MSG_SHOW_ALL_NOTIFICATIONS:
                    showAllNotifications();
                    return;
                default:
                    Slog.w(TAG, "Invalid message: " + msg);
            }
        }

    };

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

    public AutoFillManagerService(Context context) {
        super(context);

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
        if (phase == PHASE_BOOT_COMPLETED) {
            // TODO: if sent right away, the notification is not displayed. Since the notification
            // mechanism is a temporary approach anyways, just delay it..
            if (DEBUG)
                Slog.d(TAG, "Showing notifications in " + SHOW_ALL_NOTIFICATIONS_DELAY_MS + "ms");
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SHOW_ALL_NOTIFICATIONS),
                    SHOW_ALL_NOTIFICATIONS_DELAY_MS);
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
            Slog.w(TAG, "no service info for " + serviceComponent);
            return null;
        }
        return new AutoFillManagerServiceImpl(this, mContext, mLock, FgThread.getHandler(), userId,
                serviceInfo.applicationInfo.uid, serviceComponent, SERVICE_BINDING_LIFETIME_MS);
    }

    /**
     * Gets the service instance for an user.
     *
     * <p>First it tries to return the existing instance from the cache; if it's not cached, it
     * creates a new instance and caches it.
     */
    private AutoFillManagerServiceImpl getServiceForUserLocked(int userId) {
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
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UNBIND, userId, ARG_NOT_USED),
                SERVICE_BINDING_LIFETIME_MS);
        return service;
    }

    /**
     * Removes a cached service, but respecting its TTL.
     */
    private void removeStaleServiceForUser(int userId) {
        synchronized (mLock) {
            removeCachedService(userId, false);
        }
    }

    /**
     * Removes a cached service, even if it has TTL.
     */
    void removeCachedServiceForUserLocked(int userId) {
        removeCachedService(userId, true);
    }

    private void removeCachedService(int userId, boolean force) {
        if (DEBUG) Log.d(TAG, "removing cached service for userId " + userId);
        final AutoFillManagerServiceImpl service = mServicesCache.get(userId);
        if (service == null) {
            Log.w(TAG, "removeCachedServiceForUser(): no cached service for userId " + userId);
            return;
        }
        if (!force) {
            // Check TTL first.
            final long now = SystemClock.uptimeMillis();
            if (service.mEstimateTimeOfDeath > now) {
                if (DEBUG) {
                    final StringBuilder msg = new StringBuilder("service has some TTL left: ");
                    TimeUtils.formatDuration(service.mEstimateTimeOfDeath - now, msg);
                    Log.d(TAG, msg.toString());
                }
                return;
            }
        }
        mServicesCache.delete(userId);
        service.stopLocked();

    }

    final class AutoFillManagerServiceStub extends IAutoFillManagerService.Stub {

        @Override
        public void requestAutoFill(int userId, IBinder activityToken) {
            mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.requestAutoFill(activityToken);
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
                    Settings.Secure.AUTO_FILL_SERVICE), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (DEBUG) Slog.d(TAG, "settings (" + uri + " changed for " + userId);
            synchronized (mLock) {
                removeCachedServiceForUserLocked(userId);
                final ComponentName serviceComponent = getProviderForUser(userId);
                if (serviceComponent== null) {
                    cancelNotificationLocked(userId);
                } else {
                    showNotification(serviceComponent, userId);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // TODO: temporary code using a notification to request auto-fill.        //
    // Will be removed once UX decide the right way to present it to the user //
    ////////////////////////////////////////////////////////////////////////////

    // TODO: remove from frameworks/base/core/res/AndroidManifest.xml once it's not used anymore
    private static final String NOTIFICATION_INTENT =
            "com.android.internal.autofill.action.REQUEST_AUTOFILL";
    private static final String EXTRA_USER_ID = "user_id";

    private static final int MSG_SHOW_ALL_NOTIFICATIONS = 42;
    private static final int SHOW_ALL_NOTIFICATIONS_DELAY_MS = 5000;

    private BroadcastReceiver mNotificationReceiver;

    final class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(EXTRA_USER_ID, -1);
            if (DEBUG) Slog.d(TAG, "Requesting autofill by notification for user " + userId);
            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service == null) {
                    Slog.w(TAG, "no auto-fill service for user " + userId);
                } else {
                    service.requestAutoFill(null);
                }
            }
        }
    }

    private ComponentName getProviderForUser(int userId) {
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
            Slog.w(TAG, "no service info for " + serviceComponent);
            return null;
        }
        return serviceComponent;
    }

    private void showAllNotifications() {
        final UserManager userManager =
                (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        final List<UserInfo> allUsers = userManager.getUsers(true);

        for (UserInfo user : allUsers) {
            final ComponentName serviceComponent = getProviderForUser(user.id);
            if (serviceComponent != null) {
                showNotification(serviceComponent, user.id);
            }
        }
    }

    private void showNotification(ComponentName serviceComponent, int userId) {
        if (DEBUG) Log.d(TAG, "showNotification() for " + userId + ": " + serviceComponent);

        synchronized (mLock) {
            if (mNotificationReceiver == null) {
                mNotificationReceiver = new NotificationReceiver();
                mContext.registerReceiver(mNotificationReceiver,
                        new IntentFilter(NOTIFICATION_INTENT));
            }
        }

        final Intent intent = new Intent(NOTIFICATION_INTENT);
        intent.putExtra(EXTRA_USER_ID, userId);
        final PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        final String packageName = serviceComponent.getPackageName();
        String providerName = null;
        final PackageManager pm = mContext.getPackageManager();
        try {
            final ApplicationInfo info = pm.getApplicationInfoAsUser(packageName, 0, userId);
            if (info != null) {
                providerName = pm.getApplicationLabel(info).toString();
            }
        } catch (Exception e) {
            providerName = packageName;
        }
        final String title = "AutoFill by '" + providerName + "'";
        final String subTitle = "Tap notification to auto-fill top activity for user " + userId;

        final Notification notification = new Notification.Builder(mContext)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setOngoing(true)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setLocalOnly(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle().bigText(subTitle))
                .setContentIntent(pi)
                .build();
        NotificationManager.from(mContext).notify(userId, notification);
    }

    private void cancelNotificationLocked(int userId) {
        if (DEBUG) Log.d(TAG, "cancelNotificationLocked(): " + userId);
        NotificationManager.from(mContext).cancel(userId);
    }

    /////////////////////////////////////////
    // End of temporary notification code. //
    /////////////////////////////////////////
}
