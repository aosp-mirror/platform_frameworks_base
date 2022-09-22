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

package com.android.systemui.wallpapers;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.flags.Flags.USE_CANVAS_RENDERER;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.wallpapers.canvas.ImageCanvasWallpaperRenderer;
import com.android.systemui.wallpapers.gl.EglHelper;
import com.android.systemui.wallpapers.gl.ImageWallpaperRenderer;

import java.io.FileDescriptor;
import java.io.IOException;
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

    private final ArrayList<RectF> mLocalColorsToAdd = new ArrayList<>();
    private final ArraySet<RectF> mColorAreas = new ArraySet<>();
    private volatile int mPages = 1;
    private HandlerThread mWorker;
    // scaled down version
    private Bitmap mMiniBitmap;
    private final FeatureFlags mFeatureFlags;

    @Inject
    public ImageWallpaper(FeatureFlags featureFlags) {
        super();
        mFeatureFlags = featureFlags;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return mFeatureFlags.isEnabled(USE_CANVAS_RENDERER) ? new CanvasEngine() : new GLEngine();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWorker.quitSafely();
        mWorker = null;
        mMiniBitmap = null;
    }

    class GLEngine extends Engine implements DisplayListener {
        // Surface is rejected if size below a threshold on some devices (ie. 8px on elfin)
        // set min to 64 px (CTS covers this), please refer to ag/4867989 for detail.
        @VisibleForTesting
        static final int MIN_SURFACE_WIDTH = 128;
        @VisibleForTesting
        static final int MIN_SURFACE_HEIGHT = 128;

        private ImageWallpaperRenderer mRenderer;
        private EglHelper mEglHelper;
        private final Runnable mFinishRenderingTask = this::finishRendering;
        private boolean mNeedRedraw;

        private boolean mDisplaySizeValid = false;
        private int mDisplayWidth = 1;
        private int mDisplayHeight = 1;

        private int mImgWidth = 1;
        private int mImgHeight = 1;

        GLEngine() { }

        @VisibleForTesting
        GLEngine(Handler handler) {
            super(SystemClock::elapsedRealtime, handler);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            Trace.beginSection("ImageWallpaper.Engine#onCreate");
            mEglHelper = getEglHelperInstance();
            // Deferred init renderer because we need to get wallpaper by display context.
            mRenderer = getRendererInstance();
            setFixedSizeAllowed(true);
            updateSurfaceSize();
            setShowForAllUsers(true);

            mRenderer.setOnBitmapChanged(b -> {
                mLocalColorsToAdd.addAll(mColorAreas);
                if (mLocalColorsToAdd.size() > 0) {
                    updateMiniBitmapAndNotify(b);
                }
            });
            getDisplayContext().getSystemService(DisplayManager.class)
                    .registerDisplayListener(this, mWorker.getThreadHandler());
            Trace.endSection();
        }

        @Override
        public void onDisplayAdded(int displayId) { }

        @Override
        public void onDisplayRemoved(int displayId) { }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == getDisplayContext().getDisplayId()) {
                mDisplaySizeValid = false;
            }
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
            final int pages;
            if (xOffsetStep > 0 && xOffsetStep <= 1) {
                pages = (int) Math.round(1 / xOffsetStep) + 1;
            } else {
                pages = 1;
            }
            if (pages == mPages) return;
            mPages = pages;
            if (mMiniBitmap == null || mMiniBitmap.isRecycled()) return;
            mWorker.getThreadHandler().post(() ->
                    computeAndNotifyLocalColors(new ArrayList<>(mColorAreas), mMiniBitmap));
        }

        private void updateMiniBitmapAndNotify(Bitmap b) {
            if (b == null) return;
            int size = Math.min(b.getWidth(), b.getHeight());
            float scale = 1.0f;
            if (size > MIN_SURFACE_WIDTH) {
                scale = (float) MIN_SURFACE_WIDTH / (float) size;
            }
            mImgHeight = b.getHeight();
            mImgWidth = b.getWidth();
            mMiniBitmap = Bitmap.createScaledBitmap(b,  (int) Math.max(scale * b.getWidth(), 1),
                    (int) Math.max(scale * b.getHeight(), 1), false);
            computeAndNotifyLocalColors(mLocalColorsToAdd, mMiniBitmap);
            mLocalColorsToAdd.clear();
        }

        private void updateSurfaceSize() {
            Trace.beginSection("ImageWallpaper#updateSurfaceSize");
            SurfaceHolder holder = getSurfaceHolder();
            Size frameSize = mRenderer.reportSurfaceSize();
            int width = Math.max(MIN_SURFACE_WIDTH, frameSize.getWidth());
            int height = Math.max(MIN_SURFACE_HEIGHT, frameSize.getHeight());
            holder.setFixedSize(width, height);
            Trace.endSection();
        }

        @Override
        public boolean shouldZoomOutWallpaper() {
            return true;
        }

        @Override
        public boolean shouldWaitForEngineShown() {
            return true;
        }

        @Override
        public void onDestroy() {
            getDisplayContext().getSystemService(DisplayManager.class)
                    .unregisterDisplayListener(this);
            mMiniBitmap = null;
            mWorker.getThreadHandler().post(() -> {
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
                    if (mRenderer != null) mRenderer.use(this::updateMiniBitmapAndNotify);
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

        /**
         * Transform the logical coordinates into wallpaper coordinates.
         *
         * Logical coordinates are organised such that the various pages are non-overlapping. So,
         * if there are n pages, the first page will have its X coordinate on the range [0-1/n].
         *
         * The real pages are overlapping. If the Wallpaper are a width Ww and the screen a width
         * Ws, the relative width of a page Wr is Ws/Ww. This does not change if the number of
         * pages increase.
         * If there are n pages, the page k starts at the offset k * (1 - Wr) / (n - 1), as the
         * last page is at position (1-Wr) and the others are regularly spread on the range [0-
         * (1-Wr)].
         */
        private RectF pageToImgRect(RectF area) {
            if (!mDisplaySizeValid) {
                Rect window = getDisplayContext()
                        .getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics()
                        .getBounds();
                mDisplayWidth = window.width();
                mDisplayHeight = window.height();
                mDisplaySizeValid = true;
            }

            // Width of a page for the caller of this API.
            float virtualPageWidth = 1f / (float) mPages;
            float leftPosOnPage = (area.left % virtualPageWidth) / virtualPageWidth;
            float rightPosOnPage = (area.right % virtualPageWidth) / virtualPageWidth;
            int currentPage = (int) Math.floor(area.centerX() / virtualPageWidth);

            RectF imgArea = new RectF();

            if (mImgWidth == 0 || mImgHeight == 0 || mDisplayWidth <= 0 || mDisplayHeight <= 0) {
                return imgArea;
            }

            imgArea.bottom = area.bottom;
            imgArea.top = area.top;

            float imageScale = Math.min(((float) mImgHeight) / mDisplayHeight, 1);
            float mappedScreenWidth = mDisplayWidth * imageScale;
            float pageWidth = Math.min(1.0f,
                    mImgWidth > 0 ? mappedScreenWidth / (float) mImgWidth : 1.f);
            float pageOffset = (1 - pageWidth) / (float) (mPages - 1);

            imgArea.left = MathUtils.constrain(
                    leftPosOnPage * pageWidth + currentPage * pageOffset, 0, 1);
            imgArea.right = MathUtils.constrain(
                    rightPosOnPage * pageWidth + currentPage * pageOffset, 0, 1);
            if (imgArea.left > imgArea.right) {
                // take full page
                imgArea.left = 0;
                imgArea.right = 1;
            }

            return imgArea;
        }

        private List<WallpaperColors> getLocalWallpaperColors(@NonNull List<RectF> areas,
                Bitmap b) {
            List<WallpaperColors> colors = new ArrayList<>(areas.size());
            for (int i = 0; i < areas.size(); i++) {
                RectF area = pageToImgRect(areas.get(i));
                if (area == null || !LOCAL_COLOR_BOUNDS.contains(area)) {
                    colors.add(null);
                    continue;
                }
                Rect subImage = new Rect(
                        (int) Math.floor(area.left * b.getWidth()),
                        (int) Math.floor(area.top * b.getHeight()),
                        (int) Math.ceil(area.right * b.getWidth()),
                        (int) Math.ceil(area.bottom * b.getHeight()));
                if (subImage.isEmpty()) {
                    // Do not notify client. treat it as too small to sample
                    colors.add(null);
                    continue;
                }
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
                Trace.beginSection("ImageWallpaper#onSurfaceCreated");
                mEglHelper.init(holder, needSupportWideColorGamut());
                mRenderer.onSurfaceCreated();
                Trace.endSection();
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
            mWorker.getThreadHandler().post(this::drawFrame);
        }

        private void drawFrame() {
            Trace.beginSection("ImageWallpaper#drawFrame");
            preRender();
            requestRender();
            postRender();
            Trace.endSection();
        }

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

        public void postRender() {
            // This method should only be invoked from worker thread.
            scheduleFinishRendering();
            reportEngineShown(false /* waitForEngineShown */);
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
                mEglHelper.destroyEglContext();
            }
            Trace.endSection();
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
    }


    class CanvasEngine extends WallpaperService.Engine implements DisplayListener {

        // time [ms] before unloading the wallpaper after it is loaded
        private static final int DELAY_FORGET_WALLPAPER = 5000;

        private final Runnable mUnloadWallpaperCallback = this::unloadWallpaper;

        private WallpaperManager mWallpaperManager;
        private ImageCanvasWallpaperRenderer mImageCanvasWallpaperRenderer;
        private Bitmap mBitmap;

        private Display mDisplay;
        private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

        private AsyncTask<Void, Void, Bitmap> mLoader;
        private boolean mNeedsDrawAfterLoadingWallpaper = false;

        CanvasEngine() {
            super();
            setFixedSizeAllowed(true);
            setShowForAllUsers(true);
        }

        void trimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                    && level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    && isBitmapLoaded()) {
                if (DEBUG) {
                    Log.d(TAG, "trimMemory");
                }
                unloadWallpaper();
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }

            mWallpaperManager = getSystemService(WallpaperManager.class);
            super.onCreate(surfaceHolder);

            final Context displayContext = getDisplayContext();
            final int displayId = displayContext == null ? DEFAULT_DISPLAY :
                    displayContext.getDisplayId();
            DisplayManager dm = getSystemService(DisplayManager.class);
            if (dm != null) {
                mDisplay = dm.getDisplay(displayId);
                if (mDisplay == null) {
                    Log.e(TAG, "Cannot find display! Fallback to default.");
                    mDisplay = dm.getDisplay(DEFAULT_DISPLAY);
                }
            }
            setOffsetNotificationsEnabled(false);

            mImageCanvasWallpaperRenderer = new ImageCanvasWallpaperRenderer(surfaceHolder);
            loadWallpaper(false);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            unloadWallpaper();
        }

        @Override
        public boolean shouldZoomOutWallpaper() {
            return true;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }
            super.onSurfaceChanged(holder, format, width, height);
            mImageCanvasWallpaperRenderer.setSurfaceHolder(holder);
            drawFrame(false);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceDestroyed");
            }
            mImageCanvasWallpaperRenderer.setSurfaceHolder(null);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceCreated");
            }
            mImageCanvasWallpaperRenderer.setSurfaceHolder(holder);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceRedrawNeeded");
            }
            super.onSurfaceRedrawNeeded(holder);
            // At the end of this method we should have drawn into the surface.
            // This means that the bitmap should be loaded synchronously if
            // it was already unloaded.
            if (!isBitmapLoaded()) {
                setBitmap(mWallpaperManager.getBitmap(true /* hardware */));
            }
            drawFrame(true);
        }

        private DisplayInfo getDisplayInfo() {
            mDisplay.getDisplayInfo(mTmpDisplayInfo);
            return mTmpDisplayInfo;
        }

        private void drawFrame(boolean forceRedraw) {
            if (!mImageCanvasWallpaperRenderer.isSurfaceHolderLoaded()) {
                Log.e(TAG, "attempt to draw a frame without a valid surface");
                return;
            }

            if (!isBitmapLoaded()) {
                // ensure that we load the wallpaper.
                // if the wallpaper is currently loading, this call will have no effect.
                loadWallpaper(true);
                return;
            }
            mImageCanvasWallpaperRenderer.drawFrame(mBitmap, forceRedraw);
        }

        private void setBitmap(Bitmap bitmap) {
            if (bitmap == null) {
                Log.e(TAG, "Attempt to set a null bitmap");
            } else if (mBitmap == bitmap) {
                Log.e(TAG, "The value of bitmap is the same");
            } else if (bitmap.getWidth() < 1 || bitmap.getHeight() < 1) {
                Log.e(TAG, "Attempt to set an invalid wallpaper of length "
                        + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                if (mBitmap != null) {
                    mBitmap.recycle();
                }
                mBitmap = bitmap;
            }
        }

        private boolean isBitmapLoaded() {
            return mBitmap != null && !mBitmap.isRecycled();
        }

        /**
         * Loads the wallpaper on background thread and schedules updating the surface frame,
         * and if {@code needsDraw} is set also draws a frame.
         *
         * If loading is already in-flight, subsequent loads are ignored (but needDraw is or-ed to
         * the active request).
         *
         */
        private void loadWallpaper(boolean needsDraw) {
            mNeedsDrawAfterLoadingWallpaper |= needsDraw;
            if (mLoader != null) {
                if (DEBUG) {
                    Log.d(TAG, "Skipping loadWallpaper, already in flight ");
                }
                return;
            }
            mLoader = new AsyncTask<Void, Void, Bitmap>() {
                @Override
                protected Bitmap doInBackground(Void... params) {
                    Throwable exception;
                    try {
                        Bitmap wallpaper = mWallpaperManager.getBitmap(true /* hardware */);
                        if (wallpaper != null
                                && wallpaper.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE) {
                            throw new RuntimeException("Wallpaper is too large to draw!");
                        }
                        return wallpaper;
                    } catch (RuntimeException | OutOfMemoryError e) {
                        exception = e;
                    }

                    if (isCancelled()) {
                        return null;
                    }

                    // Note that if we do fail at this, and the default wallpaper can't
                    // be loaded, we will go into a cycle.  Don't do a build where the
                    // default wallpaper can't be loaded.
                    Log.w(TAG, "Unable to load wallpaper!", exception);
                    try {
                        mWallpaperManager.clear();
                    } catch (IOException ex) {
                        // now we're really screwed.
                        Log.w(TAG, "Unable reset to default wallpaper!", ex);
                    }

                    if (isCancelled()) {
                        return null;
                    }

                    try {
                        return mWallpaperManager.getBitmap(true /* hardware */);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        Log.w(TAG, "Unable to load default wallpaper!", e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Bitmap bitmap) {
                    setBitmap(bitmap);

                    if (mNeedsDrawAfterLoadingWallpaper) {
                        drawFrame(true);
                    }

                    mLoader = null;
                    mNeedsDrawAfterLoadingWallpaper = false;
                    scheduleUnloadWallpaper();
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        private void unloadWallpaper() {
            if (mLoader != null) {
                mLoader.cancel(false);
                mLoader = null;
            }

            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = null;

            final Surface surface = getSurfaceHolder().getSurface();
            surface.hwuiDestroy();
            mWallpaperManager.forgetLoadedWallpaper();
        }

        private void scheduleUnloadWallpaper() {
            Handler handler = getMainThreadHandler();
            handler.removeCallbacks(mUnloadWallpaperCallback);
            handler.postDelayed(mUnloadWallpaperCallback, DELAY_FORGET_WALLPAPER);
        }

        @Override
        public void onDisplayAdded(int displayId) {

        }

        @Override
        public void onDisplayChanged(int displayId) {

        }

        @Override
        public void onDisplayRemoved(int displayId) {

        }
    }
}
