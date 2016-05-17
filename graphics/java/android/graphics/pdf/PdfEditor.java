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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import dalvik.system.CloseGuard;
import libcore.io.IoUtils;
import libcore.io.Libcore;

import java.io.IOException;

/**
 * Class for editing PDF files.
 *
 * @hide
 */
public final class PdfEditor {

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final long mNativeDocument;

    private int mPageCount;

    private ParcelFileDescriptor mInput;

    /**
     * Creates a new instance.
     * <p>
     * <strong>Note:</strong> The provided file descriptor must be <strong>seekable</strong>,
     * i.e. its data being randomly accessed, e.g. pointing to a file. After finishing
     * with this class you must call {@link #close()}.
     * </p>
     * <p>
     * <strong>Note:</strong> This class takes ownership of the passed in file descriptor
     * and is responsible for closing it when the editor is closed.
     * </p>
     *
     * @param input Seekable file descriptor to read from.
     *
     * @throws java.io.IOException If an error occurs while reading the file.
     * @throws java.lang.SecurityException If the file requires a password or
     *         the security scheme is not supported.
     *
     * @see #close()
     */
    public PdfEditor(@NonNull ParcelFileDescriptor input) throws IOException {
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

        synchronized (PdfRenderer.sPdfiumLock) {
            mNativeDocument = nativeOpen(mInput.getFd(), size);
            mPageCount = nativeGetPageCount(mNativeDocument);
        }

        mCloseGuard.open("close");
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
     * Removes the page with a given index.
     *
     * @param pageIndex The page to remove.
     */
    public void removePage(int pageIndex) {
        throwIfClosed();
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            mPageCount = nativeRemovePage(mNativeDocument, pageIndex);
        }
    }

    /**
     * Sets a transformation and clip for a given page. The transformation matrix if
     * non-null must be affine as per {@link android.graphics.Matrix#isAffine()}. If
     * the clip is null, then no clipping is performed.
     *
     * @param pageIndex The page whose transform to set.
     * @param transform The transformation to apply.
     * @param clip The clip to apply.
     */
    public void setTransformAndClip(int pageIndex, @Nullable Matrix transform,
            @Nullable Rect clip) {
        throwIfClosed();
        throwIfPageNotInDocument(pageIndex);
        throwIfNotNullAndNotAfine(transform);
        if (transform == null) {
            transform = Matrix.IDENTITY_MATRIX;
        }
        if (clip == null) {
            Point size = new Point();
            getPageSize(pageIndex, size);

            synchronized (PdfRenderer.sPdfiumLock) {
                nativeSetTransformAndClip(mNativeDocument, pageIndex, transform.native_instance,
                        0, 0, size.x, size.y);
            }
        } else {
            synchronized (PdfRenderer.sPdfiumLock) {
                nativeSetTransformAndClip(mNativeDocument, pageIndex, transform.native_instance,
                        clip.left, clip.top, clip.right, clip.bottom);
            }
        }
    }

