/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * This class enables generating a PDF document from native Android content. You
 * create a new document and then for every page you want to add you start a page,
 * write content to the page, and finish the page. After you are done with all
 * pages, you write the document to an output stream and close the document.
 * After a document is closed you should not use it anymore. Note that pages are
 * created one by one, i.e. you can have only a single page to which you are
 * writing at any given time. This class is not thread safe.
 * </p>
 * <p>
 * A typical use of the APIs looks like this:
 * </p>
 * <pre>
 * // create a new document
 * PdfDocument document = new PdfDocument();
 *
 * // create a page description
 * PageInfo pageInfo = new PageInfo.Builder(100, 100, 1).create();
 *
 * // start a page
 * Page page = document.startPage(pageInfo);
 *
 * // draw something on the page
 * View content = getContentView();
 * content.draw(page.getCanvas());
 *
 * // finish the page
 * document.finishPage(page);
 * . . .
 * // add more pages
 * . . .
 * // write the document content
 * document.writeTo(getOutputStream());
 *
 * // close the document
 * document.close();
 * </pre>
 */
public class PdfDocument {

    // TODO: We need a constructor that will take an OutputStream to
    // support online data serialization as opposed to the current
    // on demand one. The current approach is fine until Skia starts
    // to support online PDF generation at which point we need to
    // handle this.

    private final byte[] mChunk = new byte[4096];

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final List<PageInfo> mPages = new ArrayList<PageInfo>();

    private long mNativeDocument;

    private Page mCurrentPage;

    /**
     * Creates a new instance.
     */
    public PdfDocument() {
        mNativeDocument = nativeCreateDocument();
        mCloseGuard.open("close");
    }

    /**
     * Starts a page using the provided {@link PageInfo}. After the page
     * is created you can draw arbitrary content on the page's canvas which
     * you can get by calling {@link Page#getCanvas()}. After you are done
     * drawing the content you should finish the page by calling
     * {@link #finishPage(Page)}. After the page is finished you should
     * no longer access the page or its canvas.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * Also do not call this method if the last page returned by this method
     * is not finished by calling {@link #finishPage(Page)}.
     * </p>
     *
     * @param pageInfo The page info. Cannot be null.
     * @return A blank page.
     *
     * @see #finishPage(Page)
     */
    public Page startPage(PageInfo pageInfo) {
        throwIfClosed();
        throwIfCurrentPageNotFinished();
        if (pageInfo == null) {
            throw new IllegalArgumentException("page cannot be null");
        }
        Canvas canvas = new PdfCanvas(nativeStartPage(mNativeDocument, pageInfo.mPageWidth,
                pageInfo.mPageHeight, pageInfo.mContentRect.left, pageInfo.mContentRect.top,
                pageInfo.mContentRect.right, pageInfo.mContentRect.bottom));
        mCurrentPage = new Page(canvas, pageInfo);
        return mCurrentPage;
    }

    /**
     * Finishes a started page. You should always finish the last started page.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * You should not finish the same page more than once.
     * </p>
     *
     * @param page The page. Cannot be null.
     *
     * @see #startPage(PageInfo)
     */
    public void finishPage(Page page) {
        throwIfClosed();
        if (page == null) {
            throw new IllegalArgumentException("page cannot be null");
        }
        if (page != mCurrentPage) {
            throw new IllegalStateException("invalid page");
        }
        if (page.isFinished()) {
            throw new IllegalStateException("page already finished");
        }
        mPages.add(page.getInfo());
        mCurrentPage = null;
        nativeFinishPage(mNativeDocument);
        page.finish();
    }

    /**
     * Writes the document to an output stream. You can call this method
     * multiple times.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * Also do not call this method if a page returned by {@link #startPage(
     * PageInfo)} is not finished by calling {@link #finishPage(Page)}.
     * </p>
     *
     * @param out The output stream. Cannot be null.
     *
     * @throws IOException If an error occurs while writing.
     */
    public void writeTo(OutputStream out) throws IOException {
        throwIfClosed();
        throwIfCurrentPageNotFinished();
        if (out == null) {
            throw new IllegalArgumentException("out cannot be null!");
        }
        nativeWriteTo(mNativeDocument, out, mChunk);
    }

