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


package com.android.systemui.wallpapers;

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.util.Assert;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * This class is used by the {@link ImageWallpaper} to extract colors from areas of a wallpaper.
 * It uses a background executor, and uses callbacks to inform that the work is done.
 * It uses  a downscaled version of the wallpaper to extract the colors.
 */
public class WallpaperLocalColorExtractor {

    private Bitmap mMiniBitmap;

    @VisibleForTesting
    static final int SMALL_SIDE = 128;

    private static final String TAG = WallpaperLocalColorExtractor.class.getSimpleName();
    private static final @NonNull RectF LOCAL_COLOR_BOUNDS =
            new RectF(0, 0, 1, 1);

    private int mDisplayWidth = -1;
    private int mDisplayHeight = -1;
    private int mPages = -1;
    private int mBitmapWidth = -1;
    private int mBitmapHeight = -1;

    private final Object mLock = new Object();

    private final List<RectF> mPendingRegions = new ArrayList<>();
    private final Set<RectF> mProcessedRegions = new ArraySet<>();

    @LongRunning
    private final Executor mLongExecutor;

    private final WallpaperLocalColorExtractorCallback mWallpaperLocalColorExtractorCallback;

    /**
     * Interface to handle the callbacks after the different steps of the color extraction
     */
    public interface WallpaperLocalColorExtractorCallback {
        /**
         * Callback after the colors of new regions have been extracted
         * @param regions the list of new regions that have been processed
         * @param colors the resulting colors for these regions, in the same order as the regions
         */
        void onColorsProcessed(List<RectF> regions, List<WallpaperColors> colors);

        /**
         * Callback after the mini bitmap is computed, to indicate that the wallpaper bitmap is
         * no longer used by the color extractor and can be safely recycled
         */
        void onMiniBitmapUpdated();

        /**
         * Callback to inform that the extractor has started processing colors
         */
        void onActivated();

        /**
         * Callback to inform that no more colors are being processed
         */
        void onDeactivated();
    }

    /**
     * Creates a new color extractor.
     * @param longExecutor the executor on which the color extraction will be performed
     * @param wallpaperLocalColorExtractorCallback an interface to handle the callbacks from
     *                                        the color extractor.
     */
    public WallpaperLocalColorExtractor(@LongRunning Executor longExecutor,
            WallpaperLocalColorExtractorCallback wallpaperLocalColorExtractorCallback) {
        mLongExecutor = longExecutor;
        mWallpaperLocalColorExtractorCallback = wallpaperLocalColorExtractorCallback;
    }

    /**
     * Used by the outside to inform that the display size has changed.
     * The new display size will be used in the next computations, but the current colors are
     * not recomputed.
     */
    public void setDisplayDimensions(int displayWidth, int displayHeight) {
        mLongExecutor.execute(() ->
                setDisplayDimensionsSynchronized(displayWidth, displayHeight));
    }

    private void setDisplayDimensionsSynchronized(int displayWidth, int displayHeight) {
        synchronized (mLock) {
            if (displayWidth == mDisplayWidth && displayHeight == mDisplayHeight) return;
            mDisplayWidth = displayWidth;
            mDisplayHeight = displayHeight;
            processColorsInternal();
        }
    }

    /**
     * @return whether color extraction is currently in use
     */
    private boolean isActive() {
        return mPendingRegions.size() + mProcessedRegions.size() > 0;
    }

    /**
     * Should be called when the wallpaper is changed.
     * This will recompute the mini bitmap
     * and restart the extraction of all areas
     * @param bitmap the new wallpaper
     */
    public void onBitmapChanged(@NonNull Bitmap bitmap) {
        mLongExecutor.execute(() -> onBitmapChangedSynchronized(bitmap));
    }

