/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wallpaper;

import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;
import static android.app.WallpaperManager.getOrientation;
import static android.app.WallpaperManager.getRotatedOrientation;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wallpaper.WallpaperUtils.RECORD_FILE;
import static com.android.server.wallpaper.WallpaperUtils.RECORD_LOCK_FILE;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;
import static com.android.server.wallpaper.WallpaperUtils.getWallpaperDir;
import static com.android.window.flags.Flags.multiCrop;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.FileUtils;
import android.os.SELinux;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.TimingsTraceAndSlog;

import libcore.io.IoUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helper file for wallpaper cropping
 * Meant to have a single instance, only used internally by system_server
 * @hide
 */
public class WallpaperCropper {

    private static final String TAG = WallpaperCropper.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_CROP = true;

    /**
     * Maximum acceptable parallax.
     * A value of 1 means "the additional width for parallax is at most 100% of the screen width"
     */
    @VisibleForTesting static final float MAX_PARALLAX = 1f;

    /**
     * We define three ways to adjust a crop. These modes are used depending on the situation:
     *   - When going from unfolded to folded, we want to remove content
     *   - When going from folded to unfolded, we want to add content
     *   - For a screen rotation, we want to keep the same amount of content
     */
    @VisibleForTesting static final int ADD = 1;
    @VisibleForTesting static final int REMOVE = 2;
    @VisibleForTesting static final int BALANCE = 3;

    private final WallpaperDisplayHelper mWallpaperDisplayHelper;

    /**
     * Helpers exposed to the window manager part (WallpaperController)
     */
    public interface WallpaperCropUtils {

        /**
         * Equivalent to {@link WallpaperCropper#getCrop(Point, Point, SparseArray, boolean)}
         */
        Rect getCrop(Point displaySize, Point bitmapSize,
                SparseArray<Rect> suggestedCrops, boolean rtl);
    }

    WallpaperCropper(WallpaperDisplayHelper wallpaperDisplayHelper) {
        mWallpaperDisplayHelper = wallpaperDisplayHelper;
    }

    /**
     * Given the dimensions of the original wallpaper image, some optional suggested crops
     * (either defined by the user, or coming from a backup), and whether the device has RTL layout,
     * generate a crop for the current display. This is done through the following process:
     * <ul>
     *     <li> If no suggested crops are provided, in most cases render the full image left-aligned
     *     (or right-aligned if RTL) and use any additional width for parallax up to
     *     {@link #MAX_PARALLAX}. There are exceptions, see comments in "Case 1" of this function.
     *     <li> If there is a suggested crop the given displaySize, reuse the suggested crop and
     *     adjust it using {@link #getAdjustedCrop}.
     *     <li> If there are suggested crops, but not for the orientation of the given displaySize,
     *     reuse one of the suggested crop for another orientation and adjust if using
     *     {@link #getAdjustedCrop}.
     * </ul>
     *
     * @param displaySize     The dimensions of the surface where we want to render the wallpaper
     * @param bitmapSize      The dimensions of the wallpaper bitmap
     * @param rtl             Whether the device is right-to-left
     * @param suggestedCrops  An optional list of user-defined crops for some orientations.
     *                        If there is a suggested crop for
     *
     * @return  A Rect indicating how to crop the bitmap for the current display.
     */
    public Rect getCrop(Point displaySize, Point bitmapSize,
            SparseArray<Rect> suggestedCrops, boolean rtl) {

        int orientation = getOrientation(displaySize);

        // Case 1: if no crops are provided, show the full image (from the left, or right if RTL).
        if (suggestedCrops == null || suggestedCrops.size() == 0) {
            Rect crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);

            // The first exception is if the device is a foldable and we're on the folded screen.
            // In that case, show the center of what's on the unfolded screen.
            int unfoldedOrientation = mWallpaperDisplayHelper.getUnfoldedOrientation(orientation);
            if (unfoldedOrientation != ORIENTATION_UNKNOWN) {
                // Let the system know that we're showing the full image on the unfolded screen
                SparseArray<Rect> newSuggestedCrops = new SparseArray<>();
                newSuggestedCrops.put(unfoldedOrientation, crop);
                // This will fall into "Case 4" of this function and center the folded screen
                return getCrop(displaySize, bitmapSize, newSuggestedCrops, rtl);
            }

            // The second exception is if we're on tablet and we're on portrait mode.
            // In that case, center the wallpaper relatively to landscape and put some parallax.
            boolean isTablet = mWallpaperDisplayHelper.isLargeScreen()
                    && !mWallpaperDisplayHelper.isFoldable();
            if (isTablet && displaySize.x < displaySize.y) {
                Point rotatedDisplaySize = new Point(displaySize.y, displaySize.x);
                // compute the crop on landscape (without parallax)
                Rect landscapeCrop = getCrop(rotatedDisplaySize, bitmapSize, suggestedCrops, rtl);
                landscapeCrop = noParallax(landscapeCrop, rotatedDisplaySize, bitmapSize, rtl);
                // compute the crop on portrait at the center of the landscape crop
                crop = getAdjustedCrop(landscapeCrop, bitmapSize, displaySize, false, rtl, ADD);

                // add some parallax (until the border of the landscape crop without parallax)
                if (rtl) {
                    crop.left = landscapeCrop.left;
                } else {
                    crop.right = landscapeCrop.right;
                }
            }

            return getAdjustedCrop(crop, bitmapSize, displaySize, true, rtl, ADD);
        }

