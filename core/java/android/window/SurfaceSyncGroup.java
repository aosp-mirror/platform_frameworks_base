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
import android.os.Binder;
import android.os.BinderProxy;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Used to organize syncs for surfaces.
 * </p>
 * See SurfaceSyncGroup.md
 * </p>
 *
 * @hide
 */
public class SurfaceSyncGroup extends ISurfaceSyncGroup.Stub {
    private static final String TAG = "SurfaceSyncGroup";
    private static final boolean DEBUG = false;

    private static final int MAX_COUNT = 100;

    private static final AtomicInteger sCounter = new AtomicInteger(0);

    private static Supplier<Transaction> sTransactionFactory = Transaction::new;

    /**
     * Class that collects the {@link SurfaceSyncGroup}s and notifies when all the surfaces have
     * a frame ready.
     */
    private final Object mLock = new Object();

    private final String mName;

    @GuardedBy("mLock")
    private final ArraySet<ITransactionReadyCallback> mPendingSyncs = new ArraySet<>();
    @GuardedBy("mLock")
    private final Transaction mTransaction = sTransactionFactory.get();
    @GuardedBy("mLock")
    private boolean mSyncReady;

    @GuardedBy("mLock")
    private boolean mFinished;

    @GuardedBy("mLock")
    private Consumer<Transaction> mTransactionReadyConsumer;

    @GuardedBy("mLock")
    private ISurfaceSyncGroup mParentSyncGroup;

    @GuardedBy("mLock")
    private final ArraySet<Pair<Executor, Runnable>> mSyncCompleteCallbacks = new ArraySet<>();

    @GuardedBy("mLock")
    private boolean mHasWMSync;

    @GuardedBy("mLock")
    private ISurfaceSyncGroupCompletedListener mSurfaceSyncGroupCompletedListener;

    /**
     * Token to identify this SurfaceSyncGroup. This is used to register the SurfaceSyncGroup in
     * WindowManager. This token is also sent to other processes' SurfaceSyncGroup that want to be
     * included in this SurfaceSyncGroup.
     */
    private final Binder mToken = new Binder();

    private static boolean isLocalBinder(IBinder binder) {
        return !(binder instanceof BinderProxy);
    }

    private static SurfaceSyncGroup getSurfaceSyncGroup(ISurfaceSyncGroup iSurfaceSyncGroup) {
        if (iSurfaceSyncGroup instanceof SurfaceSyncGroup) {
            return (SurfaceSyncGroup) iSurfaceSyncGroup;
        }
        return null;
    }

    /**
     * @hide
     */
    public static void setTransactionFactory(Supplier<Transaction> transactionFactory) {
        sTransactionFactory = transactionFactory;
    }

    /**
     * Starts a sync and will automatically apply the final, merged transaction.
     */
    public SurfaceSyncGroup(String name) {
        this(name, transaction -> {
            if (transaction != null) {
                if (DEBUG) {
                    Log.d(TAG, "Applying transaction " + transaction);
                }
                transaction.apply();
            }
        });
    }

    /**
     * Creates a sync.
     *
     * @param transactionReadyConsumer The complete callback that contains the syncId and
     *                                 transaction with all the sync data merged. The Transaction
     *                                 passed back can be null.
     *                                 <p>
     *                                 NOTE: Only should be used by ViewRootImpl
     * @hide
     */
    public SurfaceSyncGroup(String name, Consumer<Transaction> transactionReadyConsumer) {
        // sCounter is a way to give the SurfaceSyncGroup a unique name even if the name passed in
        // is not.
        // Avoid letting the count get too big so just reset to 0. It's unlikely that we'll have
        // more than MAX_COUNT active syncs that have overlapping names
        if (sCounter.get() >= MAX_COUNT) {
            sCounter.set(0);
        }

        mName = name + "#" + sCounter.getAndIncrement();

        mTransactionReadyConsumer = (transaction) -> {
            if (DEBUG && transaction != null) {
                Log.d(TAG, "Sending non null transaction " + transaction + " to callback for "
                        + mName);
            }
            Trace.instant(Trace.TRACE_TAG_VIEW,
                    "Final TransactionCallback with " + transaction + " for " + mName);
            transactionReadyConsumer.accept(transaction);
            synchronized (mLock) {
                // If there's a registered listener with WMS, that means we aren't actually complete
                // until WMS notifies us that the parent has completed.
                if (mSurfaceSyncGroupCompletedListener == null) {
                    invokeSyncCompleteListeners();
                }
            }
        };

        Trace.instant(Trace.TRACE_TAG_VIEW, "new SurfaceSyncGroup " + mName);

        if (DEBUG) {
            Log.d(TAG, "setupSync " + mName + " " + Debug.getCallers(2));
        }
    }

