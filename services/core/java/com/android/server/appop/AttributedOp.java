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

package com.android.server.appop;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OnOpStartedListener.START_TYPE_RESUMED;
import static android.app.AppOpsManager.makeKey;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;
import android.util.Pools;
import android.util.Slog;

import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

final class AttributedOp {
    private final @NonNull AppOpsService mAppOpsService;
    public final @Nullable String tag;
    public final @NonNull String persistentDeviceId;
    public final @NonNull AppOpsService.Op parent;

    /**
     * Last successful accesses (noteOp + finished startOp) for each uidState/opFlag combination
     *
     * <p>Key is {@link AppOpsManager#makeKey}
     */
    // TODO(b/248108338)
    // @GuardedBy("mAppOpsService")
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> mAccessEvents;

    /**
     * Last rejected accesses for each uidState/opFlag combination
     *
     * <p>Key is {@link AppOpsManager#makeKey}
     */
    // TODO(b/248108338)
    // @GuardedBy("mAppOpsService")
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> mRejectEvents;

    /**
     * Currently in progress startOp events
     *
     * <p>Key is clientId
     */
    // TODO(b/248108338)
    // @GuardedBy("mAppOpsService")
    @Nullable ArrayMap<IBinder, InProgressStartOpEvent> mInProgressEvents;

    /**
     * Currently paused startOp events
     *
     * <p>Key is clientId
     */
    // TODO(b/248108338)
    // @GuardedBy("mAppOpsService")
    @Nullable ArrayMap<IBinder, InProgressStartOpEvent> mPausedInProgressEvents;

    AttributedOp(@NonNull AppOpsService appOpsService, @Nullable String tag,
            @NonNull String persistentDeviceId, @NonNull AppOpsService.Op parent) {
        mAppOpsService = appOpsService;
        this.tag = tag;
        this.persistentDeviceId = persistentDeviceId;
        this.parent = parent;
    }

    /**
     * Update state when noteOp was rejected or startOp->finishOp event finished
     *
     * @param proxyUid            The uid of the proxy
     * @param proxyPackageName    The package name of the proxy
     * @param proxyAttributionTag The attributionTag in the proxies package
     * @param proxyDeviceId       The device Id of the proxy
     * @param uidState            UID state of the app noteOp/startOp was called for
     * @param flags               OpFlags of the call
     */
    public void accessed(int proxyUid, @Nullable String proxyPackageName,
            @Nullable String proxyAttributionTag, @Nullable String proxyDeviceId,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags) {
        long accessTime = System.currentTimeMillis();
        accessed(accessTime, -1, proxyUid, proxyPackageName, proxyAttributionTag, proxyDeviceId,
                uidState, flags);

        mAppOpsService.mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid,
                parent.packageName, tag, uidState, flags, accessTime,
                AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
    }

    /**
     * Add an access that was previously collected.
     *
     * @param noteTime            The time of the event
     * @param duration            The duration of the event
     * @param proxyUid            The uid of the proxy
     * @param proxyPackageName    The package name of the proxy
     * @param proxyAttributionTag The attributionTag in the proxies package
     * @param proxyDeviceId       The device Id of the proxy
     * @param uidState            UID state of the app noteOp/startOp was called for
     * @param flags               OpFlags of the call
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    public void accessed(long noteTime, long duration, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @Nullable String proxyDeviceId, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags) {
        long key = makeKey(uidState, flags);

        if (mAccessEvents == null) {
            mAccessEvents = new LongSparseArray<>(1);
        }

        AppOpsManager.OpEventProxyInfo proxyInfo = null;
        if (proxyUid != Process.INVALID_UID) {
            proxyInfo = mAppOpsService.mOpEventProxyInfoPool.acquire(proxyUid, proxyPackageName,
                    proxyAttributionTag, proxyDeviceId);
        }

        AppOpsManager.NoteOpEvent existingEvent = mAccessEvents.get(key);
        if (existingEvent != null) {
            existingEvent.reinit(noteTime, duration, proxyInfo,
                    mAppOpsService.mOpEventProxyInfoPool);
        } else {
            mAccessEvents.put(key, new AppOpsManager.NoteOpEvent(noteTime, duration, proxyInfo));
        }
    }

    /**
     * Update state when noteOp/startOp was rejected.
     *
     * @param uidState UID state of the app noteOp is called for
     * @param flags    OpFlags of the call
     */
    public void rejected(@AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags) {
        rejected(System.currentTimeMillis(), uidState, flags);

        mAppOpsService.mHistoricalRegistry.incrementOpRejected(parent.op, parent.uid,
                parent.packageName, tag, uidState, flags);
    }

