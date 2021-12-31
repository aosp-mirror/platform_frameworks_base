/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.graphics;

import android.annotation.Nullable;
import android.view.Surface;
import android.view.SurfaceControl;

/**
 * @hide
 */
public final class BLASTBufferQueue {
    // Note: This field is accessed by native code.
    public long mNativeObject; // BLASTBufferQueue*

    private static native long nativeCreate(String name);
    private static native void nativeDestroy(long ptr);
    private static native Surface nativeGetSurface(long ptr, boolean includeSurfaceControlHandle);
    private static native void nativeSetSyncTransaction(long ptr, long transactionPtr,
            boolean acquireSingleBuffer);
    private static native void nativeUpdate(long ptr, long surfaceControl, long width, long height,
            int format, long transactionPtr);
    private static native void nativeMergeWithNextTransaction(long ptr, long transactionPtr,
                                                              long frameNumber);
    private static native long nativeGetLastAcquiredFrameNum(long ptr);
    private static native void nativeApplyPendingTransactions(long ptr, long frameNumber);
    private static native boolean nativeIsSameSurfaceControl(long ptr, long surfaceControlPtr);

    /** Create a new connection with the surface flinger. */
    public BLASTBufferQueue(String name, SurfaceControl sc, int width, int height,
            @PixelFormat.Format int format) {
        this(name);
        update(sc, width, height, format);
    }

    public BLASTBufferQueue(String name) {
        mNativeObject = nativeCreate(name);
    }

    public void destroy() {
        nativeDestroy(mNativeObject);
        mNativeObject = 0;
    }

    /**
     * @return a new Surface instance from the IGraphicsBufferProducer of the adapter.
     */
    public Surface createSurface() {
        return nativeGetSurface(mNativeObject, false /* includeSurfaceControlHandle */);
    }

    /**
     * @return a new Surface instance from the IGraphicsBufferProducer of the adapter and
     * the SurfaceControl handle.
     */
    public Surface createSurfaceWithHandle() {
        return nativeGetSurface(mNativeObject, true /* includeSurfaceControlHandle */);
    }

    /**
     * Send the transaction to BBQ so the next frame can be added and not applied immediately. This
     * gives the caller a chance to apply the transaction when it's ready.
     *
     * @param t                   The transaction to add the frame to. This can be null to clear the
     *                            transaction.
     * @param acquireSingleBuffer If true, only acquire a single buffer when processing frames. The
     *                            transaction will be cleared once a single buffer has been
     *                            acquired. If false, continue to acquire all buffers into the
     *                            transaction until setSyncTransaction is called again with a null
     *                            transaction.
     */
    public void setSyncTransaction(@Nullable SurfaceControl.Transaction t,
            boolean acquireSingleBuffer) {
        nativeSetSyncTransaction(mNativeObject, t == null ? 0 : t.mNativeObject,
                acquireSingleBuffer);
    }

    public void setSyncTransaction(@Nullable SurfaceControl.Transaction t) {
        setSyncTransaction(t, true /* acquireSingleBuffer */);
    }

    /**
     * Updates {@link SurfaceControl}, size, and format for a particular BLASTBufferQueue
     * @param sc The new SurfaceControl that this BLASTBufferQueue will update
     * @param width The new width for the buffer.
     * @param height The new height for the buffer.
     * @param format The new format for the buffer.
     * @param t Adds destination frame changes to the passed in transaction.
     */
    public void update(SurfaceControl sc, int width, int height, @PixelFormat.Format int format,
            SurfaceControl.Transaction t) {
        nativeUpdate(mNativeObject, sc.mNativeObject, width, height, format, t.mNativeObject);
    }

    public void update(SurfaceControl sc, int width, int height, @PixelFormat.Format int format) {
        nativeUpdate(mNativeObject, sc.mNativeObject, width, height, format, 0);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeObject != 0) {
                nativeDestroy(mNativeObject);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Merge the transaction passed in to the next transaction in BlastBufferQueue. The next
     * transaction will be applied or merged when the next frame with specified frame number
     * is available.
     */
    public void mergeWithNextTransaction(SurfaceControl.Transaction t, long frameNumber) {
        nativeMergeWithNextTransaction(mNativeObject, t.mNativeObject, frameNumber);
    }

    /**
     * Merge the transaction passed in to the next transaction in BlastBufferQueue.
     * @param nativeTransaction native handle passed from native c/c++ code.
     */
    public void mergeWithNextTransaction(long nativeTransaction, long frameNumber) {
        nativeMergeWithNextTransaction(mNativeObject, nativeTransaction, frameNumber);
    }

    /**
     * Apply any transactions that were passed to {@link #mergeWithNextTransaction} with the
     * specified frameNumber. This is intended to ensure transactions don't get stuck as pending
     * if the specified frameNumber is never drawn.
     *
     * @param frameNumber The frameNumber used to determine which transactions to apply.
     */
    public void applyPendingTransactions(long frameNumber) {
        nativeApplyPendingTransactions(mNativeObject, frameNumber);
    }

    public long getLastAcquiredFrameNum() {
        return nativeGetLastAcquiredFrameNum(mNativeObject);
    }

    /**
     * @return True if the associated SurfaceControl has the same handle as {@param sc}.
     */
    public boolean isSameSurfaceControl(SurfaceControl sc) {
        return nativeIsSameSurfaceControl(mNativeObject, sc.mNativeObject);
    }
}
