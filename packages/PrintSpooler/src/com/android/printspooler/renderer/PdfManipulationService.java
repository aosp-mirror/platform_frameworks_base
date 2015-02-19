/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.renderer;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfEditor;
import android.graphics.pdf.PdfRenderer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.util.Log;
import android.view.View;
import com.android.printspooler.util.PageRangeUtils;
import libcore.io.IoUtils;
import com.android.printspooler.util.BitmapSerializeUtils;
import java.io.IOException;

/**
 * Service for manipulation of PDF documents in an isolated process.
 */
public final class PdfManipulationService extends Service {
    public static final String ACTION_GET_RENDERER =
            "com.android.printspooler.renderer.ACTION_GET_RENDERER";
    public static final String ACTION_GET_EDITOR =
            "com.android.printspooler.renderer.ACTION_GET_EDITOR";

    public static final int ERROR_MALFORMED_PDF_FILE = -2;

    public static final int ERROR_SECURE_PDF_FILE = -3;

    private static final String LOG_TAG = "PdfManipulationService";
    private static final boolean DEBUG = false;

    private static final int MILS_PER_INCH = 1000;
    private static final int POINTS_IN_INCH = 72;

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        switch (action) {
            case ACTION_GET_RENDERER: {
                return new PdfRendererImpl();
            }
            case ACTION_GET_EDITOR: {
                return new PdfEditorImpl();
            }
            default: {
                throw new IllegalArgumentException("Invalid intent action:" + action);
            }
        }
    }

    private final class PdfRendererImpl extends IPdfRenderer.Stub {
        private final Object mLock = new Object();

        private Bitmap mBitmap;
        private PdfRenderer mRenderer;

        @Override
        public int openDocument(ParcelFileDescriptor source) throws RemoteException {
            synchronized (mLock) {
                try {
                    throwIfOpened();
                    if (DEBUG) {
                        Log.i(LOG_TAG, "openDocument()");
                    }
                    mRenderer = new PdfRenderer(source);
                    return mRenderer.getPageCount();
                } catch (IOException | IllegalStateException e) {
                    IoUtils.closeQuietly(source);
                    Log.e(LOG_TAG, "Cannot open file", e);
                    return ERROR_MALFORMED_PDF_FILE;
                } catch (SecurityException e) {
                    IoUtils.closeQuietly(source);
                    Log.e(LOG_TAG, "Cannot open file", e);
                    return ERROR_SECURE_PDF_FILE;
                }
            }
        }

        @Override
        public void renderPage(int pageIndex, int bitmapWidth, int bitmapHeight,
                PrintAttributes attributes, ParcelFileDescriptor destination) {
            synchronized (mLock) {
                try {
                    throwIfNotOpened();

                    PdfRenderer.Page page = mRenderer.openPage(pageIndex);

                    final int srcWidthPts = page.getWidth();
                    final int srcHeightPts = page.getHeight();

                    final int dstWidthPts = pointsFromMils(
                            attributes.getMediaSize().getWidthMils());
                    final int dstHeightPts = pointsFromMils(
                            attributes.getMediaSize().getHeightMils());

                    final boolean scaleContent = mRenderer.shouldScaleForPrinting();
                    final boolean contentLandscape = !attributes.getMediaSize().isPortrait();

                    final float displayScale;
                    Matrix matrix = new Matrix();

                    if (scaleContent) {
                        displayScale = Math.min((float) bitmapWidth / srcWidthPts,
                                (float) bitmapHeight / srcHeightPts);
                    } else {
                        if (contentLandscape) {
                            displayScale = (float) bitmapHeight / dstHeightPts;
                        } else {
                            displayScale = (float) bitmapWidth / dstWidthPts;
                        }
                    }
                    matrix.postScale(displayScale, displayScale);

                    Configuration configuration = PdfManipulationService.this.getResources()
                            .getConfiguration();
                    if (configuration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        matrix.postTranslate(bitmapWidth - srcWidthPts * displayScale, 0);
                    }

                    Margins minMargins = attributes.getMinMargins();
                    final int paddingLeftPts = pointsFromMils(minMargins.getLeftMils());
                    final int paddingTopPts = pointsFromMils(minMargins.getTopMils());
                    final int paddingRightPts = pointsFromMils(minMargins.getRightMils());
                    final int paddingBottomPts = pointsFromMils(minMargins.getBottomMils());

                    Rect clip = new Rect();
                    clip.left = (int) (paddingLeftPts * displayScale);
                    clip.top = (int) (paddingTopPts * displayScale);
                    clip.right = (int) (bitmapWidth - paddingRightPts * displayScale);
                    clip.bottom = (int) (bitmapHeight - paddingBottomPts * displayScale);

                    if (DEBUG) {
                        Log.i(LOG_TAG, "Rendering page:" + pageIndex);
                    }

                    Bitmap bitmap = getBitmapForSize(bitmapWidth, bitmapHeight);
                    page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    page.close();

                    BitmapSerializeUtils.writeBitmapPixels(bitmap, destination);
                } finally {
                    IoUtils.closeQuietly(destination);
                }
            }
        }

        @Override
        public void closeDocument() {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "closeDocument()");
                }
                mRenderer.close();
                mRenderer = null;
            }
        }

        private Bitmap getBitmapForSize(int width, int height) {
            if (mBitmap != null) {
                if (mBitmap.getWidth() == width && mBitmap.getHeight() == height) {
                    mBitmap.eraseColor(Color.WHITE);
                    return mBitmap;
                }
                mBitmap.recycle();
            }
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mBitmap.eraseColor(Color.WHITE);
            return mBitmap;
        }

        private void throwIfOpened() {
            if (mRenderer != null) {
                throw new IllegalStateException("Already opened");
            }
        }

        private void throwIfNotOpened() {
            if (mRenderer == null) {
                throw new IllegalStateException("Not opened");
            }
        }
    }

    private final class PdfEditorImpl extends IPdfEditor.Stub {
        private final Object mLock = new Object();

        private PdfEditor mEditor;

        @Override
        public int openDocument(ParcelFileDescriptor source) throws RemoteException {
            synchronized (mLock) {
                try {
                    throwIfOpened();
                    if (DEBUG) {
                        Log.i(LOG_TAG, "openDocument()");
                    }
                    mEditor = new PdfEditor(source);
                    return mEditor.getPageCount();
                } catch (IOException | IllegalStateException e) {
                    IoUtils.closeQuietly(source);
                    Log.e(LOG_TAG, "Cannot open file", e);
                    throw new RemoteException(e.toString());
                }
            }
        }

        @Override
        public void removePages(PageRange[] ranges) {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "removePages()");
                }

                ranges = PageRangeUtils.normalize(ranges);

                final int rangeCount = ranges.length;
                for (int i = rangeCount - 1; i >= 0; i--) {
                    PageRange range = ranges[i];
                    for (int j = range.getEnd(); j >= range.getStart(); j--) {
                        mEditor.removePage(j);
                    }
                }
            }
        }

        @Override
        public void applyPrintAttributes(PrintAttributes attributes) {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "applyPrintAttributes()");
                }

                Rect mediaBox = new Rect();
                Rect cropBox = new Rect();
                Matrix transform = new Matrix();

                final boolean contentPortrait = attributes.getMediaSize().isPortrait();

                final boolean layoutDirectionRtl = getResources().getConfiguration()
                        .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

                // We do not want to rotate the media box, so take into account orientation.
                final int dstWidthPts = contentPortrait
                        ? pointsFromMils(attributes.getMediaSize().getWidthMils())
                        : pointsFromMils(attributes.getMediaSize().getHeightMils());
                final int dstHeightPts = contentPortrait
                        ? pointsFromMils(attributes.getMediaSize().getHeightMils())
                        : pointsFromMils(attributes.getMediaSize().getWidthMils());

                final boolean scaleForPrinting = mEditor.shouldScaleForPrinting();

                final int pageCount = mEditor.getPageCount();
                for (int i = 0; i < pageCount; i++) {
                    if (!mEditor.getPageMediaBox(i, mediaBox)) {
                        Log.e(LOG_TAG, "Malformed PDF file");
                        return;
                    }

                    final int srcWidthPts = mediaBox.width();
                    final int srcHeightPts = mediaBox.height();

                    // Update the media box with the desired size.
                    mediaBox.right = dstWidthPts;
                    mediaBox.bottom = dstHeightPts;
                    mEditor.setPageMediaBox(i, mediaBox);

                    // Make sure content is top-left after media box resize.
                    transform.setTranslate(0, srcHeightPts - dstHeightPts);

                    // Rotate the content if in landscape.
                    if (!contentPortrait) {
                        transform.postRotate(270);
                        transform.postTranslate(0, dstHeightPts);
                    }

                    // Scale the content if document allows it.
                    final float scale;
                    if (scaleForPrinting) {
                        if (contentPortrait) {
                            scale = Math.min((float) dstWidthPts / srcWidthPts,
                                    (float) dstHeightPts / srcHeightPts);
                            transform.postScale(scale, scale);
                        } else {
                            scale = Math.min((float) dstWidthPts / srcHeightPts,
                                    (float) dstHeightPts / srcWidthPts);
                            transform.postScale(scale, scale, mediaBox.left, mediaBox.bottom);
                        }
                    } else {
                        scale = 1.0f;
                    }

                    // Update the crop box relatively to the media box change, if needed.
                    if (mEditor.getPageCropBox(i, cropBox)) {
                        cropBox.left = (int) (cropBox.left * scale + 0.5f);
                        cropBox.top = (int) (cropBox.top * scale + 0.5f);
                        cropBox.right = (int) (cropBox.right * scale + 0.5f);
                        cropBox.bottom = (int) (cropBox.bottom * scale + 0.5f);
                        cropBox.intersect(mediaBox);
                        mEditor.setPageCropBox(i, cropBox);
                    }

                    // If in RTL mode put the content in the logical top-right corner.
                    if (layoutDirectionRtl) {
                        final float dx = contentPortrait
                                ? dstWidthPts - (int) (srcWidthPts * scale + 0.5f) : 0;
                        final float dy = contentPortrait
                                ? 0 : - (dstHeightPts - (int) (srcWidthPts * scale + 0.5f));
                        transform.postTranslate(dx, dy);
                    }

                    // Adjust the physical margins if needed.
                    Margins minMargins = attributes.getMinMargins();
                    final int paddingLeftPts = pointsFromMils(minMargins.getLeftMils());
                    final int paddingTopPts = pointsFromMils(minMargins.getTopMils());
                    final int paddingRightPts = pointsFromMils(minMargins.getRightMils());
                    final int paddingBottomPts = pointsFromMils(minMargins.getBottomMils());

                    Rect clip = new Rect(mediaBox);
                    clip.left += paddingLeftPts;
                    clip.top += paddingTopPts;
                    clip.right -= paddingRightPts;
                    clip.bottom -= paddingBottomPts;

                    // Apply the accumulated transforms.
                    mEditor.setTransformAndClip(i, transform, clip);
                }
            }
        }

        @Override
        public void write(ParcelFileDescriptor destination) throws RemoteException {
            synchronized (mLock) {
                try {
                    throwIfNotOpened();
                    if (DEBUG) {
                        Log.i(LOG_TAG, "write()");
                    }
                    mEditor.write(destination);
                } catch (IOException | IllegalStateException e) {
                    IoUtils.closeQuietly(destination);
                    Log.e(LOG_TAG, "Error writing PDF to file.", e);
                    throw new RemoteException(e.toString());
                }
            }
        }

        @Override
        public void closeDocument() {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "closeDocument()");
                }
                mEditor.close();
                mEditor = null;
            }
        }

        private void throwIfOpened() {
            if (mEditor != null) {
                throw new IllegalStateException("Already opened");
            }
        }

        private void throwIfNotOpened() {
            if (mEditor == null) {
                throw new IllegalStateException("Not opened");
            }
        }
    }

    private static int pointsFromMils(int mils) {
        return (int) (((float) mils / MILS_PER_INCH) * POINTS_IN_INCH);
    }
}