    /**
     * Add an rejection that was previously collected
     *
     * @param noteTime The time of the event
     * @param uidState UID state of the app noteOp/startOp was called for
     * @param flags    OpFlags of the call
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    public void rejected(long noteTime, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags) {
        long key = makeKey(uidState, flags);

        if (mRejectEvents == null) {
            mRejectEvents = new LongSparseArray<>(1);
        }

        // We do not collect proxy information for rejections yet
        AppOpsManager.NoteOpEvent existingEvent = mRejectEvents.get(key);
        if (existingEvent != null) {
            existingEvent.reinit(noteTime, -1, null, mAppOpsService.mOpEventProxyInfoPool);
        } else {
            mRejectEvents.put(key, new AppOpsManager.NoteOpEvent(noteTime, -1, null));
        }
    }

    /**
     * Update state when start was called
     *
     * @param clientId            Id of the startOp caller
     * @param virtualDeviceId     The virtual device id of the startOp caller
     * @param proxyUid            The UID of the proxy app
     * @param proxyPackageName    The package name of the proxy app
     * @param proxyAttributionTag The attribution tag of the proxy app
     * @param proxyDeviceId       The device id of the proxy app
     * @param uidState            UID state of the app startOp is called for
     * @param flags               The proxy flags
     * @param attributionFlags    The attribution flags associated with this operation.
     * @param attributionChainId  The if of the attribution chain this operations is a part of
     */
    public void started(@NonNull IBinder clientId, int virtualDeviceId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @Nullable String proxyDeviceId, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId) throws RemoteException {
        startedOrPaused(clientId, virtualDeviceId, proxyUid, proxyPackageName, proxyAttributionTag,
                proxyDeviceId, uidState, flags, attributionFlags, attributionChainId, false,
                true);
    }

    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    private void startedOrPaused(@NonNull IBinder clientId, int virtualDeviceId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @Nullable String proxyDeviceId, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId, boolean triggeredByUidStateChange, boolean isStarted)
            throws RemoteException {
        if (!triggeredByUidStateChange && !parent.isRunning() && isStarted) {
            mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, virtualDeviceId, true, attributionFlags,
                    attributionChainId);
        }

        if (isStarted && mInProgressEvents == null) {
            mInProgressEvents = new ArrayMap<>(1);
        } else if (!isStarted && mPausedInProgressEvents == null) {
            mPausedInProgressEvents = new ArrayMap<>(1);
        }
        ArrayMap<IBinder, InProgressStartOpEvent> events = isStarted
                ? mInProgressEvents : mPausedInProgressEvents;

        long startTime = System.currentTimeMillis();
        InProgressStartOpEvent event = events.get(clientId);
        if (event == null) {
            event = mAppOpsService.mInProgressStartOpEventPool.acquire(startTime,
                    SystemClock.elapsedRealtime(), clientId, tag, virtualDeviceId,
                    PooledLambda.obtainRunnable(AppOpsService::onClientDeath, this, clientId),
                    proxyUid, proxyPackageName, proxyAttributionTag, proxyDeviceId, uidState, flags,
                    attributionFlags, attributionChainId);
            events.put(clientId, event);
        } else {
            if (uidState != event.getUidState()) {
                onUidStateChanged(uidState);
            }
        }

        event.mNumUnfinishedStarts++;

