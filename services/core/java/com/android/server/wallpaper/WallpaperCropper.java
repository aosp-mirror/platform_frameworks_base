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
    private static final float MAX_PARALLAX = 1f;

    /**
     * We define three ways to adjust a crop. These modes are used depending on the situation:
     *   - When going from unfolded to folded, we want to remove content
     *   - When going from folded to unfolded, we want to add content
     *   - For a screen rotation, we want to keep the same amount of content
     */
    private static final int ADD = 1;
    private static final int REMOVE = 2;
    private static final int BALANCE = 3;


    private final WallpaperDisplayHelper mWallpaperDisplayHelper;

    /**
     * Helpers exposed to the window manager part (WallpaperController)
     */
    public interface WallpaperCropUtils {

        /**
         * Equivalent to {@link #getCrop(Point, Point, SparseArray, boolean)}
         */
        Rect getCrop(Point displaySize, Point bitmapSize,
                SparseArray<Rect> suggestedCrops, boolean rtl);
    }

    WallpaperCropper(WallpaperDisplayHelper wallpaperDisplayHelper) {
        mWallpaperDisplayHelper = wallpaperDisplayHelper;
    }

    /**
     * Given the dimensions of the original wallpaper image, some optional suggested crops
     * (either defined by the user, or coming from a backup), and whether the device is RTL,
     * generate a crop for the current display. This is done through the following process:
     * <ul>
     *     <li> If no suggested crops are provided, center the full image on the display. </li>
     *     <li> If there is a suggested crop the given displaySize, reuse the suggested crop and
     *     adjust it using {@link #getAdjustedCrop}. </li>
     *     <li> If there are suggested crops, but not for the orientation of the given displaySize,
     *     reuse one of the suggested crop for another orientation and adjust if using
     *     {@link #getAdjustedCrop}. </li>
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

        // Case 1: if no crops are provided, center align the full image
        if (suggestedCrops == null || suggestedCrops.size() == 0) {
            Rect crop = new Rect(0, 0, displaySize.x, displaySize.y);
            float scale = Math.min(
                    ((float) bitmapSize.x) / displaySize.x,
                    ((float) bitmapSize.y) / displaySize.y);
            crop.scale(scale);
            crop.offset((bitmapSize.x - crop.width()) / 2,
                    (bitmapSize.y - crop.height()) / 2);
            return crop;
        }
        int orientation = getOrientation(displaySize);

        // Case 2: if the orientation exists in the suggested crops, adjust the suggested crop
        Rect suggestedCrop = suggestedCrops.get(orientation);
        if (suggestedCrop != null) {
            if (suggestedCrop.left < 0 || suggestedCrop.top < 0
                    || suggestedCrop.right > bitmapSize.x || suggestedCrop.bottom > bitmapSize.y) {
                Slog.w(TAG, "invalid suggested crop: " + suggestedCrop);
                Rect fullImage = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
                return getAdjustedCrop(fullImage, bitmapSize, displaySize, true, rtl, ADD);
            } else {
                return getAdjustedCrop(suggestedCrop, bitmapSize, displaySize, true, rtl, ADD);
            }
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
            // only keep the visible part (without parallax)
            Rect adjustedCrop = noParallax(suggestedCrop, suggestedDisplaySize, bitmapSize, rtl);
            return getAdjustedCrop(adjustedCrop, bitmapSize, displaySize, false, rtl, REMOVE);
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
    private static Rect noParallax(Rect crop, Point displaySize, Point bitmapSize, boolean rtl) {
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
     *     </li>
     *     <li>If parallax = false, make sure we do not have additional width for parallax. If we
     *     have additional width for parallax, remove half of the additional width on both sides.
     *     </li>
     *     <li>Make sure the crop fills the screen, i.e. that the width/height ratio of the crop
     *     is at least the width/height ratio of the screen. If it is less, add width to the crop
     *     (if possible on both sides) to fill the screen. If not enough width available, remove
     *     height to the crop.
     *     </li>
     * </ul>
     */
    private static Rect getAdjustedCrop(Rect crop, Point bitmapSize, Point screenSize,
            boolean parallax, boolean rtl, int mode) {
        Rect adjustedCrop = new Rect(crop);
        float cropRatio = ((float) crop.width()) / crop.height();
        float screenRatio = ((float) screenSize.x) / screenSize.y;
        if (cropRatio >= screenRatio) {
            if (!parallax) {
                // rotate everything 90 degrees clockwise, compute the result, and rotate back
                int newLeft = bitmapSize.y - crop.bottom;
                int newRight = newLeft + crop.height();
                int newTop = crop.left;
                int newBottom = newTop + crop.width();
                Rect rotatedCrop = new Rect(newLeft, newTop, newRight, newBottom);
                Point rotatedBitmap = new Point(bitmapSize.y, bitmapSize.x);
                Point rotatedScreen = new Point(screenSize.y, screenSize.x);
                Rect rect = getAdjustedCrop(rotatedCrop, rotatedBitmap, rotatedScreen, false, rtl,
                        mode);
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
            int widthToAdd = mode == REMOVE ? 0
                    : mode == ADD ? (int) (0.5 + crop.height() * screenRatio - crop.width())
                    : (int) (0.5 + crop.height() - crop.width());
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
            boolean multiCrop = multiCrop() && wallpaper.mSupportsMultiCrop;

            Point bitmapSize = new Point(options.outWidth, options.outHeight);

            final Rect cropHint;
            if (multiCrop) {
                SparseArray<Rect> defaultDisplayCrops =
                        getDefaultCrops(wallpaper.mCropHints, bitmapSize);
                // adapt the entries in wallpaper.mCropHints for the actual display
                SparseArray<Rect> updatedCropHints = new SparseArray<>();
                for (int i = 0; i < wallpaper.mCropHints.size(); i++) {
                    int orientation = wallpaper.mCropHints.keyAt(i);
                    Rect defaultCrop = defaultDisplayCrops.get(orientation);
                    if (defaultCrop != null) {
                        updatedCropHints.put(orientation, defaultCrop);
                    }
                }
                wallpaper.mCropHints = updatedCropHints;
                cropHint = getTotalCrop(defaultDisplayCrops);
                wallpaper.cropHint.set(cropHint);
            } else {
                cropHint = new Rect(wallpaper.cropHint);
            }

            if (DEBUG) {
                Slog.v(TAG, "Generating crop for new wallpaper(s): 0x"
                        + Integer.toHexString(wallpaper.mWhich)
                        + " to " + wallpaper.getCropFile().getName()
                        + " crop=(" + cropHint.width() + 'x' + cropHint.height()
                        + ") dim=(" + wpData.mWidth + 'x' + wpData.mHeight + ')');
            }

            // Empty crop means use the full image
            if (cropHint.isEmpty()) {
                cropHint.left = cropHint.top = 0;
                cropHint.right = options.outWidth;
                cropHint.bottom = options.outHeight;
            } else {
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

                // Don't bother cropping if what we're left with is identity
                needCrop = (options.outHeight > cropHint.height()
                        || options.outWidth > cropHint.width());
            }

            // scale if the crop height winds up not matching the recommended metrics
            needScale = cropHint.height() > wpData.mHeight
                    || cropHint.height() > GLHelper.getMaxTextureSize()
                    || cropHint.width() > GLHelper.getMaxTextureSize();

            //make sure screen aspect ratio is preserved if width is scaled under screen size
            if (needScale && !multiCrop) {
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
                Slog.v(TAG, "dims: w=" + wpData.mWidth + " h=" + wpData.mHeight);
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
                    estimateCrop.scale(1f / options.inSampleSize);
                    float hRatio = (float) wpData.mHeight / estimateCrop.height();
                    if (multiCrop) {
                        // make sure the crop height is at most the display largest dimension
                        hRatio = (float) mWallpaperDisplayHelper.getDefaultDisplayLargestDimension()
                                / estimateCrop.height();
                        hRatio = Math.min(hRatio, 1f);
                    }
                    final int destHeight = (int) (estimateCrop.height() * hRatio);
                    final int destWidth = (int) (estimateCrop.width() * hRatio);

                    // We estimated an invalid crop, try to adjust the cropHint to get a valid one.
                    if (destWidth > GLHelper.getMaxTextureSize()) {
                        if (DEBUG) {
                            Slog.w(TAG, "Invalid crop dimensions, trying to adjust.");
                        }
                        if (multiCrop) {
                            // clear custom crop guidelines, fallback to system default
                            wallpaper.mCropHints.clear();
                            generateCropInternal(wallpaper);
                            return;
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
                    final int safeHeight = (int) (estimateCrop.height() * hRatio + 0.5f);
                    final int safeWidth = (int) (estimateCrop.width() * hRatio + 0.5f);

                    if (DEBUG_CROP) {
                        Slog.v(TAG, "Decode parameters:");
                        Slog.v(TAG, "  cropHint=" + cropHint + ", estimateCrop=" + estimateCrop);
                        Slog.v(TAG, "  down sampling=" + options.inSampleSize
                                + ", hRatio=" + hRatio);
                        Slog.v(TAG, "  dest=" + destWidth + "x" + destHeight);
                        Slog.v(TAG, "  safe=" + safeWidth + "x" + safeHeight);
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
                    final int sampleSize = scale;
                    Bitmap cropped = ImageDecoder.decodeBitmap(srcData, (decoder, info, src) -> {
                        decoder.setTargetSampleSize(sampleSize);
                        decoder.setCrop(estimateCrop);
                    });

                    record.delete();

                    if (cropped == null) {
                        Slog.e(TAG, "Could not decode new wallpaper");
                    } else {
                        // We are safe to create final crop with safe dimensions now.
                        final Bitmap finalCrop = Bitmap.createScaledBitmap(cropped,
                                safeWidth, safeHeight, true);

                        if (multiCrop) {
                            wallpaper.mSampleSize =
                                    ((float) cropHint.height()) / finalCrop.getHeight();
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
                    if (DEBUG) {
                        Slog.e(TAG, "Error decoding crop", e);
                    }
                } finally {
                    IoUtils.closeQuietly(bos);
                    IoUtils.closeQuietly(f);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Unable to apply new wallpaper");
            wallpaper.getCropFile().delete();
        }

        if (wallpaper.getCropFile().exists()) {
            boolean didRestorecon = SELinux.restorecon(wallpaper.getCropFile().getAbsoluteFile());
            if (DEBUG) {
                Slog.v(TAG, "restorecon() of crop file returned " + didRestorecon);
            }
        }
    }
}
