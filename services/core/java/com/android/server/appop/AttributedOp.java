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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.List;

final class AttributedOp {
    private final @NonNull AppOpsService mAppOpsService;
    public final @Nullable String tag;
    public final @NonNull AppOpsService.Op parent;

    /**
     * Last successful accesses (noteOp + finished startOp) for each uidState/opFlag combination
     *
     * <p>Key is {@link AppOpsManager#makeKey}
     */
    @GuardedBy("mAppOpsService")
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> mAccessEvents;

    /**
     * Last rejected accesses for each uidState/opFlag combination
     *
     * <p>Key is {@link AppOpsManager#makeKey}
     */
    @GuardedBy("mAppOpsService")
    private @Nullable LongSparseArray<AppOpsManager.NoteOpEvent> mRejectEvents;

    /**
     * Currently in progress startOp events
     *
     * <p>Key is clientId
     */
    @GuardedBy("mAppOpsService")
    @Nullable ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> mInProgressEvents;

    /**
     * Currently paused startOp events
     *
     * <p>Key is clientId
     */
    @GuardedBy("mAppOpsService")
    @Nullable ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> mPausedInProgressEvents;

    AttributedOp(@NonNull AppOpsService appOpsService, @Nullable String tag,
            @NonNull AppOpsService.Op parent) {
        mAppOpsService = appOpsService;
        this.tag = tag;
        this.parent = parent;
    }

