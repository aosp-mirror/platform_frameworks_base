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

package com.android.server.wm;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.cts.surfacevalidator.ISurfaceValidatorTestCase;
import android.view.cts.surfacevalidator.PixelChecker;
import android.widget.FrameLayout;
import android.window.SurfaceSyncer;

import androidx.annotation.NonNull;

/**
 * A validator class that will create a SurfaceView and then update its size over and over. The code
 * will request to sync the SurfaceView content with the main window and validate that there was
 * never an empty area (black color). The test uses {@link SurfaceSyncer} class to gather the
 * content it wants to synchronize.
 */
public class SurfaceSyncerValidatorTestCase implements ISurfaceValidatorTestCase {
    private static final String TAG = "SurfaceSyncerValidatorTestCase";

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            updateSurfaceViewSize();
            mHandler.postDelayed(this, 100);
        }
    };

    private Handler mHandler;
    private SurfaceView mSurfaceView;
    private boolean mLastExpanded = true;
    private final SurfaceSyncer mSurfaceSyncer = new SurfaceSyncer();

    private RenderingThread mRenderingThread;
    private FrameLayout mParent;

    private int mLastSyncId = -1;

    final SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            final Canvas canvas = holder.lockCanvas();
            canvas.drawARGB(255, 100, 100, 100);
            holder.unlockCanvasAndPost(canvas);
            Log.d(TAG, "surfaceCreated");
            mRenderingThread = new RenderingThread(holder);
            mRenderingThread.start();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
            if (mLastSyncId >= 0) {
                mSurfaceSyncer.addToSync(mLastSyncId, mSurfaceView, frameCallback ->
                        mRenderingThread.setFrameCallback(frameCallback));
                mSurfaceSyncer.markSyncReady(mLastSyncId);
                mLastSyncId = -1;
            }
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            mRenderingThread.stopRendering();
        }
    };

    @Override
    public PixelChecker getChecker() {
        return new PixelChecker(Color.BLACK) {
            @Override
            public boolean checkPixels(int matchingPixelCount, int width, int height) {
                return matchingPixelCount == 0;
            }
        };
    }

    @Override
    public void start(Context context, FrameLayout parent) {
        mSurfaceView = new SurfaceView(context);
        mSurfaceView.getHolder().addCallback(mCallback);
        mParent = parent;

        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(MATCH_PARENT, 600);
        parent.addView(mSurfaceView, layoutParams);
        mHandler = new Handler(Looper.getMainLooper());
        mHandler.post(mRunnable);
    }

    @Override
    public void end() {
        mHandler.removeCallbacks(mRunnable);
    }

    public void updateSurfaceViewSize() {
        if (mRenderingThread == null || mLastSyncId >= 0 || !mRenderingThread.isReadyToSync()) {
            return;
        }

        Log.d(TAG, "updateSurfaceViewSize");

        final int height;
        if (mLastExpanded) {
            height = 300;
        } else {
            height = 600;
        }
        mLastExpanded = !mLastExpanded;

        mRenderingThread.pauseRendering();
        mLastSyncId = mSurfaceSyncer.setupSync(() -> { });
        mSurfaceSyncer.addToSync(mLastSyncId, mParent);

        ViewGroup.LayoutParams svParams = mSurfaceView.getLayoutParams();
        svParams.height = height;
        mSurfaceView.setLayoutParams(svParams);
    }

    private static class RenderingThread extends HandlerThread {
        private final SurfaceHolder mSurfaceHolder;
        private SurfaceSyncer.SurfaceViewFrameCallback mFrameCallback;
        private boolean mPauseRendering;
        private boolean mComplete;

        int mColorValue = 0;
        int mColorDelta = 10;

        @Override
        public void run() {
            try {
                while (true) {
                    sleep(10);
                    synchronized (this) {
                        if (mComplete) {
                            break;
                        }
                        if (mPauseRendering) {
                            continue;
                        }

                        if (mFrameCallback != null) {
                            Log.d(TAG, "onFrameStarted");
                            mFrameCallback.onFrameStarted();
                        }

                        mColorValue += mColorDelta;
                        if (mColorValue > 245 || mColorValue < 10) {
                            mColorDelta *= -1;
                        }

                        Canvas c = mSurfaceHolder.lockCanvas();
                        if (c != null) {
                            c.drawRGB(255, mColorValue, 255 - mColorValue);
                            mSurfaceHolder.unlockCanvasAndPost(c);
                        }

                        mFrameCallback = null;
                    }
                }
            } catch (InterruptedException e) {
            }
        }

        RenderingThread(SurfaceHolder holder) {
            super("RenderingThread");
            mSurfaceHolder = holder;
        }

        public void pauseRendering() {
            synchronized (this) {
                mPauseRendering = true;
            }
        }

        private boolean isReadyToSync() {
            synchronized (this) {
                return mFrameCallback == null;
            }
        }
        public void setFrameCallback(SurfaceSyncer.SurfaceViewFrameCallback frameCallback) {
            synchronized (this) {
                mFrameCallback = frameCallback;
                mPauseRendering = false;
            }
        }

        public void stopRendering() {
            synchronized (this) {
                mComplete = true;
            }
        }
    }
}
