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
import android.graphics.Point;
import android.graphics.Rect;
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
        Bitmap thumbnail = Bitmap.createBitmap(size.x, size.y,
                Bitmap.Config.ARGB_8888);
        frontPage.render(thumbnail, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

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

}
