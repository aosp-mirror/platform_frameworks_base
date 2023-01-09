/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm.scvh;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.cts.surfacevalidator.ISurfaceValidatorTestCase;
import android.view.cts.surfacevalidator.PixelChecker;
import android.widget.FrameLayout;
import android.window.SurfaceSyncGroup;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SyncValidatorSCVHTestCase implements ISurfaceValidatorTestCase {
    private static final String TAG = "SCVHSyncValidatorTestCase";

    private final Point[] mSizes = new Point[]{new Point(500, 500), new Point(700, 400),
            new Point(300, 800), new Point(200, 200)};
    private int mLastSizeIndex = 1;

    private long mDelayMs;

    public SyncValidatorSCVHTestCase(long delayMs) {
        mDelayMs = delayMs;
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            Point size = mSizes[mLastSizeIndex % mSizes.length];
            Runnable svResizeRunnable = () -> {
                ViewGroup.LayoutParams svParams = mSurfaceView.getLayoutParams();
                svParams.width = size.x;
                svParams.height = size.y;
                mSurfaceView.setLayoutParams(svParams);
            };
            Runnable resizeRunnable = () -> {
                try {
                    final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size.x,
                            size.y,
                            WindowManager.LayoutParams.TYPE_APPLICATION, 0,
                            PixelFormat.TRANSPARENT);
                    mIAttachEmbeddedWindow.relayout(lp);
                } catch (RemoteException e) {
                }
            };

            SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
            syncGroup.addToSync(mSurfaceView.getRootSurfaceControl(), svResizeRunnable);
            syncGroup.addToSync(mSurfacePackage, resizeRunnable);
            syncGroup.markSyncReady();

            mLastSizeIndex++;

            mHandler.postDelayed(this, mDelayMs + 50);
        }
    };

    private Handler mHandler;
    private SurfaceView mSurfaceView;

    private final CountDownLatch mReadyLatch = new CountDownLatch(1);
    private boolean mSurfaceCreated;
    private boolean mIsAttached;
    private final Object mLock = new Object();
    private int mDisplayId;
    private IAttachEmbeddedWindow mIAttachEmbeddedWindow;
    private SurfacePackage mSurfacePackage;

    final SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            synchronized (mLock) {
                mSurfaceCreated = true;
            }
            if (isReadyToAttach()) {
                attachEmbedded();
            }
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        }
    };

    @Override
    public PixelChecker getChecker() {
        return new PixelChecker(Color.BLACK) {
            @Override
            public boolean checkPixels(int matchingPixelCount, int width, int height) {
                // Content has been set up yet.
                if (mReadyLatch.getCount() > 0) {
                    return true;
                }
                return matchingPixelCount == 0;
            }
        };
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        // Called when the connection with the service is established
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Service Connected");
            synchronized (mLock) {
                mIAttachEmbeddedWindow = IAttachEmbeddedWindow.Stub.asInterface(service);
            }
            if (isReadyToAttach()) {
                attachEmbedded();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service Disconnected");
            mIAttachEmbeddedWindow = null;
            synchronized (mLock) {
                mIsAttached = false;
            }
        }
    };

    private boolean isReadyToAttach() {
        synchronized (mLock) {
            if (!mSurfaceCreated) {
                Log.d(TAG, "surface is not created");
            }
            if (mIAttachEmbeddedWindow == null) {
                Log.d(TAG, "Service is not attached");
            }
            if (mIsAttached) {
                Log.d(TAG, "Already attached");
            }

            return mSurfaceCreated && mIAttachEmbeddedWindow != null && !mIsAttached;
        }
    }

    private void attachEmbedded() {
        synchronized (mLock) {
            mIsAttached = true;
        }
        try {
            mIAttachEmbeddedWindow.attachEmbedded(mSurfaceView.getHostToken(), mSizes[0].x,
                    mSizes[0].y, mDisplayId, mDelayMs, new IAttachEmbeddedWindowCallback.Stub() {
                        @Override
                        public void onEmbeddedWindowAttached(SurfacePackage surfacePackage) {
                            mHandler.post(() -> {
                                mSurfacePackage = surfacePackage;
                                mSurfaceView.setChildSurfacePackage(surfacePackage);
                                mReadyLatch.countDown();
                            });
                        }
                    });
        } catch (RemoteException e) {
        }
    }

    @Override
    public void start(Context context, FrameLayout parent) {
        mDisplayId = context.getDisplayId();
        mHandler = new Handler(Looper.getMainLooper());

        Intent intent = new Intent(context, EmbeddedSCVHService.class);
        intent.setAction(EmbeddedSCVHService.class.getName());
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        mSurfaceView = new SurfaceView(context);
        mSurfaceView.getHolder().addCallback(mCallback);

        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(mSizes[0].x,
                mSizes[0].y);
        layoutParams.gravity = Gravity.CENTER;
        parent.addView(mSurfaceView, layoutParams);
    }

    @Override
    public void waitForReady() {

        try {
            mReadyLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }

        assertEquals("Timed out waiting for setup", 0, mReadyLatch.getCount());
        assertNotNull("SurfacePackage is null", mSurfacePackage);

        mHandler.post(mRunnable);
    }

    @Override
    public void end() {
        mHandler.removeCallbacks(mRunnable);
    }
}
