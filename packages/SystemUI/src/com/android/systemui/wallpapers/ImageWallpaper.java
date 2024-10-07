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

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.app.WallpaperManager.SetWallpaperFlags;

import static com.android.systemui.Flags.fixImageWallpaperCrashSurfaceAlreadyReleased;
import static com.android.window.flags.Flags.multiCrop;
import static com.android.window.flags.Flags.offloadColorExtraction;

import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

import javax.inject.Inject;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {

    private static final String TAG = ImageWallpaper.class.getSimpleName();
    private static final boolean DEBUG = false;

    // keep track of the number of pages of the launcher for local color extraction purposes
    private volatile int mPages = 1;
    private boolean mPagesComputed = false;

    private final UserTracker mUserTracker;

    // used to handle WallpaperService messages (e.g. DO_ATTACH, MSG_UPDATE_SURFACE)
    // and to receive WallpaperService callbacks (e.g. onCreateEngine, onSurfaceRedrawNeeded)
    private HandlerThread mWorker;

    // used for most tasks (call canvas.drawBitmap, load/unload the bitmap)
    @LongRunning
    private final DelayableExecutor mLongExecutor;

    // wait at least this duration before unloading the bitmap
    private static final int DELAY_UNLOAD_BITMAP = 2000;

    @Inject
    public ImageWallpaper(@LongRunning DelayableExecutor longExecutor, UserTracker userTracker) {
        super();
        mLongExecutor = longExecutor;
        mUserTracker = userTracker;
    }

    @Override
    public Looper onProvideEngineLooper() {
        // Receive messages on mWorker thread instead of SystemUI's main handler.
        // All other wallpapers have their own process, and they can receive messages on their own
        // main handler without any delay. But since ImageWallpaper lives in SystemUI, performance
        // of the image wallpaper could be negatively affected when SystemUI's main handler is busy.
        return mWorker != null ? mWorker.getLooper() : super.onProvideEngineLooper();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return new CanvasEngine();
    }

    class CanvasEngine extends WallpaperService.Engine implements DisplayListener {
        private WallpaperManager mWallpaperManager;
        private final WallpaperLocalColorExtractor mWallpaperLocalColorExtractor;
        private SurfaceHolder mSurfaceHolder;
        private boolean mDrawn = false;
        @VisibleForTesting
        static final int MIN_SURFACE_WIDTH = 128;
        @VisibleForTesting
        static final int MIN_SURFACE_HEIGHT = 128;
        private Bitmap mBitmap;
        private boolean mWideColorGamut = false;

        /*
         * Counter to unload the bitmap as soon as possible.
         * Before any bitmap operation, this is incremented.
         * After an operation completion, this is decremented (synchronously),
         * and if the count is 0, unload the bitmap
         */
        private int mBitmapUsages = 0;

        /**
         * Main lock for long operations (loading the bitmap or processing colors).
         */
        private final Object mLock = new Object();

        /**
         * Lock for SurfaceHolder operations. Should only be acquired after the main lock.
         */
        private final Object mSurfaceLock = new Object();

        CanvasEngine() {
            super();
            setFixedSizeAllowed(true);
            setShowForAllUsers(true);
            mWallpaperLocalColorExtractor = new WallpaperLocalColorExtractor(
                    mLongExecutor,
                    mLock,
                    new WallpaperLocalColorExtractor.WallpaperLocalColorExtractorCallback() {

                        @Override
                        public void onColorsProcessed() {
                            CanvasEngine.this.notifyColorsChanged();
                        }

                        @Override
                        public void onColorsProcessed(List<RectF> regions,
                                List<WallpaperColors> colors) {
                            CanvasEngine.this.onColorsProcessed(regions, colors);
                        }

                        @Override
                        public void onMiniBitmapUpdated() {
                            CanvasEngine.this.onMiniBitmapUpdated();
                        }

                        @Override
                        public void onActivated() {
                            setOffsetNotificationsEnabled(true);
                        }

                        @Override
                        public void onDeactivated() {
                            setOffsetNotificationsEnabled(false);
                        }
                    });

            // if the number of pages is already computed, transmit it to the color extractor
            if (mPagesComputed) {
                mWallpaperLocalColorExtractor.onPageChanged(mPages);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#onCreate");
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }
            mWallpaperManager = getDisplayContext().getSystemService(WallpaperManager.class);
            mSurfaceHolder = surfaceHolder;
            Rect dimensions = !multiCrop()
                    ? mWallpaperManager.peekBitmapDimensions(getSourceFlag(), true)
                    : mWallpaperManager.peekBitmapDimensionsAsUser(getSourceFlag(), true,
                    mUserTracker.getUserId());
            int width = Math.max(MIN_SURFACE_WIDTH, dimensions.width());
            int height = Math.max(MIN_SURFACE_HEIGHT, dimensions.height());
            mSurfaceHolder.setFixedSize(width, height);

            getDisplayContext().getSystemService(DisplayManager.class)
                    .registerDisplayListener(this, null);
            getDisplaySizeAndUpdateColorExtractor();
            Trace.endSection();
        }

        @Override
        public void onDestroy() {
            Context context = getDisplayContext();
            if (context != null) {
                DisplayManager displayManager = context.getSystemService(DisplayManager.class);
                if (displayManager != null) displayManager.unregisterDisplayListener(this);
            }
            mWallpaperLocalColorExtractor.cleanUp();
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
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (DEBUG) {
                Log.i(TAG, "onSurfaceDestroyed");
            }
            if (fixImageWallpaperCrashSurfaceAlreadyReleased()) {
                synchronized (mSurfaceLock) {
                    mSurfaceHolder = null;
                }
                return;
            }
            mLongExecutor.execute(this::onSurfaceDestroyedSynchronized);
        }

        private void onSurfaceDestroyedSynchronized() {
            synchronized (mLock) {
                mSurfaceHolder = null;
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            if (DEBUG) {
                Log.i(TAG, "onSurfaceCreated");
            }
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceRedrawNeeded");
            }
            drawFrame();
        }

        private void drawFrame() {
            mLongExecutor.execute(this::drawFrameSynchronized);
        }

        private void drawFrameSynchronized() {
            synchronized (mLock) {
                if (mDrawn) return;
                drawFrameInternal();
            }
        }

        private void drawFrameInternal() {
            if (mSurfaceHolder == null && !fixImageWallpaperCrashSurfaceAlreadyReleased()) {
                Log.i(TAG, "attempt to draw a frame without a valid surface");
                return;
            }

            // load the wallpaper if not already done
            if (!isBitmapLoaded()) {
                loadWallpaperAndDrawFrameInternal();
            } else {
                if (fixImageWallpaperCrashSurfaceAlreadyReleased()) {
                    synchronized (mSurfaceLock) {
                        if (mSurfaceHolder == null) {
                            Log.i(TAG, "Surface released before the image could be drawn");
                            return;
                        }
                        mBitmapUsages++;
                        drawFrameOnCanvas(mBitmap);
                        reportEngineShown(false);
                        unloadBitmapIfNotUsedInternal();
                        return;
                    }
                }
                mBitmapUsages++;
                drawFrameOnCanvas(mBitmap);
                reportEngineShown(false);
                unloadBitmapIfNotUsedInternal();
            }
        }

        @VisibleForTesting
        void drawFrameOnCanvas(Bitmap bitmap) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#drawFrame");
            Surface surface = mSurfaceHolder.getSurface();
            Canvas canvas = null;
            try {
                canvas = mWideColorGamut
                        ? surface.lockHardwareWideColorGamutCanvas()
                        : surface.lockHardwareCanvas();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Unable to lock canvas", e);
            }
            if (canvas != null) {
                Rect dest = mSurfaceHolder.getSurfaceFrame();
                try {
                    canvas.drawBitmap(bitmap, null, dest, null);
                    mDrawn = true;
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
            }
            Trace.endSection();
        }

        @VisibleForTesting
        boolean isBitmapLoaded() {
            return mBitmap != null && !mBitmap.isRecycled();
        }

        private void unloadBitmapIfNotUsed() {
            mLongExecutor.execute(this::unloadBitmapIfNotUsedSynchronized);
        }

        private void unloadBitmapIfNotUsedSynchronized() {
            synchronized (mLock) {
                unloadBitmapIfNotUsedInternal();
            }
        }

        private void unloadBitmapIfNotUsedInternal() {
            mBitmapUsages -= 1;
            if (mBitmapUsages <= 0) {
                mBitmapUsages = 0;
                unloadBitmapInternal();
            }
        }

        private void unloadBitmapInternal() {
            Trace.beginSection("ImageWallpaper.CanvasEngine#unloadBitmap");
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = null;
            if (fixImageWallpaperCrashSurfaceAlreadyReleased()) {
                synchronized (mSurfaceLock) {
                    if (mSurfaceHolder != null) mSurfaceHolder.getSurface().hwuiDestroy();
                }
            } else {
                final Surface surface = getSurfaceHolder().getSurface();
                surface.hwuiDestroy();
            }
            mWallpaperManager.forgetLoadedWallpaper();
            Trace.endSection();
        }

        private void loadWallpaperAndDrawFrameInternal() {
            Trace.beginSection("WPMS.ImageWallpaper.CanvasEngine#loadWallpaper");
            boolean loadSuccess = false;
            Bitmap bitmap;
            try {
                Trace.beginSection("WPMS.getBitmapAsUser");
                bitmap = mWallpaperManager.getBitmapAsUser(
                        mUserTracker.getUserId(), false, getSourceFlag(), true);
                if (bitmap != null
                        && bitmap.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE) {
                    throw new RuntimeException("Wallpaper is too large to draw!");
                }
            } catch (RuntimeException | OutOfMemoryError exception) {

                // Note that if we do fail at this, and the default wallpaper can't
                // be loaded, we will go into a cycle. Don't do a build where the
                // default wallpaper can't be loaded.
                Log.w(TAG, "Unable to load wallpaper!", exception);
                Trace.beginSection("WPMS.clearWallpaper");
                mWallpaperManager.clearWallpaper(getWallpaperFlags(), mUserTracker.getUserId());
                Trace.endSection();

                try {
                    Trace.beginSection("WPMS.getBitmapAsUser_defaultWallpaper");
                    bitmap = mWallpaperManager.getBitmapAsUser(
                            mUserTracker.getUserId(), false, getSourceFlag(), true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "Unable to load default wallpaper!", e);
                    bitmap = null;
                } finally {
                    Trace.endSection();
                }
            } finally {
                Trace.endSection();
            }

            if (bitmap == null) {
                Log.w(TAG, "Could not load bitmap");
            } else if (bitmap.isRecycled()) {
                Log.e(TAG, "Attempt to load a recycled bitmap");
            } else if (mBitmap == bitmap) {
                Log.e(TAG, "Loaded a bitmap that was already loaded");
            } else {
                // at this point, loading is done correctly.
                loadSuccess = true;
                // recycle the previously loaded bitmap
                if (mBitmap != null) {
                    Trace.beginSection("WPMS.mBitmap.recycle");
                    mBitmap.recycle();
                    Trace.endSection();
                }
                mBitmap = bitmap;
                Trace.beginSection("WPMS.wallpaperSupportsWcg");
                mWideColorGamut = mWallpaperManager.wallpaperSupportsWcg(getSourceFlag());
                Trace.endSection();

                // +2 usages for the color extraction and the delayed unload.
                mBitmapUsages += 2;
                Trace.beginSection("WPMS.recomputeColorExtractorMiniBitmap");
                recomputeColorExtractorMiniBitmap();
                Trace.endSection();
                Trace.beginSection("WPMS.drawFrameInternal");
                drawFrameInternal();
                Trace.endSection();

                /*
                 * after loading, the bitmap will be unloaded after all these conditions:
                 *   - the frame is redrawn
                 *   - the mini bitmap from color extractor is recomputed
                 *   - the DELAY_UNLOAD_BITMAP has passed
                 */
                mLongExecutor.executeDelayed(
                        this::unloadBitmapIfNotUsedSynchronized, DELAY_UNLOAD_BITMAP);
            }
            // even if the bitmap cannot be loaded, call reportEngineShown
            if (!loadSuccess) reportEngineShown(false);
            Trace.endSection();
        }

        private void onColorsProcessed(List<RectF> regions, List<WallpaperColors> colors) {
            try {
                notifyLocalColorsChanged(regions, colors);
            } catch (RuntimeException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        /**
         * Helper to return the flag from where the source bitmap is from.
         * Similar to {@link #getWallpaperFlags()}, but returns (FLAG_SYSTEM) instead of
         * (FLAG_LOCK | FLAG_SYSTEM) if this engine is used for both lock screen & home screen.
         */
        private @SetWallpaperFlags int getSourceFlag() {
            return getWallpaperFlags() == FLAG_LOCK ? FLAG_LOCK : FLAG_SYSTEM;
        }

        @VisibleForTesting
        void recomputeColorExtractorMiniBitmap() {
            mWallpaperLocalColorExtractor.onBitmapChanged(mBitmap);
        }

        @VisibleForTesting
        void onMiniBitmapUpdated() {
            unloadBitmapIfNotUsed();
        }

        @Override
        public @Nullable WallpaperColors onComputeColors() {
            if (!offloadColorExtraction()) return null;
            return mWallpaperLocalColorExtractor.onComputeColors();
        }

        @Override
        public boolean supportsLocalColorExtraction() {
            return true;
        }

        @Override
        public void addLocalColorsAreas(@NonNull List<RectF> regions) {
            // this call will activate the offset notifications
            // if no colors were being processed before
            mWallpaperLocalColorExtractor.addLocalColorsAreas(regions);
        }

        @Override
        public void removeLocalColorsAreas(@NonNull List<RectF> regions) {
            // this call will deactivate the offset notifications
            // if we are no longer processing colors
            mWallpaperLocalColorExtractor.removeLocalColorAreas(regions);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixelOffset, int yPixelOffset) {
            final int pages;
            if (xOffsetStep > 0 && xOffsetStep <= 1) {
                pages = Math.round(1 / xOffsetStep) + 1;
            } else {
                pages = 1;
            }
            if (pages != mPages || !mPagesComputed) {
                mPages = pages;
                mPagesComputed = true;
                mWallpaperLocalColorExtractor.onPageChanged(mPages);
            }
        }

        @Override
        public void onDimAmountChanged(float dimAmount) {
            if (!offloadColorExtraction()) return;
            mWallpaperLocalColorExtractor.onDimAmountChanged(dimAmount);
        }

        @Override
        public void onDisplayAdded(int displayId) {

        }

        @Override
        public void onDisplayRemoved(int displayId) {

        }

        @Override
        public void onDisplayChanged(int displayId) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#onDisplayChanged");
            try {
                // changes the display in the color extractor
                // the new display dimensions will be used in the next color computation
                if (displayId == getDisplayContext().getDisplayId()) {
                    getDisplaySizeAndUpdateColorExtractor();
                }
            } finally {
                Trace.endSection();
            }
        }

        private void getDisplaySizeAndUpdateColorExtractor() {
            Rect window = getDisplayContext()
                    .getSystemService(WindowManager.class)
                    .getCurrentWindowMetrics()
                    .getBounds();
            mWallpaperLocalColorExtractor.setDisplayDimensions(window.width(), window.height());
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

            out.print(prefix); out.print("bitmap=");
            out.println(mBitmap == null ? "null"
                    : mBitmap.isRecycled() ? "recycled"
                    : mBitmap.getWidth() + "x" + mBitmap.getHeight());

            mWallpaperLocalColorExtractor.dump(prefix, fd, out, args);
        }
    }
}