    @GuardedBy("mLock")
    private void invokeSyncCompleteListeners() {
        mSyncCompleteCallbacks.forEach(
                executorRunnablePair -> executorRunnablePair.first.execute(
                        executorRunnablePair.second));
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
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "markSyncReady " + mName);
        synchronized (mLock) {
            if (mHasWMSync) {
                try {
                    WindowManagerGlobal.getWindowManagerService().markSurfaceSyncGroupReady(mToken);
                } catch (RemoteException e) {
                }
            }
            mSyncReady = true;
            checkIfSyncIsComplete();
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
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
        SurfaceSyncGroup surfaceSyncGroup = new SurfaceSyncGroup(surfaceView.getName());
        if (addToSync(surfaceSyncGroup, false /* parentSyncGroupMerge */)) {
            frameCallbackConsumer.accept(() -> surfaceView.syncNextFrame(transaction -> {
                surfaceSyncGroup.addTransactionToSync(transaction);
                surfaceSyncGroup.markSyncReady();
            }));
            return true;
        }
        return false;
    }

    /**
     * Add an AttachedSurfaceControl to a sync set.
     *
     * @param viewRoot The viewRoot that will be add to the sync set.
     * @return true if the View was successfully added to the SyncGroup, false otherwise.
     * @see #addToSync(AttachedSurfaceControl, Runnable)
     */
    @UiThread
    public boolean addToSync(@Nullable AttachedSurfaceControl viewRoot) {
        return addToSync(viewRoot, null /* runnable */);
    }

    /**
     * Add an AttachedSurfaceControl to a sync set. The AttachedSurfaceControl will pause rendering
     * to ensure the runnable can be invoked and the sync picks up the frame that contains the
     * changes.
     *
     * @param viewRoot The viewRoot that will be add to the sync set.
     * @param runnable The runnable to be invoked before adding to the sync group.
     * @return true if the View was successfully added to the SyncGroup, false otherwise.
     * @see #addToSync(AttachedSurfaceControl)
     */
    @UiThread
    public boolean addToSync(@Nullable AttachedSurfaceControl viewRoot,
            @Nullable Runnable runnable) {
        if (viewRoot == null) {
            return false;
        }
        SurfaceSyncGroup surfaceSyncGroup = viewRoot.getOrCreateSurfaceSyncGroup();
        if (surfaceSyncGroup == null) {
            return false;
        }

        return addToSync(surfaceSyncGroup, false /* parentSyncGroupMerge */, runnable);
    }

    /**
     * Helper method to add a SurfaceControlViewHost.SurfacePackage to the sync group. This will
     * get the SurfaceSyncGroup from the SurfacePackage, which will pause rendering for the
     * SurfaceControlViewHost. The runnable will be invoked to allow the host to update the SCVH
     * in a synchronized way. Finally, it will add the SCVH to the SurfaceSyncGroup and unpause
     * rendering in the SCVH, allowing the changes to get picked up and included in the sync.
     *
     * @param surfacePackage The SurfacePackage that should be synced
     * @param runnable       The Runnable that's invoked before getting the frame to sync.
     * @return true if the SCVH was successfully added to the current SyncGroup, false
     * otherwise.
     */
    public boolean addToSync(@NonNull SurfaceControlViewHost.SurfacePackage surfacePackage,
            @Nullable Runnable runnable) {
        ISurfaceSyncGroup surfaceSyncGroup;
        try {
            surfaceSyncGroup = surfacePackage.getRemoteInterface().getSurfaceSyncGroup();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to add SurfaceControlViewHost to SurfaceSyncGroup");
            return false;
        }

        if (surfaceSyncGroup == null) {
            Log.e(TAG, "Failed to add SurfaceControlViewHost to SurfaceSyncGroup. "
                    + "SCVH returned null SurfaceSyncGroup");
            return false;
        }
        return addToSync(surfaceSyncGroup, false /* parentSyncGroupMerge */, runnable);
    }

    @Override
    public boolean addToSync(ISurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge) {
        return addToSync(surfaceSyncGroup, parentSyncGroupMerge, null);
    }

