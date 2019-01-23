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

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.WallpaperManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {
    private static final String TAG = "ImageWallpaper";
    private static final String GL_LOG_TAG = "ImageWallpaperGL";
    private static final boolean DEBUG = false;
    private static final String PROPERTY_KERNEL_QEMU = "ro.kernel.qemu";
    private static final long DELAY_FORGET_WALLPAPER = 5000;

    private WallpaperManager mWallpaperManager;
    private DrawableEngine mEngine;

    @Override
    public void onCreate() {
        super.onCreate();
        mWallpaperManager = getSystemService(WallpaperManager.class);
    }

    @Override
    public void onTrimMemory(int level) {
        if (mEngine != null) {
            mEngine.trimMemory(level);
        }
    }

    @Override
    public Engine onCreateEngine() {
        mEngine = new DrawableEngine();
        return mEngine;
    }

    class DrawableEngine extends Engine {
        private final Runnable mUnloadWallpaperCallback = () -> {
            unloadWallpaper(false /* forgetSize */);
        };

        // Surface is rejected if size below a threshold on some devices (ie. 8px on elfin)
        // set min to 64 px (CTS covers this)
        @VisibleForTesting
        static final int MIN_BACKGROUND_WIDTH = 64;
        @VisibleForTesting
        static final int MIN_BACKGROUND_HEIGHT = 64;

        Bitmap mBackground;
        int mBackgroundWidth = -1, mBackgroundHeight = -1;
        int mLastSurfaceWidth = -1, mLastSurfaceHeight = -1;
        int mLastRotation = -1;
        float mXOffset = 0f;
        float mYOffset = 0f;
        float mScale = 1f;

        private Display mDisplay;
        private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

        boolean mVisible = true;
        boolean mOffsetsChanged;
        int mLastXTranslation;
        int mLastYTranslation;

        private int mRotationAtLastSurfaceSizeUpdate = -1;
        private int mDisplayWidthAtLastSurfaceSizeUpdate = -1;
        private int mDisplayHeightAtLastSurfaceSizeUpdate = -1;

        private int mLastRequestedWidth = -1;
        private int mLastRequestedHeight = -1;
        private AsyncTask<Void, Void, Bitmap> mLoader;
        private boolean mNeedsDrawAfterLoadingWallpaper;
        private boolean mSurfaceValid;
        private boolean mSurfaceRedrawNeeded;

        DrawableEngine() {
            super();
            setFixedSizeAllowed(true);
        }

        void trimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                    && level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    && mBackground != null) {
                if (DEBUG) {
                    Log.d(TAG, "trimMemory");
                }
                unloadWallpaper(true /* forgetSize */);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }

            super.onCreate(surfaceHolder);

            //noinspection ConstantConditions
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

            updateSurfaceSize(surfaceHolder, getDisplayInfo(), false /* forDraw */);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            mBackground = null;
            unloadWallpaper(true /* forgetSize */);
        }

        boolean updateSurfaceSize(SurfaceHolder surfaceHolder, DisplayInfo displayInfo,
                boolean forDraw) {
            boolean hasWallpaper = true;

            // Load background image dimensions, if we haven't saved them yet
            if (mBackgroundWidth <= 0 || mBackgroundHeight <= 0) {
                // Need to load the image to get dimensions
                loadWallpaper(forDraw);
                if (DEBUG) {
                    Log.d(TAG, "Reloading, redoing updateSurfaceSize later.");
                }
                hasWallpaper = false;
            }

            // Expected surface size.
            int surfaceWidth = Math.max(displayInfo.logicalWidth, mBackgroundWidth);
            int surfaceHeight = Math.max(displayInfo.logicalHeight, mBackgroundHeight);

            // Calculate the minimum drawing area of the surface, which saves memory and does not
            // distort the image.
            final float scale = Math.min(
                    (float) mBackgroundHeight / (float) surfaceHeight,
                    (float) mBackgroundWidth / (float) surfaceWidth);
            surfaceHeight = (int) (scale * surfaceHeight);
            surfaceWidth = (int) (scale * surfaceWidth);

            // Set surface size to at least MIN size.
            if (surfaceWidth < MIN_BACKGROUND_WIDTH || surfaceHeight < MIN_BACKGROUND_HEIGHT) {
                final float scaleUp = Math.max(
                        (float) MIN_BACKGROUND_WIDTH / (float) surfaceWidth,
                        (float) MIN_BACKGROUND_HEIGHT / (float) surfaceHeight);
                surfaceWidth = (int) ((float) surfaceWidth * scaleUp);
                surfaceHeight = (int) ((float) surfaceHeight * scaleUp);
            }

            // Used a fixed size surface, because we are special.  We can do
            // this because we know the current design of window animations doesn't
            // cause this to break.
            surfaceHolder.setFixedSize(surfaceWidth, surfaceHeight);
            mLastRequestedWidth = surfaceWidth;
            mLastRequestedHeight = surfaceHeight;

            return hasWallpaper;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (DEBUG) {
                Log.d(TAG, "onVisibilityChanged: mVisible, visible=" + mVisible + ", " + visible);
            }

            if (mVisible != visible) {
                if (DEBUG) {
                    Log.d(TAG, "Visibility changed to visible=" + visible);
                }
                mVisible = visible;
                if (visible) {
                    drawFrame();
                }
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixels, int yPixels) {
            if (DEBUG) {
                Log.d(TAG, "onOffsetsChanged: xOffset=" + xOffset + ", yOffset=" + yOffset
                        + ", xOffsetStep=" + xOffsetStep + ", yOffsetStep=" + yOffsetStep
                        + ", xPixels=" + xPixels + ", yPixels=" + yPixels);
            }

            if (mXOffset != xOffset || mYOffset != yOffset) {
                if (DEBUG) {
                    Log.d(TAG, "Offsets changed to (" + xOffset + "," + yOffset + ").");
                }
                mXOffset = xOffset;
                mYOffset = yOffset;
                mOffsetsChanged = true;
            }
            drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }

            super.onSurfaceChanged(holder, format, width, height);

            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceDestroyed");
            }

            mLastSurfaceWidth = mLastSurfaceHeight = -1;
            mSurfaceValid = false;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (DEBUG) {
                Log.i(TAG, "onSurfaceCreated");
            }

            mLastSurfaceWidth = mLastSurfaceHeight = -1;
            mSurfaceValid = true;
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
            if (mBackground == null) {
                updateBitmap(mWallpaperManager.getBitmap(true /* hardware */));
            }
            mSurfaceRedrawNeeded = true;
            drawFrame();
        }

        @VisibleForTesting
        DisplayInfo getDisplayInfo() {
            mDisplay.getDisplayInfo(mTmpDisplayInfo);
            return mTmpDisplayInfo;
        }

        void drawFrame() {
            if (!mSurfaceValid) {
                return;
            }
            try {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "drawWallpaper");
                DisplayInfo displayInfo = getDisplayInfo();
                int newRotation = displayInfo.rotation;

                // Sometimes a wallpaper is not large enough to cover the screen in one dimension.
                // Call updateSurfaceSize -- it will only actually do the update if the dimensions
                // should change
                if (newRotation != mLastRotation
                        || mDisplayWidthAtLastSurfaceSizeUpdate != displayInfo.logicalWidth
                        || mDisplayHeightAtLastSurfaceSizeUpdate != displayInfo.logicalHeight) {
                    // Update surface size (if necessary)
                    if (!updateSurfaceSize(getSurfaceHolder(), displayInfo, true /* forDraw */)) {
                        return; // had to reload wallpaper, will retry later
                    }
                    mRotationAtLastSurfaceSizeUpdate = newRotation;
                    mDisplayWidthAtLastSurfaceSizeUpdate = displayInfo.logicalWidth;
                    mDisplayHeightAtLastSurfaceSizeUpdate = displayInfo.logicalHeight;
                }
                SurfaceHolder sh = getSurfaceHolder();
                final Rect frame = sh.getSurfaceFrame();
                final int dw = frame.width();
                final int dh = frame.height();
                boolean surfaceDimensionsChanged = dw != mLastSurfaceWidth
                        || dh != mLastSurfaceHeight;

                boolean redrawNeeded = surfaceDimensionsChanged || newRotation != mLastRotation
                        || mSurfaceRedrawNeeded || mNeedsDrawAfterLoadingWallpaper;
                if (!redrawNeeded && !mOffsetsChanged) {
                    if (DEBUG) {
                        Log.d(TAG, "Suppressed drawFrame since redraw is not needed "
                                + "and offsets have not changed.");
                    }
                    return;
                }
                mLastRotation = newRotation;
                mSurfaceRedrawNeeded = false;

                // Load bitmap if it is not yet loaded
                if (mBackground == null) {
                    loadWallpaper(true);
                    if (DEBUG) {
                        Log.d(TAG, "Reloading, resuming draw later");
                    }
                    return;
                }

                // Left align the scaled image
                mScale = Math.max(1f, Math.max(dw / (float) mBackground.getWidth(),
                        dh / (float) mBackground.getHeight()));
                final int availw = (int) (mBackground.getWidth() * mScale) - dw;
                final int availh = (int) (mBackground.getHeight() * mScale) - dh;
                int xPixels = (int) (availw * mXOffset);
                int yPixels = (int) (availh * mYOffset);

                mOffsetsChanged = false;
                if (surfaceDimensionsChanged) {
                    mLastSurfaceWidth = dw;
                    mLastSurfaceHeight = dh;
                }
                if (!redrawNeeded && xPixels == mLastXTranslation && yPixels == mLastYTranslation) {
                    if (DEBUG) {
                        Log.d(TAG, "Suppressed drawFrame since the image has not "
                                + "actually moved an integral number of pixels.");
                    }
                    return;
                }
                mLastXTranslation = xPixels;
                mLastYTranslation = yPixels;

                if (DEBUG) {
                    Log.d(TAG, "Redrawing wallpaper");
                }

                drawWallpaperWithCanvas(sh, availw, availh, xPixels, yPixels);
                scheduleUnloadWallpaper();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
        }

        /**
         * Loads the wallpaper on background thread and schedules updating the surface frame,
         * and if {@param needsDraw} is set also draws a frame.
         *
         * If loading is already in-flight, subsequent loads are ignored (but needDraw is or-ed to
         * the active request).
         *
         * If {@param needsReset} is set also clears the cache in WallpaperManager first.
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
                protected void onPostExecute(Bitmap b) {
                    updateBitmap(b);

                    if (mNeedsDrawAfterLoadingWallpaper) {
                        drawFrame();
                    }

                    mLoader = null;
                    mNeedsDrawAfterLoadingWallpaper = false;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @VisibleForTesting
        void updateBitmap(Bitmap bitmap) {
            mBackground = null;
            mBackgroundWidth = -1;
            mBackgroundHeight = -1;

            if (bitmap != null) {
                mBackground = bitmap;
                mBackgroundWidth = mBackground.getWidth();
                mBackgroundHeight = mBackground.getHeight();
            }

            if (DEBUG) {
                Log.d(TAG, "Wallpaper loaded: " + mBackground);
            }
            updateSurfaceSize(getSurfaceHolder(), getDisplayInfo(),
                    false /* forDraw */);
        }

        private void unloadWallpaper(boolean forgetSize) {
            if (mLoader != null) {
                mLoader.cancel(false);
                mLoader = null;
            }
            mBackground = null;
            if (forgetSize) {
                mBackgroundWidth = -1;
                mBackgroundHeight = -1;
            }

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
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);

            out.print(prefix); out.println("ImageWallpaper.DrawableEngine:");
            out.print(prefix); out.print(" mBackground="); out.print(mBackground);
            out.print(" mBackgroundWidth="); out.print(mBackgroundWidth);
            out.print(" mBackgroundHeight="); out.println(mBackgroundHeight);

            out.print(prefix); out.print(" mLastRotation="); out.print(mLastRotation);
            out.print(" mLastSurfaceWidth="); out.print(mLastSurfaceWidth);
            out.print(" mLastSurfaceHeight="); out.println(mLastSurfaceHeight);

            out.print(prefix); out.print(" mXOffset="); out.print(mXOffset);
            out.print(" mYOffset="); out.println(mYOffset);

            out.print(prefix); out.print(" mVisible="); out.print(mVisible);
            out.print(" mOffsetsChanged="); out.println(mOffsetsChanged);

            out.print(prefix); out.print(" mLastXTranslation="); out.print(mLastXTranslation);
            out.print(" mLastYTranslation="); out.print(mLastYTranslation);
            out.print(" mScale="); out.println(mScale);

            out.print(prefix); out.print(" mLastRequestedWidth="); out.print(mLastRequestedWidth);
            out.print(" mLastRequestedHeight="); out.println(mLastRequestedHeight);

            out.print(prefix); out.println(" DisplayInfo at last updateSurfaceSize:");
            out.print(prefix);
            out.print("  rotation="); out.print(mRotationAtLastSurfaceSizeUpdate);
            out.print("  width="); out.print(mDisplayWidthAtLastSurfaceSizeUpdate);
            out.print("  height="); out.println(mDisplayHeightAtLastSurfaceSizeUpdate);
        }

        private void drawWallpaperWithCanvas(SurfaceHolder sh, int w, int h, int left, int top) {
            Canvas c = sh.lockHardwareCanvas();
            if (c != null) {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "Redrawing: left=" + left + ", top=" + top);
                    }

                    final float right = left + mBackground.getWidth() * mScale;
                    final float bottom = top + mBackground.getHeight() * mScale;
                    if (w < 0 || h < 0) {
                        c.save(Canvas.CLIP_SAVE_FLAG);
                        c.clipRect(left, top, right, bottom,
                                Op.DIFFERENCE);
                        c.drawColor(0xff000000);
                        c.restore();
                    }
                    if (mBackground != null) {
                        RectF dest = new RectF(left, top, right, bottom);
                        Log.i(TAG, "Redrawing in rect: " + dest + " with surface size: "
                                + mLastRequestedWidth + "x" + mLastRequestedHeight);
                        c.drawBitmap(mBackground, null, dest, null);
                    }
                } finally {
                    sh.unlockCanvasAndPost(c);
                }
            }
        }
    }
}