        if (isStarted) {
            mAppOpsService.mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid,
                    parent.packageName, tag, uidState, flags, startTime, attributionFlags,
                    attributionChainId);
        }
    }

    public void doForAllInProgressStartOpEvents(Consumer<InProgressStartOpEvent> action) {
        ArrayMap<IBinder, AttributedOp.InProgressStartOpEvent> events = isPaused()
                ? mPausedInProgressEvents : mInProgressEvents;
        if (events == null) {
            return;
        }

        int numStartedOps = events.size();
        ArraySet<IBinder> keys = new ArraySet<>(events.keySet());
        for (int i = 0; i < numStartedOps; i++) {
            action.accept(events.get(keys.valueAt(i)));
        }
    }

    /**
     * Update state when finishOp was called. Will finish started ops, and delete paused ops.
     *
     * @param clientId Id of the finishOp caller
     */
    public void finished(@NonNull IBinder clientId) {
        finished(clientId, false);
    }

    private void finished(@NonNull IBinder clientId, boolean triggeredByUidStateChange) {
        finishOrPause(clientId, triggeredByUidStateChange, false);
    }

    /**
     * Update state when paused or finished is called. If pausing, it records the op as
     * stopping in the HistoricalRegistry, but does not delete it.
     *
     * @param triggeredByUidStateChange If {@code true}, then this method operates as usual, except
     * that {@link AppOpsService#mActiveWatchers} will not be notified. This is currently only
     * used in {@link #onUidStateChanged(int)}, for the purpose of restarting (i.e.,
     * finishing then immediately starting again in the new uid state) the AttributedOp. In this
     * case, the caller is responsible for guaranteeing that either the AttributedOp is started
     * again or all {@link AppOpsService#mActiveWatchers} are notified that the AttributedOp is
     * finished.
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    private void finishOrPause(@NonNull IBinder clientId, boolean triggeredByUidStateChange,
            boolean isPausing) {
        int indexOfToken = isRunning() ? mInProgressEvents.indexOfKey(clientId) : -1;
        if (indexOfToken < 0) {
            finishPossiblyPaused(clientId, isPausing);
            return;
        }

        InProgressStartOpEvent event = mInProgressEvents.valueAt(indexOfToken);
        if (!isPausing) {
            event.mNumUnfinishedStarts--;
        }
        // If we are pausing, create a NoteOpEvent, but don't change the InProgress event
        if (event.mNumUnfinishedStarts == 0 || isPausing) {
            if (!isPausing) {
                event.finish();
                mInProgressEvents.removeAt(indexOfToken);
            }

            if (mAccessEvents == null) {
                mAccessEvents = new LongSparseArray<>(1);
            }

            AppOpsManager.OpEventProxyInfo proxyCopy = event.getProxy() != null
                    ? new AppOpsManager.OpEventProxyInfo(event.getProxy()) : null;

            long accessDurationMillis =
                    SystemClock.elapsedRealtime() - event.getStartElapsedTime();
            AppOpsManager.NoteOpEvent finishedEvent = new AppOpsManager.NoteOpEvent(
                    event.getStartTime(),
                    accessDurationMillis, proxyCopy);
            mAccessEvents.put(makeKey(event.getUidState(), event.getFlags()),
                    finishedEvent);

            mAppOpsService.mHistoricalRegistry.increaseOpAccessDuration(parent.op, parent.uid,
                    parent.packageName, tag, event.getUidState(),
                    event.getFlags(), finishedEvent.getNoteTime(), finishedEvent.getDuration(),
                    event.getAttributionFlags(), event.getAttributionChainId());

            if (!isPausing) {
                mAppOpsService.mInProgressStartOpEventPool.release(event);
                if (mInProgressEvents.isEmpty()) {
                    mInProgressEvents = null;

                    // TODO ntmyren: Also callback for single attribution tag activity changes
                    if (!triggeredByUidStateChange && !parent.isRunning()) {
                        mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op,
                                parent.uid, parent.packageName, tag, event.getVirtualDeviceId(),
                                false, event.getAttributionFlags(), event.getAttributionChainId());
                    }
                }
            }
        }
    }

    // Finish or pause (no-op) an already paused op
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    private void finishPossiblyPaused(@NonNull IBinder clientId, boolean isPausing) {
        if (!isPaused()) {
            Slog.wtf(AppOpsService.TAG, "No ops running or paused");
            return;
        }

        int indexOfToken = mPausedInProgressEvents.indexOfKey(clientId);
        if (indexOfToken < 0) {
            Slog.wtf(AppOpsService.TAG, "No op running or paused for the client");
            return;
        } else if (isPausing) {
            // already paused
            return;
        }

        // no need to record a paused event finishing.
        InProgressStartOpEvent event = mPausedInProgressEvents.valueAt(indexOfToken);
        event.mNumUnfinishedStarts--;
        if (event.mNumUnfinishedStarts == 0) {
            mPausedInProgressEvents.removeAt(indexOfToken);
            mAppOpsService.mInProgressStartOpEventPool.release(event);
            if (mPausedInProgressEvents.isEmpty()) {
                mPausedInProgressEvents = null;
            }
        }
    }

    /**
     * Create an event that will be started, if the op is unpaused.
     */
    public void createPaused(@NonNull IBinder clientId, int virtualDeviceId,
            int proxyUid, @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @Nullable String proxyDeviceId, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId) throws RemoteException {
        startedOrPaused(clientId, virtualDeviceId, proxyUid, proxyPackageName, proxyAttributionTag,
                proxyDeviceId, uidState, flags, attributionFlags, attributionChainId, false,
                false);
    }

    /**
     * Pause all currently started ops. This will create a HistoricalRegistry
     */
    public void pause() {
        if (!isRunning()) {
            return;
        }

        if (mPausedInProgressEvents == null) {
            mPausedInProgressEvents = new ArrayMap<>(1);
        }

        for (int i = 0; i < mInProgressEvents.size(); i++) {
            InProgressStartOpEvent event = mInProgressEvents.valueAt(i);
            mPausedInProgressEvents.put(event.getClientId(), event);
            finishOrPause(event.getClientId(), false, true);

            mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, event.getVirtualDeviceId(), false,
                    event.getAttributionFlags(), event.getAttributionChainId());
        }
        mInProgressEvents = null;
    }

    /**
     * Unpause all currently paused ops. This will reinitialize their start and duration
     * times, but keep all other values the same
     */
    public void resume() {
        if (!isPaused()) {
            return;
        }

        if (mInProgressEvents == null) {
            mInProgressEvents = new ArrayMap<>(mPausedInProgressEvents.size());
        }
        boolean shouldSendActive = !mPausedInProgressEvents.isEmpty()
                && mInProgressEvents.isEmpty();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < mPausedInProgressEvents.size(); i++) {
            InProgressStartOpEvent event = mPausedInProgressEvents.valueAt(i);
            mInProgressEvents.put(event.getClientId(), event);
            event.setStartElapsedTime(SystemClock.elapsedRealtime());
            event.setStartTime(startTime);
            mAppOpsService.mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid,
                    parent.packageName, tag, event.getUidState(), event.getFlags(), startTime,
                    event.getAttributionFlags(), event.getAttributionChainId());
            if (shouldSendActive) {
                mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                        parent.packageName, tag, event.getVirtualDeviceId(), true,
                        event.getAttributionFlags(), event.getAttributionChainId());
            }
            // Note: this always sends MODE_ALLOWED, even if the mode is FOREGROUND
            // TODO ntmyren: figure out how to get the real mode.
            mAppOpsService.scheduleOpStartedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, event.getVirtualDeviceId(), event.getFlags(),
                    MODE_ALLOWED, START_TYPE_RESUMED, event.getAttributionFlags(),
                    event.getAttributionChainId());
        }
        mPausedInProgressEvents = null;
    }

    /**
     * Called in the case the client dies without calling finish first
     *
     * @param clientId The client that died
     */
    void onClientDeath(@NonNull IBinder clientId) {
        synchronized (mAppOpsService) {
            if (!isPaused() && !isRunning()) {
                return;
            }

            ArrayMap<IBinder, InProgressStartOpEvent> events = isPaused()
                    ? mPausedInProgressEvents : mInProgressEvents;
            InProgressStartOpEvent deadEvent = events.get(clientId);
            if (deadEvent != null) {
                deadEvent.mNumUnfinishedStarts = 1;
            }

            finished(clientId);
        }
    }

    /**
     * Notify that the state of the uid changed
     *
     * @param newState The new state
     */
    public void onUidStateChanged(@AppOpsManager.UidState int newState) {
        if (!isPaused() && !isRunning()) {
            return;
        }

        boolean isRunning = isRunning();
        ArrayMap<IBinder, InProgressStartOpEvent> events =
                isRunning ? mInProgressEvents : mPausedInProgressEvents;

        int numInProgressEvents = events.size();
        List<IBinder> binders = new ArrayList<>(events.keySet());
        for (int i = 0; i < numInProgressEvents; i++) {
            InProgressStartOpEvent event = events.get(binders.get(i));

            if (event != null && event.getUidState() != newState) {
                int eventAttributionFlags = event.getAttributionFlags();
                int eventAttributionChainId = event.getAttributionChainId();
                try {
                    // Remove all but one unfinished start count and then call finished() to
                    // remove start event object
                    int numPreviousUnfinishedStarts = event.mNumUnfinishedStarts;
                    event.mNumUnfinishedStarts = 1;
                    AppOpsManager.OpEventProxyInfo proxy = event.getProxy();

                    finished(event.getClientId(), true);

                    // Call started() to add a new start event object and then add the
                    // previously removed unfinished start counts back
                    if (proxy != null) {
                        startedOrPaused(event.getClientId(), event.getVirtualDeviceId(),
                                proxy.getUid(), proxy.getPackageName(), proxy.getAttributionTag(),
                                proxy.getDeviceId(), newState, event.getFlags(),
                                event.getAttributionFlags(), event.getAttributionChainId(), true,
                                isRunning);
                    } else {
                        startedOrPaused(event.getClientId(), event.getVirtualDeviceId(),
                                Process.INVALID_UID, null, null, null,
                                newState, event.getFlags(), event.getAttributionFlags(),
                                event.getAttributionChainId(), true, isRunning);
                    }

                    events = isRunning ? mInProgressEvents : mPausedInProgressEvents;
                    InProgressStartOpEvent newEvent = events.get(binders.get(i));
                    if (newEvent != null) {
                        newEvent.mNumUnfinishedStarts += numPreviousUnfinishedStarts - 1;
                    }
                } catch (RemoteException e) {
                    if (AppOpsService.DEBUG) {
                        Slog.e(AppOpsService.TAG,
                                "Cannot switch to new uidState " + newState);
                    }
                    mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op,
                            parent.uid, parent.packageName, tag, event.getVirtualDeviceId(), false,
                            eventAttributionFlags, eventAttributionChainId);
                }
            }
        }
    }

    /**
     * Combine {@code a} and {@code b} and return the result. The result might be {@code a}
     * or {@code b}. If there is an event for the same key in both the later event is retained.
     */
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> add(
            @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> a,
            @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> b) {
        if (a == null) {
            return b;
        }

        if (b == null) {
            return a;
        }

        int numEventsToAdd = b.size();
        for (int i = 0; i < numEventsToAdd; i++) {
            long keyOfEventToAdd = b.keyAt(i);
            AppOpsManager.NoteOpEvent bEvent = b.valueAt(i);
            AppOpsManager.NoteOpEvent aEvent = a.get(keyOfEventToAdd);

            if (aEvent == null || bEvent.getNoteTime() > aEvent.getNoteTime()) {
                a.put(keyOfEventToAdd, bEvent);
            }
        }

        return a;
    }

    /**
     * Add all data from the {@code opToAdd} to this op.
     *
     * <p>If there is an event for the same key in both the later event is retained.
     * <p>{@code opToAdd} should not be used after this method is called.
     *
     * @param opToAdd The op to add
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    public void add(@NonNull AttributedOp opToAdd) {
        if (opToAdd.isRunning() || opToAdd.isPaused()) {
            ArrayMap<IBinder, InProgressStartOpEvent> ignoredEvents =
                    opToAdd.isRunning()
                            ? opToAdd.mInProgressEvents : opToAdd.mPausedInProgressEvents;
            Slog.w(AppOpsService.TAG, "Ignoring " + ignoredEvents.size() + " app-ops, running: "
                    + opToAdd.isRunning());

            int numInProgressEvents = ignoredEvents.size();
            for (int i = 0; i < numInProgressEvents; i++) {
                InProgressStartOpEvent event = ignoredEvents.valueAt(i);

                event.finish();
                mAppOpsService.mInProgressStartOpEventPool.release(event);
            }
        }

        mAccessEvents = add(mAccessEvents, opToAdd.mAccessEvents);
        mRejectEvents = add(mRejectEvents, opToAdd.mRejectEvents);
    }

    public boolean isRunning() {
        return mInProgressEvents != null && !mInProgressEvents.isEmpty();
    }

    public boolean isPaused() {
        return mPausedInProgressEvents != null && !mPausedInProgressEvents.isEmpty();
    }

    boolean hasAnyTime() {
        return (mAccessEvents != null && mAccessEvents.size() > 0)
                || (mRejectEvents != null && mRejectEvents.size() > 0);
    }

    /**
     * Clone a {@link LongSparseArray} and clone all values.
     */
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> deepClone(
            @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> original) {
        if (original == null) {
            return original;
        }

        int size = original.size();
        LongSparseArray<AppOpsManager.NoteOpEvent> clone = new LongSparseArray<>(size);
        for (int i = 0; i < size; i++) {
            clone.put(original.keyAt(i), new AppOpsManager.NoteOpEvent(original.valueAt(i)));
        }

        return clone;
    }

    @NonNull AppOpsManager.AttributedOpEntry createAttributedOpEntryLocked() {
        LongSparseArray<AppOpsManager.NoteOpEvent> accessEvents = deepClone(mAccessEvents);

        // Add in progress events as access events
        if (isRunning()) {
            long now = SystemClock.elapsedRealtime();
            int numInProgressEvents = mInProgressEvents.size();

            if (accessEvents == null) {
                accessEvents = new LongSparseArray<>(numInProgressEvents);
            }

            for (int i = 0; i < numInProgressEvents; i++) {
                InProgressStartOpEvent event = mInProgressEvents.valueAt(i);

                accessEvents.append(makeKey(event.getUidState(), event.getFlags()),
                        new AppOpsManager.NoteOpEvent(event.getStartTime(),
                                now - event.getStartElapsedTime(),
                                event.getProxy()));
            }
        }

        LongSparseArray<AppOpsManager.NoteOpEvent> rejectEvents = deepClone(mRejectEvents);

        return new AppOpsManager.AttributedOpEntry(parent.op, isRunning(), accessEvents,
                rejectEvents);
    }

    /** A in progress startOp->finishOp event */
    static final class InProgressStartOpEvent implements IBinder.DeathRecipient {
        /** Wall clock time of startOp event (not monotonic) */
        private long mStartTime;

        /** Elapsed time since boot of startOp event */
        private long mStartElapsedTime;

        /** Id of the client that started the event */
        private @NonNull IBinder mClientId;

        /** virtual device id */
        private int mVirtualDeviceId;

        /** The attribution tag for this operation */
        private @Nullable String mAttributionTag;

        /** To call when client dies */
        private @NonNull Runnable mOnDeath;

        /** uidstate used when calling startOp */
        private @AppOpsManager.UidState int mUidState;

        /** Proxy information of the startOp event */
        private @Nullable AppOpsManager.OpEventProxyInfo mProxy;

        /** Proxy flag information */
        private @AppOpsManager.OpFlags int mFlags;

        /** How many times the op was started but not finished yet */
        int mNumUnfinishedStarts;

        /** The attribution flags related to this event */
        private @AppOpsManager.AttributionFlags int mAttributionFlags;

        /** The id of the attribution chain this even is a part of */
        private int mAttributionChainId;

        /**
         * Create a new {@link InProgressStartOpEvent}.
         *
         * @param startTime          The time {@link #startOperation} was called
         * @param startElapsedTime   The elapsed time when {@link #startOperation} was called
         * @param clientId           The client id of the caller of {@link #startOperation}
         * @param attributionTag     The attribution tag for the operation.
         * @param onDeath            The code to execute on client death
         * @param uidState           The uidstate of the app {@link #startOperation} was called for
         * @param attributionFlags   the attribution flags for this operation.
         * @param attributionChainId the unique id of the attribution chain this op is a part of.
         * @param proxy              The proxy information, if {@link #startProxyOperation} was
         *                           called
         * @param flags              The trusted/nontrusted/self flags.
         * @throws RemoteException If the client is dying
         */
        InProgressStartOpEvent(long startTime, long startElapsedTime,
                @NonNull IBinder clientId, int virtualDeviceId, @Nullable String attributionTag,
                @NonNull Runnable onDeath, @AppOpsManager.UidState int uidState,
                @Nullable AppOpsManager.OpEventProxyInfo proxy, @AppOpsManager.OpFlags int flags,
                @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId)
                throws RemoteException {
            mStartTime = startTime;
            mStartElapsedTime = startElapsedTime;
            mClientId = clientId;
            mVirtualDeviceId = virtualDeviceId;
            mAttributionTag = attributionTag;
            mOnDeath = onDeath;
            mUidState = uidState;
            mProxy = proxy;
            mFlags = flags;
            mAttributionFlags = attributionFlags;
            mAttributionChainId = attributionChainId;

            clientId.linkToDeath(this, 0);
        }

        /** Clean up event */
        public void finish() {
            try {
                mClientId.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // Either not linked, or already unlinked. Either way, nothing to do.
            }
        }

        @Override
        public void binderDied() {
            mOnDeath.run();
        }

        /**
         * Reinit existing object with new state.
         *
         * @param startTime          The time {@link #startOperation} was called
         * @param startElapsedTime   The elapsed time when {@link #startOperation} was called
         * @param clientId           The client id of the caller of {@link #startOperation}
         * @param attributionTag     The attribution tag for this operation.
         * @param onDeath            The code to execute on client death
         * @param uidState           The uidstate of the app {@link #startOperation} was called for
         * @param flags              The flags relating to the proxy
         * @param proxy              The proxy information, if {@link #startProxyOperation}
         *                           was called
         * @param attributionFlags   the attribution flags for this operation.
         * @param attributionChainId the unique id of the attribution chain this op is a part of.
         * @param proxyPool          The pool to release
         *                           previous {@link AppOpsManager.OpEventProxyInfo} to
         * @throws RemoteException If the client is dying
         */
        public void reinit(long startTime, long startElapsedTime, @NonNull IBinder clientId,
                @Nullable String attributionTag, int virtualDeviceId, @NonNull Runnable onDeath,
                @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags,
                @Nullable AppOpsManager.OpEventProxyInfo proxy,
                @AppOpsManager.AttributionFlags int attributionFlags,
                int attributionChainId,
                @NonNull Pools.Pool<AppOpsManager.OpEventProxyInfo> proxyPool
        ) throws RemoteException {
            mStartTime = startTime;
            mStartElapsedTime = startElapsedTime;
            mClientId = clientId;
            mAttributionTag = attributionTag;
            mOnDeath = onDeath;
            mVirtualDeviceId = virtualDeviceId;
            mUidState = uidState;
            mFlags = flags;

            if (mProxy != null) {
                proxyPool.release(mProxy);
            }
            mProxy = proxy;
            mAttributionFlags = attributionFlags;
            mAttributionChainId = attributionChainId;

            clientId.linkToDeath(this, 0);
        }

        /** @return Wall clock time of startOp event */
        public long getStartTime() {
            return mStartTime;
        }

        /** @return Elapsed time since boot of startOp event */
        public long getStartElapsedTime() {
            return mStartElapsedTime;
        }

        /** @return Id of the client that started the event */
        public @NonNull IBinder getClientId() {
            return mClientId;
        }

        /** @return uidstate used when calling startOp */
        public @AppOpsManager.UidState int getUidState() {
            return mUidState;
        }

        /** @return proxy tag for the access */
        public @Nullable AppOpsManager.OpEventProxyInfo getProxy() {
            return mProxy;
        }

        /** @return flags used for the access */
        public @AppOpsManager.OpFlags int getFlags() {
            return mFlags;
        }

        /** @return attributoin flags used for the access */
        public @AppOpsManager.AttributionFlags int getAttributionFlags() {
            return mAttributionFlags;
        }

        /** @return attribution chain id for the access */
        public int getAttributionChainId() {
            return mAttributionChainId;
        }

        /** @return virtual device id for the access */
        public int getVirtualDeviceId() {
            return mVirtualDeviceId;
        }

        public void setStartTime(long startTime) {
            mStartTime = startTime;
        }

        public void setStartElapsedTime(long startElapsedTime) {
            mStartElapsedTime = startElapsedTime;
        }
    }

    /**
     * An unsynchronized pool of {@link InProgressStartOpEvent} objects.
     */
    static class InProgressStartOpEventPool extends Pools.SimplePool<InProgressStartOpEvent> {
        private OpEventProxyInfoPool mOpEventProxyInfoPool;

        InProgressStartOpEventPool(OpEventProxyInfoPool opEventProxyInfoPool,
                int maxUnusedPooledObjects) {
            super(maxUnusedPooledObjects);
            this.mOpEventProxyInfoPool = opEventProxyInfoPool;
        }

        InProgressStartOpEvent acquire(long startTime, long elapsedTime, @NonNull IBinder clientId,
                @Nullable String attributionTag, int virtualDeviceId,  @NonNull Runnable onDeath,
                int proxyUid, @Nullable String proxyPackageName,
                @Nullable String proxyAttributionTag, @Nullable String proxyDeviceId,
                @AppOpsManager.UidState int uidState,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.AttributionFlags
                int attributionFlags, int attributionChainId) throws RemoteException {

            InProgressStartOpEvent recycled = acquire();

            AppOpsManager.OpEventProxyInfo proxyInfo = null;
            if (proxyUid != Process.INVALID_UID) {
                proxyInfo = mOpEventProxyInfoPool.acquire(proxyUid, proxyPackageName,
                        proxyAttributionTag, proxyDeviceId);
            }

            if (recycled != null) {
                recycled.reinit(startTime, elapsedTime, clientId, attributionTag, virtualDeviceId,
                        onDeath, uidState, flags, proxyInfo, attributionFlags, attributionChainId,
                        mOpEventProxyInfoPool);
                return recycled;
            }

            return new InProgressStartOpEvent(startTime, elapsedTime, clientId, virtualDeviceId,
                    attributionTag, onDeath, uidState, proxyInfo, flags, attributionFlags,
                    attributionChainId);
        }
    }

    /**
     * An unsynchronized pool of {@link AppOpsManager.OpEventProxyInfo} objects.
     */
    static class OpEventProxyInfoPool extends Pools.SimplePool<AppOpsManager.OpEventProxyInfo> {
        OpEventProxyInfoPool(int maxUnusedPooledObjects) {
            super(maxUnusedPooledObjects);
        }

        AppOpsManager.OpEventProxyInfo acquire(
                @IntRange(from = 0) int uid,
                @Nullable String packageName,
                @Nullable String attributionTag,
                @Nullable String deviceId) {
            AppOpsManager.OpEventProxyInfo recycled = acquire();
            if (recycled != null) {
                recycled.reinit(uid, packageName, attributionTag, deviceId);
                return recycled;
            }

            return new AppOpsManager.OpEventProxyInfo(uid, packageName, attributionTag, deviceId);
        }
    }
}
