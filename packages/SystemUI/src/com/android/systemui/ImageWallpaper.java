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
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.util.Size;
import android.view.DisplayInfo;
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

import javax.inject.Inject;

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
    private final DozeParameters mDozeParameters;
    private HandlerThread mWorker;

    @Inject
    public ImageWallpaper(DozeParameters dozeParameters) {
        super();
        mDozeParameters = dozeParameters;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return new GLEngine(this, mDozeParameters);
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
        private boolean mShouldStopTransition;
        private final DisplayInfo mDisplayInfo = new DisplayInfo();
        private final Object mMonitor = new Object();
        @VisibleForTesting
        boolean mIsHighEndGfx;
        private boolean mDisplayNeedsBlanking;
        private boolean mNeedTransition;
        private boolean mNeedRedraw;
        // This variable can only be accessed in synchronized block.
        private boolean mWaitingForRendering;

        GLEngine(Context context, DozeParameters dozeParameters) {
            init(dozeParameters);
        }

        @VisibleForTesting
        GLEngine(DozeParameters dozeParameters, Handler handler) {
            super(SystemClock::elapsedRealtime, handler);
            init(dozeParameters);
        }

        private void init(DozeParameters dozeParameters) {
            mIsHighEndGfx = ActivityManager.isHighEndGfx();
            mDisplayNeedsBlanking = dozeParameters.getDisplayNeedsBlanking();
            mNeedTransition = mIsHighEndGfx && !mDisplayNeedsBlanking;

            // We will preserve EGL context when we are in lock screen or aod
            // to avoid janking in following transition, we need to release when back to home.
            mController = Dependency.get(StatusBarStateController.class);
            if (mController != null) {
                mController.addCallback(this /* StateListener */);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            mEglHelper = getEglHelperInstance();
            // Deferred init renderer because we need to get wallpaper by display context.
            mRenderer = getRendererInstance();
            getDisplayContext().getDisplay().getDisplayInfo(mDisplayInfo);
            setFixedSizeAllowed(true);
            setOffsetNotificationsEnabled(true);
            updateSurfaceSize();
        }

        EglHelper getEglHelperInstance() {
            return new EglHelper();
        }

        ImageWallpaperRenderer getRendererInstance() {
            return new ImageWallpaperRenderer(getDisplayContext(), this /* SurfaceProxy */);
        }

        private void updateSurfaceSize() {
            SurfaceHolder holder = getSurfaceHolder();
            Size frameSize = mRenderer.reportSurfaceSize();
            int width = Math.max(MIN_SURFACE_WIDTH, frameSize.getWidth());
            int height = Math.max(MIN_SURFACE_HEIGHT, frameSize.getHeight());
            holder.setFixedSize(width, height);
        }

        /**
         * Check if necessary to stop transition with current wallpaper on this device. <br/>
         * This should only be invoked after {@link #onSurfaceCreated(SurfaceHolder)}}
         * is invoked since it needs display context and surface frame size.
         * @return true if need to stop transition.
         */
        @VisibleForTesting
        boolean checkIfShouldStopTransition() {
            int orientation = getDisplayContext().getResources().getConfiguration().orientation;
            Rect frame = getSurfaceHolder().getSurfaceFrame();
            Rect display = new Rect();
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                display.set(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
            } else {
                display.set(0, 0, mDisplayInfo.logicalHeight, mDisplayInfo.logicalWidth);
            }
            return mNeedTransition
                    && (frame.width() < display.width() || frame.height() < display.height());
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (mWorker == null) return;
            mWorker.getThreadHandler().post(() -> mRenderer.updateOffsets(xOffset, yOffset));
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode, long animationDuration) {
            if (mWorker == null || !mNeedTransition) return;
            final long duration = mShouldStopTransition ? 0 : animationDuration;
            if (DEBUG) {
                Log.d(TAG, "onAmbientModeChanged: inAmbient=" + inAmbientMode
                        + ", duration=" + duration
                        + ", mShouldStopTransition=" + mShouldStopTransition);
            }
            mWorker.getThreadHandler().post(
                    () -> mRenderer.updateAmbientMode(inAmbientMode, duration));
            if (inAmbientMode && animationDuration == 0) {
                // This means that we are transiting from home to aod, to avoid
                // race condition between window visibility and transition,
                // we don't return until the transition is finished. See b/136643341.
                waitForBackgroundRendering();
            }
        }

        @Override
        public boolean shouldZoomOutWallpaper() {
            return true;
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
            });
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            mShouldStopTransition = checkIfShouldStopTransition();
            if (mWorker == null) return;
            mWorker.getThreadHandler().post(() -> {
                mEglHelper.init(holder, needSupportWideColorGamut());
                mRenderer.onSurfaceCreated();
            });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mWorker == null) return;
            mWorker.getThreadHandler().post(() -> {
                mRenderer.onSurfaceChanged(width, height);
                mNeedRedraw = true;
            });
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (mWorker == null) return;
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
            if (mWorker != null && mController.getState() == StatusBarState.SHADE) {
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
                if (!mEglHelper.createEglSurface(getSurfaceHolder(), needSupportWideColorGamut())) {
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
            if (mWorker == null) return;
            mWorker.getThreadHandler().removeCallbacks(mFinishRenderingTask);
        }

        private void scheduleFinishRendering() {
            if (mWorker == null) return;
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

        private boolean needSupportWideColorGamut() {
            return mRenderer.isWcgContent();
        }

        @Override
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);
            out.print(prefix); out.print("Engine="); out.println(this);
            out.print(prefix); out.print("isHighEndGfx="); out.println(mIsHighEndGfx);
            out.print(prefix); out.print("displayNeedsBlanking=");
            out.println(mDisplayNeedsBlanking);
            out.print(prefix); out.print("displayInfo="); out.print(mDisplayInfo);
            out.print(prefix); out.print("mNeedTransition="); out.println(mNeedTransition);
            out.print(prefix); out.print("mShouldStopTransition=");
            out.println(mShouldStopTransition);
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