        // If any suggested crop is invalid, fallback to case 1
        for (int i = 0; i < suggestedCrops.size(); i++) {
            Rect testCrop = suggestedCrops.valueAt(i);
            if (testCrop == null || testCrop.left < 0 || testCrop.top < 0
                    || testCrop.right > bitmapSize.x || testCrop.bottom > bitmapSize.y) {
                Slog.w(TAG, "invalid crop: " + testCrop + " for bitmap size: " + bitmapSize);
                return getCrop(displaySize, bitmapSize, new SparseArray<>(), rtl);
            }
        }

        // Case 2: if the orientation exists in the suggested crops, adjust the suggested crop
        Rect suggestedCrop = suggestedCrops.get(orientation);
        if (suggestedCrop != null) {
            return getAdjustedCrop(suggestedCrop, bitmapSize, displaySize, true, rtl, ADD);
        }

        // Case 3: if we have the 90° rotated orientation in the suggested crops, reuse it and
        // trying to preserve the zoom level and the center of the image
        SparseArray<Point> defaultDisplaySizes = mWallpaperDisplayHelper.getDefaultDisplaySizes();
        int rotatedOrientation = getRotatedOrientation(orientation);
        suggestedCrop = suggestedCrops.get(rotatedOrientation);
        Point suggestedDisplaySize = defaultDisplaySizes.get(rotatedOrientation);
        if (suggestedCrop != null) {
            // only keep the visible part (without parallax)
            Rect adjustedCrop = noParallax(suggestedCrop, suggestedDisplaySize, bitmapSize, rtl);
            return getAdjustedCrop(adjustedCrop, bitmapSize, displaySize, false, rtl, BALANCE);
        }

        // Case 4: if the device is a foldable, if we're looking for a folded orientation and have
        // the suggested crop of the relative unfolded orientation, reuse it by removing content.
        int unfoldedOrientation = mWallpaperDisplayHelper.getUnfoldedOrientation(orientation);
        suggestedCrop = suggestedCrops.get(unfoldedOrientation);
        suggestedDisplaySize = defaultDisplaySizes.get(unfoldedOrientation);
        if (suggestedCrop != null) {
            // compute the visible part (without parallax) of the unfolded screen
            Rect adjustedCrop = noParallax(suggestedCrop, suggestedDisplaySize, bitmapSize, rtl);
            // compute the folded crop, at the center of the crop of the unfolded screen
            Rect res = getAdjustedCrop(adjustedCrop, bitmapSize, displaySize, false, rtl, REMOVE);
            // if we removed some width, add it back to add a parallax effect
            if (res.width() < adjustedCrop.width()) {
                if (rtl) res.left = Math.min(res.left, adjustedCrop.left);
                else res.right = Math.max(res.right, adjustedCrop.right);
                // use getAdjustedCrop(parallax=true) to make sure we don't exceed MAX_PARALLAX
                res = getAdjustedCrop(res, bitmapSize, displaySize, true, rtl, ADD);
            }
            return res;
        }


        // Case 5: if the device is a foldable, if we're looking for an unfolded orientation and
        // have the suggested crop of the relative folded orientation, reuse it by adding content.
        int foldedOrientation = mWallpaperDisplayHelper.getFoldedOrientation(orientation);
        suggestedCrop = suggestedCrops.get(foldedOrientation);
        suggestedDisplaySize = defaultDisplaySizes.get(foldedOrientation);
        if (suggestedCrop != null) {
            // only keep the visible part (without parallax)
            Rect adjustedCrop = noParallax(suggestedCrop, suggestedDisplaySize, bitmapSize, rtl);
            return getAdjustedCrop(adjustedCrop, bitmapSize, displaySize, false, rtl, ADD);
        }

