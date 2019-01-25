/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.role;

import android.Manifest;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.sms.FinancialSmsService;
import android.service.sms.IFinancialSmsService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * This class binds to {@code FinancialSmsService}.
 */
final class FinancialSmsManager {

    private static final String TAG = "FinancialSmsManager";

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ServiceConnection mServiceConnection;

    @GuardedBy("mLock")
    private IFinancialSmsService mRemoteService;

    @GuardedBy("mLock")
    private ArrayList<Command> mQueuedCommands;

    FinancialSmsManager(Context context) {
        mContext = context;
    }

    @Nullable
    ServiceInfo getServiceInfo() {
        final String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }

        final Intent intent = new Intent(FinancialSmsService.ACTION_FINANCIAL_SERVICE_INTENT);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }
        return resolveInfo.serviceInfo;
    }

    @Nullable
    private ComponentName getServiceComponentName() {
        final ServiceInfo serviceInfo = getServiceInfo();
        if (serviceInfo == null) return null;

        final ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!Manifest.permission.BIND_FINANCIAL_SMS_SERVICE.equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " does not require permission "
                    + Manifest.permission.BIND_FINANCIAL_SMS_SERVICE);
            return null;
        }

        return name;
    }

    void reset() {
        synchronized (mLock) {
            if (mServiceConnection != null) {
                mContext.unbindService(mServiceConnection);
                mServiceConnection = null;
            } else {
                Slog.d(TAG, "reset(): service is not bound. Do nothing.");
            }
        }
    }

    /**
     * Run a command, starting the service connection if necessary.
     */
    private void connectAndRun(@NonNull Command command) {
        synchronized (mLock) {
            if (mRemoteService != null) {
                try {
                    command.run(mRemoteService);
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception calling service: " + e);
                }
                return;
            } else {
                if (mQueuedCommands == null) {
                    mQueuedCommands = new ArrayList<>(1);
                }
                mQueuedCommands.add(command);
                // If we're already connected, don't create a new connection, just leave - the
                // command will be run when the service connects
                if (mServiceConnection != null) return;
            }

            // Create the connection
            mServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mLock) {
                        mRemoteService = IFinancialSmsService.Stub.asInterface(service);
                        if (mQueuedCommands != null) {
                            final int size = mQueuedCommands.size();
                            for (int i = 0; i < size; i++) {
                                final Command queuedCommand = mQueuedCommands.get(i);
                                try {
                                    queuedCommand.run(mRemoteService);
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "exception calling " + name + ": " + e);
                                }
                            }
                            mQueuedCommands = null;
                        }
                    }
                }

                @Override
                @MainThread
                public void onServiceDisconnected(ComponentName name) {
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }
            };

            final ComponentName component = getServiceComponentName();
            if (component != null) {
                final Intent intent = new Intent();
                intent.setComponent(component);
                final long token = Binder.clearCallingIdentity();
                try {
                    mContext.bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE,
                            UserHandle.getUserHandleForUid(UserHandle.getCallingUserId()));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }
    }

    void getSmsMessages(RemoteCallback callback, @Nullable Bundle params) {
        connectAndRun((service) -> service.getSmsMessages(callback, params));
    }

    void dump(String prefix, PrintWriter pw) {
        final ComponentName impl = getServiceComponentName();
        pw.print(prefix); pw.print("User ID: "); pw.println(UserHandle.getCallingUserId());
        pw.print(prefix); pw.print("Queued commands: ");
        if (mQueuedCommands == null) {
            pw.println("N/A");
        } else {
            pw.println(mQueuedCommands.size());
        }
        pw.print(prefix); pw.print("Implementation: ");
        if (impl == null) {
            pw.println("N/A");
            return;
        }
        pw.println(impl.flattenToShortString());
    }

    private interface Command {
        void run(IFinancialSmsService service) throws RemoteException;
    }
}
