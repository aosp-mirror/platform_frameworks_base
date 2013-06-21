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

package android.print.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;

import dalvik.system.CloseGuard;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * This class enables generating a PDF document from native Android content. You
 * open a new document and then for every page you want to add you start a page,
 * write content to the page, and finish the page. After you are done with all
 * pages, you write the document to an output stream and close the document.
 * After a document is closed you should not use it anymore.
 * </p>
 * <p>
 * A typical use of the APIs looks like this:
 * </p>
 * <pre>
 * // open a new document
 * PdfDocument document = PdfDocument.open();
 *
 * // crate a page description
 * PageInfo pageInfo = new PageInfo.Builder(new Rect(0, 0, 100, 100), 1, 300).create();
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
 * add more pages
 * . . .
 * // write the document content
 * document.writeTo(getOutputStream());
 *
 * //close the document
 * document.close();
 * </pre>
 */
public final class PdfDocument {

    private final byte[] mChunk = new byte[4096];

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final List<PageInfo> mPages = new ArrayList<PageInfo>();

    private int mNativeDocument;

    private Page mCurrentPage;

    /**
     * Opens a new document.
     * <p>
     * <strong>Note:</strong> You must close the document after you are
     * done by calling {@link #close()}
     * </p>
     *
     * @return The document.
     *
     * @see #close()
     */
    public static PdfDocument open() {
        return new PdfDocument();
    }

    /**
     * Creates a new instance.
     */
    private PdfDocument() {
        mNativeDocument = nativeCreateDocument();
        mCloseGuard.open("close");
    }

    /**
     * Starts a page using the provided {@link PageInfo}. After the page
     * is created you can draw arbitrary content on the page's canvas which
     * you can get by calling {@link Page#getCanvas()}. After you are done
     * drawing the content you should finish the page by calling
     * {@link #finishPage(Page). After the page is finished you should
     * no longer access the page or its canvas.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param pageInfo The page info.
     * @return A blank page.
     *
     * @see #finishPage(Page)
     */
    public Page startPage(PageInfo pageInfo) {
        throwIfClosed();
        if (pageInfo == null) {
            throw new IllegalArgumentException("page cannot be null!");
        }
        if (mCurrentPage != null) {
            throw new IllegalStateException("Previous page not finished!");
        }
        Canvas canvas = new PdfCanvas(nativeCreatePage(pageInfo.mPageSize,
                pageInfo.mContentSize, pageInfo.mInitialTransform.native_instance),
                pageInfo.mDensity);
        mCurrentPage = new Page(canvas, pageInfo);
        return mCurrentPage;
    }

    /**
     * Finishes a started page. You should always finish the last started page.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param page The page.
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
        mPages.add(page.getInfo());
        mCurrentPage = null;
        nativeAppendPage(mNativeDocument, page.mCanvas.mNativeCanvas);
        page.finish();
    }

    /**
     * Writes the document to an output stream.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param out The output stream.
     */
    public void writeTo(OutputStream out) {
        throwIfClosed();
        if (out == null) {
            throw new IllegalArgumentException("out cannot be null!");
        }
        nativeWriteTo(mNativeDocument, out, mChunk);
    }

    /**
     * Gets the pages of the document.
     *
     * @return The pages.
     */
    public List<PageInfo> getPages() {
        return Collections.unmodifiableList(mPages);
    }

