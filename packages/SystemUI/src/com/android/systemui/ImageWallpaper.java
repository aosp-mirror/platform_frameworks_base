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

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.glwallpaper.EglHelper;
import com.android.systemui.glwallpaper.ImageWallpaperRenderer;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
    private static final @android.annotation.NonNull RectF LOCAL_COLOR_BOUNDS =
            new RectF(0, 0, 1, 1);
    private static final boolean DEBUG = false;
    private final StatusBarStateController mStatusBarStateController;
    private final ArrayList<RectF> mLocalColorsToAdd = new ArrayList<>();
    private final ArraySet<RectF> mColorAreas = new ArraySet<>();
    private float mShift;
    private volatile int mPages;
    private HandlerThread mWorker;
    // scaled down version
    private Bitmap mMiniBitmap;

    @Inject
    public ImageWallpaper(StatusBarStateController statusBarStateController) {
        super();
        mStatusBarStateController = statusBarStateController;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return new GLEngine();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWorker.quitSafely();
        mWorker = null;
        mMiniBitmap = null;
    }


    class GLEngine extends Engine implements StatusBarStateController.StateListener,
            Choreographer.FrameCallback {
        // Surface is rejected if size below a threshold on some devices (ie. 8px on elfin)
        // set min to 64 px (CTS covers this), please refer to ag/4867989 for detail.
        @VisibleForTesting
        static final int MIN_SURFACE_WIDTH = 64;
        @VisibleForTesting
        static final int MIN_SURFACE_HEIGHT = 64;

        private ImageWallpaperRenderer mRenderer;
        private EglHelper mEglHelper;
        private final Runnable mFinishRenderingTask = this::finishRendering;
        private final Runnable mInitChoreographerTask = this::initChoreographerInternal;
        private int mWidth = 1;
        private int mHeight = 1;
        private int mImgWidth = 1;
        private int mImgHeight = 1;
        private volatile float mDozeAmount;
        private volatile boolean mNewDozeValue = false;
        private volatile boolean mShouldScheduleFrame = false;

        GLEngine() {
        }

        @VisibleForTesting
        GLEngine(Handler handler) {
            super(SystemClock::elapsedRealtime, handler);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            mEglHelper = getEglHelperInstance();
            // Deferred init renderer because we need to get wallpaper by display context.
            mRenderer = getRendererInstance();
            setFixedSizeAllowed(true);
            updateSurfaceSize();
            Rect window = getDisplayContext()
                    .getSystemService(WindowManager.class)
                    .getCurrentWindowMetrics()
                    .getBounds();
            mHeight = window.height();
            mWidth = window.width();
            mMiniBitmap = null;
            if (mWorker != null && mWorker.getThreadHandler() != null) {
                mWorker.getThreadHandler().post(this::updateMiniBitmap);
            }

            mDozeAmount = mStatusBarStateController.getDozeAmount();
            mStatusBarStateController.addCallback(this);
        }

        EglHelper getEglHelperInstance() {
            return new EglHelper();
        }

        ImageWallpaperRenderer getRendererInstance() {
            return new ImageWallpaperRenderer(getDisplayContext());
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixelOffset, int yPixelOffset) {
            if (mMiniBitmap == null || mMiniBitmap.isRecycled()) return;
            final int pages;
            if (xOffsetStep > 0 && xOffsetStep <= 1) {
                pages = (int) (1 / xOffsetStep + 1);
            } else {
                pages = 1;
            }
            if (pages == mPages) return;
            mPages = pages;
            updateShift();
            mWorker.getThreadHandler().post(() ->
                    computeAndNotifyLocalColors(new ArrayList<>(mColorAreas), mMiniBitmap));
        }

        private void updateShift() {
            if (mImgHeight == 0) {
                mShift = 0;
                return;
            }
            // calculate shift
            float imgWidth = (float) mImgWidth / (float) mImgHeight;
            float displayWidth =
                    (float) mWidth / (float) mHeight;
            // if need to shift
            if (imgWidth > displayWidth) {
                mShift = imgWidth / imgWidth - displayWidth / imgWidth;
            } else {
                mShift = 0;
            }
        }

        private void updateMiniBitmap() {
            mRenderer.useBitmap(b -> {
                int size = Math.min(b.getWidth(), b.getHeight());
                float scale = 1.0f;
                if (size > MIN_SURFACE_WIDTH) {
                    scale = (float) MIN_SURFACE_WIDTH / (float) size;
                }
                mImgHeight = b.getHeight();
                mImgWidth = b.getWidth();
                mMiniBitmap = Bitmap.createScaledBitmap(b, Math.round(scale * b.getWidth()),
                        Math.round(scale * b.getHeight()), false);
                computeAndNotifyLocalColors(mLocalColorsToAdd, mMiniBitmap);
                mLocalColorsToAdd.clear();
            });
        }

        private void updateSurfaceSize() {
            SurfaceHolder holder = getSurfaceHolder();
            Size frameSize = mRenderer.reportSurfaceSize();
            int width = Math.max(MIN_SURFACE_WIDTH, frameSize.getWidth());
            int height = Math.max(MIN_SURFACE_HEIGHT, frameSize.getHeight());
            holder.setFixedSize(width, height);
        }

        @Override
        public boolean shouldZoomOutWallpaper() {
            return true;
        }

        @Override
        public void onDestroy() {
            mMiniBitmap = null;

            mStatusBarStateController.removeCallback(this);

            mWorker.getThreadHandler().post(() -> {
                finishChoreographerInternal();
                mRenderer.finish();
                mRenderer = null;
                mEglHelper.finish();
                mEglHelper = null;
            });
        }

        @Override
        public boolean supportsLocalColorExtraction() {
            return true;
        }

        @Override
        public void addLocalColorsAreas(@NonNull List<RectF> regions) {
            mWorker.getThreadHandler().post(() -> {
                if (mColorAreas.size() + mLocalColorsToAdd.size() == 0) {
                    setOffsetNotificationsEnabled(true);
                }
                Bitmap bitmap = mMiniBitmap;
                if (bitmap == null) {
                    mLocalColorsToAdd.addAll(regions);
                } else {
                    computeAndNotifyLocalColors(regions, bitmap);
                }
            });
        }

        private void computeAndNotifyLocalColors(@NonNull List<RectF> regions, Bitmap b) {
            List<WallpaperColors> colors = getLocalWallpaperColors(regions, b);
            mColorAreas.addAll(regions);
            try {
                notifyLocalColorsChanged(regions, colors);
            } catch (RuntimeException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        @Override
        public void removeLocalColorsAreas(@NonNull List<RectF> regions) {
            mWorker.getThreadHandler().post(() -> {
                mColorAreas.removeAll(regions);
                mLocalColorsToAdd.removeAll(regions);
                if (mColorAreas.size() + mLocalColorsToAdd.size() == 0) {
                    setOffsetNotificationsEnabled(false);
                }
            });
        }

        private RectF pageToImgRect(RectF area) {
            float pageWidth = 1f / (float) mPages;
            if (pageWidth < 1 && pageWidth >= 0) pageWidth = 1;
            float imgWidth = (float) mImgWidth / (float) mImgHeight;
            float displayWidth =
                    (float) mWidth / (float) mHeight;
            float expansion = imgWidth > displayWidth ? displayWidth / imgWidth : 1;
            int page = (int) Math.floor(area.centerX() / pageWidth);
            float shiftWidth = mShift * page * pageWidth;
            RectF imgArea = new RectF();
            imgArea.bottom = area.bottom;
            imgArea.top = area.top;
            imgArea.left = MathUtils.constrain(area.left % pageWidth, 0, 1)
                    * expansion + shiftWidth;
            imgArea.right = MathUtils.constrain(area.right % pageWidth, 0, 1)
                    * expansion + shiftWidth;
            if (imgArea.left > imgArea.right) {
                // take full page
                imgArea.left = shiftWidth;
                imgArea.right = 1 - (mShift - shiftWidth);
            }
            return imgArea;
        }

        private List<WallpaperColors> getLocalWallpaperColors(@NonNull List<RectF> areas,
                Bitmap b) {
            List<WallpaperColors> colors = new ArrayList<>(areas.size());
            updateShift();
            for (int i = 0; i < areas.size(); i++) {
                RectF area = pageToImgRect(areas.get(i));
                if (area == null || !LOCAL_COLOR_BOUNDS.contains(area)) {
                    colors.add(null);
                    continue;
                }
                Rect subImage = new Rect(
                        Math.round(area.left * b.getWidth()),
                        Math.round(area.top * b.getHeight()),
                        Math.round(area.right * b.getWidth()),
                        Math.round(area.bottom * b.getHeight()));
                Bitmap colorImg = Bitmap.createBitmap(b,
                        subImage.left, subImage.top, subImage.width(), subImage.height());
                WallpaperColors color = WallpaperColors.fromBitmap(colorImg);
                colors.add(color);
            }
            return colors;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            if (mWorker == null) return;
            mWorker.getThreadHandler().post(() -> {
                mEglHelper.init(holder, needSupportWideColorGamut());
                mRenderer.onSurfaceCreated();
            });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mWorker == null) return;
            mWorker.getThreadHandler().post(() -> mRenderer.onSurfaceChanged(width, height));
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (mWorker == null) return;
            mDozeAmount = mStatusBarStateController.getDozeAmount();
            mWorker.getThreadHandler().post(this::drawFrame);
        }

        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            initChoreographer();

            mDozeAmount = linear;
            mNewDozeValue = true;
        }

        private void drawFrame() {
            preRender();
            requestRender();
            postRender();
        }

        /**
         * Important: this method should only be invoked from the ImageWallpaper (worker) Thread.
         */
        public void preRender() {
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

        /**
         * Important: this method should only be invoked from the ImageWallpaper (worker) Thread.
         */
        public void requestRender() {
            Trace.beginSection("ImageWallpaper#requestRender");
            requestRenderInternal();
            Trace.endSection();
        }

        private void requestRenderInternal() {
            Rect frame = getSurfaceHolder().getSurfaceFrame();
            boolean readyToRender = mEglHelper.hasEglContext() && mEglHelper.hasEglSurface()
                    && frame.width() > 0 && frame.height() > 0;

            if (readyToRender) {
                mRenderer.setExposureValue(1 - mDozeAmount);
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

        /**
         * Important: this method should only be invoked from the ImageWallpaper (worker) Thread.
         */
        public void postRender() {
            Trace.beginSection("ImageWallpaper#postRender");
            scheduleFinishRendering();
            Trace.endSection();
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
            finishChoreographerInternal();
            if (mEglHelper != null) {
                mEglHelper.destroyEglSurface();
                mEglHelper.destroyEglContext();
            }
            Trace.endSection();
        }

        private void initChoreographer() {
            if (!mWorker.getThreadHandler().hasCallbacks(mInitChoreographerTask)
                    && !mShouldScheduleFrame) {
                mWorker.getThreadHandler().post(mInitChoreographerTask);
            }
        }

        /**
         * Subscribes the engine to listen to Choreographer frame events.
         * Important: this method should only be invoked from the ImageWallpaper (worker) Thread.
         */
        private void initChoreographerInternal() {
            if (!mShouldScheduleFrame) {
                // Prepare EGL Context and Surface
                preRender();
                mShouldScheduleFrame = true;
                Choreographer.getInstance().postFrameCallback(GLEngine.this);
            }
        }

        /**
         * Unsubscribe the engine from listening to Choreographer frame events.
         * Important: this method should only be invoked from the ImageWallpaper (worker) Thread.
         */
        private void finishChoreographerInternal() {
            mShouldScheduleFrame = false;
            Choreographer.getInstance().removeFrameCallback(GLEngine.this);
        }

        private boolean needSupportWideColorGamut() {
            return mRenderer.isWcgContent();
        }

        @Override
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);
            out.print(prefix); out.print("Engine="); out.println(this);
            out.print(prefix); out.print("valid surface=");
            out.println(getSurfaceHolder() != null && getSurfaceHolder().getSurface() != null
                    ? getSurfaceHolder().getSurface().isValid()
                    : "null");

            out.print(prefix); out.print("surface frame=");
            out.println(getSurfaceHolder() != null ? getSurfaceHolder().getSurfaceFrame() : "null");

            mEglHelper.dump(prefix, fd, out, args);
            mRenderer.dump(prefix, fd, out, args);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (mNewDozeValue) {
                drawFrame();
                mNewDozeValue = false;
            }

            if (mShouldScheduleFrame) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    }
}
