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
package com.android.server.autofill;

import static android.view.autofill.AutofillManager.EXTRA_AVAILABLE_ALGORITHMS;
import static android.view.autofill.AutofillManager.EXTRA_DEFAULT_ALGORITHM;
import static android.view.autofill.AutofillManager.FC_SERVICE_TIMEOUT;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

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
import android.service.autofill.AutofillFieldClassificationService;
import android.service.autofill.IAutofillFieldClassificationService;
import android.util.Log;
import android.util.Slog;
import android.view.autofill.AutofillValue;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Strategy used to bridge the field classification algorithms provided by a service in an external
 * package.
 */
//TODO(b/70291841): add unit tests ?
final class FieldClassificationStrategy {

    private static final String TAG = "FieldClassificationStrategy";

    private final Context mContext;
    private final Object mLock = new Object();
    private final int mUserId;

    @GuardedBy("mLock")
    private ServiceConnection mServiceConnection;

    @GuardedBy("mLock")
    private IAutofillFieldClassificationService mRemoteService;

    @GuardedBy("mLock")
    private ArrayList<Command> mQueuedCommands;

    public FieldClassificationStrategy(Context context, int userId) {
        mContext = context;
        mUserId = userId;
    }

    private ComponentName getServiceComponentName() {
        final String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }

        final Intent intent = new Intent(AutofillFieldClassificationService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }
        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        final ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);

        if (!Manifest.permission.BIND_AUTOFILL_FIELD_CLASSIFICATION_SERVICE
                .equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " does not require permission "
                    + Manifest.permission.BIND_AUTOFILL_FIELD_CLASSIFICATION_SERVICE);
            return null;
        }

        if (sVerbose) Slog.v(TAG, "getServiceComponentName(): " + name);
        return name;
    }

    /**
     * Run a command, starting the service connection if necessary.
     */
    private void connectAndRun(@NonNull Command command) {
        synchronized (mLock) {
            if (mRemoteService != null) {
                try {
                    if (sVerbose) Slog.v(TAG, "running command right away");
                    command.run(mRemoteService);
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception calling service: " + e);
                }
                return;
            } else {
                if (sDebug) Slog.d(TAG, "service is null; queuing command");
                if (mQueuedCommands == null) {
                    mQueuedCommands = new ArrayList<>(1);
                }
                mQueuedCommands.add(command);
                // If we're already connected, don't create a new connection, just leave - the
                // command will be run when the service connects
                if (mServiceConnection != null) return;
            }

            if (sVerbose) Slog.v(TAG, "creating connection");

            // Create the connection
            mServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (sVerbose) Slog.v(TAG, "onServiceConnected(): " + name);
                    synchronized (mLock) {
                        mRemoteService = IAutofillFieldClassificationService.Stub
                                .asInterface(service);
                        if (mQueuedCommands != null) {
                            final int size = mQueuedCommands.size();
                            if (sDebug) Slog.d(TAG, "running " + size + " queued commands");
                            for (int i = 0; i < size; i++) {
                                final Command queuedCommand = mQueuedCommands.get(i);
                                try {
                                    if (sVerbose) Slog.v(TAG, "running queued command #" + i);
                                    queuedCommand.run(mRemoteService);
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "exception calling " + name + ": " + e);
                                }
                            }
                            mQueuedCommands = null;
                        } else if (sDebug) Slog.d(TAG, "no queued commands");
                    }
                }

                @Override
                @MainThread
                public void onServiceDisconnected(ComponentName name) {
                    if (sVerbose) Slog.v(TAG, "onServiceDisconnected(): " + name);
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    if (sVerbose) Slog.v(TAG, "onBindingDied(): " + name);
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    if (sVerbose) Slog.v(TAG, "onNullBinding(): " + name);
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }
            };

            final ComponentName component = getServiceComponentName();
            if (sVerbose) Slog.v(TAG, "binding to: " + component);
            if (component != null) {
                final Intent intent = new Intent();
                intent.setComponent(component);
                final long token = Binder.clearCallingIdentity();
                try {
                    mContext.bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE,
                            UserHandle.of(mUserId));
                    if (sVerbose) Slog.v(TAG, "bound");
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }
    }

    void getAvailableAlgorithms(RemoteCallback callback) {
        connectAndRun((service) -> service.getAvailableAlgorithms(callback));
    }

    void getDefaultAlgorithm(RemoteCallback callback) {
        connectAndRun((service) -> service.getDefaultAlgorithm(callback));
    }

    //TODO(b/70291841): rename this method (and all others in the chain) to something like
    // calculateScores() ?
    void getScores(RemoteCallback callback, @Nullable String algorithmName,
            @Nullable Bundle algorithmArgs, @NonNull List<AutofillValue> actualValues,
            @NonNull String[] userDataValues) {
        connectAndRun((service) -> service.getScores(callback, algorithmName,
                algorithmArgs, actualValues, userDataValues));
    }

    void dump(String prefix, PrintWriter pw) {
        final ComponentName impl = getServiceComponentName();
        pw.print(prefix); pw.print("User ID: "); pw.println(mUserId);
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

        final CountDownLatch latch = new CountDownLatch(2);

        // Lock used to make sure lines don't overlap
        final Object lock = latch;

        connectAndRun((service) -> service.getAvailableAlgorithms(new RemoteCallback((bundle) -> {
            synchronized (lock) {
                pw.print(prefix); pw.print("Available algorithms: ");
                pw.println(bundle.getStringArrayList(EXTRA_AVAILABLE_ALGORITHMS));
            }
            latch.countDown();
        })));

        connectAndRun((service) -> service.getDefaultAlgorithm(new RemoteCallback((bundle) -> {
            synchronized (lock) {
                pw.print(prefix); pw.print("Default algorithm: ");
                pw.println(bundle.getString(EXTRA_DEFAULT_ALGORITHM));
            }
            latch.countDown();
        })));

        try {
            if (!latch.await(FC_SERVICE_TIMEOUT, TimeUnit.MILLISECONDS)) {
                synchronized (lock) {
                    pw.print(prefix); pw.print("timeout ("); pw.print(FC_SERVICE_TIMEOUT);
                    pw.println("ms) waiting for service");
                }
            }
        } catch (InterruptedException e) {
            synchronized (lock) {
                pw.print(prefix); pw.println("interrupted while waiting for service");
            }
            Thread.currentThread().interrupt();
        }
    }

    private interface Command {
        void run(IAutofillFieldClassificationService service) throws RemoteException;
    }
}
