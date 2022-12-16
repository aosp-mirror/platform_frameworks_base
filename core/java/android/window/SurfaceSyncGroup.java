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
import android.os.Debug;
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
 * 1. {@link #addToSync(SurfaceSyncGroup, boolean)} is called for every SurfaceSyncGroup object that
 * wants to be included in the sync. If the addSync is called for an {@link AttachedSurfaceControl}
 * or {@link SurfaceView} it needs to be called on the UI thread. When addToSync is called, it's
 * guaranteed that any UI updates that were requested before addToSync but after the last frame
 * drew, will be included in the sync.
 * 2. {@link #markSyncReady()} should be called when all the {@link SurfaceSyncGroup}s have been
 * added to the SurfaceSyncGroup. At this point, the SurfaceSyncGroup is closed and no more
 * SurfaceSyncGroups can be added to it.
 * 3. The SurfaceSyncGroup will gather the data for each SurfaceSyncGroup using the steps described
 * below. When all the SurfaceSyncGroups have finished, the syncRequestComplete will be invoked and
 * the transaction will either be applied or sent to the caller. In most cases, only the
 * SurfaceSyncGroup should be handling the Transaction object directly. However, there are some
 * cases where the framework needs to send the Transaction elsewhere, like in ViewRootImpl, so that
 * option is provided.
 *
 * The following is what happens within the {@link android.window.SurfaceSyncGroup}
 * 1. Each SurfaceSyncGroup will get a
 * {@link SurfaceSyncGroup#onAddedToSyncGroup(SurfaceSyncGroup, TransactionReadyCallback)} callback
 * that contains a  {@link TransactionReadyCallback}.
 * 2. Each {@link SurfaceSyncGroup} needs to invoke
 * {@link SurfaceSyncGroup#onTransactionReady(Transaction)}.
 * This makes sure the parent SurfaceSyncGroup knows when the SurfaceSyncGroup is complete, allowing
 * the parent SurfaceSyncGroup to get the Transaction that contains the changes for the child
 * SurfaceSyncGroup
 * 3. When the final TransactionReadyCallback finishes for the child SurfaceSyncGroups, the
 * transaction is either applied if it's the top most parent or the final merged transaction is sent
 * up to its parent SurfaceSyncGroup.
 *
 * @hide
 */
public class SurfaceSyncGroup {
    private static final String TAG = "SurfaceSyncGroup";
    private static final boolean DEBUG = false;

    private static Supplier<Transaction> sTransactionFactory = Transaction::new;

    /**
     * Class that collects the {@link SurfaceSyncGroup}s and notifies when all the surfaces have
     * a frame ready.
     */
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Set<TransactionReadyCallback> mPendingSyncs = new ArraySet<>();
    @GuardedBy("mLock")
    private final Transaction mTransaction = sTransactionFactory.get();
    @GuardedBy("mLock")
    private boolean mSyncReady;

    @GuardedBy("mLock")
    private boolean mFinished;

    @GuardedBy("mLock")
    private TransactionReadyCallback mTransactionReadyCallback;

    @GuardedBy("mLock")
    private SurfaceSyncGroup mParentSyncGroup;

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
     * @param transactionReadyCallback The complete callback that contains the syncId and
     *                                 transaction with all the sync data merged. The Transaction
     *                                 passed back can be null.
     *
     * NOTE: Only should be used by ViewRootImpl
     * @hide
     */
    public SurfaceSyncGroup(Consumer<Transaction> transactionReadyCallback) {
        mTransactionReadyCallback = transaction -> {
            transactionReadyCallback.accept(transaction);
            synchronized (mLock) {
                for (Pair<Executor, Runnable> callback : mSyncCompleteCallbacks) {
                    callback.first.execute(callback.second);
                }
            }
        };

        if (DEBUG) {
            Log.d(TAG, "setupSync " + this + " " + Debug.getCallers(2));
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
     * Mark the sync set as ready to complete. No more data can be added to the specified
     * syncId.
     * Once the sync set is marked as ready, it will be able to complete once all Syncables in the
     * set have completed their sync
     */
    public void markSyncReady() {
        onTransactionReady(null);
    }

    /**
     * Similar to {@link #markSyncReady()}, but a transaction is passed in to merge with the
     * SurfaceSyncGroup.
     * @param t The transaction that merges into the main Transaction for the SurfaceSyncGroup.
     */
    public void onTransactionReady(@Nullable Transaction t) {
        synchronized (mLock) {
            mSyncReady = true;
            if (t != null) {
                mTransaction.merge(t);
            }
            checkIfSyncIsComplete();
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
        SurfaceSyncGroup surfaceSyncGroup = new SurfaceSyncGroup();
        if (addToSync(surfaceSyncGroup, false /* parentSyncGroupMerge */)) {
            frameCallbackConsumer.accept(
                    () -> surfaceView.syncNextFrame(surfaceSyncGroup::onTransactionReady));
            return true;
        }
        return false;
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
        SurfaceSyncGroup surfaceSyncGroup = viewRoot.getOrCreateSurfaceSyncGroup();
        if (surfaceSyncGroup == null) {
            return false;
        }
        return addToSync(surfaceSyncGroup, false /* parentSyncGroupMerge */);
    }

    /**
     * Add a {@link SurfaceSyncGroup} to a sync set. The sync set will wait for all
     * SyncableSurfaces to complete before notifying.
     *
     * @param surfaceSyncGroup A SyncableSurface that implements how to handle syncing
     *                         buffers.
     * @return true if the SyncGroup was successfully added to the current SyncGroup, false
     * otherwise.
     */
    public boolean addToSync(SurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge) {
        TransactionReadyCallback transactionReadyCallback = new TransactionReadyCallback() {
            @Override
            public void onTransactionReady(Transaction t) {
                synchronized (mLock) {
                    if (t != null) {
                        // When an older parent sync group is added due to a child syncGroup getting
                        // added to multiple groups, we need to maintain merge order so the older
                        // parentSyncGroup transactions are overwritten by anything in the newer
                        // parentSyncGroup.
                        if (parentSyncGroupMerge) {
                            t.merge(mTransaction);
                        }
                        mTransaction.merge(t);
                    }
                    mPendingSyncs.remove(this);
                    checkIfSyncIsComplete();
                }
            }
        };

        synchronized (mLock) {
            if (mSyncReady) {
                Log.e(TAG, "Sync " + this + " was already marked as ready. No more "
                        + "SurfaceSyncGroups can be added.");
                return false;
            }
            mPendingSyncs.add(transactionReadyCallback);
        }
        surfaceSyncGroup.onAddedToSyncGroup(this, transactionReadyCallback);
        return true;
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

    @GuardedBy("mLock")
    private void checkIfSyncIsComplete() {
        if (mFinished) {
            if (DEBUG) {
                Log.d(TAG, "SurfaceSyncGroup=" + this + " is already complete");
            }
            return;
        }

        if (!mSyncReady || !mPendingSyncs.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "SurfaceSyncGroup=" + this + " is not complete. mSyncReady="
                        + mSyncReady + " mPendingSyncs=" + mPendingSyncs.size());
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Successfully finished sync id=" + this);
        }
        mTransactionReadyCallback.onTransactionReady(mTransaction);
        mFinished = true;
    }

    private void onAddedToSyncGroup(SurfaceSyncGroup parentSyncGroup,
            TransactionReadyCallback transactionReadyCallback) {
        boolean finished = false;
        synchronized (mLock) {
            if (mFinished) {
                finished = true;
            } else {
                // If this SurfaceSyncGroup was already added to a different SurfaceSyncGroup, we
                // need to combine everything. We can add the old SurfaceSyncGroup parent to the new
                // parent so the new parent doesn't complete until the old parent does.
                // Additionally, the old parent will not get the final transaction object and
                // instead will send it to the new parent, ensuring that any other SurfaceSyncGroups
                // from the original parent are also combined with the new parent SurfaceSyncGroup.
                if (mParentSyncGroup != null && mParentSyncGroup != parentSyncGroup) {
                    if (DEBUG) {
                        Log.d(TAG, "Already part of sync group " + mParentSyncGroup + " " + this);
                    }
                    parentSyncGroup.addToSync(mParentSyncGroup, true /* parentSyncGroupMerge */);
                }

                if (mParentSyncGroup == parentSyncGroup) {
                    if (DEBUG) {
                        Log.d(TAG, "Added to parent that was already the parent");
                    }
                }
                mParentSyncGroup = parentSyncGroup;
                final TransactionReadyCallback lastCallback = mTransactionReadyCallback;
                mTransactionReadyCallback = t -> {
                    lastCallback.onTransactionReady(null);
                    transactionReadyCallback.onTransactionReady(t);
                };
            }
        }

        // Invoke the callback outside of the lock when the SurfaceSyncGroup being added was already
        // complete.
        if (finished) {
            transactionReadyCallback.onTransactionReady(null);
        }
    }
    /**
     * Interface so the SurfaceSyncer can know when it's safe to start and when everything has been
     * completed. The caller should invoke the calls when the rendering has started and finished a
     * frame.
     */
    private interface TransactionReadyCallback {
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
