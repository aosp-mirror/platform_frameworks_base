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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wallpaper.WallpaperUtils.RECORD_FILE;
import static com.android.server.wallpaper.WallpaperUtils.RECORD_LOCK_FILE;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;
import static com.android.server.wallpaper.WallpaperUtils.getWallpaperDir;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.os.FileUtils;
import android.os.SELinux;
import android.util.Slog;
import android.view.DisplayInfo;

import com.android.server.utils.TimingsTraceAndSlog;

import libcore.io.IoUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Helper file for wallpaper cropping
 * Meant to have a single instance, only used by the WallpaperManagerService
 */
class WallpaperCropper {

    private static final String TAG = WallpaperCropper.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_CROP = true;

    private final WallpaperDisplayHelper mWallpaperDisplayHelper;

    WallpaperCropper(WallpaperDisplayHelper wallpaperDisplayHelper) {
        mWallpaperDisplayHelper = wallpaperDisplayHelper;
    }

    /**
     * Once a new wallpaper has been written via setWallpaper(...), it needs to be cropped
     * for display.
     *
     * This will generate the crop and write it in the file
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
        final Rect cropHint = new Rect(wallpaper.cropHint);
        final DisplayInfo displayInfo = mWallpaperDisplayHelper.getDisplayInfo(DEFAULT_DISPLAY);

        if (DEBUG) {
            Slog.v(TAG, "Generating crop for new wallpaper(s): 0x"
                    + Integer.toHexString(wallpaper.mWhich)
                    + " to " + wallpaper.getCropFile().getName()
                    + " crop=(" + cropHint.width() + 'x' + cropHint.height()
                    + ") dim=(" + wpData.mWidth + 'x' + wpData.mHeight + ')');
        }

        // Analyse the source; needed in multiple cases
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(wallpaper.getWallpaperFile().getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Slog.w(TAG, "Invalid wallpaper data");
            success = false;
        } else {
            boolean needCrop = false;
            boolean needScale;

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
            if (needScale) {
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
                    // a proper scaling blit later.  This is to minimize transient RAM use.
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
                    final float hRatio = (float) wpData.mHeight / estimateCrop.height();
                    final int destHeight = (int) (estimateCrop.height() * hRatio);
                    final int destWidth = (int) (estimateCrop.width() * hRatio);

                    // We estimated an invalid crop, try to adjust the cropHint to get a valid one.
                    if (destWidth > GLHelper.getMaxTextureSize()) {
                        int newHeight = (int) (wpData.mHeight / hRatio);
                        int newWidth = (int) (wpData.mWidth / hRatio);

                        if (DEBUG) {
                            Slog.v(TAG, "Invalid crop dimensions, trying to adjust.");
                        }

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
                    final int safeHeight = (int) (estimateCrop.height() * hRatio);
                    final int safeWidth = (int) (estimateCrop.width() * hRatio);

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
