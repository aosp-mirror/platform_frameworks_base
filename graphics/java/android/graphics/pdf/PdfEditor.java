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
        mNativeDocument = nativeOpen(mInput.getFd(), size);
        mPageCount = nativeGetPageCount(mNativeDocument);
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
        mPageCount = nativeRemovePage(mNativeDocument, pageIndex);
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
            nativeWrite(mNativeDocument, output.getFd());
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
        nativeClose(mNativeDocument);
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

    private static native long nativeOpen(int fd, long size);
    private static native void nativeClose(long documentPtr);
    private static native int nativeGetPageCount(long documentPtr);
    private static native int nativeRemovePage(long documentPtr, int pageIndex);
    private static native void nativeWrite(long documentPtr, int fd);
}