    /**
     * Update state when noteOp was rejected or startOp->finishOp event finished
     *
     * @param proxyUid            The uid of the proxy
     * @param proxyPackageName    The package name of the proxy
     * @param proxyAttributionTag the attributionTag in the proxies package
     * @param uidState            UID state of the app noteOp/startOp was called for
     * @param flags               OpFlags of the call
     */
    public void accessed(int proxyUid, @Nullable String proxyPackageName,
            @Nullable String proxyAttributionTag, @AppOpsManager.UidState int uidState,
            @AppOpsManager.OpFlags int flags) {
        long accessTime = System.currentTimeMillis();
        accessed(accessTime, -1, proxyUid, proxyPackageName,
                proxyAttributionTag, uidState, flags);

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
     * @param proxyAttributionTag the attributionTag in the proxies package
     * @param uidState            UID state of the app noteOp/startOp was called for
     * @param flags               OpFlags of the call
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    public void accessed(long noteTime, long duration, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags) {
        long key = makeKey(uidState, flags);

        if (mAccessEvents == null) {
            mAccessEvents = new LongSparseArray<>(1);
        }

        AppOpsManager.OpEventProxyInfo proxyInfo = null;
        if (proxyUid != Process.INVALID_UID) {
            proxyInfo = mAppOpsService.mOpEventProxyInfoPool.acquire(proxyUid, proxyPackageName,
                    proxyAttributionTag);
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
     * @param proxyUid            The UID of the proxy app
     * @param proxyPackageName    The package name of the proxy app
     * @param proxyAttributionTag The attribution tag of the proxy app
     * @param uidState            UID state of the app startOp is called for
     * @param flags               The proxy flags
     * @param attributionFlags    The attribution flags associated with this operation.
     * @param attributionChainId  The if of the attribution chain this operations is a part of.
     */
    public void started(@NonNull IBinder clientId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags,
            @AppOpsManager.AttributionFlags
                    int attributionFlags, int attributionChainId) throws RemoteException {
        started(clientId, proxyUid, proxyPackageName, proxyAttributionTag,
                uidState, flags, /*triggerCallbackIfNeeded*/ true, attributionFlags,
                attributionChainId);
    }

    private void started(@NonNull IBinder clientId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags,
            boolean triggerCallbackIfNeeded, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId) throws RemoteException {
        startedOrPaused(clientId, proxyUid, proxyPackageName,
                proxyAttributionTag, uidState, flags, triggerCallbackIfNeeded,
                /*triggerCallbackIfNeeded*/ true, attributionFlags, attributionChainId);
    }

    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    private void startedOrPaused(@NonNull IBinder clientId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags,
            boolean triggerCallbackIfNeeded, boolean isStarted, @AppOpsManager.AttributionFlags
            int attributionFlags, int attributionChainId) throws RemoteException {
        if (triggerCallbackIfNeeded && !parent.isRunning() && isStarted) {
            mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, true, attributionFlags, attributionChainId);
        }

        if (isStarted && mInProgressEvents == null) {
            mInProgressEvents = new ArrayMap<>(1);
        } else if (!isStarted && mPausedInProgressEvents == null) {
            mPausedInProgressEvents = new ArrayMap<>(1);
        }
        ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> events = isStarted
                ? mInProgressEvents : mPausedInProgressEvents;

        long startTime = System.currentTimeMillis();
        AppOpsService.InProgressStartOpEvent event = events.get(clientId);
        if (event == null) {
            event = mAppOpsService.mInProgressStartOpEventPool.acquire(startTime,
                    SystemClock.elapsedRealtime(), clientId, tag,
                    PooledLambda.obtainRunnable(AppOpsService::onClientDeath, this, clientId),
                    proxyUid, proxyPackageName, proxyAttributionTag, uidState, flags,
                    attributionFlags, attributionChainId);
            events.put(clientId, event);
        } else {
            if (uidState != event.getUidState()) {
                onUidStateChanged(uidState);
            }
        }

        event.numUnfinishedStarts++;

        if (isStarted) {
            mAppOpsService.mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid,
                    parent.packageName, tag, uidState, flags, startTime, attributionFlags,
                    attributionChainId);
        }
    }

    /**
     * Update state when finishOp was called. Will finish started ops, and delete paused ops.
     *
     * @param clientId Id of the finishOp caller
     */
    public void finished(@NonNull IBinder clientId) {
        finished(clientId, true);
    }

    private void finished(@NonNull IBinder clientId, boolean triggerCallbackIfNeeded) {
        finishOrPause(clientId, triggerCallbackIfNeeded, false);
    }

    /**
     * Update state when paused or finished is called. If pausing, it records the op as
     * stopping in the HistoricalRegistry, but does not delete it.
     */
    @SuppressWarnings("GuardedBy") // Lock is held on mAppOpsService
    private void finishOrPause(@NonNull IBinder clientId, boolean triggerCallbackIfNeeded,
            boolean isPausing) {
        int indexOfToken = isRunning() ? mInProgressEvents.indexOfKey(clientId) : -1;
        if (indexOfToken < 0) {
            finishPossiblyPaused(clientId, isPausing);
            return;
        }

        AppOpsService.InProgressStartOpEvent event = mInProgressEvents.valueAt(indexOfToken);
        if (!isPausing) {
            event.numUnfinishedStarts--;
        }
        // If we are pausing, create a NoteOpEvent, but don't change the InProgress event
        if (event.numUnfinishedStarts == 0 || isPausing) {
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
                    if (triggerCallbackIfNeeded && !parent.isRunning()) {
                        mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op,
                                parent.uid, parent.packageName, tag, false,
                                event.getAttributionFlags(), event.getAttributionChainId());
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
        AppOpsService.InProgressStartOpEvent event = mPausedInProgressEvents.valueAt(indexOfToken);
        event.numUnfinishedStarts--;
        if (event.numUnfinishedStarts == 0) {
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
    public void createPaused(@NonNull IBinder clientId, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
            @AppOpsManager.UidState int uidState, @AppOpsManager.OpFlags int flags,
            @AppOpsManager.AttributionFlags
                    int attributionFlags, int attributionChainId) throws RemoteException {
        startedOrPaused(clientId, proxyUid, proxyPackageName, proxyAttributionTag,
                uidState, flags, true, false, attributionFlags, attributionChainId);
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
            AppOpsService.InProgressStartOpEvent event = mInProgressEvents.valueAt(i);
            mPausedInProgressEvents.put(event.getClientId(), event);
            finishOrPause(event.getClientId(), true, true);

            mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, false,
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
            AppOpsService.InProgressStartOpEvent event = mPausedInProgressEvents.valueAt(i);
            mInProgressEvents.put(event.getClientId(), event);
            event.setStartElapsedTime(SystemClock.elapsedRealtime());
            event.setStartTime(startTime);
            mAppOpsService.mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid,
                    parent.packageName, tag, event.getUidState(), event.getFlags(), startTime,
                    event.getAttributionFlags(), event.getAttributionChainId());
            if (shouldSendActive) {
                mAppOpsService.scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                        parent.packageName, tag, true, event.getAttributionFlags(),
                        event.getAttributionChainId());
            }
            // Note: this always sends MODE_ALLOWED, even if the mode is FOREGROUND
            // TODO ntmyren: figure out how to get the real mode.
            mAppOpsService.scheduleOpStartedIfNeededLocked(parent.op, parent.uid,
                    parent.packageName, tag, event.getFlags(), MODE_ALLOWED, START_TYPE_RESUMED,
                    event.getAttributionFlags(), event.getAttributionChainId());
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

            ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> events = isPaused()
                    ? mPausedInProgressEvents : mInProgressEvents;
            AppOpsService.InProgressStartOpEvent deadEvent = events.get(clientId);
            if (deadEvent != null) {
                deadEvent.numUnfinishedStarts = 1;
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
        ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> events =
                isRunning ? mInProgressEvents : mPausedInProgressEvents;

        int numInProgressEvents = events.size();
        List<IBinder> binders = new ArrayList<>(events.keySet());
        for (int i = 0; i < numInProgressEvents; i++) {
            AppOpsService.InProgressStartOpEvent event = events.get(binders.get(i));

            if (event != null && event.getUidState() != newState) {
                try {
                    // Remove all but one unfinished start count and then call finished() to
                    // remove start event object
                    int numPreviousUnfinishedStarts = event.numUnfinishedStarts;
                    event.numUnfinishedStarts = 1;
                    AppOpsManager.OpEventProxyInfo proxy = event.getProxy();

                    finished(event.getClientId(), false);

                    // Call started() to add a new start event object and then add the
                    // previously removed unfinished start counts back
                    if (proxy != null) {
                        startedOrPaused(event.getClientId(), proxy.getUid(),
                                proxy.getPackageName(), proxy.getAttributionTag(), newState,
                                event.getFlags(), false, isRunning,
                                event.getAttributionFlags(), event.getAttributionChainId());
                    } else {
                        startedOrPaused(event.getClientId(), Process.INVALID_UID, null, null,
                                newState, event.getFlags(), false, isRunning,
                                event.getAttributionFlags(), event.getAttributionChainId());
                    }

                    events = isRunning ? mInProgressEvents : mPausedInProgressEvents;
                    AppOpsService.InProgressStartOpEvent newEvent = events.get(binders.get(i));
                    if (newEvent != null) {
                        newEvent.numUnfinishedStarts += numPreviousUnfinishedStarts - 1;
                    }
                } catch (RemoteException e) {
                    if (AppOpsService.DEBUG) {
                        Slog.e(AppOpsService.TAG,
                                "Cannot switch to new uidState " + newState);
                    }
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
            ArrayMap<IBinder, AppOpsService.InProgressStartOpEvent> ignoredEvents =
                    opToAdd.isRunning()
                            ? opToAdd.mInProgressEvents : opToAdd.mPausedInProgressEvents;
            Slog.w(AppOpsService.TAG, "Ignoring " + ignoredEvents.size() + " app-ops, running: "
                    + opToAdd.isRunning());

            int numInProgressEvents = ignoredEvents.size();
            for (int i = 0; i < numInProgressEvents; i++) {
                AppOpsService.InProgressStartOpEvent event = ignoredEvents.valueAt(i);

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
                AppOpsService.InProgressStartOpEvent event = mInProgressEvents.valueAt(i);

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
}
