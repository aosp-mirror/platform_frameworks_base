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
 * A way for data to be gathered so multiple surfaces can be synced. This is intended to be
 * used with AttachedSurfaceControl, SurfaceView, and SurfaceControlViewHost. This allows different
 * parts of the system to synchronize different surfaces themselves without having to manage timing
 * of different rendering threads.
 * This will also allow synchronization of surfaces across multiple processes. The caller can add
 * SurfaceControlViewHosts from another process to the SurfaceSyncGroup in a different process
 * and this clas will ensure all the surfaces are ready before applying everything together.
 * </p>
 * see the <a href="https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/window/SurfaceSyncGroup.md">SurfaceSyncGroup documentation</a>
 * </p>
 */
public final class SurfaceSyncGroup {
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
     * @hide
     */
    public final ISurfaceSyncGroup mISurfaceSyncGroup = new ISurfaceSyncGroupImpl();

    @GuardedBy("mLock")
    private Runnable mAddedToSyncListener;

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
        if (iSurfaceSyncGroup instanceof ISurfaceSyncGroupImpl) {
            return ((ISurfaceSyncGroupImpl) iSurfaceSyncGroup).getSurfaceSyncGroup();
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
     *
     * @param name Used for identifying and debugging.
     */
    public SurfaceSyncGroup(@NonNull String name) {
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
     * @param name                     Used for identifying and debugging.
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
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.instant(Trace.TRACE_TAG_VIEW,
                        "Final TransactionCallback with " + transaction + " for " + mName);
            }
            transactionReadyConsumer.accept(transaction);
            synchronized (mLock) {
                // If there's a registered listener with WMS, that means we aren't actually complete
                // until WMS notifies us that the parent has completed.
                if (mSurfaceSyncGroupCompletedListener == null) {
                    invokeSyncCompleteCallbacks();
                }
            }
        };

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.instant(Trace.TRACE_TAG_VIEW, "new SurfaceSyncGroup " + mName);
        }

        if (DEBUG) {
            Log.d(TAG, "setupSync " + mName + " " + Debug.getCallers(2));
        }
    }

    @GuardedBy("mLock")
    private void invokeSyncCompleteCallbacks() {
        mSyncCompleteCallbacks.forEach(
                executorRunnablePair -> executorRunnablePair.first.execute(
                        executorRunnablePair.second));
    }

    /**
     * Add a {@link Runnable} to be executed when the sync completes.
     *
     * @param executor The Executor to invoke the Runnable on
     * @param runnable The Runnable to get called
     * @hide
     */
    public void addSyncCompleteCallback(Executor executor, Runnable runnable) {
        synchronized (mLock) {
            mSyncCompleteCallbacks.add(new Pair<>(executor, runnable));
        }
    }

    /**
     * Mark the SurfaceSyncGroup as ready to complete. No more data can be added to this
     * SurfaceSyncGroup.
     * <p>
     * Once the SurfaceSyncGroup is marked as ready, it will be able to complete once all child
     * SurfaceSyncGroup have completed their sync.
     */
    public void markSyncReady() {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "markSyncReady " + mName);
        }
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
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    /**
     * Add a SurfaceView to a SurfaceSyncGroup. This requires the caller to notify the start
     * and finish drawing in order to sync since the client owns the rendering of the SurfaceView.
     *
     * @param surfaceView           The SurfaceView to add to the sync.
     * @param frameCallbackConsumer The callback that's invoked to allow the caller to notify
     *                              SurfaceSyncGroup when the SurfaceView has started drawing.
     * @return true if the SurfaceView was successfully added to the SyncGroup, false otherwise.
     * @hide
     */
    @UiThread
    public boolean add(SurfaceView surfaceView,
            Consumer<SurfaceViewFrameCallback> frameCallbackConsumer) {
        SurfaceSyncGroup surfaceSyncGroup = new SurfaceSyncGroup(surfaceView.getName());
        if (add(surfaceSyncGroup.mISurfaceSyncGroup, false /* parentSyncGroupMerge */,
                null /* runnable */)) {
            frameCallbackConsumer.accept(() -> surfaceView.syncNextFrame(transaction -> {
                surfaceSyncGroup.addTransaction(transaction);
                surfaceSyncGroup.markSyncReady();
            }));
            return true;
        }
        return false;
    }

    /**
     * Add an AttachedSurfaceControl to the SurfaceSyncGroup. The AttachedSurfaceControl will pause
     * rendering to ensure the runnable can be invoked and that the sync picks up the frame that
     * contains the changes.
     *
     * @param attachedSurfaceControl The AttachedSurfaceControl that will be add to this
     *                               SurfaceSyncGroup.
     * @param runnable               This is run on the same thread that the call was made on, but
     *                               after the rendering is paused and before continuing to render
     *                               the next frame. This method will not return until the
     *                               execution of the runnable completes. This can be used to make
     *                               changes to the AttachedSurfaceControl, ensuring that the
     *                               changes are included in the sync.
     * @return true if the AttachedSurfaceControl was successfully added to the SurfaceSyncGroup,
     * false otherwise.
     */
    @UiThread
    public boolean add(@Nullable AttachedSurfaceControl attachedSurfaceControl,
            @Nullable Runnable runnable) {
        if (attachedSurfaceControl == null) {
            return false;
        }
        SurfaceSyncGroup surfaceSyncGroup = attachedSurfaceControl.getOrCreateSurfaceSyncGroup();
        if (surfaceSyncGroup == null) {
            return false;
        }

        return add(surfaceSyncGroup, runnable);
    }

    /**
     * Add a SurfaceControlViewHost.SurfacePackage to the SurfaceSyncGroup. This will
     * get the SurfaceSyncGroup from the SurfacePackage, which will pause rendering for the
     * SurfaceControlViewHost. The runnable will be invoked to allow the host to update the SCVH
     * in a synchronized way. Finally, it will add the SCVH to the SurfaceSyncGroup and unpause
     * rendering in the SCVH, allowing the changes to get picked up and included in the sync.
     *
     * @param surfacePackage The SurfacePackage that will be added to this SurfaceSyncGroup.
     * @param runnable       This is run on the same thread that the call was made on, but
     *                       after the rendering is paused and before continuing to render
     *                       the next frame. This method will not return until the
     *                       execution of the runnable completes. This can be used to make
     *                       changes to the SurfaceControlViewHost, ensuring that the
     *                       changes are included in the sync.
     * @return true if the SurfaceControlViewHost was successfully added to the current
     * SurfaceSyncGroup, false otherwise.
     */
    public boolean add(@NonNull SurfaceControlViewHost.SurfacePackage surfacePackage,
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
        return add(surfaceSyncGroup, false /* parentSyncGroupMerge */, runnable);
    }

    /**
     * Add a SurfaceSyncGroup to the current SurfaceSyncGroup.
     *
     * @param surfaceSyncGroup The SurfaceSyncGroup that will be added to this SurfaceSyncGroup.
     * @param runnable         This is run on the same thread that the call was made on, This
     *                         method will not return until the execution of the runnable
     *                         completes. This can be used to make changes to the SurfaceSyncGroup,
     *                         ensuring that the changes are included in the sync.
     * @return true if the requested SurfaceSyncGroup was successfully added to the
     * SurfaceSyncGroup, false otherwise.
     * @hide
     */
    public boolean add(@NonNull SurfaceSyncGroup surfaceSyncGroup,
            @Nullable Runnable runnable) {
        return add(surfaceSyncGroup.mISurfaceSyncGroup, false /* parentSyncGroupMerge */,
                runnable);
    }

    /**
     * Add a {@link ISurfaceSyncGroup} to a SurfaceSyncGroup.
     *
     * @param surfaceSyncGroup     An ISyncableSurface that will be added to this SurfaceSyncGroup.
     * @param parentSyncGroupMerge true if the ISurfaceSyncGroup is added because its child was
     *                             added to a new SurfaceSyncGroup. That would require the code to
     *                             call newParent.addToSync(oldParent). When this occurs, we need to
     *                             reverse the merge order because the oldParent should always be
     *                             considered older than any other SurfaceSyncGroups.
     * @param runnable             The Runnable that's invoked before adding the SurfaceSyncGroup
     * @return true if the SyncGroup was successfully added to the current SyncGroup, false
     * otherwise.
     * @hide
     */
    public boolean add(ISurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge,
            @Nullable Runnable runnable) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                    "addToSync token=" + mToken.hashCode() + " parent=" + mName);
        }
        synchronized (mLock) {
            if (mSyncReady) {
                Log.w(TAG, "Trying to add to sync when already marked as ready " + mName);
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
                return false;
            }
        }

        if (runnable != null) {
            runnable.run();
        }

        if (isLocalBinder(surfaceSyncGroup.asBinder())) {
            boolean didAddLocalSync = addLocalSync(surfaceSyncGroup, parentSyncGroupMerge);
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
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
                            invokeSyncCompleteCallbacks();
                        }
                    }
                };
                if (!addSyncToWm(mToken, false /* parentSyncGroupMerge */,
                        mSurfaceSyncGroupCompletedListener)) {
                    mSurfaceSyncGroupCompletedListener = null;
                    if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                    }
                    return false;
                }
                mHasWMSync = true;
            }
        }

        try {
            surfaceSyncGroup.onAddedToSyncGroup(mToken, parentSyncGroupMerge);
        } catch (RemoteException e) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return false;
        }

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        return true;
    }

    /**
     * Add a Transaction to this SurfaceSyncGroup. This allows the caller to provide other info that
     * should be synced with the other transactions in this SurfaceSyncGroup.
     *
     * @param transaction The transaction to add to the SurfaceSyncGroup.
     */
    public void addTransaction(@NonNull Transaction transaction) {
        synchronized (mLock) {
            mTransaction.merge(transaction);
        }
    }

    /**
     * Add a Runnable to be invoked when the SurfaceSyncGroup has been added to another
     * SurfaceSyncGroup. This is useful to know when it's safe to proceed rendering.
     *
     * @hide
     */
    public void setAddedToSyncListener(Runnable addedToSyncListener) {
        synchronized (mLock) {
            mAddedToSyncListener = addedToSyncListener;
        }
    }

    private boolean addSyncToWm(IBinder token, boolean parentSyncGroupMerge,
            @Nullable ISurfaceSyncGroupCompletedListener surfaceSyncGroupCompletedListener) {
        try {
            if (DEBUG) {
                Log.d(TAG, "Attempting to add remote sync to " + mName
                        + ". Setting up Sync in WindowManager.");
            }
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                        "addSyncToWm=" + token.hashCode() + " group=" + mName);
            }
            AddToSurfaceSyncGroupResult addToSyncGroupResult = new AddToSurfaceSyncGroupResult();
            if (!WindowManagerGlobal.getWindowManagerService().addToSurfaceSyncGroup(token,
                    parentSyncGroupMerge, surfaceSyncGroupCompletedListener,
                    addToSyncGroupResult)) {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
                return false;
            }

            setTransactionCallbackFromParent(addToSyncGroupResult.mParentSyncGroup,
                    addToSyncGroupResult.mTransactionReadyCallback);
        } catch (RemoteException e) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return false;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
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

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                    "addLocalSync=" + childSurfaceSyncGroup.mName + " parent=" + mName);
        }
        ITransactionReadyCallback callback =
                createTransactionReadyCallback(parentSyncGroupMerge);

        if (callback == null) {
            return false;
        }

        childSurfaceSyncGroup.setTransactionCallbackFromParent(mISurfaceSyncGroup, callback);
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        return true;
    }

    private void setTransactionCallbackFromParent(ISurfaceSyncGroup parentSyncGroup,
            ITransactionReadyCallback transactionReadyCallback) {
        if (DEBUG) {
            Log.d(TAG, "setTransactionCallbackFromParent " + mName);
        }

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                    "setTransactionCallbackFromParent " + mName + " callback="
                            + transactionReadyCallback.hashCode());
        }

        boolean finished = false;
        Runnable addedToSyncListener = null;
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
                    if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                        Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                                "transactionReadyCallback " + mName + " callback="
                                        + transactionReadyCallback.hashCode());
                    }
                    lastCallback.accept(null);

                    try {
                        transactionReadyCallback.onTransactionReady(transaction);
                    } catch (RemoteException e) {
                        transaction.apply();
                    }
                    if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                    }
                };
                addedToSyncListener = mAddedToSyncListener;
            }
        }

        // Invoke the callback outside of the lock when the SurfaceSyncGroup being added was already
        // complete.
        if (finished) {
            try {
                transactionReadyCallback.onTransactionReady(null);
            } catch (RemoteException e) {
            }
        } else if (addedToSyncListener != null) {
            addedToSyncListener.run();
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    /**
     * @hide
     */
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

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.instant(Trace.TRACE_TAG_VIEW,
                    "checkIfSyncIsComplete " + mName + " mSyncReady=" + mSyncReady
                            + " mPendingSyncs=" + mPendingSyncs.size());
        }

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
     * {@link ITransactionReadyCallback#onTransactionReady(Transaction)} is called in order for this
     * SurfaceSyncGroup to complete.
     *
     * @param parentSyncGroupMerge true if the ISurfaceSyncGroup is added because its child was
     *                             added to a new SurfaceSyncGroup. That would require the code to
     *                             call newParent.addToSync(oldParent). When this occurs, we need to
     *                             reverse the merge order because the oldParent should always be
     *                             considered older than any other SurfaceSyncGroups.
     * @hide
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
                            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                                Trace.instant(Trace.TRACE_TAG_VIEW,
                                        "onTransactionReady group=" + mName + " callback="
                                                + hashCode());
                            }
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
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.instant(Trace.TRACE_TAG_VIEW,
                        "createTransactionReadyCallback " + mName + " mPendingSyncs="
                                + mPendingSyncs.size() + " transactionReady="
                                + transactionReadyCallback.hashCode());
            }
        }

        return transactionReadyCallback;
    }

    private class ISurfaceSyncGroupImpl extends ISurfaceSyncGroup.Stub {
        @Override
        public boolean onAddedToSyncGroup(IBinder parentSyncGroupToken,
                boolean parentSyncGroupMerge) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                        "onAddedToSyncGroup token=" + parentSyncGroupToken.hashCode() + " child="
                                + mName);
            }
            boolean didAdd = addSyncToWm(parentSyncGroupToken, parentSyncGroupMerge, null);
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return didAdd;
        }

        @Override
        public boolean addToSync(ISurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge) {
            return SurfaceSyncGroup.this.add(surfaceSyncGroup, parentSyncGroupMerge,
                    null /* runnable */);
        }

        SurfaceSyncGroup getSurfaceSyncGroup() {
            return SurfaceSyncGroup.this;
        }
    }

    /**
     * A frame callback that is used to synchronize SurfaceViews. The owner of the SurfaceView must
     * implement onFrameStarted when trying to sync the SurfaceView. This is to ensure the sync
     * knows when the frame is ready to add to the sync.
     *
     * @hide
     */
    public interface SurfaceViewFrameCallback {
        /**
         * Called when the SurfaceView is going to render a frame
         */
        void onFrameStarted();
    }
}
