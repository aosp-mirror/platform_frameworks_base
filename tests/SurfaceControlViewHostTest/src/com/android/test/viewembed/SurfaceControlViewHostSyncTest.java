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

package com.android.test.viewembed;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.window.SurfaceSyncGroup;

public class SurfaceControlViewHostSyncTest extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "SurfaceControlViewHostSyncTest";
    private SurfaceView mSv;

    private final Object mLock = new Object();
    private boolean mIsAttached;
    private boolean mSurfaceCreated;

    private IAttachEmbeddedWindow mIAttachEmbeddedWindow;
    private SurfacePackage mSurfacePackage;

    private final Point[] mSizes = new Point[]{new Point(500, 500), new Point(700, 400),
            new Point(300, 800), new Point(200, 200)};
    private int mLastSizeIndex = 0;

    private boolean mSync = true;

    private final ServiceConnection mConnection = new ServiceConnection() {
        // Called when the connection with the service is established
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Service Connected");
            synchronized (mLock) {
                mIAttachEmbeddedWindow = IAttachEmbeddedWindow.Stub.asInterface(service);
            }
            loadEmbedded();
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service Disconnected");
            mIAttachEmbeddedWindow = null;
        }
    };

    protected void onCreate(Bundle savedInstanceState) {
        FrameLayout content = new FrameLayout(this);
        super.onCreate(savedInstanceState);
        mSv = new SurfaceView(this);
        Button button = new Button(this);
        Switch enableSyncButton = new Switch(this);
        content.addView(mSv, new FrameLayout.LayoutParams(
                mSizes[0].x, mSizes[0].y, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        content.addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM));
        content.addView(enableSyncButton,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM));
        content.setFitsSystemWindows(true);
        setContentView(content);

        mSv.setZOrderOnTop(false);
        mSv.getHolder().addCallback(this);

        button.setText("Change Size");
        enableSyncButton.setText("Enable Sync");
        enableSyncButton.setChecked(true);
        button.setOnClickListener(v -> {
            resize();
        });

        enableSyncButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSync = isChecked;
        });

        Intent intent = new Intent(this, EmbeddedWindowService.class);
        intent.setAction(IAttachEmbeddedWindow.class.getName());
        Log.d(TAG, "bindService");
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void resize() {
        if (mSurfacePackage == null) {
            return;
        }
        Point size = mSizes[mLastSizeIndex % mSizes.length];

        Runnable svResizeRunnable = () -> {
            mSv.getLayoutParams().width = size.x;
            mSv.getLayoutParams().height = size.y;
            mSv.requestLayout();
        };

        Runnable resizeRunnable = () -> {
            try {
                final WindowManager.LayoutParams lp =
                        new WindowManager.LayoutParams(size.x, size.y,
                                WindowManager.LayoutParams.TYPE_APPLICATION, 0,
                                PixelFormat.TRANSPARENT);
                mIAttachEmbeddedWindow.relayout(lp);
            } catch (RemoteException e) {
            }
        };

        if (mSync) {
            SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
            syncGroup.add(getWindow().getRootSurfaceControl(), svResizeRunnable);
            syncGroup.add(mSurfacePackage, resizeRunnable);
            syncGroup.markSyncReady();
        } else {
            svResizeRunnable.run();
            resizeRunnable.run();
        }

        mLastSizeIndex++;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (mLock) {
            mSurfaceCreated = true;
        }
        attachEmbedded();
    }

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

            return mSurfaceCreated && mIAttachEmbeddedWindow != null && !mIsAttached
                    && mSurfacePackage != null;
        }
    }

    private void loadEmbedded() {
        try {
            mIAttachEmbeddedWindow.attachEmbedded(mSv.getHostToken(), mSizes[0].x, mSizes[0].y,
                    new IAttachEmbeddedWindowCallback.Stub() {
                        @Override
                        public void onEmbeddedWindowAttached(SurfacePackage surfacePackage) {
                            getMainThreadHandler().post(() -> {
                                mSurfacePackage = surfacePackage;
                                attachEmbedded();
                            });
                        }
                    });
            mLastSizeIndex++;
        } catch (RemoteException e) {
        }
    }

    private void attachEmbedded() {
        if (!isReadyToAttach()) {
            return;
        }

        synchronized (mLock) {
            mIsAttached = true;
        }
        mSv.setChildSurfacePackage(mSurfacePackage);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (mLock) {
            mSurfaceCreated = false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        resize();
    }
}
