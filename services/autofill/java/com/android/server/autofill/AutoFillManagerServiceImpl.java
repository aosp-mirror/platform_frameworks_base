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

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.icu.text.DateFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.autofill.AutoFillService;
import android.service.autofill.AutoFillServiceInfo;
import android.service.autofill.IAutoFillService;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.autofill.AutoFillManagerService.AutoFillManagerServiceStub;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Bridge between the {@code system_server}'s {@link AutoFillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutoFillManagerServiceImpl {

    private static final String TAG = "AutoFillManagerServiceImpl";
    private static final boolean DEBUG = true; // TODO: change to false once stable

    final int mUser;
    final ComponentName mComponent;

    private final Context mContext;
    private final IActivityManager mAm;
    private final Object mLock;
    private final AutoFillManagerServiceStub mServiceStub;
    private final AutoFillServiceInfo mInfo;

    // TODO: improve its usage
    // - set maximum number of entries
    // - disable on low-memory devices.
    private final List<String> mRequestHistory = new ArrayList<>();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                final String reason = intent.getStringExtra("reason");
                if (DEBUG) Slog.d(TAG, "close system dialogs: " + reason);
                // TODO: close any pending UI like account selection (or remove this receiver)
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.d(TAG, "onServiceConnected():" + name);
            synchronized (mLock) {
                mService = IAutoFillService.Stub.asInterface(service);
                try {
                    mService.ready();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception on service.ready(): " + e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.d(TAG, name + " disconnected");
            mService = null;
        }
    };

    @GuardedBy("mLock")
    private IAutoFillService mService;
    private boolean mBound;
    private boolean mValid;

    AutoFillManagerServiceImpl(Context context, Object lock, AutoFillManagerServiceStub stub,
            Handler handler, int user, ComponentName component) {
        mContext = context;
        mLock = lock;
        mServiceStub = stub;
        mUser = user;
        mComponent = component;
        mAm = ActivityManager.getService();

        final AutoFillServiceInfo info;
        try {
            info = new AutoFillServiceInfo(component, mUser);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Auto-fill service not found: " + component, e);
            mInfo = null;
            mValid = false;
            return;
        }
        mInfo = info;
        if (mInfo.getParseError() != null) {
            Slog.w(TAG, "Bad auto-fill service: " + mInfo.getParseError());
            mValid = false;
            return;
        }

        mValid = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, handler);
    }

    void startLocked() {
        if (DEBUG) Slog.d(TAG, "startLocked()");

        final Intent intent = new Intent(AutoFillService.SERVICE_INTERFACE);
        intent.setComponent(mComponent);
        mBound = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, new UserHandle(mUser));
        if (!mBound) {
            Slog.w(TAG, "Failed binding to auto-fill service " + mComponent);
            return;
        }
        if (DEBUG) Slog.d(TAG, "Bound to " + mComponent);
    }

    boolean requestAutoFill(IBinder activityToken) {
        if (!mBound) {
            // TODO: should it bind on demand? Or perhaps always run when on on low-memory?
            Slog.w(TAG, "requestAutoFill() failed because it's not bound to service");
            return false;
        }

        // TODO: activityToken should probably not be null, but we need to wait until the UI is
        // triggering the call (for now it's trough 'adb shell cmd autofill request'
        if (activityToken == null) {
            // Let's get top activities from all visible stacks.

            // TODO: overload getTopVisibleActivities() to take userId, otherwise it could return
            // activities for different users when a work profile app is displayed in another
            // window (in a multi-window environment).
            final List<IBinder> topActivities = LocalServices
                    .getService(ActivityManagerInternal.class).getTopVisibleActivities();
            if (DEBUG)
                Slog.d(TAG, "Top activities (" + topActivities.size() + "): " + topActivities);
            if (topActivities.isEmpty()) {
                Slog.w(TAG, "Could not get top activity");
                return false;
            }
            activityToken = topActivities.get(0);
        }

        synchronized (mLock) {
            return requestAutoFillLocked(activityToken);
        }
    }

    private boolean requestAutoFillLocked(IBinder activityToken) {
        mRequestHistory.add(
                DateFormat.getDateTimeInstance().format(new Date()) + " - " + activityToken);
        if (DEBUG) Slog.d(TAG, "Requesting for user " + mUser + " and activity " + activityToken);

        // Sanity check
        if (mService == null) {
            Slog.w(TAG, "requestAutoFillLocked(: service is null");
            return false;
        }

        /*
         * TODO: apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            // TODO: add MetricsLogger call
            if (!mAm.requestAutoFillData(mService.getAssistReceiver(), null, activityToken)) {
                return false;
            }
        } catch (RemoteException e) {
            // Should happen, it's a local call.
        }
        return true;
    }

    void shutdownLocked() {
        if (DEBUG) Slog.d(TAG, "shutdownLocked()");

        try {
            if (mService != null) {
                mService.shutdown();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in shutdown", e);
        }

        if (mBound) {
            mContext.unbindService(mConnection);
            mBound = false;
        }
        if (mValid) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        if (!mValid) {
            pw.print("  NOT VALID: ");
            if (mInfo == null) {
                pw.println("no info");
            } else {
                pw.println(mInfo.getParseError());
            }
            return;
        }

        pw.print(prefix); pw.print("mUser="); pw.println(mUser);
        pw.print(prefix); pw.print("mComponent="); pw.println(mComponent.flattenToShortString());
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        pw.print(prefix); pw.print("mService="); pw.println(mService);

        if (DEBUG) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("Service info:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
        }

        if (mRequestHistory.isEmpty()) {
            pw.print(prefix); pw.println("No history");
        } else {
            pw.print(prefix); pw.println("History:");
            final String prefix2 = prefix + prefix;
            for (int i = 0; i < mRequestHistory.size(); i++) {
                pw.print(prefix2); pw.print(i); pw.print(": "); pw.println(mRequestHistory.get(i));
            }
        }
    }

    @Override
    public String toString() {
        return "[AutoFillManagerServiceImpl: user=" + mUser
                + ", component=" + mComponent.flattenToShortString() + "]";
    }
}