    /**
     * Closes this document. This method should be called after you
     * are done working with the document. After this call the document
     * is considered closed and none of its methods should be called.
     */
    public void close() {
        dispose();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            dispose();
        } finally {
            super.finalize();
        }
    }

    private void dispose() {
        if (mNativeDocument != 0) {
            nativeFinalize(mNativeDocument);
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

    private native int nativeCreateDocument();

    private native void nativeFinalize(int document);

    private native void nativeAppendPage(int document, int page);

    private native void nativeWriteTo(int document, OutputStream out, byte[] chunk);

    private static native int nativeCreatePage(Rect pageSize,
            Rect contentSize, int nativeMatrix);


    private final class PdfCanvas extends Canvas {

        public PdfCanvas(int nativeCanvas, int density) {
            super(nativeCanvas);
            super.setDensity(density);
        }

        @Override
        public void setBitmap(Bitmap bitmap) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setDensity(int density) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setScreenDensity(int density) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * This class represents meta-data that describes a PDF {@link Page}.
     */
    public static final class PageInfo {
        private Rect mPageSize;
        private Rect mContentSize;
        private Matrix mInitialTransform;
        private int mPageNumber;
        private int mDensity;

        /**
         * Creates a new instance.
         */
        private PageInfo() {
            /* do nothing */
        }

        /**
         * Gets the page size in pixels.
         *
         * @return The page size.
         */
        public Rect getPageSize() {
            return mPageSize;
        }

        /**
         * Get the content size in pixels.
         *
         * @return The content size.
         */
        public Rect getContentSize() {
            return mContentSize;
        }

        /**
         * Gets the initial transform which is applied to the page. This may be
         * useful to move the origin to account for a margin, apply scale, or
         * apply a rotation.
         *
         * @return The initial transform.
         */
        public Matrix getInitialTransform() {
            return mInitialTransform;
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
         * Gets the density of the page in DPI.
         *
         * @return The density.
         */
        public int getDesity() {
            return mDensity;
        }

        /**
         * Builder for creating a {@link PageInfo}.
         */
        public static final class Builder {
            private final PageInfo mPageInfo = new PageInfo();

            /**
             * Creates a new builder with the mandatory page info attributes.
             *
             * @param pageSize The page size in pixels.
             * @param pageNumber The page number.
             * @param density The page density in DPI.
             */
            public Builder(Rect pageSize, int pageNumber, int density) {
                if (pageSize.width() == 0 || pageSize.height() == 0) {
                    throw new IllegalArgumentException("page width and height" +
                            " must be greater than zero!");
                }
                if (pageNumber < 0) {
                    throw new IllegalArgumentException("pageNumber cannot be less than zero!");
                }
                if (density <= 0) {
                    throw new IllegalArgumentException("density must be greater than zero!");
                }
                mPageInfo.mPageSize = pageSize;
                mPageInfo.mPageNumber = pageNumber;
                mPageInfo.mDensity = density;
            }

            /**
             * Sets the content size in pixels.
             *
             * @param contentSize The content size.
             */
            public Builder setContentSize(Rect contentSize) {
                Rect pageSize = mPageInfo.mPageSize;
                if (contentSize != null && (pageSize.left > contentSize.left
                        || pageSize.top > contentSize.top
                        || pageSize.right < contentSize.right
                        || pageSize.bottom < contentSize.bottom)) {
                    throw new IllegalArgumentException("contentSize does not fit the pageSize!");
                }
                mPageInfo.mContentSize = contentSize;
                return this;
            }

            /**
             * Sets the initial transform which is applied to the page. This may be
             * useful to move the origin to account for a margin, apply scale, or
             * apply a rotation.
             *
             * @param transform The initial transform.
             */
            public Builder setInitialTransform(Matrix transform) {
                mPageInfo.mInitialTransform = transform;
                return this;
            }

            /**
             * Creates a new {@link PageInfo}.
             *
             * @return The new instance.
             */
            public PageInfo create() {
                if (mPageInfo.mContentSize == null) {
                    mPageInfo.mContentSize = mPageInfo.mPageSize;
                }
                if (mPageInfo.mInitialTransform == null) {
                    mPageInfo.mInitialTransform = new Matrix();
                }
                return mPageInfo;
            }
        }
    }

    /**
     * This class represents a PDF document page. It has associated
     * a canvas on which you can draw content and is acquired by a
     * call to {@link #getCanvas()}. It also has associated a
     * {@link PageInfo} instance that describes its attributes.
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

        private void finish() {
            if (mCanvas != null) {
                mCanvas.release();
                mCanvas = null;
            }
        }
    }
}
