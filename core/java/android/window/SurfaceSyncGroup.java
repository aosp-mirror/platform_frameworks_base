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

import android.annotation.Nullable;
import android.annotation.UiThread;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceView;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Used to organize syncs for surfaces.
 *
 * The SurfaceSyncGroup allows callers to add desired syncs into a set and wait for them to all
 * complete before getting a callback. The purpose of the SurfaceSyncGroup is to be an accounting
 * mechanism so each sync implementation doesn't need to handle it themselves. The SurfaceSyncGroup
 * class is used the following way.
 *
 * 1. {@link #SurfaceSyncGroup()} constructor is called
 * 2. {@link #addToSync(SyncTarget)} is called for every SyncTarget object that wants to be
 * included in the sync. If the addSync is called for an {@link AttachedSurfaceControl} or
 * {@link SurfaceView} it needs to be called on the UI thread. When addToSync is called, it's
 * guaranteed that any UI updates that were requested before addToSync but after the last frame
 * drew, will be included in the sync.
 * 3. {@link #markSyncReady()} should be called when all the {@link SyncTarget}s have been added
 * to the SurfaceSyncGroup. At this point, the SurfaceSyncGroup is closed and no more SyncTargets
 * can be added to it.
 * 4. The SurfaceSyncGroup will gather the data for each SyncTarget using the steps described below.
 * When all the SyncTargets have finished, the syncRequestComplete will be invoked and the
 * transaction will either be applied or sent to the caller. In most cases, only the
 * SurfaceSyncGroup  should be handling the Transaction object directly. However, there are some
 * cases where the framework needs to send the Transaction elsewhere, like in ViewRootImpl, so that
 * option is provided.
 *
 * The following is what happens within the {@link SurfaceSyncGroup}
 * 1. Each SyncTarget will get a {@link SyncTarget#onAddedToSyncGroup} callback that contains a
 * {@link TransactionReadyCallback}.
 * 2. Each {@link SyncTarget} needs to invoke
 * {@link TransactionReadyCallback#onTransactionReady(Transaction)}. This makes sure the
 * SurfaceSyncGroup knows when the SyncTarget is complete, allowing the SurfaceSyncGroup to get the
 * Transaction that contains the buffer.
 * 3. When the final TransactionReadyCallback finishes for the SurfaceSyncGroup, in most cases the
 * transaction is applied and then the sync complete callbacks are invoked, letting the callers know
 * the sync is now complete.
 *
 * @hide
 */
public final class SurfaceSyncGroup {
    private static final String TAG = "SurfaceSyncGroup";
    private static final boolean DEBUG = false;

    private static Supplier<Transaction> sTransactionFactory = Transaction::new;

    /**
     * Class that collects the {@link SyncTarget}s and notifies when all the surfaces have
     * a frame ready.
     */
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Set<Integer> mPendingSyncs = new ArraySet<>();
    @GuardedBy("mLock")
    private final Transaction mTransaction = sTransactionFactory.get();
    @GuardedBy("mLock")
    private boolean mSyncReady;

    @GuardedBy("mLock")
    private Consumer<Transaction> mSyncRequestCompleteCallback;

    @GuardedBy("mLock")
    private final Set<SurfaceSyncGroup> mMergedSyncGroups = new ArraySet<>();

    @GuardedBy("mLock")
    private boolean mFinished;

    @GuardedBy("mLock")
    private final ArraySet<Pair<Executor, Runnable>> mSyncCompleteCallbacks = new ArraySet<>();

    /**
     * @hide
     */
    public static void setTransactionFactory(Supplier<Transaction> transactionFactory) {
        sTransactionFactory = transactionFactory;
    }

    /**
     * Starts a sync and will automatically apply the final, merged transaction.
     */
    public SurfaceSyncGroup() {
        this(transaction -> {
            if (transaction != null) {
                transaction.apply();
            }
        });

    }

    /**
     * Creates a sync.
     *
     * @param syncRequestComplete The complete callback that contains the syncId and transaction
     *                            with all the sync data merged. The Transaction passed back can be
     *                            null.
     *
     * NOTE: Only should be used by ViewRootImpl
     * @hide
     */
    public SurfaceSyncGroup(Consumer<Transaction> syncRequestComplete) {
        mSyncRequestCompleteCallback = transaction -> {
            syncRequestComplete.accept(transaction);
            synchronized (mLock) {
                for (Pair<Executor, Runnable> callback : mSyncCompleteCallbacks) {
                    callback.first.execute(callback.second);
                }
            }
        };

        if (DEBUG) {
            Log.d(TAG, "setupSync");
        }
    }

    /**
     * Add a {@link Runnable} to be executed when the sync completes.
     *
     * @param executor The Executor to invoke the Runnable on
     * @param runnable The Runnable to get called
     */
    public void addSyncCompleteCallback(Executor executor, Runnable runnable) {
        synchronized (mLock) {
            mSyncCompleteCallbacks.add(new Pair<>(executor, runnable));
        }
    }

    /**
     * Add a SurfaceView to a sync set. This is different than
     * {@link #addToSync(AttachedSurfaceControl)} because it requires the caller to notify the start
     * and finish drawing in order to sync.
     *
     * @param surfaceView           The SurfaceView to add to the sync.
     * @param frameCallbackConsumer The callback that's invoked to allow the caller to notify
     *                              the
     *                              Syncer when the SurfaceView has started drawing and
     *                              finished.
     * @return true if the SurfaceView was successfully added to the SyncGroup, false otherwise.
     */
    @UiThread
    public boolean addToSync(SurfaceView surfaceView,
            Consumer<SurfaceViewFrameCallback> frameCallbackConsumer) {
        return addToSync(new SurfaceViewSyncTarget(surfaceView, frameCallbackConsumer));
    }

    /**
     * Add a View's rootView to a sync set.
     *
     * @param viewRoot The viewRoot that will be add to the sync set
     * @return true if the View was successfully added to the SyncGroup, false otherwise.
     */
    @UiThread
    public boolean addToSync(@Nullable AttachedSurfaceControl viewRoot) {
        if (viewRoot == null) {
            return false;
        }
        SyncTarget syncTarget = viewRoot.getSyncTarget();
        if (syncTarget == null) {
            return false;
        }
        return addToSync(syncTarget);
    }

    /**
     * Add a {@link SyncTarget} to a sync set. The sync set will wait for all
     * SyncableSurfaces to complete before notifying.
     *
     * @param syncTarget A SyncTarget that implements how to handle syncing transactions.
     * @return true if the SyncTarget was successfully added to the SyncGroup, false otherwise.
     */
    public boolean addToSync(SyncTarget syncTarget) {
        TransactionReadyCallback transactionReadyCallback = new TransactionReadyCallback() {
            @Override
            public void onTransactionReady(Transaction t) {
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
                Log.e(TAG, "Sync " + this + " was already marked as ready. No more "
                        + "SyncTargets can be added.");
                return false;
            }
            mPendingSyncs.add(transactionReadyCallback.hashCode());
        }
        syncTarget.onAddedToSyncGroup(this, transactionReadyCallback);
        return true;
    }

    /**
     * Mark the sync set as ready to complete. No more data can be added to the specified
     * syncId.
     * Once the sync set is marked as ready, it will be able to complete once all Syncables in the
     * set have completed their sync
     */
    public void markSyncReady() {
        synchronized (mLock) {
            mSyncReady = true;
            checkIfSyncIsComplete();
        }
    }

    @GuardedBy("mLock")
    private void checkIfSyncIsComplete() {
        if (!mSyncReady || !mPendingSyncs.isEmpty() || !mMergedSyncGroups.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "Syncable is not complete. mSyncReady=" + mSyncReady
                        + " mPendingSyncs=" + mPendingSyncs.size() + " mergedSyncs="
                        + mMergedSyncGroups.size());
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Successfully finished sync id=" + this);
        }

        mSyncRequestCompleteCallback.accept(mTransaction);
        mFinished = true;
    }

    /**
     * Add a Transaction to this sync set. This allows the caller to provide other info that
     * should be synced with the transactions.
     */
    public void addTransactionToSync(Transaction t) {
        synchronized (mLock) {
            mTransaction.merge(t);
        }
    }

    private void updateCallback(Consumer<Transaction> transactionConsumer) {
        synchronized (mLock) {
            if (mFinished) {
                Log.e(TAG, "Attempting to merge SyncGroup " + this + " when sync is"
                        + " already complete");
                transactionConsumer.accept(null);
            }

            final Consumer<Transaction> oldCallback = mSyncRequestCompleteCallback;
            mSyncRequestCompleteCallback = transaction -> {
                oldCallback.accept(null);
                transactionConsumer.accept(transaction);
            };
        }
    }

    /**
     * Merge a SyncGroup into this SyncGroup. Since SyncGroups could still have pending SyncTargets,
     * we need to make sure those can still complete before the mergeTo SyncGroup is considered
     * complete.
     *
     * We keep track of all the merged SyncGroups until they are marked as done, and then they
     * are removed from the set. This SyncGroup is not considered done until all the merged
     * SyncGroups are done.
     *
     * When the merged SyncGroup is complete, it will invoke the original syncRequestComplete
     * callback but send an empty transaction to ensure the changes are applied early. This
     * is needed in case the original sync is relying on the callback to continue processing.
     *
     * @param otherSyncGroup The other SyncGroup to merge into this one.
     */
    public void merge(SurfaceSyncGroup otherSyncGroup) {
        synchronized (mLock) {
            mMergedSyncGroups.add(otherSyncGroup);
        }
        otherSyncGroup.updateCallback(transaction -> {
            synchronized (mLock) {
                mMergedSyncGroups.remove(otherSyncGroup);
                if (transaction != null) {
                    mTransaction.merge(transaction);
                }
                checkIfSyncIsComplete();
            }
        });
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
        public void onAddedToSyncGroup(SurfaceSyncGroup parentSyncGroup,
                TransactionReadyCallback transactionReadyCallback) {
            mFrameCallbackConsumer.accept(
                    () -> mSurfaceView.syncNextFrame(transactionReadyCallback::onTransactionReady));
        }
    }

    /**
     * A SyncTarget that can be added to a sync set.
     */
    public interface SyncTarget {
        /**
         * Called when the SyncTarget has been added to a SyncGroup as is ready to begin handing a
         * sync request. When invoked, the implementor is required to call
         * {@link TransactionReadyCallback#onTransactionReady(Transaction)} in order for this
         * SurfaceSyncGroup to fully complete.
         *
         * Always invoked on the thread that initiated the call to {@link #addToSync(SyncTarget)}
         *
         * @param parentSyncGroup The sync group this target has been added to.
         * @param transactionReadyCallback A TransactionReadyCallback that the caller must invoke
         *                                 onTransactionReady
         */
        void onAddedToSyncGroup(SurfaceSyncGroup parentSyncGroup,
                TransactionReadyCallback transactionReadyCallback);
    }

    /**
     * Interface so the SurfaceSyncer can know when it's safe to start and when everything has been
     * completed. The caller should invoke the calls when the rendering has started and finished a
     * frame.
     */
    public interface TransactionReadyCallback {
        /**
         * Invoked when the transaction is ready to sync.
         *
         * @param t The transaction that contains the anything to be included in the synced. This
         *          can be null if there's nothing to sync
         */
        void onTransactionReady(@Nullable Transaction t);
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
