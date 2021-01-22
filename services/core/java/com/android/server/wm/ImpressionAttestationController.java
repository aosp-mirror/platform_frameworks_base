/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.service.attestation.ImpressionAttestationService.SERVICE_META_DATA_KEY_AVAILABLE_ALGORITHMS;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.attestation.IImpressionAttestationService;
import android.service.attestation.ImpressionAttestationService;
import android.service.attestation.ImpressionToken;
import android.util.Slog;
import android.view.MagnificationSpec;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Handles requests into {@link ImpressionAttestationService}
 *
 * Do not hold the {@link WindowManagerService#mGlobalLock} when calling methods since they are
 * blocking calls into another service.
 */
public class ImpressionAttestationController {
    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "ImpressionAttestationController" : TAG_WM;
    private static final boolean DEBUG = false;

    private final Object mServiceConnectionLock = new Object();

    @GuardedBy("mServiceConnectionLock")
    private ImpressionAttestationServiceConnection mServiceConnection;

    private final Context mContext;

    /**
     * Lock used for the cached {@link #mImpressionAlgorithms} array
     */
    private final Object mImpressionAlgorithmsLock = new Object();

    @GuardedBy("mImpressionAlgorithmsLock")
    private String[] mImpressionAlgorithms;

    private final Handler mHandler;

    private final byte[] mSalt;

    private final float[] mTmpFloat9 = new float[9];
    private final Matrix mTmpMatrix = new Matrix();
    private final RectF mTmpRectF = new RectF();

    private interface Command {
        void run(IImpressionAttestationService service) throws RemoteException;
    }

    ImpressionAttestationController(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mSalt = UUID.randomUUID().toString().getBytes();
    }

    String[] getSupportedImpressionAlgorithms() {
        // We have a separate lock for the impression algorithm array since it doesn't need to make
        // the request through the service connection. Instead, we have a lock to ensure we can
        // properly cache the impression algorithms array so we don't need to call into the
        // ExtServices process for each request.
        synchronized (mImpressionAlgorithmsLock) {
            // Already have cached values
            if (mImpressionAlgorithms != null) {
                return mImpressionAlgorithms;
            }

            final ServiceInfo serviceInfo = getServiceInfo();
            if (serviceInfo == null) return null;

            final PackageManager pm = mContext.getPackageManager();
            final Resources res;
            try {
                res = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Error getting application resources for " + serviceInfo, e);
                return null;
            }

            final int resourceId = serviceInfo.metaData.getInt(
                    SERVICE_META_DATA_KEY_AVAILABLE_ALGORITHMS);
            mImpressionAlgorithms = res.getStringArray(resourceId);

            return mImpressionAlgorithms;
        }
    }

    boolean verifyImpressionToken(ImpressionToken impressionToken) {
        final SyncCommand syncCommand = new SyncCommand();
        Bundle results = syncCommand.run((service, remoteCallback) -> {
            try {
                service.verifyImpressionToken(mSalt, impressionToken, remoteCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to invoke verifyImpressionToken command");
            }
        });

        return results.getBoolean(ImpressionAttestationService.EXTRA_VERIFICATION_STATUS);
    }

    ImpressionToken generateImpressionToken(HardwareBuffer screenshot, Rect bounds,
            String hashAlgorithm) {
        final SyncCommand syncCommand = new SyncCommand();
        Bundle results = syncCommand.run((service, remoteCallback) -> {
            try {
                service.generateImpressionToken(mSalt, screenshot, bounds, hashAlgorithm,
                        remoteCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to invoke generateImpressionToken command", e);
            }
        });

        return results.getParcelable(ImpressionAttestationService.EXTRA_IMPRESSION_TOKEN);
    }

    /**
     * Calculate the bounds to take the screenshot when generating the impression token. This takes
     * into account window transform, magnification, and display bounds.
     *
     * Call while holding {@link WindowManagerService#mGlobalLock}
     *
     * @param win Window that the impression token is generated for.
     * @param boundsInWindow The bounds passed in about where in the window to take the screenshot.
     * @param outBounds The result of the calculated bounds
     */
    void calculateImpressionTokenBoundsLocked(WindowState win, Rect boundsInWindow,
            Rect outBounds) {
        if (DEBUG) {
            Slog.d(TAG, "calculateImpressionTokenBounds: boundsInWindow=" + boundsInWindow);
        }
        outBounds.set(boundsInWindow);

        DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            return;
        }

        // Intersect boundsInWindow with the window to make sure it's not outside the window
        // requesting the token. Offset the window bounds to 0,0 since the boundsInWindow are
        // offset from the window location, not display.
        final Rect windowBounds = new Rect();
        win.getBounds(windowBounds);
        windowBounds.offsetTo(0, 0);
        outBounds.intersectUnchecked(windowBounds);

        if (DEBUG) {
            Slog.d(TAG, "calculateImpressionTokenBounds: boundsIntersectWindow=" + outBounds);
        }

        if (outBounds.isEmpty()) {
            return;
        }

        // Transform the bounds using the window transform in case there's a scale or offset.
        // This allows the bounds to be in display space.
        win.getTransformationMatrix(mTmpFloat9, mTmpMatrix);
        mTmpRectF.set(outBounds);
        mTmpMatrix.mapRect(mTmpRectF, mTmpRectF);
        outBounds.set((int) mTmpRectF.left, (int) mTmpRectF.top, (int) mTmpRectF.right,
                (int) mTmpRectF.bottom);
        if (DEBUG) {
            Slog.d(TAG, "calculateImpressionTokenBounds: boundsInDisplay=" + outBounds);
        }

        // Apply the magnification spec values to the bounds since the content could be magnified
        final MagnificationSpec magSpec = displayContent.getMagnificationSpec();
        if (magSpec != null) {
            outBounds.scale(magSpec.scale);
            outBounds.offset((int) magSpec.offsetX, (int) magSpec.offsetY);
        }

        if (DEBUG) {
            Slog.d(TAG, "calculateImpressionTokenBounds: boundsWithMagnification=" + outBounds);
        }

        if (outBounds.isEmpty()) {
            return;
        }

        // Intersect with the display bounds since it shouldn't take a screenshot of content
        // outside the display since it's not visible to the user.
        final Rect displayBounds = displayContent.getBounds();
        outBounds.intersectUnchecked(displayBounds);
        if (DEBUG) {
            Slog.d(TAG, "calculateImpressionTokenBounds: finalBounds=" + outBounds);
        }
    }

    /**
     * Run a command, starting the service connection if necessary.
     */
    private void connectAndRun(@NonNull Command command) {
        synchronized (mServiceConnectionLock) {
            mHandler.resetTimeoutMessage();
            if (mServiceConnection == null) {
                if (DEBUG) Slog.v(TAG, "creating connection");

                // Create the connection
                mServiceConnection = new ImpressionAttestationServiceConnection();

                final ComponentName component = getServiceComponentName();
                if (DEBUG) Slog.v(TAG, "binding to: " + component);
                if (component != null) {
                    final Intent intent = new Intent();
                    intent.setComponent(component);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mContext.bindServiceAsUser(intent, mServiceConnection,
                                Context.BIND_AUTO_CREATE, UserHandle.CURRENT);
                        if (DEBUG) Slog.v(TAG, "bound");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            }

            mServiceConnection.runCommandLocked(command);
        }
    }

    @Nullable
    private ServiceInfo getServiceInfo() {
        final String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }

        final Intent intent = new Intent(ImpressionAttestationService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
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
        if (!Manifest.permission.BIND_IMPRESSION_ATTESTATION_SERVICE
                .equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " requires permission "
                    + Manifest.permission.BIND_IMPRESSION_ATTESTATION_SERVICE);
            return null;
        }

        if (DEBUG) Slog.v(TAG, "getServiceComponentName(): " + name);
        return name;
    }

    private class SyncCommand {
        private static final int WAIT_TIME_S = 5;
        private Bundle mResult;
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

        public Bundle run(BiConsumer<IImpressionAttestationService, RemoteCallback> func) {
            connectAndRun(service -> {
                RemoteCallback callback = new RemoteCallback(result -> {
                    mResult = result;
                    mCountDownLatch.countDown();
                });
                func.accept(service, callback);
            });

            try {
                mCountDownLatch.await(WAIT_TIME_S, TimeUnit.SECONDS);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to wait for command", e);
            }

            return mResult;
        }
    }

    private class ImpressionAttestationServiceConnection implements ServiceConnection {
        @GuardedBy("mServiceConnectionLock")
        private IImpressionAttestationService mRemoteService;

        @GuardedBy("mServiceConnectionLock")
        private ArrayList<Command> mQueuedCommands;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.v(TAG, "onServiceConnected(): " + name);
            synchronized (mServiceConnectionLock) {
                mRemoteService = IImpressionAttestationService.Stub.asInterface(service);
                if (mQueuedCommands != null) {
                    final int size = mQueuedCommands.size();
                    if (DEBUG) Slog.d(TAG, "running " + size + " queued commands");
                    for (int i = 0; i < size; i++) {
                        final Command queuedCommand = mQueuedCommands.get(i);
                        try {
                            if (DEBUG) Slog.v(TAG, "running queued command #" + i);
                            queuedCommand.run(mRemoteService);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "exception calling " + name + ": " + e);
                        }
                    }
                    mQueuedCommands = null;
                } else if (DEBUG) {
                    Slog.d(TAG, "no queued commands");
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.v(TAG, "onServiceDisconnected(): " + name);
            synchronized (mServiceConnectionLock) {
                mRemoteService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DEBUG) Slog.v(TAG, "onBindingDied(): " + name);
            synchronized (mServiceConnectionLock) {
                mRemoteService = null;
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (DEBUG) Slog.v(TAG, "onNullBinding(): " + name);
            synchronized (mServiceConnectionLock) {
                mRemoteService = null;
            }
        }

        /**
         * Only call while holding {@link #mServiceConnectionLock}
         */
        private void runCommandLocked(Command command) {
            if (mRemoteService == null) {
                if (DEBUG) Slog.d(TAG, "service is null; queuing command");
                if (mQueuedCommands == null) {
                    mQueuedCommands = new ArrayList<>(1);
                }
                mQueuedCommands.add(command);
            } else {
                try {
                    if (DEBUG) Slog.v(TAG, "running command right away");
                    command.run(mRemoteService);
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception calling service: " + e);
                }
            }
        }
    }

    private class Handler extends android.os.Handler {
        static final long SERVICE_SHUTDOWN_TIMEOUT_MILLIS = 10000; // 10s
        static final int MSG_SERVICE_SHUTDOWN_TIMEOUT = 1;

        Handler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SERVICE_SHUTDOWN_TIMEOUT) {
                if (DEBUG) {
                    Slog.v(TAG, "Shutting down service");
                }
                synchronized (mServiceConnectionLock) {
                    if (mServiceConnection != null) {
                        mContext.unbindService(mServiceConnection);
                        mServiceConnection = null;
                    }
                }
            }
        }

        /**
         * Set a timer for {@link #SERVICE_SHUTDOWN_TIMEOUT_MILLIS} so we can tear down the service
         * if it's inactive. The requests will be coming from apps so it's hard to tell how often
         * the requests can come in. Therefore, we leave the service running if requests continue
         * to come in. Once there's been no activity for 10s, we can shut down the service and
         * restart when we get a new request.
         */
        void resetTimeoutMessage() {
            if (DEBUG) {
                Slog.v(TAG, "Reset shutdown message");
            }
            removeMessages(MSG_SERVICE_SHUTDOWN_TIMEOUT);
            sendEmptyMessageDelayed(MSG_SERVICE_SHUTDOWN_TIMEOUT, SERVICE_SHUTDOWN_TIMEOUT_MILLIS);
        }
    }

}
