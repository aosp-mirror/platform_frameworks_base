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

import android.view.Surface;
import android.view.SurfaceControl;

import java.util.function.Consumer;

/**
 * @hide
 */
public final class BLASTBufferQueue {
    // Note: This field is accessed by native code.
    public long mNativeObject; // BLASTBufferQueue*

    private static native long nativeCreate(String name, boolean updateDestinationFrame);
    private static native void nativeDestroy(long ptr);
    private static native Surface nativeGetSurface(long ptr, boolean includeSurfaceControlHandle);
    private static native void nativeSyncNextTransaction(long ptr,
            Consumer<SurfaceControl.Transaction> callback, boolean acquireSingleBuffer);
    private static native void nativeStopContinuousSyncTransaction(long ptr);
    private static native void nativeUpdate(long ptr, long surfaceControl, long width, long height,
            int format);
    private static native void nativeMergeWithNextTransaction(long ptr, long transactionPtr,
                                                              long frameNumber);
    private static native long nativeGetLastAcquiredFrameNum(long ptr);
    private static native void nativeApplyPendingTransactions(long ptr, long frameNumber);
    private static native boolean nativeIsSameSurfaceControl(long ptr, long surfaceControlPtr);
    private static native SurfaceControl.Transaction nativeGatherPendingTransactions(long ptr,
            long frameNumber);

    /** Create a new connection with the surface flinger. */
    public BLASTBufferQueue(String name, SurfaceControl sc, int width, int height,
            @PixelFormat.Format int format) {
        this(name, true /* updateDestinationFrame */);
        update(sc, width, height, format);
    }

    public BLASTBufferQueue(String name, boolean updateDestinationFrame) {
        mNativeObject = nativeCreate(name, updateDestinationFrame);
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
     * Send a callback that accepts a transaction to BBQ. BBQ will acquire buffers into the a
     * transaction it created and will eventually send the transaction into the callback
     * when it is ready.
     * @param callback The callback invoked when the buffer has been added to the transaction. The
     *                 callback will contain the transaction with the buffer.
     * @param acquireSingleBuffer If true, only acquire a single buffer when processing frames. The
     *                            callback will be cleared once a single buffer has been
     *                            acquired. If false, continue to acquire all buffers into the
     *                            transaction until stopContinuousSyncTransaction is called.
     */
    public void syncNextTransaction(boolean acquireSingleBuffer,
            Consumer<SurfaceControl.Transaction> callback) {
        nativeSyncNextTransaction(mNativeObject, callback, acquireSingleBuffer);
    }

    /**
     * Send a callback that accepts a transaction to BBQ. BBQ will acquire buffers into the a
     * transaction it created and will eventually send the transaction into the callback
     * when it is ready.
     * @param callback The callback invoked when the buffer has been added to the transaction. The
     *                 callback will contain the transaction with the buffer.
     */
    public void syncNextTransaction(Consumer<SurfaceControl.Transaction> callback) {
        syncNextTransaction(true /* acquireSingleBuffer */, callback);
    }

    /**
     * Tell BBQ to stop acquiring buffers into a single transaction. BBQ will send the sync
     * transaction callback after this has been called. This should only be used when
     * syncNextTransaction was called with acquireSingleBuffer set to false.
     */
    public void stopContinuousSyncTransaction() {
        nativeStopContinuousSyncTransaction(mNativeObject);
    }

    /**
     * Updates {@link SurfaceControl}, size, and format for a particular BLASTBufferQueue
     * @param sc The new SurfaceControl that this BLASTBufferQueue will update
     * @param width The new width for the buffer.
     * @param height The new height for the buffer.
     * @param format The new format for the buffer.
     */
    public void update(SurfaceControl sc, int width, int height, @PixelFormat.Format int format) {
        nativeUpdate(mNativeObject, sc.mNativeObject, width, height, format);
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

    /**
     * Get any transactions that were passed to {@link #mergeWithNextTransaction} with the
     * specified frameNumber. This is intended to ensure transactions don't get stuck as pending
     * if the specified frameNumber is never drawn.
     *
     * @param frameNumber The frameNumber used to determine which transactions to apply.
     * @return a Transaction that contains the merge of all the transactions that were sent to
     *         mergeWithNextTransaction
     */
    public SurfaceControl.Transaction gatherPendingTransactions(long frameNumber) {
        return nativeGatherPendingTransactions(mNativeObject, frameNumber);
    }
}
