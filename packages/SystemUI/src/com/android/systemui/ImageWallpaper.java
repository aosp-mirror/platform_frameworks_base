/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.systemui;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.glwallpaper.EglHelper;
import com.android.systemui.glwallpaper.GLWallpaperRenderer;
import com.android.systemui.glwallpaper.ImageWallpaperRenderer;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.DozeParameters;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {
    private static final String TAG = ImageWallpaper.class.getSimpleName();
    // We delayed destroy render context that subsequent render requests have chance to cancel it.
    // This is to avoid destroying then recreating render context in a very short time.
    private static final int DELAY_FINISH_RENDERING = 1000;
    private static final int INTERVAL_WAIT_FOR_RENDERING = 100;
    private static final int PATIENCE_WAIT_FOR_RENDERING = 10;
    private static final boolean DEBUG = true;
    private HandlerThread mWorker;

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return new GLEngine(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWorker.quitSafely();
        mWorker = null;
    }

    class GLEngine extends Engine implements GLWallpaperRenderer.SurfaceProxy, StateListener {
        // Surface is rejected if size below a threshold on some devices (ie. 8px on elfin)
        // set min to 64 px (CTS covers this), please refer to ag/4867989 for detail.
        @VisibleForTesting
        static final int MIN_SURFACE_WIDTH = 64;
        @VisibleForTesting
        static final int MIN_SURFACE_HEIGHT = 64;

        private GLWallpaperRenderer mRenderer;
        private EglHelper mEglHelper;
        private StatusBarStateController mController;
        private final Runnable mFinishRenderingTask = this::finishRendering;
        private final boolean mNeedTransition;
        private final Object mMonitor = new Object();
        private boolean mNeedRedraw;
        // This variable can only be accessed in synchronized block.
        private boolean mWaitingForRendering;

        GLEngine(Context context) {
            mNeedTransition = ActivityManager.isHighEndGfx()
                    && !DozeParameters.getInstance(context).getDisplayNeedsBlanking();

            // We will preserve EGL context when we are in lock screen or aod
            // to avoid janking in following transition, we need to release when back to home.
            mController = Dependency.get(StatusBarStateController.class);
            if (mController != null) {
                mController.addCallback(this /* StateListener */);
            }
            mEglHelper = new EglHelper();
            mRenderer = new ImageWallpaperRenderer(context, this /* SurfaceProxy */);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            setFixedSizeAllowed(true);
            setOffsetNotificationsEnabled(true);
            updateSurfaceSize();
        }

        private void updateSurfaceSize() {
            SurfaceHolder holder = getSurfaceHolder();
            Size frameSize = mRenderer.reportSurfaceSize();
            int width = Math.max(MIN_SURFACE_WIDTH, frameSize.getWidth());
            int height = Math.max(MIN_SURFACE_HEIGHT, frameSize.getHeight());
            holder.setFixedSize(width, height);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            mWorker.getThreadHandler().post(() -> mRenderer.updateOffsets(xOffset, yOffset));
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode, long animationDuration) {
            if (!mNeedTransition) return;
            if (DEBUG) {
                Log.d(TAG, "onAmbientModeChanged: inAmbient=" + inAmbientMode
                        + ", duration=" + animationDuration);
            }
            mWorker.getThreadHandler().post(
                    () -> mRenderer.updateAmbientMode(inAmbientMode, animationDuration));
            if (inAmbientMode && animationDuration == 0) {
                // This means that we are transiting from home to aod, to avoid
                // race condition between window visibility and transition,
                // we don't return until the transition is finished. See b/136643341.
                waitForBackgroundRendering();
            }
        }

        private void waitForBackgroundRendering() {
            synchronized (mMonitor) {
                try {
                    mWaitingForRendering = true;
                    for (int patience = 1; mWaitingForRendering; patience++) {
                        mMonitor.wait(INTERVAL_WAIT_FOR_RENDERING);
                        mWaitingForRendering &= patience < PATIENCE_WAIT_FOR_RENDERING;
                    }
                } catch (InterruptedException ex) {
                } finally {
                    mWaitingForRendering = false;
                }
            }
        }

        @Override
        public void onDestroy() {
            if (mController != null) {
                mController.removeCallback(this /* StateListener */);
            }
            mController = null;

            mWorker.getThreadHandler().post(() -> {
                mRenderer.finish();
                mRenderer = null;
                mEglHelper.finish();
                mEglHelper = null;
                getSurfaceHolder().getSurface().hwuiDestroy();
            });
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            mWorker.getThreadHandler().post(() -> {
                mEglHelper.init(holder);
                mRenderer.onSurfaceCreated();
            });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mWorker.getThreadHandler().post(() -> {
                mRenderer.onSurfaceChanged(width, height);
                mNeedRedraw = true;
            });
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceRedrawNeeded: mNeedRedraw=" + mNeedRedraw);
            }

            mWorker.getThreadHandler().post(() -> {
                if (mNeedRedraw) {
                    drawFrame();
                    mNeedRedraw = false;
                }
            });
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (DEBUG) {
                Log.d(TAG, "wallpaper visibility changes: " + visible);
            }
        }

        private void drawFrame() {
            preRender();
            requestRender();
            postRender();
        }

        @Override
        public void onStatePostChange() {
            // When back to home, we try to release EGL, which is preserved in lock screen or aod.
            if (mController.getState() == StatusBarState.SHADE) {
                mWorker.getThreadHandler().post(this::scheduleFinishRendering);
            }
        }

        @Override
        public void preRender() {
            // This method should only be invoked from worker thread.
            Trace.beginSection("ImageWallpaper#preRender");
            preRenderInternal();
            Trace.endSection();
        }

        private void preRenderInternal() {
            boolean contextRecreated = false;
            Rect frame = getSurfaceHolder().getSurfaceFrame();
            cancelFinishRenderingTask();

            // Check if we need to recreate egl context.
            if (!mEglHelper.hasEglContext()) {
                mEglHelper.destroyEglSurface();
                if (!mEglHelper.createEglContext()) {
                    Log.w(TAG, "recreate egl context failed!");
                } else {
                    contextRecreated = true;
                }
            }

            // Check if we need to recreate egl surface.
            if (mEglHelper.hasEglContext() && !mEglHelper.hasEglSurface()) {
                if (!mEglHelper.createEglSurface(getSurfaceHolder())) {
                    Log.w(TAG, "recreate egl surface failed!");
                }
            }

            // If we recreate egl context, notify renderer to setup again.
            if (mEglHelper.hasEglContext() && mEglHelper.hasEglSurface() && contextRecreated) {
                mRenderer.onSurfaceCreated();
                mRenderer.onSurfaceChanged(frame.width(), frame.height());
            }
        }

        @Override
        public void requestRender() {
            // This method should only be invoked from worker thread.
            Trace.beginSection("ImageWallpaper#requestRender");
            requestRenderInternal();
            Trace.endSection();
        }

        private void requestRenderInternal() {
            Rect frame = getSurfaceHolder().getSurfaceFrame();
            boolean readyToRender = mEglHelper.hasEglContext() && mEglHelper.hasEglSurface()
                    && frame.width() > 0 && frame.height() > 0;

            if (readyToRender) {
                mRenderer.onDrawFrame();
                if (!mEglHelper.swapBuffer()) {
                    Log.e(TAG, "drawFrame failed!");
                }
            } else {
                Log.e(TAG, "requestRender: not ready, has context=" + mEglHelper.hasEglContext()
                        + ", has surface=" + mEglHelper.hasEglSurface()
                        + ", frame=" + frame);
            }
        }

        @Override
        public void postRender() {
            // This method should only be invoked from worker thread.
            Trace.beginSection("ImageWallpaper#postRender");
            notifyWaitingThread();
            scheduleFinishRendering();
            Trace.endSection();
        }

        private void notifyWaitingThread() {
            synchronized (mMonitor) {
                if (mWaitingForRendering) {
                    try {
                        mWaitingForRendering = false;
                        mMonitor.notify();
                    } catch (IllegalMonitorStateException ex) {
                    }
                }
            }
        }

        private void cancelFinishRenderingTask() {
            mWorker.getThreadHandler().removeCallbacks(mFinishRenderingTask);
        }

        private void scheduleFinishRendering() {
            cancelFinishRenderingTask();
            mWorker.getThreadHandler().postDelayed(mFinishRenderingTask, DELAY_FINISH_RENDERING);
        }

        private void finishRendering() {
            Trace.beginSection("ImageWallpaper#finishRendering");
            if (mEglHelper != null) {
                mEglHelper.destroyEglSurface();
                if (!needPreserveEglContext()) {
                    mEglHelper.destroyEglContext();
                }
            }
            Trace.endSection();
        }

        private boolean needPreserveEglContext() {
            return mNeedTransition && mController != null
                    && mController.getState() == StatusBarState.KEYGUARD;
        }

        @Override
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);
            out.print(prefix); out.print("Engine="); out.println(this);

            boolean isHighEndGfx = ActivityManager.isHighEndGfx();
            out.print(prefix); out.print("isHighEndGfx="); out.println(isHighEndGfx);

            DozeParameters dozeParameters = DozeParameters.getInstance(getApplicationContext());
            out.print(prefix); out.print("displayNeedsBlanking=");
            out.println(dozeParameters != null ? dozeParameters.getDisplayNeedsBlanking() : "null");

            out.print(prefix); out.print("mNeedTransition="); out.println(mNeedTransition);
            out.print(prefix); out.print("StatusBarState=");
            out.println(mController != null ? mController.getState() : "null");

            out.print(prefix); out.print("valid surface=");
            out.println(getSurfaceHolder() != null && getSurfaceHolder().getSurface() != null
                    ? getSurfaceHolder().getSurface().isValid()
                    : "null");

            out.print(prefix); out.print("surface frame=");
            out.println(getSurfaceHolder() != null ? getSurfaceHolder().getSurfaceFrame() : "null");

            mEglHelper.dump(prefix, fd, out, args);
            mRenderer.dump(prefix, fd, out, args);
        }
    }
}
