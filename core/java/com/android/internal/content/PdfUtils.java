/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.content;

import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.pdf.PdfRenderer;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Utils class for extracting PDF Thumbnails
 */
public final class PdfUtils {

    private PdfUtils() {
    }

    /**
     * Returns the front page of the pdf as a thumbnail
     * @param file Given PDF File
     * @param size Cropping of the front page.
     * @return AssetFileDescriptor containing the thumbnail as a bitmap.
     * @throws IOException if the file isn't a pdf or if the file doesn't exist.
     */
    public static @Nullable AssetFileDescriptor openPdfThumbnail(File file, Point size)
            throws IOException {
        // Create the bitmap of the PDF's first page
        ParcelFileDescriptor pdfDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(pdfDescriptor);
        PdfRenderer.Page frontPage = renderer.openPage(0);
        Bitmap thumbnail = Bitmap.createBitmap(frontPage.getWidth(), frontPage.getHeight(),
                Bitmap.Config.ARGB_8888);

        frontPage.render(thumbnail, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        thumbnail = crop(thumbnail, size.x, size.y, .5f, 0f);

        // Create an AssetFileDescriptor that contains the Bitmap's information
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Quality is an integer that determines how much compression is used.
        // However, this integer is ignored when using the PNG format
        int quality = 100;
        // The use of Bitmap.CompressFormat.JPEG leads to a black PDF background on the thumbnail
        thumbnail.compress(Bitmap.CompressFormat.PNG, quality, out);

        final ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createReliablePipe();
        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(Object... params) {
                final FileOutputStream fos = new FileOutputStream(fds[1].getFileDescriptor());
                try {
                    Streams.copy(in, fos);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                IoUtils.closeQuietly(fds[1]);
                try {
                    pdfDescriptor.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        pdfDescriptor.close();
        return new AssetFileDescriptor(fds[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    /**
     * Returns a new Bitmap copy with a crop effect depending on the crop anchor given. 0.5f is like
     * {@link android.widget.ImageView.ScaleType#CENTER_CROP}. The crop anchor will be be nudged
     * so the entire cropped bitmap will fit inside the src. May return the input bitmap if no
     * scaling is necessary.
     *
     *
     * Example of changing verticalCenterPercent:
     *   _________            _________
     *  |         |          |         |
     *  |         |          |_________|
     *  |         |          |         |/___0.3f
     *  |---------|          |_________|\
     *  |         |<---0.5f  |         |
     *  |---------|          |         |
     *  |         |          |         |
     *  |         |          |         |
     *  |_________|          |_________|
     *
     * @param src original bitmap of any size
     * @param w desired width in px
     * @param h desired height in px
     * @param horizontalCenterPercent determines which part of the src to crop from. Range from 0
     *                                .0f to 1.0f. The value determines which part of the src
     *                                maps to the horizontal center of the resulting bitmap.
     * @param verticalCenterPercent determines which part of the src to crop from. Range from 0
     *                              .0f to 1.0f. The value determines which part of the src maps
     *                              to the vertical center of the resulting bitmap.
     * @return a copy of src conforming to the given width and height, or src itself if it already
     *         matches the given width and height
     *
     */
    private static Bitmap crop(final Bitmap src, final int w, final int h,
            final float horizontalCenterPercent, final float verticalCenterPercent) {
        if (horizontalCenterPercent < 0 || horizontalCenterPercent > 1 || verticalCenterPercent < 0
                || verticalCenterPercent > 1) {
            throw new IllegalArgumentException(
                    "horizontalCenterPercent and verticalCenterPercent must be between 0.0f and "
                            + "1.0f, inclusive.");
        }
        final int srcWidth = src.getWidth();
        final int srcHeight = src.getHeight();
        // exit early if no resize/crop needed
        if (w == srcWidth && h == srcHeight) {
            return src;
        }
        final Matrix m = new Matrix();
        final float scale = Math.max(
                (float) w / srcWidth,
                (float) h / srcHeight);
        m.setScale(scale, scale);
        final int srcCroppedW, srcCroppedH;
        int srcX, srcY;
        srcCroppedW = Math.round(w / scale);
        srcCroppedH = Math.round(h / scale);
        srcX = (int) (srcWidth * horizontalCenterPercent - srcCroppedW / 2);
        srcY = (int) (srcHeight * verticalCenterPercent - srcCroppedH / 2);
        // Nudge srcX and srcY to be within the bounds of src
        srcX = Math.max(Math.min(srcX, srcWidth - srcCroppedW), 0);
        srcY = Math.max(Math.min(srcY, srcHeight - srcCroppedH), 0);
        final Bitmap cropped = Bitmap.createBitmap(src, srcX, srcY,
                srcCroppedW, srcCroppedH, m, true);
        return cropped;
    }

}