    private void onBitmapChangedSynchronized(@NonNull Bitmap bitmap) {
        synchronized (mLock) {
            if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                Log.e(TAG, "Attempt to extract colors from an invalid bitmap");
                return;
            }
            mBitmapWidth = bitmap.getWidth();
            mBitmapHeight = bitmap.getHeight();
            mMiniBitmap = createMiniBitmap(bitmap);
            mWallpaperLocalColorExtractorCallback.onMiniBitmapUpdated();
            recomputeColors();
        }
    }

    /**
     * Should be called when the number of pages is changed
     * This will restart the extraction of all areas
     * @param pages the total number of pages of the launcher
     */
    public void onPageChanged(int pages) {
        mLongExecutor.execute(() -> onPageChangedSynchronized(pages));
    }

    private void onPageChangedSynchronized(int pages) {
        synchronized (mLock) {
            if (mPages == pages) return;
            mPages = pages;
            if (mMiniBitmap != null && !mMiniBitmap.isRecycled()) {
                recomputeColors();
            }
        }
    }

    // helper to recompute colors, to be called in synchronized methods
    private void recomputeColors() {
        mPendingRegions.addAll(mProcessedRegions);
        mProcessedRegions.clear();
        processColorsInternal();
    }

    /**
     * Add new regions to extract
     * This will trigger the color extraction and call the callback only for these new regions
     * @param regions The areas of interest in our wallpaper (in screen pixel coordinates)
     */
    public void addLocalColorsAreas(@NonNull List<RectF> regions) {
        if (regions.size() > 0) {
            mLongExecutor.execute(() -> addLocalColorsAreasSynchronized(regions));
        } else {
            Log.w(TAG, "Attempt to add colors with an empty list");
        }
    }

    private void addLocalColorsAreasSynchronized(@NonNull List<RectF> regions) {
        synchronized (mLock) {
            boolean wasActive = isActive();
            mPendingRegions.addAll(regions);
            if (!wasActive && isActive()) {
                mWallpaperLocalColorExtractorCallback.onActivated();
            }
            processColorsInternal();
        }
    }

    /**
     * Remove regions to extract. If a color extraction is ongoing does not stop it.
     * But if there are subsequent changes that restart the extraction, the removed regions
     * will not be recomputed.
     * @param regions The areas of interest in our wallpaper (in screen pixel coordinates)
     */
    public void removeLocalColorAreas(@NonNull List<RectF> regions) {
        mLongExecutor.execute(() -> removeLocalColorAreasSynchronized(regions));
    }

    private void removeLocalColorAreasSynchronized(@NonNull List<RectF> regions) {
        synchronized (mLock) {
            boolean wasActive = isActive();
            mPendingRegions.removeAll(regions);
            regions.forEach(mProcessedRegions::remove);
            if (wasActive && !isActive()) {
                mWallpaperLocalColorExtractorCallback.onDeactivated();
            }
        }
    }

    /**
     * Clean up the memory (in particular, the mini bitmap) used by this class.
     */
    public void cleanUp() {
        mLongExecutor.execute(this::cleanUpSynchronized);
    }

    private void cleanUpSynchronized() {
        synchronized (mLock) {
            if (mMiniBitmap != null) {
                mMiniBitmap.recycle();
                mMiniBitmap = null;
            }
            mProcessedRegions.clear();
            mPendingRegions.clear();
        }
    }

    private Bitmap createMiniBitmap(@NonNull Bitmap bitmap) {
        Trace.beginSection("WallpaperLocalColorExtractor#createMiniBitmap");
        // if both sides of the image are larger than SMALL_SIDE, downscale the bitmap.
        int smallestSide = Math.min(bitmap.getWidth(), bitmap.getHeight());
        float scale = Math.min(1.0f, (float) SMALL_SIDE / smallestSide);
        Bitmap result = createMiniBitmap(bitmap,
                (int) (scale * bitmap.getWidth()),
                (int) (scale * bitmap.getHeight()));
        Trace.endSection();
        return result;
    }

    @VisibleForTesting
    Bitmap createMiniBitmap(@NonNull Bitmap bitmap, int width, int height) {
        return Bitmap.createScaledBitmap(bitmap, width, height, false);
    }

    private WallpaperColors getLocalWallpaperColors(@NonNull RectF area) {
        RectF imageArea = pageToImgRect(area);
        if (imageArea == null || !LOCAL_COLOR_BOUNDS.contains(imageArea)) {
            return null;
        }
        Rect subImage = new Rect(
                (int) Math.floor(imageArea.left * mMiniBitmap.getWidth()),
                (int) Math.floor(imageArea.top * mMiniBitmap.getHeight()),
                (int) Math.ceil(imageArea.right * mMiniBitmap.getWidth()),
                (int) Math.ceil(imageArea.bottom * mMiniBitmap.getHeight()));
        if (subImage.isEmpty()) {
            // Do not notify client. treat it as too small to sample
            return null;
        }
        return getLocalWallpaperColors(subImage);
    }

    @VisibleForTesting
    WallpaperColors getLocalWallpaperColors(@NonNull Rect subImage) {
        Assert.isNotMainThread();
        Bitmap colorImg = Bitmap.createBitmap(mMiniBitmap,
                subImage.left, subImage.top, subImage.width(), subImage.height());
        return WallpaperColors.fromBitmap(colorImg);
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
        // Width of a page for the caller of this API.
        float virtualPageWidth = 1f / (float) mPages;
        float leftPosOnPage = (area.left % virtualPageWidth) / virtualPageWidth;
        float rightPosOnPage = (area.right % virtualPageWidth) / virtualPageWidth;
        int currentPage = (int) Math.floor(area.centerX() / virtualPageWidth);

        if (mDisplayWidth <= 0 || mDisplayHeight <= 0) {
            Log.e(TAG, "Trying to extract colors with invalid display dimensions");
            return null;
        }

        RectF imgArea = new RectF();
        imgArea.bottom = area.bottom;
        imgArea.top = area.top;

        float imageScale = Math.min(((float) mBitmapHeight) / mDisplayHeight, 1);
        float mappedScreenWidth = mDisplayWidth * imageScale;
        float pageWidth = Math.min(1.0f,
                mBitmapWidth > 0 ? mappedScreenWidth / (float) mBitmapWidth : 1.f);
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

    /**
     * Extract the colors from the pending regions,
     * then notify the callback with the resulting colors for these regions
     * This method should only be called synchronously
     */
    private void processColorsInternal() {
        /*
         * if the miniBitmap is not yet loaded, that means the onBitmapChanged has not yet been
         * called, and thus the wallpaper is not yet loaded. In that case, exit, the function
         * will be called again when the bitmap is loaded and the miniBitmap is computed.
         */
        if (mMiniBitmap == null || mMiniBitmap.isRecycled())  return;

        /*
         * if the screen size or number of pages is not yet known, exit
         * the function will be called again once the screen size and page are known
         */
        if (mDisplayWidth < 0 || mDisplayHeight < 0 || mPages < 0) return;

        Trace.beginSection("WallpaperLocalColorExtractor#processColorsInternal");
        List<WallpaperColors> processedColors = new ArrayList<>();
        for (int i = 0; i < mPendingRegions.size(); i++) {
            RectF nextArea = mPendingRegions.get(i);
            WallpaperColors colors = getLocalWallpaperColors(nextArea);

            mProcessedRegions.add(nextArea);
            processedColors.add(colors);
        }
        List<RectF> processedRegions = new ArrayList<>(mPendingRegions);
        mPendingRegions.clear();
        Trace.endSection();

        mWallpaperLocalColorExtractorCallback.onColorsProcessed(processedRegions, processedColors);
    }

    /**
     * Called to dump current state.
     * @param prefix prefix.
     * @param fd fd.
     * @param out out.
     * @param args args.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        out.print(prefix); out.print("display="); out.println(mDisplayWidth + "x" + mDisplayHeight);
        out.print(prefix); out.print("mPages="); out.println(mPages);

        out.print(prefix); out.print("bitmap dimensions=");
        out.println(mBitmapWidth + "x" + mBitmapHeight);

        out.print(prefix); out.print("bitmap=");
        out.println(mMiniBitmap == null ? "null"
                : mMiniBitmap.isRecycled() ? "recycled"
                : mMiniBitmap.getWidth() + "x" + mMiniBitmap.getHeight());

        out.print(prefix); out.print("PendingRegions size="); out.print(mPendingRegions.size());
        out.print(prefix); out.print("ProcessedRegions size="); out.print(mProcessedRegions.size());
    }
}