    /**
     * Gets the size of a given page in mils (1/72").
     *
     * @param pageIndex The page index.
     * @param outSize The size output.
     */
    public void getPageSize(int pageIndex, @NonNull Point outSize) {
        throwIfClosed();
        throwIfOutSizeNull(outSize);
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            nativeGetPageSize(mNativeDocument, pageIndex, outSize);
        }
    }

    /**
     * Gets the media box of a given page in mils (1/72").
     *
     * @param pageIndex The page index.
     * @param outMediaBox The media box output.
     */
    public boolean getPageMediaBox(int pageIndex, @NonNull Rect outMediaBox) {
        throwIfClosed();
        throwIfOutMediaBoxNull(outMediaBox);
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            return nativeGetPageMediaBox(mNativeDocument, pageIndex, outMediaBox);
        }
    }

    /**
     * Sets the media box of a given page in mils (1/72").
     *
     * @param pageIndex The page index.
     * @param mediaBox The media box.
     */
    public void setPageMediaBox(int pageIndex, @NonNull Rect mediaBox) {
        throwIfClosed();
        throwIfMediaBoxNull(mediaBox);
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            nativeSetPageMediaBox(mNativeDocument, pageIndex, mediaBox);
        }
    }

    /**
     * Gets the crop box of a given page in mils (1/72").
     *
     * @param pageIndex The page index.
     * @param outCropBox The crop box output.
     */
    public boolean getPageCropBox(int pageIndex, @NonNull Rect outCropBox) {
        throwIfClosed();
        throwIfOutCropBoxNull(outCropBox);
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            return nativeGetPageCropBox(mNativeDocument, pageIndex, outCropBox);
        }
    }

    /**
     * Sets the crop box of a given page in mils (1/72").
     *
     * @param pageIndex The page index.
     * @param cropBox The crop box.
     */
    public void setPageCropBox(int pageIndex, @NonNull Rect cropBox) {
        throwIfClosed();
        throwIfCropBoxNull(cropBox);
        throwIfPageNotInDocument(pageIndex);

        synchronized (PdfRenderer.sPdfiumLock) {
            nativeSetPageCropBox(mNativeDocument, pageIndex, cropBox);
        }
    }

    /**
     * Gets whether the document prefers to be scaled for printing.
     *
     * @return Whether to scale the document.
     */
    public boolean shouldScaleForPrinting() {
        throwIfClosed();

        synchronized (PdfRenderer.sPdfiumLock) {
            return nativeScaleForPrinting(mNativeDocument);
        }
    }

    /**
     * Writes the PDF file to the provided destination.
     * <p>
     * <strong>Note:</strong> This method takes ownership of the passed in file
     * descriptor and is responsible for closing it when writing completes.
     * </p>
     * @param output The destination.
     */
    public void write(ParcelFileDescriptor output) throws IOException {
        try {
            throwIfClosed();

            synchronized (PdfRenderer.sPdfiumLock) {
                nativeWrite(mNativeDocument, output.getFd());
            }
        } finally {
            IoUtils.closeQuietly(output);
        }
    }

    /**
     * Closes this editor. You should not use this instance
     * after this method is called.
     */
    public void close() {
        throwIfClosed();
        doClose();
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
        synchronized (PdfRenderer.sPdfiumLock) {
            nativeClose(mNativeDocument);
        }
        IoUtils.closeQuietly(mInput);
        mInput = null;
        mCloseGuard.close();
    }

    private void throwIfClosed() {
        if (mInput == null) {
            throw new IllegalStateException("Already closed");
        }
    }

    private void throwIfPageNotInDocument(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= mPageCount) {
            throw new IllegalArgumentException("Invalid page index");
        }
    }

    private void throwIfNotNullAndNotAfine(Matrix matrix) {
        if (matrix != null && !matrix.isAffine()) {
            throw new IllegalStateException("Matrix must be afine");
        }
    }

    private void throwIfOutSizeNull(Point outSize) {
        if (outSize == null) {
            throw new NullPointerException("outSize cannot be null");
        }
    }

    private void throwIfOutMediaBoxNull(Rect outMediaBox) {
        if (outMediaBox == null) {
            throw new NullPointerException("outMediaBox cannot be null");
        }
    }

    private void throwIfMediaBoxNull(Rect mediaBox) {
        if (mediaBox == null) {
            throw new NullPointerException("mediaBox cannot be null");
        }
    }

    private void throwIfOutCropBoxNull(Rect outCropBox) {
        if (outCropBox == null) {
            throw new NullPointerException("outCropBox cannot be null");
        }
    }

    private void throwIfCropBoxNull(Rect cropBox) {
        if (cropBox == null) {
            throw new NullPointerException("cropBox cannot be null");
        }
    }

    private static native long nativeOpen(int fd, long size);
    private static native void nativeClose(long documentPtr);
    private static native int nativeGetPageCount(long documentPtr);
    private static native int nativeRemovePage(long documentPtr, int pageIndex);
    private static native void nativeWrite(long documentPtr, int fd);
    private static native void nativeSetTransformAndClip(long documentPtr, int pageIndex,
            long transformPtr, int clipLeft, int clipTop, int clipRight, int clipBottom);
    private static native void nativeGetPageSize(long documentPtr, int pageIndex, Point outSize);
    private static native boolean nativeGetPageMediaBox(long documentPtr, int pageIndex,
            Rect outMediaBox);
    private static native void nativeSetPageMediaBox(long documentPtr, int pageIndex,
            Rect mediaBox);
    private static native boolean nativeGetPageCropBox(long documentPtr, int pageIndex,
            Rect outMediaBox);
    private static native void nativeSetPageCropBox(long documentPtr, int pageIndex,
            Rect mediaBox);
    private static native boolean nativeScaleForPrinting(long documentPtr);
}