    /**
     * Gets the pages of the document.
     *
     * @return The pages or an empty list.
     */
    public List<PageInfo> getPages() {
        return Collections.unmodifiableList(mPages);
    }

    /**
     * Closes this document. This method should be called after you
     * are done working with the document. After this call the document
     * is considered closed and none of its methods should be called.
     * <p>
     * <strong>Note:</strong> Do not call this method if the page
     * returned by {@link #startPage(PageInfo)} is not finished by
     * calling {@link #finishPage(Page)}.
     * </p>
     */
    public void close() {
        throwIfCurrentPageNotFinished();
        dispose();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            dispose();
        } finally {
            super.finalize();
        }
    }

    private void dispose() {
        if (mNativeDocument != 0) {
            nativeClose(mNativeDocument);
            mCloseGuard.close();
            mNativeDocument = 0;
        }
    }

    /**
     * Throws an exception if the document is already closed.
     */
    private void throwIfClosed() {
        if (mNativeDocument == 0) {
            throw new IllegalStateException("document is closed!");
        }
    }

    /**
     * Throws an exception if the last started page is not finished.
     */
    private void throwIfCurrentPageNotFinished() {
        if (mCurrentPage != null) {
            throw new IllegalStateException("Current page not finished!");
        }
    }

    private native long nativeCreateDocument();

    private native void nativeClose(long nativeDocument);

    private native void nativeFinishPage(long nativeDocument);

    private native void nativeWriteTo(long nativeDocument, OutputStream out, byte[] chunk);

    private static native long nativeStartPage(long nativeDocument, int pageWidth, int pageHeight,
            int contentLeft, int contentTop, int contentRight, int contentBottom);

    private final class PdfCanvas extends Canvas {

        public PdfCanvas(long nativeCanvas) {
            super(nativeCanvas);
        }

        @Override
        public void setBitmap(Bitmap bitmap) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * This class represents meta-data that describes a PDF {@link Page}.
     */
    public static final class PageInfo {
        private int mPageWidth;
        private int mPageHeight;
        private Rect mContentRect;
        private int mPageNumber;

        /**
         * Creates a new instance.
         */
        private PageInfo() {
            /* do nothing */
        }

        /**
         * Gets the page width in PostScript points (1/72th of an inch).
         *
         * @return The page width.
         */
        public int getPageWidth() {
            return mPageWidth;
        }

        /**
         * Gets the page height in PostScript points (1/72th of an inch).
         *
         * @return The page height.
         */
        public int getPageHeight() {
            return mPageHeight;
        }

        /**
         * Get the content rectangle in PostScript points (1/72th of an inch).
         * This is the area that contains the page content and is relative to
         * the page top left.
         *
         * @return The content rectangle.
         */
        public Rect getContentRect() {
            return mContentRect;
        }

        /**
         * Gets the page number.
         *
         * @return The page number.
         */
        public int getPageNumber() {
            return mPageNumber;
        }

        /**
         * Builder for creating a {@link PageInfo}.
         */
        public static final class Builder {
            private final PageInfo mPageInfo = new PageInfo();

            /**
             * Creates a new builder with the mandatory page info attributes.
             *
             * @param pageWidth The page width in PostScript (1/72th of an inch).
             * @param pageHeight The page height in PostScript (1/72th of an inch).
             * @param pageNumber The page number.
             */
            public Builder(int pageWidth, int pageHeight, int pageNumber) {
                if (pageWidth <= 0) {
                    throw new IllegalArgumentException("page width must be positive");
                }
                if (pageHeight <= 0) {
                    throw new IllegalArgumentException("page width must be positive");
                }
                if (pageNumber < 0) {
                    throw new IllegalArgumentException("pageNumber must be non negative");
                }
                mPageInfo.mPageWidth = pageWidth;
                mPageInfo.mPageHeight = pageHeight;
                mPageInfo.mPageNumber = pageNumber;
            }

            /**
             * Sets the content rectangle in PostScript point (1/72th of an inch).
             * This is the area that contains the page content and is relative to
             * the page top left.
             *
             * @param contentRect The content rectangle. Must fit in the page.
             */
            public Builder setContentRect(Rect contentRect) {
                if (contentRect != null && (contentRect.left < 0
                        || contentRect.top < 0
                        || contentRect.right > mPageInfo.mPageWidth
                        || contentRect.bottom > mPageInfo.mPageHeight)) {
                    throw new IllegalArgumentException("contentRect does not fit the page");
                }
                mPageInfo.mContentRect = contentRect;
                return this;
            }

            /**
             * Creates a new {@link PageInfo}.
             *
             * @return The new instance.
             */
            public PageInfo create() {
                if (mPageInfo.mContentRect == null) {
                    mPageInfo.mContentRect = new Rect(0, 0,
                            mPageInfo.mPageWidth, mPageInfo.mPageHeight);
                }
                return mPageInfo;
            }
        }
    }

    /**
     * This class represents a PDF document page. It has associated
     * a canvas on which you can draw content and is acquired by a
     * call to {@link #getCanvas()}. It also has associated a
     * {@link PageInfo} instance that describes its attributes. Also
     * a page has 
     */
    public static final class Page {
        private final PageInfo mPageInfo;
        private Canvas mCanvas;

        /**
         * Creates a new instance.
         *
         * @param canvas The canvas of the page.
         * @param pageInfo The info with meta-data.
         */
        private Page(Canvas canvas, PageInfo pageInfo) {
            mCanvas = canvas;
            mPageInfo = pageInfo;
        }

        /**
         * Gets the {@link Canvas} of the page.
         *
         * <p>
         * <strong>Note: </strong> There are some draw operations that are not yet
         * supported by the canvas returned by this method. More specifically:
         * <ul>
         * <li>Inverse path clipping performed via {@link Canvas#clipPath(android.graphics.Path,
         *     android.graphics.Region.Op) Canvas.clipPath(android.graphics.Path,
         *     android.graphics.Region.Op)} for {@link
         *     android.graphics.Region.Op#REVERSE_DIFFERENCE
         *     Region.Op#REVERSE_DIFFERENCE} operations.</li>
         * <li>{@link Canvas#drawVertices(android.graphics.Canvas.VertexMode, int,
         *     float[], int, float[], int, int[], int, short[], int, int,
         *     android.graphics.Paint) Canvas.drawVertices(
         *     android.graphics.Canvas.VertexMode, int, float[], int, float[],
         *     int, int[], int, short[], int, int, android.graphics.Paint)}</li>
         * <li>Color filters set via {@link Paint#setColorFilter(
         *     android.graphics.ColorFilter)}</li>
         * <li>Mask filters set via {@link Paint#setMaskFilter(
         *     android.graphics.MaskFilter)}</li>
         * <li>Some XFER modes such as
         *     {@link android.graphics.PorterDuff.Mode#SRC_ATOP PorterDuff.Mode SRC},
         *     {@link android.graphics.PorterDuff.Mode#DST_ATOP PorterDuff.DST_ATOP},
         *     {@link android.graphics.PorterDuff.Mode#XOR PorterDuff.XOR},
         *     {@link android.graphics.PorterDuff.Mode#ADD PorterDuff.ADD}</li>
         * </ul>
         *
         * @return The canvas if the page is not finished, null otherwise.
         *
         * @see PdfDocument#finishPage(Page)
         */
        public Canvas getCanvas() {
            return mCanvas;
        }

        /**
         * Gets the {@link PageInfo} with meta-data for the page.
         *
         * @return The page info.
         *
         * @see PdfDocument#finishPage(Page)
         */
        public PageInfo getInfo() {
            return mPageInfo;
        }

        boolean isFinished() {
            return mCanvas == null;
        }

        private void finish() {
            if (mCanvas != null) {
                mCanvas.release();
                mCanvas = null;
            }
        }
    }
}