    /**
     * Add a {@link SurfaceSyncGroup} to a sync set. The sync set will wait for all
     * SyncableSurfaces to complete before notifying.
     *
     * @param surfaceSyncGroup     A SyncableSurface that implements how to handle syncing
     *                             buffers.
     * @param parentSyncGroupMerge true if the ISurfaceSyncGroup is added because its child was
     *                             added to a new SurfaceSyncGroup. That would require the code to
     *                             call newParent.addToSync(oldParent). When this occurs, we need to
     *                             reverse the merge order because the oldParent should always be
     *                             considered older than any other SurfaceSyncGroups.
     * @param runnable             The Runnable that's invoked before adding the SurfaceSyncGroup
     * @return true if the SyncGroup was successfully added to the current SyncGroup, false
     * otherwise.
     */
    public boolean addToSync(ISurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge,
            @Nullable Runnable runnable) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                "addToSync token=" + mToken.hashCode() + " parent=" + mName);
        synchronized (mLock) {
            if (mSyncReady) {
                Log.w(TAG, "Trying to add to sync when already marked as ready " + mName);
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                return false;
            }
        }

        if (runnable != null) {
            runnable.run();
        }

        if (isLocalBinder(surfaceSyncGroup.asBinder())) {
            boolean didAddLocalSync = addLocalSync(surfaceSyncGroup, parentSyncGroupMerge);
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            return didAddLocalSync;
        }

        synchronized (mLock) {
            if (!mHasWMSync) {
                // We need to add a signal into WMS since WMS will be creating a new parent
                // SurfaceSyncGroup. When the parent SSG in WMS completes, only then do we
                // notify the registered listeners that the entire SurfaceSyncGroup is complete.
                // This is because the callers don't realize that when adding a different process
                // to this SSG, it isn't actually adding to this SSG and really just creating a
                // link in WMS. Because of this, the callers would expect the complete listeners
                // to only be called when everything, including the other process's
                // SurfaceSyncGroups, have completed. Only WMS has that info so we need to send the
                // listener to WMS when we set up a server side sync.
                mSurfaceSyncGroupCompletedListener = new ISurfaceSyncGroupCompletedListener.Stub() {
                    @Override
                    public void onSurfaceSyncGroupComplete() {
                        synchronized (mLock) {
                            invokeSyncCompleteListeners();
                        }
                    }
                };
                if (!addSyncToWm(mToken, false /* parentSyncGroupMerge */,
                        mSurfaceSyncGroupCompletedListener)) {
                    mSurfaceSyncGroupCompletedListener = null;
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                    return false;
                }
                mHasWMSync = true;
            }
        }

        try {
            surfaceSyncGroup.onAddedToSyncGroup(mToken, parentSyncGroupMerge);
        } catch (RemoteException e) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            return false;
        }

        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        return true;
    }

    @Override
    public final boolean onAddedToSyncGroup(IBinder parentSyncGroupToken,
            boolean parentSyncGroupMerge) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                "onAddedToSyncGroup token=" + parentSyncGroupToken.hashCode() + " child=" + mName);
        boolean didAdd = addSyncToWm(parentSyncGroupToken, parentSyncGroupMerge, null);
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        return didAdd;
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

    /**
     * Invoked when the SurfaceSyncGroup has been added to another SurfaceSyncGroup and is ready
     * to proceed.
     */
    public void onSyncReady() {
    }

    private boolean addSyncToWm(IBinder token, boolean parentSyncGroupMerge,
            @Nullable ISurfaceSyncGroupCompletedListener surfaceSyncGroupCompletedListener) {
        try {
            if (DEBUG) {
                Log.d(TAG, "Attempting to add remote sync to " + mName
                        + ". Setting up Sync in WindowManager.");
            }
            Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                    "addSyncToWm=" + token.hashCode() + " group=" + mName);
            AddToSurfaceSyncGroupResult addToSyncGroupResult = new AddToSurfaceSyncGroupResult();
            if (!WindowManagerGlobal.getWindowManagerService().addToSurfaceSyncGroup(token,
                    parentSyncGroupMerge, surfaceSyncGroupCompletedListener,
                    addToSyncGroupResult)) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                return false;
            }

            setTransactionCallbackFromParent(addToSyncGroupResult.mParentSyncGroup,
                    addToSyncGroupResult.mTransactionReadyCallback);
        } catch (RemoteException e) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            return false;
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        return true;
    }

    private boolean addLocalSync(ISurfaceSyncGroup childSyncToken, boolean parentSyncGroupMerge) {
        if (DEBUG) {
            Log.d(TAG, "Adding local sync " + mName);
        }

        SurfaceSyncGroup childSurfaceSyncGroup = getSurfaceSyncGroup(childSyncToken);
        if (childSurfaceSyncGroup == null) {
            Log.e(TAG, "Trying to add a local sync that's either not valid or not from the"
                    + " local process=" + childSyncToken);
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                "addLocalSync=" + childSurfaceSyncGroup.mName + " parent=" + mName);
        ITransactionReadyCallback callback =
                createTransactionReadyCallback(parentSyncGroupMerge);

        if (callback == null) {
            return false;
        }

        childSurfaceSyncGroup.setTransactionCallbackFromParent(this, callback);
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        return true;
    }

    private void setTransactionCallbackFromParent(ISurfaceSyncGroup parentSyncGroup,
            ITransactionReadyCallback transactionReadyCallback) {
        if (DEBUG) {
            Log.d(TAG, "setTransactionCallbackFromParent " + mName);
        }
        boolean finished = false;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                "setTransactionCallbackFromParent " + mName + " callback="
                        + transactionReadyCallback.hashCode());
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
                        Log.d(TAG, "Trying to add to " + parentSyncGroup
                                + " but already part of sync group " + mParentSyncGroup + " "
                                + mName);
                    }
                    try {
                        parentSyncGroup.addToSync(mParentSyncGroup,
                                true /* parentSyncGroupMerge */);
                    } catch (RemoteException e) {
                    }
                }

                if (DEBUG && mParentSyncGroup == parentSyncGroup) {
                    Log.d(TAG, "Added to parent that was already the parent");
                }

                Consumer<Transaction> lastCallback = mTransactionReadyConsumer;
                mParentSyncGroup = parentSyncGroup;
                mTransactionReadyConsumer = (transaction) -> {
                    Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                            "transactionReadyCallback " + mName + " callback="
                                    + transactionReadyCallback.hashCode());
                    lastCallback.accept(null);

                    try {
                        transactionReadyCallback.onTransactionReady(transaction);
                    } catch (RemoteException e) {
                        transaction.apply();
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                };
            }
        }

        // Invoke the callback outside of the lock when the SurfaceSyncGroup being added was already
        // complete.
        if (finished) {
            try {
                transactionReadyCallback.onTransactionReady(null);
            } catch (RemoteException e) {
            }
        } else {
            onSyncReady();
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    public String getName() {
        return mName;
    }

    @GuardedBy("mLock")
    private void checkIfSyncIsComplete() {
        if (mFinished) {
            if (DEBUG) {
                Log.d(TAG, "SurfaceSyncGroup=" + mName + " is already complete");
            }
            mTransaction.apply();
            return;
        }

        Trace.instant(Trace.TRACE_TAG_VIEW,
                "checkIfSyncIsComplete " + mName + " mSyncReady=" + mSyncReady + " mPendingSyncs="
                        + mPendingSyncs.size());

        if (!mSyncReady || !mPendingSyncs.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "SurfaceSyncGroup=" + mName + " is not complete. mSyncReady="
                        + mSyncReady + " mPendingSyncs=" + mPendingSyncs.size());
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Successfully finished sync id=" + mName);
        }
        mTransactionReadyConsumer.accept(mTransaction);
        mFinished = true;
    }

    /**
     * Create an {@link ITransactionReadyCallback} that the current SurfaceSyncGroup will wait on
     * before completing. The caller must ensure that the
     * {@link ITransactionReadyCallback#onTransactionReady(Transaction)} in order for this
     * SurfaceSyncGroup to complete.
     *
     * @param parentSyncGroupMerge true if the ISurfaceSyncGroup is added because its child was
     *                             added to a new SurfaceSyncGroup. That would require the code to
     *                             call newParent.addToSync(oldParent). When this occurs, we need to
     *                             reverse the merge order because the oldParent should always be
     *                             considered older than any other SurfaceSyncGroups.
     */
    public ITransactionReadyCallback createTransactionReadyCallback(boolean parentSyncGroupMerge) {
        if (DEBUG) {
            Log.d(TAG, "createTransactionReadyCallback " + mName);
        }
        ITransactionReadyCallback transactionReadyCallback =
                new ITransactionReadyCallback.Stub() {
                    @Override
                    public void onTransactionReady(Transaction t) {
                        synchronized (mLock) {
                            if (t != null) {
                                // When an older parent sync group is added due to a child syncGroup
                                // getting added to multiple groups, we need to maintain merge order
                                // so the older parentSyncGroup transactions are overwritten by
                                // anything in the newer parentSyncGroup.
                                if (parentSyncGroupMerge) {
                                    t.merge(mTransaction);
                                }
                                mTransaction.merge(t);
                            }
                            mPendingSyncs.remove(this);
                            Trace.instant(Trace.TRACE_TAG_VIEW,
                                    "onTransactionReady group=" + mName + " callback="
                                            + hashCode());
                            checkIfSyncIsComplete();
                        }
                    }
                };

        synchronized (mLock) {
            if (mSyncReady) {
                Log.e(TAG, "Sync " + mName
                        + " was already marked as ready. No more SurfaceSyncGroups can be added.");
                return null;
            }
            mPendingSyncs.add(transactionReadyCallback);
            Trace.instant(Trace.TRACE_TAG_VIEW,
                    "createTransactionReadyCallback " + mName + " mPendingSyncs="
                            + mPendingSyncs.size() + " transactionReady="
                            + transactionReadyCallback.hashCode());
        }

        return transactionReadyCallback;
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