        // Case 6: for a foldable device, try to combine case 3 + case 4 or 5:
        // rotate, then fold or unfold
        Point rotatedDisplaySize = defaultDisplaySizes.get(rotatedOrientation);
        if (rotatedDisplaySize != null) {
            int rotatedFolded = mWallpaperDisplayHelper.getFoldedOrientation(rotatedOrientation);
            int rotateUnfolded = mWallpaperDisplayHelper.getUnfoldedOrientation(rotatedOrientation);
            for (int suggestedOrientation : new int[]{rotatedFolded, rotateUnfolded}) {
                suggestedCrop = suggestedCrops.get(suggestedOrientation);
                if (suggestedCrop != null) {
                    Rect rotatedCrop = getCrop(rotatedDisplaySize, bitmapSize, suggestedCrops, rtl);
                    SparseArray<Rect> rotatedCropMap = new SparseArray<>();
                    rotatedCropMap.put(rotatedOrientation, rotatedCrop);
                    return getCrop(displaySize, bitmapSize, rotatedCropMap, rtl);
                }
            }
        }

        // Case 7: could not properly reuse the suggested crops. Fall back to case 1.
        Slog.w(TAG, "Could not find a proper default crop for display: " + displaySize
                + ", bitmap size: " + bitmapSize + ", suggested crops: " + suggestedCrops
                + ", orientation: " + orientation + ", rtl: " + rtl
                + ", defaultDisplaySizes: " + defaultDisplaySizes);
        return getCrop(displaySize, bitmapSize, new SparseArray<>(), rtl);
    }

    /**
     * Given a crop, a displaySize for the orientation of that crop, compute the visible part of the
     * crop. This removes any additional width used for parallax. No-op if displaySize == null.
     */
    @VisibleForTesting
    static Rect noParallax(Rect crop, Point displaySize, Point bitmapSize, boolean rtl) {
        if (displaySize == null) return crop;
        Rect adjustedCrop = getAdjustedCrop(crop, bitmapSize, displaySize, true, rtl, ADD);
        // only keep the visible part (without parallax)
        float suggestedDisplayRatio = 1f * displaySize.x / displaySize.y;
        int widthToRemove = (int) (adjustedCrop.width()
                - (((float) adjustedCrop.height()) * suggestedDisplayRatio) + 0.5f);
        if (rtl) {
            adjustedCrop.left += widthToRemove;
        } else {
            adjustedCrop.right -= widthToRemove;
        }
        return adjustedCrop;
    }

    /**
     * Adjust a given crop:
     * <ul>
     *     <li>If parallax = true, make sure we have a parallax of at most {@link #MAX_PARALLAX},
     *     by removing content from the right (or left if RTL) if necessary.
     *     <li>If parallax = false, make sure we do not have additional width for parallax. If we
     *     have additional width for parallax, remove half of the additional width on both sides.
     *     <li>Make sure the crop fills the screen, i.e. that the width/height ratio of the crop
     *     is at least the width/height ratio of the screen. This is done accordingly to the
     *     {@code mode} used, which can be either {@link #ADD}, {@link #REMOVE} or {@link #BALANCE}.
     * </ul>
     */
    @VisibleForTesting
    static Rect getAdjustedCrop(Rect crop, Point bitmapSize, Point screenSize,
            boolean parallax, boolean rtl, int mode) {
        Rect adjustedCrop = new Rect(crop);
        float cropRatio = ((float) crop.width()) / crop.height();
        float screenRatio = ((float) screenSize.x) / screenSize.y;
        if (cropRatio == screenRatio) return crop;
        if (cropRatio > screenRatio) {
            if (!parallax) {
                // rotate everything 90 degrees clockwise, compute the result, and rotate back
                int newLeft = bitmapSize.y - crop.bottom;
                int newRight = newLeft + crop.height();
                int newTop = crop.left;
                int newBottom = newTop + crop.width();
                Rect rotatedCrop = new Rect(newLeft, newTop, newRight, newBottom);
                Point rotatedBitmap = new Point(bitmapSize.y, bitmapSize.x);
                Point rotatedScreen = new Point(screenSize.y, screenSize.x);
                Rect rect = getAdjustedCrop(
                        rotatedCrop, rotatedBitmap, rotatedScreen, false, rtl, mode);
                int resultLeft = rect.top;
                int resultRight = resultLeft + rect.height();
                int resultTop = rotatedBitmap.x - rect.right;
                int resultBottom = resultTop + rect.width();
                return new Rect(resultLeft, resultTop, resultRight, resultBottom);
            }
            float additionalWidthForParallax = cropRatio / screenRatio - 1f;
            if (additionalWidthForParallax > MAX_PARALLAX) {
                int widthToRemove = (int) Math.ceil(
                        (additionalWidthForParallax - MAX_PARALLAX) * screenRatio * crop.height());
                if (rtl) {
                    adjustedCrop.left += widthToRemove;
                } else {
                    adjustedCrop.right -= widthToRemove;
                }
            }
        } else {
            // Note: the third case when MODE == BALANCE, -W + sqrt(W * H * R), is the width to add
            // so that, when removing the appropriate height, we get a bitmap of aspect ratio R and
            // total surface of W * H. In other words it is the width to add to get the desired
            // aspect ratio R, while preserving the total number of pixels W * H.
            int widthToAdd = mode == REMOVE ? 0
                    : mode == ADD ? (int) (crop.height() * screenRatio - crop.width())
                    : (int) (-crop.width() + Math.sqrt(crop.width() * crop.height() * screenRatio));
            int availableWidth = bitmapSize.x - crop.width();
            if (availableWidth >= widthToAdd) {
                int widthToAddLeft = widthToAdd / 2;
                int widthToAddRight = widthToAdd / 2 + widthToAdd % 2;

                if (crop.left < widthToAddLeft) {
                    widthToAddRight += (widthToAddLeft - crop.left);
                    widthToAddLeft = crop.left;
                } else if (bitmapSize.x - crop.right < widthToAddRight) {
                    widthToAddLeft += (widthToAddRight - (bitmapSize.x - crop.right));
                    widthToAddRight = bitmapSize.x - crop.right;
                }
                adjustedCrop.left -= widthToAddLeft;
                adjustedCrop.right += widthToAddRight;
            } else {
                adjustedCrop.left = 0;
                adjustedCrop.right = bitmapSize.x;
            }
            int heightToRemove = (int) (crop.height() - (adjustedCrop.width() / screenRatio));
            adjustedCrop.top += heightToRemove / 2 + heightToRemove % 2;
            adjustedCrop.bottom -= heightToRemove / 2;
        }
        return adjustedCrop;
    }

    /**
     * To find the smallest sub-image that contains all the given crops.
     * This is used in {@link #generateCrop(WallpaperData)}
     * to determine how the file from {@link WallpaperData#getCropFile()} needs to be cropped.
     *
     * @param crops a list of rectangles
     * @return the smallest rectangle that contains them all.
     */
    public static Rect getTotalCrop(SparseArray<Rect> crops) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (int i = 0; i < crops.size(); i++) {
            Rect rect = crops.valueAt(i);
            left = Math.min(left, rect.left);
            top = Math.min(top, rect.top);
            right = Math.max(right, rect.right);
            bottom = Math.max(bottom, rect.bottom);
        }
        return new Rect(left, top, right, bottom);
    }

    /**
     * The crops stored in {@link WallpaperData#mCropHints} are relative to the original image.
     * This computes the crops relative to the sub-image that will actually be rendered on a window.
     */
    SparseArray<Rect> getRelativeCropHints(WallpaperData wallpaper) {
        SparseArray<Rect> result = new SparseArray<>();
        for (int i = 0; i < wallpaper.mCropHints.size(); i++) {
            Rect adjustedRect = new Rect(wallpaper.mCropHints.valueAt(i));
            adjustedRect.offset(-wallpaper.cropHint.left, -wallpaper.cropHint.top);
            adjustedRect.scale(1f / wallpaper.mSampleSize);
            result.put(wallpaper.mCropHints.keyAt(i), adjustedRect);
        }
        return result;
    }

    /**
     * Inverse operation of {@link #getRelativeCropHints}
     */
    static List<Rect> getOriginalCropHints(
            WallpaperData wallpaper, List<Rect> relativeCropHints) {
        List<Rect> result = new ArrayList<>();
        for (Rect crop : relativeCropHints) {
            Rect originalRect = new Rect(crop);
            originalRect.scale(wallpaper.mSampleSize);
            originalRect.offset(wallpaper.cropHint.left, wallpaper.cropHint.top);
            result.add(originalRect);
        }
        return result;
    }

    /**
     * Given some suggested crops, find cropHints for all orientations of the default display.
     */
    SparseArray<Rect> getDefaultCrops(SparseArray<Rect> suggestedCrops, Point bitmapSize) {

        // If the suggested crops is single-element map with (ORIENTATION_UNKNOWN, cropHint),
        // Crop the bitmap using the cropHint and compute the crops for cropped bitmap.
        Rect cropHint = suggestedCrops.get(ORIENTATION_UNKNOWN);
        if (cropHint != null) {
            Rect bitmapRect = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
            if (suggestedCrops.size() != 1 || !bitmapRect.contains(cropHint)) {
                Slog.w(TAG, "Couldn't get default crops from suggested crops " + suggestedCrops
                        + " for bitmap of size " + bitmapSize + "; ignoring suggested crops");
                return getDefaultCrops(new SparseArray<>(), bitmapSize);
            }
            Point cropSize = new Point(cropHint.width(), cropHint.height());
            SparseArray<Rect> relativeDefaultCrops = getDefaultCrops(new SparseArray<>(), cropSize);
            for (int i = 0; i < relativeDefaultCrops.size(); i++) {
                relativeDefaultCrops.valueAt(i).offset(cropHint.left, cropHint.top);
            }
            return relativeDefaultCrops;
        }

        SparseArray<Point> defaultDisplaySizes = mWallpaperDisplayHelper.getDefaultDisplaySizes();
        boolean rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                == View.LAYOUT_DIRECTION_RTL;

        // adjust existing entries for the default display
        SparseArray<Rect> adjustedSuggestedCrops = new SparseArray<>();
        for (int i = 0; i < defaultDisplaySizes.size(); i++) {
            int orientation = defaultDisplaySizes.keyAt(i);
            Point displaySize = defaultDisplaySizes.valueAt(i);
            Rect suggestedCrop = suggestedCrops.get(orientation);
            if (suggestedCrop != null) {
                adjustedSuggestedCrops.put(orientation,
                        getCrop(displaySize, bitmapSize, suggestedCrops, rtl));
            }
        }

        // add missing cropHints for all orientation of the default display
        SparseArray<Rect> result = adjustedSuggestedCrops.clone();
        for (int i = 0; i < defaultDisplaySizes.size(); i++) {
            int orientation = defaultDisplaySizes.keyAt(i);
            if (result.contains(orientation)) continue;
            Point displaySize = defaultDisplaySizes.valueAt(i);
            Rect newCrop = getCrop(displaySize, bitmapSize, adjustedSuggestedCrops, rtl);
            result.put(orientation, newCrop);
        }
        return result;
    }

    /**
     * Once a new wallpaper has been written via setWallpaper(...), it needs to be cropped
     * for display. This will generate the crop and write it in the file.
     */
    void generateCrop(WallpaperData wallpaper) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("WPMS.generateCrop");
        generateCropInternal(wallpaper);
        t.traceEnd();
    }

    private void generateCropInternal(WallpaperData wallpaper) {
        boolean success = false;

        // Only generate crop for default display.
        final WallpaperDisplayHelper.DisplayData wpData =
                mWallpaperDisplayHelper.getDisplayDataOrCreate(DEFAULT_DISPLAY);
        final DisplayInfo displayInfo = mWallpaperDisplayHelper.getDisplayInfo(DEFAULT_DISPLAY);

        // Analyse the source; needed in multiple cases
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(wallpaper.getWallpaperFile().getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Slog.w(TAG, "Invalid wallpaper data");
        } else {
            boolean needCrop = false;
            boolean needScale;

            Point bitmapSize = new Point(options.outWidth, options.outHeight);
            Rect bitmapRect = new Rect(0, 0, bitmapSize.x, bitmapSize.y);

            if (multiCrop()) {
                // Check that the suggested crops per screen orientation are all within the bitmap.
                for (int i = 0; i < wallpaper.mCropHints.size(); i++) {
                    int orientation = wallpaper.mCropHints.keyAt(i);
                    Rect crop = wallpaper.mCropHints.valueAt(i);
                    if (crop.isEmpty() || !bitmapRect.contains(crop)) {
                        Slog.w(TAG, "Invalid crop " + crop + " for orientation " + orientation
                                + " and bitmap size " + bitmapSize + "; clearing suggested crops.");
                        wallpaper.mCropHints.clear();
                        wallpaper.cropHint.set(bitmapRect);
                        break;
                    }
                }
            }
            final Rect cropHint;
            final SparseArray<Rect> defaultCrops;

            // A wallpaper with cropHints = Map.of(ORIENTATION_UNKNOWN, rect) is treated like
            // a wallpaper with cropHints = null and  cropHint = rect.
            Rect tempCropHint = wallpaper.mCropHints.get(ORIENTATION_UNKNOWN);
            if (multiCrop() && tempCropHint != null) {
                wallpaper.cropHint.set(tempCropHint);
                wallpaper.mCropHints.clear();
            }
            if (multiCrop() && wallpaper.mCropHints.size() > 0) {
                // Some suggested crops per screen orientation were provided,
                // use them to compute the default crops for this device
                defaultCrops = getDefaultCrops(wallpaper.mCropHints, bitmapSize);
                // Adapt the provided crops to match the actual crops for the default display
                SparseArray<Rect> updatedCropHints = new SparseArray<>();
                for (int i = 0; i < wallpaper.mCropHints.size(); i++) {
                    int orientation = wallpaper.mCropHints.keyAt(i);
                    Rect defaultCrop = defaultCrops.get(orientation);
                    if (defaultCrop != null) {
                        updatedCropHints.put(orientation, defaultCrop);
                    }
                }
                wallpaper.mCropHints = updatedCropHints;

                // Finally, compute the cropHint based on the default crops
                cropHint = getTotalCrop(defaultCrops);
                wallpaper.cropHint.set(cropHint);
                if (DEBUG) {
                    Slog.d(TAG, "Generated default crops for wallpaper: " + defaultCrops
                            + " based on suggested crops: " + wallpaper.mCropHints);
                }
            } else if (multiCrop()) {
                // No crops per screen orientation were provided, but an overall cropHint may be
                // defined in wallpaper.cropHint. Compute the default crops for the sub-image
                // defined by the cropHint, then recompute the cropHint based on the default crops.
                // If the cropHint is empty or invalid, ignore it and use the full image.
                if (wallpaper.cropHint.isEmpty()) wallpaper.cropHint.set(bitmapRect);
                if (!bitmapRect.contains(wallpaper.cropHint)) {
                    Slog.w(TAG, "Ignoring wallpaper.cropHint = " + wallpaper.cropHint
                            + "; not within the bitmap of size " + bitmapSize);
                    wallpaper.cropHint.set(bitmapRect);
                }
                Point cropSize = new Point(wallpaper.cropHint.width(), wallpaper.cropHint.height());
                defaultCrops = getDefaultCrops(new SparseArray<>(), cropSize);
                cropHint = getTotalCrop(defaultCrops);
                cropHint.offset(wallpaper.cropHint.left, wallpaper.cropHint.top);
                wallpaper.cropHint.set(cropHint);
                if (DEBUG) {
                    Slog.d(TAG, "Generated default crops for wallpaper: " + defaultCrops);
                }
            } else {
                cropHint = new Rect(wallpaper.cropHint);
                defaultCrops = null;
            }

            if (DEBUG) {
                Slog.v(TAG, "Generating crop for new wallpaper(s): 0x"
                        + Integer.toHexString(wallpaper.mWhich)
                        + " to " + wallpaper.getCropFile().getName()
                        + " crop=(" + cropHint.width() + 'x' + cropHint.height()
                        + ") dim=(" + wpData.mWidth + 'x' + wpData.mHeight + ')');
            }

            // Empty crop means use the full image
            if (!multiCrop() && cropHint.isEmpty()) {
                cropHint.left = cropHint.top = 0;
                cropHint.right = options.outWidth;
                cropHint.bottom = options.outHeight;
            } else if (!multiCrop()) {
                // force the crop rect to lie within the measured bounds
                int dx = cropHint.right > options.outWidth ? options.outWidth - cropHint.right : 0;
                int dy = cropHint.bottom > options.outHeight
                        ? options.outHeight - cropHint.bottom : 0;
                cropHint.offset(dx, dy);

                // If the crop hint was larger than the image we just overshot. Patch things up.
                if (cropHint.left < 0) {
                    cropHint.left = 0;
                }
                if (cropHint.top < 0) {
                    cropHint.top = 0;
                }
            }

            // Don't bother cropping if what we're left with is identity
            needCrop = (options.outHeight > cropHint.height()
                    || options.outWidth > cropHint.width());

            // scale if the crop height winds up not matching the recommended metrics
            needScale = cropHint.height() > wpData.mHeight
                    || cropHint.height() > GLHelper.getMaxTextureSize()
                    || cropHint.width() > GLHelper.getMaxTextureSize();

            float sampleSize = Float.MAX_VALUE;
            if (multiCrop()) {
                // If all crops for all orientations have more width and height in pixel
                // than the display for this orientation, downsample the image
                for (int i = 0; i < defaultCrops.size(); i++) {
                    int orientation = defaultCrops.keyAt(i);
                    Rect crop = defaultCrops.valueAt(i);
                    Point displayForThisOrientation = mWallpaperDisplayHelper
                            .getDefaultDisplaySizes().get(orientation);
                    if (displayForThisOrientation == null) continue;
                    float sampleSizeForThisOrientation = Math.max(1f, Math.min(
                            crop.width() / displayForThisOrientation.x,
                            crop.height() / displayForThisOrientation.y));
                    sampleSize = Math.min(sampleSize, sampleSizeForThisOrientation);
                }
                // If the total crop has more width or height than either the max texture size
                // or twice the largest display dimension, downsample the image
                int maxCropSize = Math.min(
                        2 * mWallpaperDisplayHelper.getDefaultDisplayLargestDimension(),
                        GLHelper.getMaxTextureSize());
                float minimumSampleSize = Math.max(1f, Math.max(
                        (float) cropHint.height() / maxCropSize,
                        (float) cropHint.width()) / maxCropSize);
                sampleSize = Math.max(sampleSize, minimumSampleSize);
                needScale = sampleSize > 1f;
            }

            //make sure screen aspect ratio is preserved if width is scaled under screen size
            if (needScale && !multiCrop()) {
                final float scaleByHeight = (float) wpData.mHeight / (float) cropHint.height();
                final int newWidth = (int) (cropHint.width() * scaleByHeight);
                if (newWidth < displayInfo.logicalWidth) {
                    final float screenAspectRatio =
                            (float) displayInfo.logicalHeight / (float) displayInfo.logicalWidth;
                    cropHint.bottom = (int) (cropHint.width() * screenAspectRatio);
                    needCrop = true;
                }
            }

            if (DEBUG_CROP) {
                Slog.v(TAG, "crop: w=" + cropHint.width() + " h=" + cropHint.height());
                if (multiCrop()) Slog.v(TAG, "defaultCrops: " + defaultCrops);
                if (!multiCrop()) Slog.v(TAG, "dims: w=" + wpData.mWidth + " h=" + wpData.mHeight);
                Slog.v(TAG, "meas: w=" + options.outWidth + " h=" + options.outHeight);
                Slog.v(TAG, "crop?=" + needCrop + " scale?=" + needScale);
            }

            if (!needCrop && !needScale) {
                // Simple case:  the nominal crop fits what we want, so we take
                // the whole thing and just copy the image file directly.

                // TODO: It is not accurate to estimate bitmap size without decoding it,
                //  may be we can try to remove this optimized way in the future,
                //  that means, we will always go into the 'else' block.

                success = FileUtils.copyFile(wallpaper.getWallpaperFile(), wallpaper.getCropFile());

                if (!success) {
                    wallpaper.getCropFile().delete();
                }

                if (DEBUG) {
                    long estimateSize = (long) options.outWidth * options.outHeight * 4;
                    Slog.v(TAG, "Null crop of new wallpaper, estimate size="
                            + estimateSize + ", success=" + success);
                }
            } else {
                // Fancy case: crop and scale.  First, we decode and scale down if appropriate.
                FileOutputStream f = null;
                BufferedOutputStream bos = null;
                try {
                    // This actually downsamples only by powers of two, but that's okay; we do
                    // a proper scaling a bit later.  This is to minimize transient RAM use.
                    // We calculate the largest power-of-two under the actual ratio rather than
                    // just let the decode take care of it because we also want to remap where the
                    // cropHint rectangle lies in the decoded [super]rect.
                    final int actualScale = cropHint.height() / wpData.mHeight;
                    int scale = 1;
                    while (2 * scale <= actualScale) {
                        scale *= 2;
                    }
                    options.inSampleSize = scale;
                    options.inJustDecodeBounds = false;

                    final Rect estimateCrop = new Rect(cropHint);
                    if (!multiCrop()) estimateCrop.scale(1f / options.inSampleSize);
                    else {
                        estimateCrop.left = (int) Math.floor(estimateCrop.left / sampleSize);
                        estimateCrop.top = (int) Math.floor(estimateCrop.top / sampleSize);
                        estimateCrop.right = (int) Math.ceil(estimateCrop.right / sampleSize);
                        estimateCrop.bottom = (int) Math.ceil(estimateCrop.bottom / sampleSize);
                    }
                    float hRatio = (float) wpData.mHeight / estimateCrop.height();
                    final int destHeight = (int) (estimateCrop.height() * hRatio);
                    final int destWidth = (int) (estimateCrop.width() * hRatio);

                    // We estimated an invalid crop, try to adjust the cropHint to get a valid one.
                    if (!multiCrop() && destWidth > GLHelper.getMaxTextureSize()) {
                        if (DEBUG) {
                            Slog.w(TAG, "Invalid crop dimensions, trying to adjust.");
                        }

                        int newHeight = (int) (wpData.mHeight / hRatio);
                        int newWidth = (int) (wpData.mWidth / hRatio);

                        estimateCrop.set(cropHint);
                        estimateCrop.left += (cropHint.width() - newWidth) / 2;
                        estimateCrop.top += (cropHint.height() - newHeight) / 2;
                        estimateCrop.right = estimateCrop.left + newWidth;
                        estimateCrop.bottom = estimateCrop.top + newHeight;
                        cropHint.set(estimateCrop);
                        estimateCrop.scale(1f / options.inSampleSize);
                    }

                    // We've got the safe cropHint; now we want to scale it properly to
                    // the desired rectangle.
                    // That's a height-biased operation: make it fit the hinted height.
                    final int safeHeight = !multiCrop()
                            ? (int) (estimateCrop.height() * hRatio + 0.5f)
                            : (int) (cropHint.height() / sampleSize + 0.5f);
                    final int safeWidth = !multiCrop()
                            ? (int) (estimateCrop.width() * hRatio + 0.5f)
                            : (int) (cropHint.width() / sampleSize + 0.5f);

                    if (DEBUG_CROP) {
                        Slog.v(TAG, "Decode parameters:");
                        if (!multiCrop()) {
                            Slog.v(TAG,
                                    "  cropHint=" + cropHint + ", estimateCrop=" + estimateCrop);
                            Slog.v(TAG, "  down sampling=" + options.inSampleSize
                                    + ", hRatio=" + hRatio);
                            Slog.v(TAG, "  dest=" + destWidth + "x" + destHeight);
                        }
                        if (multiCrop()) {
                            Slog.v(TAG, "  cropHint=" + cropHint);
                            Slog.v(TAG, "  estimateCrop=" + estimateCrop);
                            Slog.v(TAG, "  sampleSize=" + sampleSize);
                            Slog.v(TAG, "  user defined crops: " + wallpaper.mCropHints);
                            Slog.v(TAG, "  all crops: " + defaultCrops);
                        }
                        Slog.v(TAG, "  targetSize=" + safeWidth + "x" + safeHeight);
                        Slog.v(TAG, "  maxTextureSize=" + GLHelper.getMaxTextureSize());
                    }

                    //Create a record file and will delete if ImageDecoder work well.
                    final String recordName =
                            (wallpaper.getWallpaperFile().getName().equals(WALLPAPER)
                                    ? RECORD_FILE : RECORD_LOCK_FILE);
                    final File record = new File(getWallpaperDir(wallpaper.userId), recordName);
                    record.createNewFile();
                    Slog.v(TAG, "record path =" + record.getPath()
                            + ", record name =" + record.getName());

                    final ImageDecoder.Source srcData =
                            ImageDecoder.createSource(wallpaper.getWallpaperFile());
                    final int finalScale = scale;
                    final int rescaledBitmapWidth = (int) (0.5f + bitmapSize.x / sampleSize);
                    final int rescaledBitmapHeight = (int) (0.5f + bitmapSize.y / sampleSize);
                    Bitmap cropped = ImageDecoder.decodeBitmap(srcData, (decoder, info, src) -> {
                        if (!multiCrop()) decoder.setTargetSampleSize(finalScale);
                        if (multiCrop()) {
                            decoder.setTargetSize(rescaledBitmapWidth, rescaledBitmapHeight);
                        }
                        decoder.setCrop(estimateCrop);
                    });

                    record.delete();

                    if (!multiCrop() && cropped == null) {
                        Slog.e(TAG, "Could not decode new wallpaper");
                    } else {
                        // We are safe to create final crop with safe dimensions now.
                        final Bitmap finalCrop = multiCrop() ? cropped
                                : Bitmap.createScaledBitmap(cropped, safeWidth, safeHeight, true);

                        if (multiCrop()) {
                            wallpaper.mSampleSize = sampleSize;
                        }

                        if (DEBUG) {
                            Slog.v(TAG, "Final extract:");
                            Slog.v(TAG, "  dims: w=" + wpData.mWidth
                                    + " h=" + wpData.mHeight);
                            Slog.v(TAG, "  out: w=" + finalCrop.getWidth()
                                    + " h=" + finalCrop.getHeight());
                        }

                        f = new FileOutputStream(wallpaper.getCropFile());
                        bos = new BufferedOutputStream(f, 32 * 1024);
                        finalCrop.compress(Bitmap.CompressFormat.PNG, 100, bos);
                        // don't rely on the implicit flush-at-close when noting success
                        bos.flush();
                        success = true;
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error decoding crop", e);
                } finally {
                    IoUtils.closeQuietly(bos);
                    IoUtils.closeQuietly(f);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Unable to apply new wallpaper");
            wallpaper.getCropFile().delete();
            wallpaper.mCropHints.clear();
            wallpaper.cropHint.set(0, 0, 0, 0);
            wallpaper.mSampleSize = 1f;
        }

        if (wallpaper.getCropFile().exists()) {
            boolean didRestorecon = SELinux.restorecon(wallpaper.getCropFile().getAbsoluteFile());
            if (DEBUG) {
                Slog.v(TAG, "restorecon() of crop file returned " + didRestorecon);
            }
        }
    }
}
