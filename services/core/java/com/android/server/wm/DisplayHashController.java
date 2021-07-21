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

import static android.service.displayhash.DisplayHashingService.EXTRA_INTERVAL_BETWEEN_REQUESTS;
import static android.service.displayhash.DisplayHashingService.EXTRA_VERIFIED_DISPLAY_HASH;
import static android.view.displayhash.DisplayHashResultCallback.DISPLAY_HASH_ERROR_INVALID_HASH_ALGORITHM;
import static android.view.displayhash.DisplayHashResultCallback.DISPLAY_HASH_ERROR_TOO_MANY_REQUESTS;
import static android.view.displayhash.DisplayHashResultCallback.DISPLAY_HASH_ERROR_UNKNOWN;
import static android.view.displayhash.DisplayHashResultCallback.EXTRA_DISPLAY_HASH_ERROR_CODE;

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
import android.service.displayhash.DisplayHashParams;
import android.service.displayhash.DisplayHashingService;
import android.service.displayhash.IDisplayHashingService;
import android.util.Size;
import android.util.Slog;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.VerifiedDisplayHash;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Handles requests into {@link DisplayHashingService}
 *
 * Do not hold the {@link WindowManagerService#mGlobalLock} when calling methods since they are
 * blocking calls into another service.
 */
public class DisplayHashController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayHashController" : TAG_WM;
    private static final boolean DEBUG = false;

    private final Object mServiceConnectionLock = new Object();

    @GuardedBy("mServiceConnectionLock")
    private DisplayHashingServiceConnection mServiceConnection;

    private final Context mContext;

    /**
     * Lock used for the cached {@link #mDisplayHashAlgorithms} map
     */
    private final Object mDisplayHashAlgorithmsLock = new Object();

    /**
     * The cached map of display hash algorithms to the {@link DisplayHashParams}
     */
    @GuardedBy("mDisplayHashAlgorithmsLock")
    private Map<String, DisplayHashParams> mDisplayHashAlgorithms;

    private final Handler mHandler;

    private final byte[] mSalt;

    private final float[] mTmpFloat9 = new float[9];
    private final Matrix mTmpMatrix = new Matrix();
    private final RectF mTmpRectF = new RectF();


    /**
     * Lock used for the cached {@link #mIntervalBetweenRequestMillis}
     */
    private final Object mIntervalBetweenRequestsLock = new Object();

    /**
     * Specified duration between requests to generate a display hash in milliseconds. Requests
     * faster than this delay will be throttled.
     */
    @GuardedBy("mDurationBetweenRequestsLock")
    private int mIntervalBetweenRequestMillis = -1;

    /**
     * The last time an app requested to generate a display hash in System time.
     */
    private long mLastRequestTimeMs;

    /**
     * The last uid that requested to generate a hash.
     */
    private int mLastRequestUid;

    /**
     * Only used for testing. Throttling should always be enabled unless running tests
     */
    private boolean mDisplayHashThrottlingEnabled = true;

    private interface Command {
        void run(IDisplayHashingService service) throws RemoteException;
    }

    DisplayHashController(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mSalt = UUID.randomUUID().toString().getBytes();
    }

    String[] getSupportedHashAlgorithms() {
        Map<String, DisplayHashParams> displayHashAlgorithms = getDisplayHashAlgorithms();
        return displayHashAlgorithms.keySet().toArray(new String[0]);
    }

    @Nullable
    VerifiedDisplayHash verifyDisplayHash(DisplayHash displayHash) {
        final SyncCommand syncCommand = new SyncCommand();
        Bundle results = syncCommand.run((service, remoteCallback) -> {
            try {
                service.verifyDisplayHash(mSalt, displayHash, remoteCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to invoke verifyDisplayHash command");
            }
        });

        return results.getParcelable(EXTRA_VERIFIED_DISPLAY_HASH);
    }

    void setDisplayHashThrottlingEnabled(boolean enable) {
        mDisplayHashThrottlingEnabled = enable;
    }

    private void generateDisplayHash(HardwareBuffer buffer, Rect bounds,
            String hashAlgorithm, RemoteCallback callback) {
        connectAndRun(
                service -> service.generateDisplayHash(mSalt, buffer, bounds, hashAlgorithm,
                        callback));
    }

    private boolean allowedToGenerateHash(int uid) {
        if (!mDisplayHashThrottlingEnabled) {
            // Always allow to generate the hash. This is used to allow tests to run without
            // waiting on the designated threshold.
            return true;
        }

        long currentTime = System.currentTimeMillis();
        if (mLastRequestUid != uid) {
            mLastRequestUid = uid;
            mLastRequestTimeMs = currentTime;
            return true;
        }

        int mIntervalBetweenRequestsMs = getIntervalBetweenRequestMillis();
        if (currentTime - mLastRequestTimeMs < mIntervalBetweenRequestsMs) {
            return false;
        }

        mLastRequestTimeMs = currentTime;
        return true;
    }

    void generateDisplayHash(SurfaceControl.LayerCaptureArgs.Builder args,
            Rect boundsInWindow, String hashAlgorithm, int uid, RemoteCallback callback) {
        if (!allowedToGenerateHash(uid)) {
            sendDisplayHashError(callback, DISPLAY_HASH_ERROR_TOO_MANY_REQUESTS);
            return;
        }

        final Map<String, DisplayHashParams> displayHashAlgorithmsMap = getDisplayHashAlgorithms();
        DisplayHashParams displayHashParams = displayHashAlgorithmsMap.get(hashAlgorithm);
        if (displayHashParams == null) {
            Slog.w(TAG, "Failed to generateDisplayHash. Invalid hashAlgorithm");
            sendDisplayHashError(callback, DISPLAY_HASH_ERROR_INVALID_HASH_ALGORITHM);
            return;
        }

        Size size = displayHashParams.getBufferSize();
        if (size != null && (size.getWidth() > 0 || size.getHeight() > 0)) {
            args.setFrameScale((float) size.getWidth() / boundsInWindow.width(),
                    (float) size.getHeight() / boundsInWindow.height());
        }

        args.setGrayscale(displayHashParams.isGrayscaleBuffer());

        SurfaceControl.ScreenshotHardwareBuffer screenshotHardwareBuffer =
                SurfaceControl.captureLayers(args.build());
        if (screenshotHardwareBuffer == null
                || screenshotHardwareBuffer.getHardwareBuffer() == null) {
            Slog.w(TAG, "Failed to generate DisplayHash. Couldn't capture content");
            sendDisplayHashError(callback, DISPLAY_HASH_ERROR_UNKNOWN);
            return;
        }

        generateDisplayHash(screenshotHardwareBuffer.getHardwareBuffer(), boundsInWindow,
                hashAlgorithm, callback);
    }

    private Map<String, DisplayHashParams> getDisplayHashAlgorithms() {
        // We have a separate lock for the hashing params to ensure we can properly cache the
        // hashing params so we don't need to call into the ExtServices process for each request.
        synchronized (mDisplayHashAlgorithmsLock) {
            if (mDisplayHashAlgorithms != null) {
                return mDisplayHashAlgorithms;
            }

            final SyncCommand syncCommand = new SyncCommand();
            Bundle results = syncCommand.run((service, remoteCallback) -> {
                try {
                    service.getDisplayHashAlgorithms(remoteCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to invoke getDisplayHashAlgorithms command", e);
                }
            });

            mDisplayHashAlgorithms = new HashMap<>(results.size());
            for (String key : results.keySet()) {
                mDisplayHashAlgorithms.put(key, results.getParcelable(key));
            }

            return mDisplayHashAlgorithms;
        }
    }

    void sendDisplayHashError(RemoteCallback callback, int errorCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_DISPLAY_HASH_ERROR_CODE, errorCode);
        callback.sendResult(bundle);
    }

    /**
     * Calculate the bounds to generate the hash for. This takes into account window transform,
     * magnification, and display bounds.
     *
     * Call while holding {@link WindowManagerService#mGlobalLock}
     *
     * @param win            Window that the DisplayHash is generated for.
     * @param boundsInWindow The bounds in the window where to generate the hash.
     * @param outBounds      The result of the calculated bounds
     */
    void calculateDisplayHashBoundsLocked(WindowState win, Rect boundsInWindow,
            Rect outBounds) {
        if (DEBUG) {
            Slog.d(TAG,
                    "calculateDisplayHashBoundsLocked: boundsInWindow=" + boundsInWindow);
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
            Slog.d(TAG,
                    "calculateDisplayHashBoundsLocked: boundsIntersectWindow=" + outBounds);
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
            Slog.d(TAG, "calculateDisplayHashBoundsLocked: boundsInDisplay=" + outBounds);
        }

        // Apply the magnification spec values to the bounds since the content could be magnified
        final MagnificationSpec magSpec = displayContent.getMagnificationSpec();
        if (magSpec != null) {
            outBounds.scale(magSpec.scale);
            outBounds.offset((int) magSpec.offsetX, (int) magSpec.offsetY);
        }

        if (DEBUG) {
            Slog.d(TAG, "calculateDisplayHashBoundsLocked: boundsWithMagnification="
                    + outBounds);
        }

        if (outBounds.isEmpty()) {
            return;
        }

        // Intersect with the display bounds since content outside the display are not visible to
        // the user.
        final Rect displayBounds = displayContent.getBounds();
        outBounds.intersectUnchecked(displayBounds);
        if (DEBUG) {
            Slog.d(TAG, "calculateDisplayHashBoundsLocked: finalBounds=" + outBounds);
        }
    }

    private int getIntervalBetweenRequestMillis() {
        // We have a separate lock for the hashing params to ensure we can properly cache the
        // hashing params so we don't need to call into the ExtServices process for each request.
        synchronized (mIntervalBetweenRequestsLock) {
            if (mIntervalBetweenRequestMillis != -1) {
                return mIntervalBetweenRequestMillis;
            }

            final SyncCommand syncCommand = new SyncCommand();
            Bundle results = syncCommand.run((service, remoteCallback) -> {
                try {
                    service.getIntervalBetweenRequestsMillis(remoteCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to invoke getDisplayHashAlgorithms command", e);
                }
            });

            mIntervalBetweenRequestMillis = results.getInt(EXTRA_INTERVAL_BETWEEN_REQUESTS, 0);
            return mIntervalBetweenRequestMillis;
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
                mServiceConnection = new DisplayHashingServiceConnection();

                final ComponentName component = getServiceComponentName();
                if (DEBUG) Slog.v(TAG, "binding to: " + component);
                if (component != null) {
                    final Intent intent = new Intent();
                    intent.setComponent(component);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
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

        final Intent intent = new Intent(DisplayHashingService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo;
        final long token = Binder.clearCallingIdentity();
        try {
            resolveInfo = mContext.getPackageManager().resolveService(intent,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

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
        if (!Manifest.permission.BIND_DISPLAY_HASHING_SERVICE
                .equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " requires permission "
                    + Manifest.permission.BIND_DISPLAY_HASHING_SERVICE);
            return null;
        }

        if (DEBUG) Slog.v(TAG, "getServiceComponentName(): " + name);
        return name;
    }

    private class SyncCommand {
        private static final int WAIT_TIME_S = 5;
        private Bundle mResult;
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

        public Bundle run(BiConsumer<IDisplayHashingService, RemoteCallback> func) {
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

    private class DisplayHashingServiceConnection implements ServiceConnection {
        @GuardedBy("mServiceConnectionLock")
        private IDisplayHashingService mRemoteService;

        @GuardedBy("mServiceConnectionLock")
        private ArrayList<Command> mQueuedCommands;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.v(TAG, "onServiceConnected(): " + name);
            synchronized (mServiceConnectionLock) {
                mRemoteService = IDisplayHashingService.Stub.asInterface(service);
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
