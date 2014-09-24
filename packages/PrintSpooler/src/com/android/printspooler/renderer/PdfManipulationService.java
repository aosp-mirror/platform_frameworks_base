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

    public static final int MALFORMED_PDF_FILE_ERROR = -2;

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
                } catch (IOException|IllegalStateException e) {
                    IoUtils.closeQuietly(source);
                    Log.e(LOG_TAG, "Cannot open file", e);
                    return MALFORMED_PDF_FILE_ERROR;
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
                } catch (IOException|IllegalStateException e) {
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
