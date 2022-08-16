/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Used to organize syncs for surfaces.
 *
 * The SurfaceSyncer allows callers to add desired syncs into a set and wait for them to all
 * complete before getting a callback. The purpose of the Syncer is to be an accounting mechanism
 * so each sync implementation doesn't need to handle it themselves. The Syncer class is used the
 * following way.
 *
 * 1. {@link #setupSync(Runnable)} is called
 * 2. {@link #addToSync(int, SyncTarget)} is called for every SyncTarget object that wants to be
 *    included in the sync. If the addSync is called for a View or SurfaceView it needs to be called
 *    on the UI thread. When addToSync is called, it's guaranteed that any UI updates that were
 *    requested before addToSync but after the last frame drew, will be included in the sync.
 * 3. {@link #markSyncReady(int)} should be called when all the {@link SyncTarget}s have been added
 *    to the SyncSet. Now the SyncSet is closed and no more SyncTargets can be added to it.
 * 4. The SyncSet will gather the data for each SyncTarget using the steps described below. When
 *    all the SyncTargets have finished, the syncRequestComplete will be invoked and the transaction
 *    will either be applied or sent to the caller. In most cases, only the SurfaceSyncer should be
 *    handling the Transaction object directly. However, there are some cases where the framework
 *    needs to send the Transaction elsewhere, like in ViewRootImpl, so that option is provided.
 *
 * The following is what happens within the {@link SyncSet}
 *  1. Each SyncableTarget will get a {@link SyncTarget#onReadyToSync} callback that contains
 *     a {@link SyncBufferCallback}.
 * 2. Each {@link SyncTarget} needs to invoke {@link SyncBufferCallback#onBufferReady(Transaction)}.
 *    This makes sure the SyncSet knows when the SyncTarget is complete, allowing the SyncSet to get
 *    the Transaction that contains the buffer.
 * 3. When the final SyncBufferCallback finishes for the SyncSet, the syncRequestComplete Consumer
 *    will be invoked with the transaction that contains all information requested in the sync. This
 *    could include buffers and geometry changes. The buffer update will include the UI changes that
 *    were requested for the View.
 *
 * @hide
 */
public class SurfaceSyncer {
    private static final String TAG = "SurfaceSyncer";
    private static final boolean DEBUG = false;

    private static Supplier<Transaction> sTransactionFactory = Transaction::new;

    private final Object mSyncSetLock = new Object();
    @GuardedBy("mSyncSetLock")
    private final SparseArray<SyncSet> mSyncSets = new SparseArray<>();
    @GuardedBy("mSyncSetLock")
    private int mIdCounter = 0;

    /**
     * @hide
     */
    public static void setTransactionFactory(Supplier<Transaction> transactionFactory) {
        sTransactionFactory = transactionFactory;
    }

    /**
     * Starts a sync and will automatically apply the final, merged transaction.
     *
     * @param onComplete The runnable that is invoked when the sync has completed. This will run on
     *                   the same thread that the sync was started on.
     * @return The syncId for the newly created sync.
     * @see #setupSync(Consumer)
     */
    public int setupSync(@Nullable Runnable onComplete) {
        Handler handler = new Handler(Looper.myLooper());
        return setupSync(transaction -> {
            transaction.apply();
            if (onComplete != null) {
                handler.post(onComplete);
            }
        });
    }

    /**
     * Starts a sync.
     *
     * @param syncRequestComplete The complete callback that contains the syncId and transaction
     *                            with all the sync data merged.
     * @return The syncId for the newly created sync.
     * @hide
     * @see #setupSync(Runnable)
     */
    public int setupSync(@NonNull Consumer<Transaction> syncRequestComplete) {
        synchronized (mSyncSetLock) {
            final int syncId = mIdCounter++;
            if (DEBUG) {
                Log.d(TAG, "setupSync " + syncId);
            }
            SyncSet syncSet = new SyncSet(syncId, transaction -> {
                synchronized (mSyncSetLock) {
                    mSyncSets.remove(syncId);
                }
                syncRequestComplete.accept(transaction);
            });
            mSyncSets.put(syncId, syncSet);
            return syncId;
        }
    }

    /**
     * Mark the sync set as ready to complete. No more data can be added to the specified syncId.
     * Once the sync set is marked as ready, it will be able to complete once all Syncables in the
     * set have completed their sync
     *
     * @param syncId The syncId to mark as ready.
     */
    public void markSyncReady(int syncId) {
        SyncSet syncSet;
        synchronized (mSyncSetLock) {
            syncSet = mSyncSets.get(syncId);
        }
        if (syncSet == null) {
            Log.e(TAG, "Failed to find syncSet for syncId=" + syncId);
            return;
        }
        syncSet.markSyncReady();
    }

    /**
     * Merge another SyncSet into the specified syncId.
     * @param syncId The current syncId to merge into
     * @param otherSyncId The other syncId to be merged
     * @param otherSurfaceSyncer The other SurfaceSyncer where the otherSyncId is from
     */
    public void merge(int syncId, int otherSyncId, SurfaceSyncer otherSurfaceSyncer) {
        SyncSet syncSet;
        synchronized (mSyncSetLock) {
            syncSet = mSyncSets.get(syncId);
        }

        SyncSet otherSyncSet = otherSurfaceSyncer.getAndValidateSyncSet(otherSyncId);
        if (otherSyncSet == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG,
                    "merge id=" + otherSyncId + " from=" + otherSurfaceSyncer + " into id=" + syncId
                            + " from" + this);
        }
        syncSet.merge(otherSyncSet);
    }

    /**
     * Add a SurfaceView to a sync set. This is different than {@link #addToSync(int, View)} because
     * it requires the caller to notify the start and finish drawing in order to sync.
     *
     * @param syncId The syncId to add an entry to.
     * @param surfaceView The SurfaceView to add to the sync.
     * @param frameCallbackConsumer The callback that's invoked to allow the caller to notify the
     *                              Syncer when the SurfaceView has started drawing and finished.
     *
     * @return true if the SurfaceView was successfully added to the SyncSet, false otherwise.
     */
    @UiThread
    public boolean addToSync(int syncId, SurfaceView surfaceView,
            Consumer<SurfaceViewFrameCallback> frameCallbackConsumer) {
        return addToSync(syncId, new SurfaceViewSyncTarget(surfaceView, frameCallbackConsumer));
    }

    /**
     * Add a View's rootView to a sync set.
     *
     * @param syncId The syncId to add an entry to.
     * @param view The view where the root will be add to the sync set
     *
     * @return true if the View was successfully added to the SyncSet, false otherwise.
     */
    @UiThread
    public boolean addToSync(int syncId, @NonNull View view) {
        ViewRootImpl viewRoot = view.getViewRootImpl();
        if (viewRoot == null) {
            return false;
        }
        return addToSync(syncId, viewRoot.mSyncTarget);
    }

    /**
     * Add a {@link SyncTarget} to a sync set. The sync set will wait for all
     * SyncableSurfaces to complete before notifying.
     *
     * @param syncId                 The syncId to add an entry to.
     * @param syncTarget A SyncableSurface that implements how to handle syncing
     *                               buffers.
     *
     * @return true if the SyncTarget was successfully added to the SyncSet, false otherwise.
     */
    public boolean addToSync(int syncId, @NonNull SyncTarget syncTarget) {
        SyncSet syncSet = getAndValidateSyncSet(syncId);
        if (syncSet == null) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "addToSync id=" + syncId);
        }
        return syncSet.addSyncableSurface(syncTarget);
    }

    /**
     * Add a transaction to a specific sync so it can be merged along with the frames from the
     * Syncables in the set. This is so the caller can add arbitrary transaction info that will be
     * applied at the same time as the buffers
     * @param syncId  The syncId where the transaction will be merged to.
     * @param t The transaction to merge in the sync set.
     */
    public void addTransactionToSync(int syncId, Transaction t) {
        SyncSet syncSet = getAndValidateSyncSet(syncId);
        if (syncSet != null) {
            syncSet.addTransactionToSync(t);
        }
    }

    private SyncSet getAndValidateSyncSet(int syncId) {
        SyncSet syncSet;
        synchronized (mSyncSetLock) {
            syncSet = mSyncSets.get(syncId);
        }
        if (syncSet == null) {
            Log.e(TAG, "Failed to find sync for id=" + syncId);
            return null;
        }
        return syncSet;
    }

    /**
     * A SyncTarget that can be added to a sync set.
     */
    public interface SyncTarget {
        /**
         * Called when the Syncable is ready to begin handing a sync request. When invoked, the
         * implementor is required to call {@link SyncBufferCallback#onBufferReady(Transaction)}
         * and {@link SyncBufferCallback#onBufferReady(Transaction)} in order for this Syncable
         * to be marked as complete.
         *
         * Always invoked on the thread that initiated the call to
         * {@link #addToSync(int, SyncTarget)}
         *
         * @param syncBufferCallback A SyncBufferCallback that the caller must invoke onBufferReady
         */
        void onReadyToSync(SyncBufferCallback syncBufferCallback);

        /**
         * There's no guarantee about the thread this callback is invoked on.
         */
        default void onSyncComplete() {}
    }

    /**
     * Interface so the SurfaceSyncer can know when it's safe to start and when everything has been
     * completed. The caller should invoke the calls when the rendering has started and finished a
     * frame.
     */
    public interface SyncBufferCallback {
        /**
         * Invoked when the transaction contains the buffer and is ready to sync.
         *
         * @param t The transaction that contains the buffer to be synced. This can be null if
         *          there's nothing to sync
         */
        void onBufferReady(@Nullable Transaction t);
    }

    /**
     * Class that collects the {@link SyncTarget}s and notifies when all the surfaces have
     * a frame ready.
     */
    private static class SyncSet {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final Set<Integer> mPendingSyncs = new ArraySet<>();
        @GuardedBy("mLock")
        private final Transaction mTransaction = sTransactionFactory.get();
        @GuardedBy("mLock")
        private boolean mSyncReady;
        @GuardedBy("mLock")
        private final Set<SyncTarget> mSyncTargets = new ArraySet<>();

        private final int mSyncId;
        @GuardedBy("mLock")
        private Consumer<Transaction> mSyncRequestCompleteCallback;

        @GuardedBy("mLock")
        private final Set<SyncSet> mMergedSyncSets = new ArraySet<>();

        @GuardedBy("mLock")
        private boolean mFinished;

        private SyncSet(int syncId, Consumer<Transaction> syncRequestComplete) {
            mSyncId = syncId;
            mSyncRequestCompleteCallback = syncRequestComplete;
        }

        boolean addSyncableSurface(SyncTarget syncTarget) {
            SyncBufferCallback syncBufferCallback = new SyncBufferCallback() {
                @Override
                public void onBufferReady(Transaction t) {
                    synchronized (mLock) {
                        if (t != null) {
                            mTransaction.merge(t);
                        }
                        mPendingSyncs.remove(hashCode());
                        checkIfSyncIsComplete();
                    }
                }
            };

            synchronized (mLock) {
                if (mSyncReady) {
                    Log.e(TAG, "Sync " + mSyncId + " was already marked as ready. No more "
                            + "SyncTargets can be added.");
                    return false;
                }
                mPendingSyncs.add(syncBufferCallback.hashCode());
                mSyncTargets.add(syncTarget);
            }
            syncTarget.onReadyToSync(syncBufferCallback);
            return true;
        }

        void markSyncReady() {
            synchronized (mLock) {
                mSyncReady = true;
                checkIfSyncIsComplete();
            }
        }

        @GuardedBy("mLock")
        private void checkIfSyncIsComplete() {
            if (!mSyncReady || !mPendingSyncs.isEmpty() || !mMergedSyncSets.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "Syncable is not complete. mSyncReady=" + mSyncReady
                            + " mPendingSyncs=" + mPendingSyncs.size() + " mergedSyncs="
                            + mMergedSyncSets.size());
                }
                return;
            }

            if (DEBUG) {
                Log.d(TAG, "Successfully finished sync id=" + mSyncId);
            }

            for (SyncTarget syncTarget : mSyncTargets) {
                syncTarget.onSyncComplete();
            }
            mSyncTargets.clear();
            mSyncRequestCompleteCallback.accept(mTransaction);
            mFinished = true;
        }

        /**
         * Add a Transaction to this sync set. This allows the caller to provide other info that
         * should be synced with the buffers.
         */
        void addTransactionToSync(Transaction t) {
            synchronized (mLock) {
                mTransaction.merge(t);
            }
        }

        public void updateCallback(Consumer<Transaction> transactionConsumer) {
            synchronized (mLock) {
                if (mFinished) {
                    Log.e(TAG, "Attempting to merge SyncSet " + mSyncId + " when sync is"
                            + " already complete");
                    transactionConsumer.accept(new Transaction());
                }

                final Consumer<Transaction> oldCallback = mSyncRequestCompleteCallback;
                mSyncRequestCompleteCallback = transaction -> {
                    oldCallback.accept(new Transaction());
                    transactionConsumer.accept(transaction);
                };
            }
        }

        /**
         * Merge a SyncSet into this SyncSet. Since SyncSets could still have pending SyncTargets,
         * we need to make sure those can still complete before the mergeTo syncSet is considered
         * complete.
         *
         * We keep track of all the merged SyncSets until they are marked as done, and then they
         * are removed from the set. This SyncSet is not considered done until all the merged
         * SyncSets are done.
         *
         * When the merged SyncSet is complete, it will invoke the original syncRequestComplete
         * callback but send an empty transaction to ensure the changes are applied early. This
         * is needed in case the original sync is relying on the callback to continue processing.
         *
         * @param otherSyncSet The other SyncSet to merge into this one.
         */
        public void merge(SyncSet otherSyncSet) {
            synchronized (mLock) {
                mMergedSyncSets.add(otherSyncSet);
            }
            otherSyncSet.updateCallback(transaction -> {
                synchronized (mLock) {
                    mMergedSyncSets.remove(otherSyncSet);
                    mTransaction.merge(transaction);
                    checkIfSyncIsComplete();
                }
            });
        }
    }

    /**
     * Wrapper class to help synchronize SurfaceViews
     */
    private static class SurfaceViewSyncTarget implements SyncTarget {
        private final SurfaceView mSurfaceView;
        private final Consumer<SurfaceViewFrameCallback> mFrameCallbackConsumer;

        SurfaceViewSyncTarget(SurfaceView surfaceView,
                Consumer<SurfaceViewFrameCallback> frameCallbackConsumer) {
            mSurfaceView = surfaceView;
            mFrameCallbackConsumer = frameCallbackConsumer;
        }

        @Override
        public void onReadyToSync(SyncBufferCallback syncBufferCallback) {
            mFrameCallbackConsumer.accept(
                    () -> mSurfaceView.syncNextFrame(syncBufferCallback::onBufferReady));
        }
    }

    /**
     * A frame callback that is used to synchronize SurfaceViews. The owner of the SurfaceView must
     * implement onFrameStarted when trying to sync the SurfaceView. This is to ensure the sync
     * knows when the frame is ready to add to the sync.
     */
    public interface SurfaceViewFrameCallback {
        /**
         * Called when the SurfaceView is going to render a frame
         */
        void onFrameStarted();
    }
}
