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

package android.graphics.pdf;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import dalvik.system.CloseGuard;
import libcore.io.Libcore;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * <p>
 * This class enables rendering a PDF document. This class is not thread safe.
 * </p>
 * <p>
 * If you want to render a PDF, you create a renderer and for every page you want
 * to render, you open the page, render it, and close the page. After you are done
 * with rendering, you close the renderer. After the renderer is closed it should not
 * be used anymore. Note that the pages are rendered one by one, i.e. you can have
 * only a single page opened at any given time.
 * </p>
 * <p>
 * A typical use of the APIs to render a PDF looks like this:
 * </p>
 * <pre>
 * // create a new renderer
 * PdfRenderer renderer = new PdfRenderer(getSeekableFileDescriptor());
 *
 * // let us just render all pages
 * final int pageCount = renderer.getPageCount();
 * for (int i = 0; i < pageCount; i++) {
 *     Page page = renderer.openPage(i);
 *
 *     // say we render for showing on the screen
 *     page.render(mBitmap, null, null, Page.RENDER_MODE_FOR_DISPLAY);
 *
 *     // do stuff with the bitmap
 *
 *     // close the page
 *     page.close();
 * }
 *
 * // close the renderer
 * renderer.close();
 * </pre>
 *
 * <h3>Print preview and print output</h3>
 * <p>
 * If you are using this class to rasterize a PDF for printing or show a print
 * preview, it is recommended that you respect the following contract in order
 * to provide a consistent user experience when seeing a preview and printing,
 * i.e. the user sees a preview that is the same as the printout.
 * </p>
 * <ul>
 * <li>
 * Respect the property whether the document would like to be scaled for printing
 * as per {@link #shouldScaleForPrinting()}.
 * </li>
 * <li>
 * When scaling a document for printing the aspect ratio should be preserved.
 * </li>
 * <li>
 * Do not inset the content with any margins from the {@link android.print.PrintAttributes}
 * as the application is responsible to render it such that the margins are respected.
 * </li>
 * <li>
 * If document page size is greater than the printed media size the content should
 * be anchored to the upper left corner of the page for left-to-right locales and
 * top right corner for right-to-left locales.
 * </li>
 * </ul>
 *
 * @see #close()
 */
public final class PdfRenderer implements AutoCloseable {
    /**
     * Any call the native pdfium code has to be single threaded as the library does not support
     * parallel use.
     */
    final static Object sPdfiumLock = new Object();

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final Point mTempPoint = new Point();

    private final long mNativeDocument;

    private final int mPageCount;

    private ParcelFileDescriptor mInput;

    private Page mCurrentPage;

    /** @hide */
    @IntDef({
        Page.RENDER_MODE_FOR_DISPLAY,
        Page.RENDER_MODE_FOR_PRINT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RenderMode {}

    /**
     * Creates a new instance.
     * <p>
     * <strong>Note:</strong> The provided file descriptor must be <strong>seekable</strong>,
     * i.e. its data being randomly accessed, e.g. pointing to a file.
     * </p>
     * <p>
     * <strong>Note:</strong> This class takes ownership of the passed in file descriptor
     * and is responsible for closing it when the renderer is closed.
     * </p>
     * <p>
     * If the file is from an untrusted source it is recommended to run the renderer in a separate,
     * isolated process with minimal permissions to limit the impact of security exploits.
     * </p>
     *
     * @param input Seekable file descriptor to read from.
     *
     * @throws java.io.IOException If an error occurs while reading the file.
     * @throws java.lang.SecurityException If the file requires a password or
     *         the security scheme is not supported.
     */
    public PdfRenderer(@NonNull ParcelFileDescriptor input) throws IOException {
        if (input == null) {
            throw new NullPointerException("input cannot be null");
        }

        final long size;
        try {
            Libcore.os.lseek(input.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            size = Libcore.os.fstat(input.getFileDescriptor()).st_size;
        } catch (ErrnoException ee) {
            throw new IllegalArgumentException("file descriptor not seekable");
        }

        mInput = input;

        synchronized (sPdfiumLock) {
            mNativeDocument = nativeCreate(mInput.getFd(), size);
            mPageCount = nativeGetPageCount(mNativeDocument);
        }

        mCloseGuard.open("close");
    }

    /**
     * Closes this renderer. You should not use this instance
     * after this method is called.
     */
    public void close() {
        throwIfClosed();
        throwIfPageOpened();
        doClose();
    }

    /**
     * Gets the number of pages in the document.
     *
     * @return The page count.
     */
    public int getPageCount() {
        throwIfClosed();
        return mPageCount;
    }

    /**
     * Gets whether the document prefers to be scaled for printing.
     * You should take this info account if the document is rendered
     * for printing and the target media size differs from the page
     * size.
     *
     * @return If to scale the document.
     */
    public boolean shouldScaleForPrinting() {
        throwIfClosed();

        synchronized (sPdfiumLock) {
            return nativeScaleForPrinting(mNativeDocument);
        }
    }

    /**
     * Opens a page for rendering.
     *
     * @param index The page index.
     * @return A page that can be rendered.
     *
     * @see android.graphics.pdf.PdfRenderer.Page#close() PdfRenderer.Page.close()
     */
    public Page openPage(int index) {
        throwIfClosed();
        throwIfPageOpened();
        throwIfPageNotInDocument(index);
        mCurrentPage = new Page(index);
        return mCurrentPage;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            if (mInput != null) {
                doClose();
            }
        } finally {
            super.finalize();
        }
    }

    private void doClose() {
        if (mCurrentPage != null) {
            mCurrentPage.close();
        }
        synchronized (sPdfiumLock) {
            nativeClose(mNativeDocument);
        }
        try {
            mInput.close();
        } catch (IOException ioe) {
            /* ignore - best effort */
        }
        mInput = null;
        mCloseGuard.close();
    }

    private void throwIfClosed() {
        if (mInput == null) {
            throw new IllegalStateException("Already closed");
        }
    }

    private void throwIfPageOpened() {
        if (mCurrentPage != null) {
            throw new IllegalStateException("Current page not closed");
        }
    }

    private void throwIfPageNotInDocument(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= mPageCount) {
            throw new IllegalArgumentException("Invalid page index");
        }
    }

    /**
     * This class represents a PDF document page for rendering.
     */
    public final class Page implements AutoCloseable {

        private final CloseGuard mCloseGuard = CloseGuard.get();

        /**
         * Mode to render the content for display on a screen.
         */
        public static final int RENDER_MODE_FOR_DISPLAY = 1;

        /**
         * Mode to render the content for printing.
         */
        public static final int RENDER_MODE_FOR_PRINT = 2;

        private final int mIndex;
        private final int mWidth;
        private final int mHeight;

        private long mNativePage;

        private Page(int index) {
            Point size = mTempPoint;
            synchronized (sPdfiumLock) {
                mNativePage = nativeOpenPageAndGetSize(mNativeDocument, index, size);
            }
            mIndex = index;
            mWidth = size.x;
            mHeight = size.y;
            mCloseGuard.open("close");
        }

        /**
         * Gets the page index.
         *
         * @return The index.
         */
        public int getIndex() {
            return  mIndex;
        }

        /**
         * Gets the page width in points (1/72").
         *
         * @return The width in points.
         */
        public int getWidth() {
            return mWidth;
        }

        /**
         * Gets the page height in points (1/72").
         *
         * @return The height in points.
         */
        public int getHeight() {
            return mHeight;
        }

        /**
         * Renders a page to a bitmap.
         * <p>
         * You may optionally specify a rectangular clip in the bitmap bounds. No rendering
         * outside the clip will be performed, hence it is your responsibility to initialize
         * the bitmap outside the clip.
         * </p>
         * <p>
         * You may optionally specify a matrix to transform the content from page coordinates
         * which are in points (1/72") to bitmap coordinates which are in pixels. If this
         * matrix is not provided this method will apply a transformation that will fit the
         * whole page to the destination clip if provided or the destination bitmap if no
         * clip is provided.
         * </p>
         * <p>
         * The clip and transformation are useful for implementing tile rendering where the
         * destination bitmap contains a portion of the image, for example when zooming.
         * Another useful application is for printing where the size of the bitmap holding
         * the page is too large and a client can render the page in stripes.
         * </p>
         * <p>
         * <strong>Note: </strong> The destination bitmap format must be
         * {@link Config#ARGB_8888 ARGB}.
         * </p>
         * <p>
         * <strong>Note: </strong> The optional transformation matrix must be affine as per
         * {@link android.graphics.Matrix#isAffine() Matrix.isAffine()}. Hence, you can specify
         * rotation, scaling, translation but not a perspective transformation.
         * </p>
         *
         * @param destination Destination bitmap to which to render.
         * @param destClip Optional clip in the bitmap bounds.
         * @param transform Optional transformation to apply when rendering.
         * @param renderMode The render mode.
         *
         * @see #RENDER_MODE_FOR_DISPLAY
         * @see #RENDER_MODE_FOR_PRINT
         */
        public void render(@NonNull Bitmap destination, @Nullable Rect destClip,
                           @Nullable Matrix transform, @RenderMode int renderMode) {
            if (destination.getConfig() != Config.ARGB_8888) {
                throw new IllegalArgumentException("Unsupported pixel format");
            }

            if (destClip != null) {
                if (destClip.left < 0 || destClip.top < 0
                        || destClip.right > destination.getWidth()
                        || destClip.bottom > destination.getHeight()) {
                    throw new IllegalArgumentException("destBounds not in destination");
                }
            }

            if (transform != null && !transform.isAffine()) {
                 throw new IllegalArgumentException("transform not affine");
            }

            if (renderMode != RENDER_MODE_FOR_PRINT && renderMode != RENDER_MODE_FOR_DISPLAY) {
                throw new IllegalArgumentException("Unsupported render mode");
            }

            if (renderMode == RENDER_MODE_FOR_PRINT && renderMode == RENDER_MODE_FOR_DISPLAY) {
                throw new IllegalArgumentException("Only single render mode supported");
            }

            final int contentLeft = (destClip != null) ? destClip.left : 0;
            final int contentTop = (destClip != null) ? destClip.top : 0;
            final int contentRight = (destClip != null) ? destClip.right
                    : destination.getWidth();
            final int contentBottom = (destClip != null) ? destClip.bottom
                    : destination.getHeight();

            final long transformPtr = (transform != null) ? transform.native_instance : 0;

            synchronized (sPdfiumLock) {
                nativeRenderPage(mNativeDocument, mNativePage, destination, contentLeft,
                        contentTop, contentRight, contentBottom, transformPtr, renderMode);
            }
        }

        /**
         * Closes this page.
         *
         * @see android.graphics.pdf.PdfRenderer#openPage(int)
         */
        @Override
        public void close() {
            throwIfClosed();
            doClose();
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                mCloseGuard.warnIfOpen();
                if (mNativePage != 0) {
                    doClose();
                }
            } finally {
                super.finalize();
            }
        }

        private void doClose() {
            synchronized (sPdfiumLock) {
                nativeClosePage(mNativePage);
            }
            mNativePage = 0;
            mCloseGuard.close();
            mCurrentPage = null;
        }

        private void throwIfClosed() {
            if (mNativePage == 0) {
                throw new IllegalStateException("Already closed");
            }
        }
    }

    private static native long nativeCreate(int fd, long size);
    private static native void nativeClose(long documentPtr);
    private static native int nativeGetPageCount(long documentPtr);
    private static native boolean nativeScaleForPrinting(long documentPtr);
    private static native void nativeRenderPage(long documentPtr, long pagePtr, Bitmap dest,
            int destLeft, int destTop, int destRight, int destBottom, long matrixPtr, int renderMode);
    private static native long nativeOpenPageAndGetSize(long documentPtr, int pageIndex,
            Point outSize);
    private static native void nativeClosePage(long pagePtr);
}
