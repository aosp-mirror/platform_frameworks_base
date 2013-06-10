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

import java.io.OutputStream;

/**
 * This class enables generating a PDF document from native Android
 * content. A client uses a factory method in this class to create a
 * blank page, a {@link Canvas}, on which native Android content can
 * be drawn. Then the client draws arbitrary content on the page via
 * the {@link Canvas} APIs. Next the client adds the page to the
 * document. After all pages have been added the client calls the
 * {@link #write(OutputStream)} method to emit a PDF document.
 * </p>
 * <p>
 * A typical use of the APIs looks like this:
 * </p>
 * <pre>
 * // Create a document.
 * PdfDocument document = new PdfDocument();
 *
 * // Create a blank page.
 * PdfPage page = document.createPage(pageBounds, contentBounds,
 *         initialTransform);
 *
 * // Draw the content view on the page.
 * content.draw(page);
 *
 * // Add the page to the document.
 * document.appendPage(page);
 *
 * // Write the document to a file.
 * document.write(createOutputStream());
 * </pre>
 */
public class PdfDocument {

    private final byte[] mChunk = new byte[1024];

    private final int mNativeDocument;

    /**
     * Creates a new instance.
     */
    public PdfDocument() {
        mNativeDocument = native_createDocument();
    }

    /**
     * Creates a PDF page with the specified <code>pageSize</code> and
     * <code>contentSize</code> Anything outside of the drawing area will be
     * clipped.
     *
     * @param pageSize The page size in points.
     * @param contentSize The content size in points.
     * @return A blank page.
     */
    public PdfPage createPage(Rect pageSize, Rect contentSize) {
        return new PdfPage(pageSize, contentSize, new Matrix());
    }

    /**
     * Creates a PDF page with the specified <code>pageSize</code>,
     * <code>contentSize</code>, and <code>initialTransform</code>. The
     * initial transform is combined with the content size to determine
     * the drawing area. Anything outside of the drawing area will be
     * clipped. The initial transform may be useful to move the origin
     * to account for applying a margin, scale, or rotation.
     *
     * @param pageSize The page size in points.
     * @param contentSize The content size in points.
     * @param initialTransform The initial transform to apply to the page.
     * @return A blank page.
     */
    public PdfPage createPage(Rect pageSize, Rect contentSize, Matrix initialTransform) {
        return new PdfPage(pageSize, contentSize, initialTransform);
    }

    /**
     * Append the page to the document.
     *
     * @param page The page.
     * @return Whether the addition succeeded.
     */
    public boolean appendPage(PdfPage page) {
        return native_appendPage(mNativeDocument, page.mNativeCanvas);
    }

    /**
     * Writes the document to an output stream.
     *
     * @param out The output stream.
     */
    public void write(OutputStream out) {
        native_write(mNativeDocument, out, mChunk);
    }

    @Override
    public void finalize() throws Throwable {
        try {
            if (mNativeDocument != 0) {
                native_finalize(mNativeDocument);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * This class represents a page in a PDF document. It is a
     * {@link Canvas} on which any arbitrary content can be drawn.
     *
     * @see PdfDocument
     */
    public class PdfPage extends Canvas {

        private PdfPage(Rect pageSize, Rect contentSize, Matrix initialTransform) {
            super(native_createPage(pageSize, contentSize, initialTransform.native_instance));
        }

        @Override
        public void setBitmap(Bitmap bitmap) {
            throw new UnsupportedOperationException(
                    "Cannot set bitmap device on a pdf canvas!");
        }
    }

    private native int native_createDocument();

    private native void native_finalize(int document);

    private native boolean native_appendPage(int document, int page);

    private native void native_write(int document, OutputStream out, byte[] chunk);

    private static native int native_createPage(Rect pageSize,
            Rect contentSize, int nativeMatrix);
}
