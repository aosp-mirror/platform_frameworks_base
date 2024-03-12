/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BACKUP;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.ProcessList.BACKUP_APP_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.HEAVY_WEIGHT_APP_ADJ;
import static com.android.server.am.ProcessList.HOME_APP_ADJ;
import static com.android.server.am.ProcessList.NATIVE_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_PROC_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_SERVICE_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_ADJ;
import static com.android.server.am.ProcessList.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.ProcessList.SERVICE_ADJ;
import static com.android.server.am.ProcessList.SERVICE_B_ADJ;
import static com.android.server.am.ProcessList.SYSTEM_ADJ;
import static com.android.server.am.ProcessList.UNKNOWN_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_ADJ;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A modern implementation of the oom adjuster.
 */
public class OomAdjusterModernImpl extends OomAdjuster {
    static final String TAG = "OomAdjusterModernImpl";

    // The ADJ_SLOT_INVALID is NOT an actual slot.
    static final int ADJ_SLOT_INVALID = -1;
    static final int ADJ_SLOT_NATIVE = 0;
    static final int ADJ_SLOT_SYSTEM = 1;
    static final int ADJ_SLOT_PERSISTENT_PROC = 2;
    static final int ADJ_SLOT_PERSISTENT_SERVICE = 3;
    static final int ADJ_SLOT_FOREGROUND_APP = 4;
    static final int ADJ_SLOT_PERCEPTIBLE_RECENT_FOREGROUND_APP = 5;
    static final int ADJ_SLOT_VISIBLE_APP = 6;
    static final int ADJ_SLOT_PERCEPTIBLE_APP = 7;
    static final int ADJ_SLOT_PERCEPTIBLE_MEDIUM_APP = 8;
    static final int ADJ_SLOT_PERCEPTIBLE_LOW_APP = 9;
    static final int ADJ_SLOT_BACKUP_APP = 10;
    static final int ADJ_SLOT_HEAVY_WEIGHT_APP = 11;
    static final int ADJ_SLOT_SERVICE = 12;
    static final int ADJ_SLOT_HOME_APP = 13;
    static final int ADJ_SLOT_PREVIOUS_APP = 14;
    static final int ADJ_SLOT_SERVICE_B = 15;
    static final int ADJ_SLOT_CACHED_APP = 16;
    static final int ADJ_SLOT_UNKNOWN = 17;

    @IntDef(prefix = { "ADJ_SLOT_" }, value = {
        ADJ_SLOT_INVALID,
        ADJ_SLOT_NATIVE,
        ADJ_SLOT_SYSTEM,
        ADJ_SLOT_PERSISTENT_PROC,
        ADJ_SLOT_PERSISTENT_SERVICE,
        ADJ_SLOT_FOREGROUND_APP,
        ADJ_SLOT_PERCEPTIBLE_RECENT_FOREGROUND_APP,
        ADJ_SLOT_VISIBLE_APP,
        ADJ_SLOT_PERCEPTIBLE_APP,
        ADJ_SLOT_PERCEPTIBLE_MEDIUM_APP,
        ADJ_SLOT_PERCEPTIBLE_LOW_APP,
        ADJ_SLOT_BACKUP_APP,
        ADJ_SLOT_HEAVY_WEIGHT_APP,
        ADJ_SLOT_SERVICE,
        ADJ_SLOT_HOME_APP,
        ADJ_SLOT_PREVIOUS_APP,
        ADJ_SLOT_SERVICE_B,
        ADJ_SLOT_CACHED_APP,
        ADJ_SLOT_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AdjSlot{}

    static final int[] ADJ_SLOT_VALUES = new int[] {
        NATIVE_ADJ,
        SYSTEM_ADJ,
        PERSISTENT_PROC_ADJ,
        PERSISTENT_SERVICE_ADJ,
        FOREGROUND_APP_ADJ,
        PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ,
        VISIBLE_APP_ADJ,
        PERCEPTIBLE_APP_ADJ,
        PERCEPTIBLE_MEDIUM_APP_ADJ,
        PERCEPTIBLE_LOW_APP_ADJ,
        BACKUP_APP_ADJ,
        HEAVY_WEIGHT_APP_ADJ,
        SERVICE_ADJ,
        HOME_APP_ADJ,
        PREVIOUS_APP_ADJ,
        SERVICE_B_ADJ,
        CACHED_APP_MIN_ADJ,
        UNKNOWN_ADJ,
    };

    /**
     * Note: Always use the raw adj to call this API.
     */
    static @AdjSlot int adjToSlot(int adj) {
        if (adj >= ADJ_SLOT_VALUES[0] && adj <= ADJ_SLOT_VALUES[ADJ_SLOT_VALUES.length - 1]) {
            // Conduct a binary search, in most of the cases it'll get a hit.
            final int index = Arrays.binarySearch(ADJ_SLOT_VALUES, adj);
            if (index >= 0) {
                return index;
            }
            // If not found, the returned index above should be (-(insertion point) - 1),
            // let's return the first slot that's less than the adj value.
            return -(index + 1) - 1;
        }
        return ADJ_SLOT_VALUES.length - 1;
    }

    static final int[] PROC_STATE_SLOTS = new int[] {
        PROCESS_STATE_PERSISTENT, // 0
        PROCESS_STATE_PERSISTENT_UI,
        PROCESS_STATE_TOP,
        PROCESS_STATE_BOUND_TOP,
        PROCESS_STATE_FOREGROUND_SERVICE,
        PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
        PROCESS_STATE_IMPORTANT_FOREGROUND,
        PROCESS_STATE_IMPORTANT_BACKGROUND,
        PROCESS_STATE_TRANSIENT_BACKGROUND,
        PROCESS_STATE_BACKUP,
        PROCESS_STATE_SERVICE,
        PROCESS_STATE_RECEIVER,
        PROCESS_STATE_TOP_SLEEPING,
        PROCESS_STATE_HEAVY_WEIGHT,
        PROCESS_STATE_HOME,
        PROCESS_STATE_LAST_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY_CLIENT,
        PROCESS_STATE_CACHED_RECENT,
        PROCESS_STATE_CACHED_EMPTY,
        PROCESS_STATE_UNKNOWN, // -1
    };

    static int processStateToSlot(@ActivityManager.ProcessState int state) {
        if (state >= PROCESS_STATE_PERSISTENT && state <= PROCESS_STATE_CACHED_EMPTY) {
            return state;
        }
        return PROC_STATE_SLOTS.length - 1;
    }

    /**
     * A container node in the {@link LinkedProcessRecordList},
     * holding the references to {@link ProcessRecord}.
     */
    static class ProcessRecordNode {
        static final int NODE_TYPE_PROC_STATE = 0;
        static final int NODE_TYPE_ADJ = 1;

        @IntDef(prefix = { "NODE_TYPE_" }, value = {
            NODE_TYPE_PROC_STATE,
            NODE_TYPE_ADJ,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface NodeType {}

        static final int NUM_NODE_TYPE = NODE_TYPE_ADJ + 1;

        @Nullable ProcessRecordNode mPrev;
        @Nullable ProcessRecordNode mNext;
        final @Nullable ProcessRecord mApp;

        ProcessRecordNode(@Nullable ProcessRecord app) {
            mApp = app;
        }

        void unlink() {
            if (mPrev != null) {
                mPrev.mNext = mNext;
            }
            if (mNext != null) {
                mNext.mPrev = mPrev;
            }
            mPrev = mNext = null;
        }

        boolean isLinked() {
            return mPrev != null && mNext != null;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("ProcessRecordNode{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            sb.append(mApp);
            sb.append(' ');
            sb.append(mApp != null ? mApp.mState.getCurProcState() : PROCESS_STATE_UNKNOWN);
            sb.append(' ');
            sb.append(mApp != null ? mApp.mState.getCurAdj() : UNKNOWN_ADJ);
            sb.append(' ');
            sb.append(Integer.toHexString(System.identityHashCode(mPrev)));
            sb.append(' ');
            sb.append(Integer.toHexString(System.identityHashCode(mNext)));
            sb.append('}');
            return sb.toString();
        }
    }

    private class ProcessRecordNodes {
        private final @ProcessRecordNode.NodeType int mType;

        private final LinkedProcessRecordList[] mProcessRecordNodes;
        // The last node besides the tail.
        private final ProcessRecordNode[] mLastNode;

        ProcessRecordNodes(@ProcessRecordNode.NodeType int type, int size) {
            mType = type;
            mProcessRecordNodes = new LinkedProcessRecordList[size];
            for (int i = 0; i < size; i++) {
                mProcessRecordNodes[i] = new LinkedProcessRecordList(type);
            }
            mLastNode = new ProcessRecordNode[size];
        }

        int size() {
            return mProcessRecordNodes.length;
        }

        @VisibleForTesting
        void reset() {
            for (int i = 0; i < mProcessRecordNodes.length; i++) {
                mProcessRecordNodes[i].reset();
                mLastNode[i] = null;
            }
        }

        void resetLastNodes() {
            for (int i = 0; i < mProcessRecordNodes.length; i++) {
                mLastNode[i] = mProcessRecordNodes[i].getLastNodeBeforeTail();
            }
        }

        void setLastNodeToHead(int slot) {
            mLastNode[slot] = mProcessRecordNodes[slot].HEAD;
        }

        void forEachNewNode(int slot, @NonNull Consumer<OomAdjusterArgs> callback) {
            ProcessRecordNode node = mLastNode[slot].mNext;
            final ProcessRecordNode tail = mProcessRecordNodes[slot].TAIL;
            while (node != tail) {
                mTmpOomAdjusterArgs.mApp = node.mApp;
                // Save the next before calling callback, since that may change the node.mNext.
                final ProcessRecordNode next = node.mNext;
                callback.accept(mTmpOomAdjusterArgs);
                // There are couple of cases:
                // a) The current node is moved to another slot
                //    - for this case, we'd need to keep using the "next" node.
                // b) There are one or more new nodes being appended to this slot
                //    - for this case, we'd need to make sure we scan the new node too.
                // Based on the assumption that case a) is only possible with
                // the computeInitialOomAdjLSP(), where the movings are for single node only,
                // we may safely assume that, if the "next" used to be the "tail" here, and it's
                // now a new tail somewhere else, that's case a); otherwise, it's case b);
                node = next == tail && node.mNext != null && node.mNext.mNext != null
                        ? node.mNext : next;
            }
        }

        int getNumberOfSlots() {
            return mProcessRecordNodes.length;
        }

        void moveAppTo(@NonNull ProcessRecord app, int prevSlot, int newSlot) {
            final ProcessRecordNode node = app.mLinkedNodes[mType];
            if (prevSlot != ADJ_SLOT_INVALID) {
                if (mLastNode[prevSlot] == node) {
                    mLastNode[prevSlot] = node.mPrev;
                }
                node.unlink();
            }
            mProcessRecordNodes[newSlot].append(node);
        }

        void moveAllNodesTo(int fromSlot, int toSlot) {
            final LinkedProcessRecordList fromList = mProcessRecordNodes[fromSlot];
            final LinkedProcessRecordList toList = mProcessRecordNodes[toSlot];
            if (fromSlot != toSlot && fromList.HEAD.mNext != fromList.TAIL) {
                fromList.moveTo(toList);
                mLastNode[fromSlot] = fromList.getLastNodeBeforeTail();
            }
        }

        void moveAppToTail(ProcessRecord app) {
            final ProcessRecordNode node = app.mLinkedNodes[mType];
            int slot;
            switch (mType) {
                case ProcessRecordNode.NODE_TYPE_PROC_STATE:
                    slot = processStateToSlot(app.mState.getCurProcState());
                    if (mLastNode[slot] == node) {
                        mLastNode[slot] = node.mPrev;
                    }
                    mProcessRecordNodes[slot].moveNodeToTail(node);
                    break;
                case ProcessRecordNode.NODE_TYPE_ADJ:
                    slot = adjToSlot(app.mState.getCurRawAdj());
                    if (mLastNode[slot] == node) {
                        mLastNode[slot] = node.mPrev;
                    }
                    mProcessRecordNodes[slot].moveNodeToTail(node);
                    break;
                default:
                    return;
            }

        }

        void reset(int slot) {
            mProcessRecordNodes[slot].reset();
        }

        void unlink(@NonNull ProcessRecord app) {
            final ProcessRecordNode node = app.mLinkedNodes[mType];
            final int slot = getCurrentSlot(app);
            if (slot != ADJ_SLOT_INVALID) {
                if (mLastNode[slot] == node) {
                    mLastNode[slot] = node.mPrev;
                }
            }
            node.unlink();
        }

        void append(@NonNull ProcessRecord app) {
            append(app, getCurrentSlot(app));
        }

        void append(@NonNull ProcessRecord app, int targetSlot) {
            final ProcessRecordNode node = app.mLinkedNodes[mType];
            mProcessRecordNodes[targetSlot].append(node);
        }

        private int getCurrentSlot(@NonNull ProcessRecord app) {
            switch (mType) {
                case ProcessRecordNode.NODE_TYPE_PROC_STATE:
                    return processStateToSlot(app.mState.getCurProcState());
                case ProcessRecordNode.NODE_TYPE_ADJ:
                    return adjToSlot(app.mState.getCurRawAdj());
            }
            return ADJ_SLOT_INVALID;
        }

        String toString(int slot, int logUid) {
            return "lastNode=" + mLastNode[slot] + " " + mProcessRecordNodes[slot].toString(logUid);
        }

        /**
         * A simple version of {@link java.util.LinkedList}, as here we don't allocate new node
         * while adding an object to it.
         */
        private static class LinkedProcessRecordList {
            // Sentinel head/tail, to make bookkeeping work easier.
            final ProcessRecordNode HEAD = new ProcessRecordNode(null);
            final ProcessRecordNode TAIL = new ProcessRecordNode(null);
            final @ProcessRecordNode.NodeType int mNodeType;

            LinkedProcessRecordList(@ProcessRecordNode.NodeType int nodeType) {
                HEAD.mNext = TAIL;
                TAIL.mPrev = HEAD;
                mNodeType = nodeType;
            }

            void append(@NonNull ProcessRecordNode node) {
                node.mNext = TAIL;
                node.mPrev = TAIL.mPrev;
                TAIL.mPrev.mNext = node;
                TAIL.mPrev = node;
            }

            void moveTo(@NonNull LinkedProcessRecordList toList) {
                if (HEAD.mNext != TAIL) {
                    toList.TAIL.mPrev.mNext = HEAD.mNext;
                    HEAD.mNext.mPrev = toList.TAIL.mPrev;
                    toList.TAIL.mPrev = TAIL.mPrev;
                    TAIL.mPrev.mNext = toList.TAIL;
                    HEAD.mNext = TAIL;
                    TAIL.mPrev = HEAD;
                }
            }

            void moveNodeToTail(@NonNull ProcessRecordNode node) {
                node.unlink();
                append(node);
            }

            @NonNull ProcessRecordNode getLastNodeBeforeTail() {
                return TAIL.mPrev;
            }

            @VisibleForTesting
            void reset() {
                HEAD.mNext = TAIL;
                TAIL.mPrev = HEAD;
            }

            String toString(int logUid) {
                final StringBuilder sb = new StringBuilder();
                sb.append("LinkedProcessRecordList{");
                sb.append(HEAD);
                sb.append(' ');
                sb.append(TAIL);
                sb.append('[');
                ProcessRecordNode node = HEAD.mNext;
                while (node != TAIL) {
                    if (node.mApp != null && node.mApp.uid == logUid) {
                        sb.append(node);
                        sb.append(',');
                    }
                    node = node.mNext;
                }
                sb.append(']');
                sb.append('}');
                return sb.toString();
            }
        }
    }

    /**
     * A data class for holding the parameters in computing oom adj.
     */
    private class OomAdjusterArgs {
        ProcessRecord mApp;
        ProcessRecord mTopApp;
        long mNow;
        int mCachedAdj;
        @OomAdjReason int mOomAdjReason;
        @NonNull ActiveUids mUids;
        boolean mFullUpdate;

        void update(ProcessRecord topApp, long now, int cachedAdj,
                @OomAdjReason int oomAdjReason, @NonNull ActiveUids uids, boolean fullUpdate) {
            mTopApp = topApp;
            mNow = now;
            mCachedAdj = cachedAdj;
            mOomAdjReason = oomAdjReason;
            mUids = uids;
            mFullUpdate = fullUpdate;
        }
    }

    /**
     * A helper consumer for collecting processes that have not been reached yet. To avoid object
     * allocations every OomAdjuster update, the results will be stored in
     * {@link UnreachedProcessCollector#processList}. The process list reader is responsible
     * for setting it before usage, as well as, clearing the reachable state of each process in the
     * list.
     */
    private static class UnreachedProcessCollector implements Consumer<ProcessRecord> {
        public ArrayList<ProcessRecord> processList = null;
        @Override
        public void accept(ProcessRecord process) {
            if (process.mState.isReachable()) {
                return;
            }
            process.mState.setReachable(true);
            processList.add(process);
        }
    }

    private final UnreachedProcessCollector mUnreachedProcessCollector =
            new UnreachedProcessCollector();

    OomAdjusterModernImpl(ActivityManagerService service, ProcessList processList,
            ActiveUids activeUids) {
        this(service, processList, activeUids, createAdjusterThread());
    }

    OomAdjusterModernImpl(ActivityManagerService service, ProcessList processList,
            ActiveUids activeUids, ServiceThread adjusterThread) {
        super(service, processList, activeUids, adjusterThread);
    }

    private final ProcessRecordNodes mProcessRecordProcStateNodes = new ProcessRecordNodes(
            ProcessRecordNode.NODE_TYPE_PROC_STATE, PROC_STATE_SLOTS.length);
    private final ProcessRecordNodes mProcessRecordAdjNodes = new ProcessRecordNodes(
            ProcessRecordNode.NODE_TYPE_ADJ, ADJ_SLOT_VALUES.length);
    private final OomAdjusterArgs mTmpOomAdjusterArgs = new OomAdjusterArgs();

    void linkProcessRecordToList(@NonNull ProcessRecord app) {
        mProcessRecordProcStateNodes.append(app);
        mProcessRecordAdjNodes.append(app);
    }

    void unlinkProcessRecordFromList(@NonNull ProcessRecord app) {
        mProcessRecordProcStateNodes.unlink(app);
        mProcessRecordAdjNodes.unlink(app);
    }

    @Override
    @VisibleForTesting
    void resetInternal() {
        mProcessRecordProcStateNodes.reset();
        mProcessRecordAdjNodes.reset();
    }

    @GuardedBy("mService")
    @Override
    void onProcessBeginLocked(@NonNull ProcessRecord app) {
        // Check one type should be good enough.
        if (app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE] == null) {
            for (int i = 0; i < app.mLinkedNodes.length; i++) {
                app.mLinkedNodes[i] = new ProcessRecordNode(app);
            }
        }
        if (!app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE].isLinked()) {
            linkProcessRecordToList(app);
        }
    }

    @GuardedBy("mService")
    @Override
    void onProcessEndLocked(@NonNull ProcessRecord app) {
        if (app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE] != null
                && app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE].isLinked()) {
            unlinkProcessRecordFromList(app);
        }
    }

    @GuardedBy("mService")
    @Override
    void onProcessStateChanged(@NonNull ProcessRecord app, int prevProcState) {
        updateProcStateSlotIfNecessary(app, prevProcState);
    }

    @GuardedBy("mService")
    void onProcessOomAdjChanged(@NonNull ProcessRecord app, int prevAdj) {
        updateAdjSlotIfNecessary(app, prevAdj);
    }

    @GuardedBy("mService")
    @Override
    protected int getInitialAdj(@NonNull ProcessRecord app) {
        return UNKNOWN_ADJ;
    }

    @GuardedBy("mService")
    @Override
    protected int getInitialProcState(@NonNull ProcessRecord app) {
        return PROCESS_STATE_UNKNOWN;
    }

    @GuardedBy("mService")
    @Override
    protected int getInitialCapability(@NonNull ProcessRecord app) {
        return 0;
    }

    @GuardedBy("mService")
    @Override
    protected boolean getInitialIsCurBoundByNonBgRestrictedApp(@NonNull ProcessRecord app) {
        return false;
    }

    private void updateAdjSlotIfNecessary(ProcessRecord app, int prevRawAdj) {
        if (app.mState.getCurRawAdj() != prevRawAdj) {
            final int slot = adjToSlot(app.mState.getCurRawAdj());
            final int prevSlot = adjToSlot(prevRawAdj);
            if (slot != prevSlot && slot != ADJ_SLOT_INVALID) {
                mProcessRecordAdjNodes.moveAppTo(app, prevSlot, slot);
            }
        }
    }

    private void updateProcStateSlotIfNecessary(ProcessRecord app, int prevProcState) {
        if (app.mState.getCurProcState() != prevProcState) {
            final int slot = processStateToSlot(app.mState.getCurProcState());
            final int prevSlot = processStateToSlot(prevProcState);
            if (slot != prevSlot) {
                mProcessRecordProcStateNodes.moveAppTo(app, prevSlot, slot);
            }
        }
    }

    @Override
    protected boolean performUpdateOomAdjLSP(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));
        mService.mOomAdjProfiler.oomAdjStarted();
        mAdjSeq++;

        final ProcessStateRecord state = app.mState;
        final int oldAdj = state.getCurRawAdj();
        final int cachedAdj = oldAdj >= CACHED_APP_MIN_ADJ
                ? oldAdj : UNKNOWN_ADJ;

        final ActiveUids uids = mTmpUidRecords;
        final ArraySet<ProcessRecord> targetProcesses = mTmpProcessSet;
        final ArrayList<ProcessRecord> reachableProcesses = mTmpProcessList;
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();

        uids.clear();
        targetProcesses.clear();
        targetProcesses.add(app);
        reachableProcesses.clear();

        // Find out all reachable processes from this app.
        collectReachableProcessesLocked(targetProcesses, reachableProcesses, uids);

        // Copy all of the reachable processes into the target process set.
        targetProcesses.addAll(reachableProcesses);
        reachableProcesses.clear();

        final boolean result = performNewUpdateOomAdjLSP(oomAdjReason,
                topApp, targetProcesses, uids, false, now, cachedAdj);

        reachableProcesses.addAll(targetProcesses);
        assignCachedAdjIfNecessary(reachableProcesses);
        for (int  i = uids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = uids.valueAt(i);
            uidRec.forEachProcess(this::updateAppUidRecIfNecessaryLSP);
        }
        updateUidsLSP(uids, nowElapsed);
        for (int i = 0, size = targetProcesses.size(); i < size; i++) {
            applyOomAdjLSP(targetProcesses.valueAt(i), false, now, nowElapsed, oomAdjReason);
        }
        targetProcesses.clear();
        reachableProcesses.clear();

        mService.mOomAdjProfiler.oomAdjEnded();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        return result;
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected void updateOomAdjInnerLSP(@OomAdjReason int oomAdjReason, final ProcessRecord topApp,
            ArrayList<ProcessRecord> processes, ActiveUids uids, boolean potentialCycles,
            boolean startProfiling) {
        final boolean fullUpdate = processes == null;
        final ArrayList<ProcessRecord> activeProcesses = fullUpdate
                ? mProcessList.getLruProcessesLOSP() : processes;
        ActiveUids activeUids = uids;
        if (activeUids == null) {
            final int numUids = mActiveUids.size();
            activeUids = mTmpUidRecords;
            activeUids.clear();
            for (int i = 0; i < numUids; i++) {
                UidRecord uidRec = mActiveUids.valueAt(i);
                activeUids.put(uidRec.getUid(), uidRec);
            }
        }

        if (startProfiling) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));
            mService.mOomAdjProfiler.oomAdjStarted();
        }
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long oldTime = now - mConstants.mMaxEmptyTimeMillis;
        final int numProc = activeProcesses.size();

        mAdjSeq++;
        if (fullUpdate) {
            mNewNumServiceProcs = 0;
            mNewNumAServiceProcs = 0;
        }

        final ArraySet<ProcessRecord> targetProcesses = mTmpProcessSet;
        targetProcesses.clear();
        if (!fullUpdate) {
            targetProcesses.addAll(activeProcesses);
        }

        performNewUpdateOomAdjLSP(oomAdjReason, topApp, targetProcesses, activeUids,
                fullUpdate, now, UNKNOWN_ADJ);

        if (fullUpdate) {
            assignCachedAdjIfNecessary(mProcessList.getLruProcessesLOSP());
            postUpdateOomAdjInnerLSP(oomAdjReason, activeUids, now, nowElapsed, oldTime);
        } else {
            activeProcesses.clear();
            activeProcesses.addAll(targetProcesses);
            assignCachedAdjIfNecessary(activeProcesses);

            for (int  i = activeUids.size() - 1; i >= 0; i--) {
                final UidRecord uidRec = activeUids.valueAt(i);
                uidRec.forEachProcess(this::updateAppUidRecIfNecessaryLSP);
            }
            updateUidsLSP(activeUids, nowElapsed);

            for (int i = 0, size = targetProcesses.size(); i < size; i++) {
                applyOomAdjLSP(targetProcesses.valueAt(i), false, now, nowElapsed, oomAdjReason);
            }

            activeProcesses.clear();
        }
        targetProcesses.clear();

        if (startProfiling) {
            mService.mOomAdjProfiler.oomAdjEnded();
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
        return;
    }

    /**
     * Perform the oom adj update on the given {@code targetProcesses}.
     *
     * <p>Note: The expectation to the given {@code targetProcesses} is, the caller
     * must have called {@link collectReachableProcessesLocked} on it.
     */
    private boolean performNewUpdateOomAdjLSP(@OomAdjReason int oomAdjReason,
            ProcessRecord topApp,  ArraySet<ProcessRecord> targetProcesses, ActiveUids uids,
            boolean fullUpdate, long now, int cachedAdj) {

        final ArrayList<ProcessRecord> clientProcesses = mTmpProcessList2;
        clientProcesses.clear();

        // We'll need to collect the upstream processes of the target apps here, because those
        // processes would potentially impact the procstate/adj via bindings.
        if (!fullUpdate) {
            collectExcludedClientProcessesLocked(targetProcesses, clientProcesses);

            for (int i = 0, size = targetProcesses.size(); i < size; i++) {
                final ProcessRecord app = targetProcesses.valueAt(i);
                app.mState.resetCachedInfo();
                final UidRecord uidRec = app.getUidRecord();
                if (uidRec != null) {
                    if (DEBUG_UID_OBSERVERS) {
                        Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
                    }
                    uidRec.reset();
                }
            }
        } else {
            final ArrayList<ProcessRecord> lru = mProcessList.getLruProcessesLOSP();
            for (int i = 0, size = lru.size(); i < size; i++) {
                final ProcessRecord app = lru.get(i);
                app.mState.resetCachedInfo();
                final UidRecord uidRec = app.getUidRecord();
                if (uidRec != null) {
                    if (DEBUG_UID_OBSERVERS) {
                        Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
                    }
                    uidRec.reset();
                }
            }
        }

        updateNewOomAdjInnerLSP(oomAdjReason, topApp, targetProcesses, clientProcesses, uids,
                cachedAdj, now, fullUpdate);

        clientProcesses.clear();

        return true;
    }

    /**
     * Collect the client processes from the given {@code apps}, the result will be returned in the
     * given {@code clientProcesses}, which will <em>NOT</em> include the processes from the given
     * {@code apps}.
     */
    @GuardedBy("mService")
    private void collectExcludedClientProcessesLocked(ArraySet<ProcessRecord> apps,
            ArrayList<ProcessRecord> clientProcesses) {
        // Mark all of the provided apps as reachable to avoid including them in the client list.
        final int appsSize = apps.size();
        for (int i = 0; i < appsSize; i++) {
            final ProcessRecord app = apps.valueAt(i);
            app.mState.setReachable(true);
        }

        clientProcesses.clear();
        mUnreachedProcessCollector.processList = clientProcesses;
        for (int i = 0; i < appsSize; i++) {
            final ProcessRecord app = apps.valueAt(i);
            app.forEachClient(mUnreachedProcessCollector);
        }
        mUnreachedProcessCollector.processList = null;

        // Reset the temporary bits.
        for (int i = clientProcesses.size() - 1; i >= 0; i--) {
            clientProcesses.get(i).mState.setReachable(false);
        }
        for (int i = 0, size = apps.size(); i < size; i++) {
            final ProcessRecord app = apps.valueAt(i);
            app.mState.setReachable(false);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateNewOomAdjInnerLSP(@OomAdjReason int oomAdjReason, final ProcessRecord topApp,
            ArraySet<ProcessRecord> targetProcesses, ArrayList<ProcessRecord> clientProcesses,
            ActiveUids uids, int cachedAdj, long now, boolean fullUpdate) {
        mTmpOomAdjusterArgs.update(topApp, now, cachedAdj, oomAdjReason, uids, fullUpdate);

        mProcessRecordProcStateNodes.resetLastNodes();
        mProcessRecordAdjNodes.resetLastNodes();

        final int procStateTarget = mProcessRecordProcStateNodes.size() - 1;
        final int adjTarget = mProcessRecordAdjNodes.size() - 1;

        mAdjSeq++;
        // All apps to be updated will be moved to the lowest slot.
        if (fullUpdate) {
            // Move all the process record node to the lowest slot, we'll do recomputation on all of
            // them. Use the processes from the lru list, because the scanning order matters here.
            final ArrayList<ProcessRecord> lruList = mProcessList.getLruProcessesLOSP();
            for (int i = procStateTarget; i >= 0; i--) {
                mProcessRecordProcStateNodes.reset(i);
                // Force the last node to the head since we'll recompute all of them.
                mProcessRecordProcStateNodes.setLastNodeToHead(i);
            }
            // enqueue the targets in the reverse order of the lru list.
            for (int i = lruList.size() - 1; i >= 0; i--) {
                mProcessRecordProcStateNodes.append(lruList.get(i), procStateTarget);
            }
            // Do the same to the adj nodes.
            for (int i = adjTarget; i >= 0; i--) {
                mProcessRecordAdjNodes.reset(i);
                // Force the last node to the head since we'll recompute all of them.
                mProcessRecordAdjNodes.setLastNodeToHead(i);
            }
            for (int i = lruList.size() - 1; i >= 0; i--) {
                mProcessRecordAdjNodes.append(lruList.get(i), adjTarget);
            }
        } else {
            // Move the target processes to the lowest slot.
            for (int i = 0, size = targetProcesses.size(); i < size; i++) {
                final ProcessRecord app = targetProcesses.valueAt(i);
                final int procStateSlot = processStateToSlot(app.mState.getCurProcState());
                final int adjSlot = adjToSlot(app.mState.getCurRawAdj());
                mProcessRecordProcStateNodes.moveAppTo(app, procStateSlot, procStateTarget);
                mProcessRecordAdjNodes.moveAppTo(app, adjSlot, adjTarget);
            }
            // Move the "lastNode" to head to make sure we scan all nodes in this slot.
            mProcessRecordProcStateNodes.setLastNodeToHead(procStateTarget);
            mProcessRecordAdjNodes.setLastNodeToHead(adjTarget);
        }

        // All apps to be updated have been moved to the lowest slot.
        // Do an initial pass of the computation.
        mProcessRecordProcStateNodes.forEachNewNode(mProcessRecordProcStateNodes.size() - 1,
                this::computeInitialOomAdjLSP);

        if (!fullUpdate) {
            // We didn't update the client processes with the computeInitialOomAdjLSP
            // because they don't need to do so. But they'll be playing vital roles in
            // computing the bindings. So include them into the scan list below.
            for (int i = 0, size = clientProcesses.size(); i < size; i++) {
                mProcessRecordProcStateNodes.moveAppToTail(clientProcesses.get(i));
            }
            // We don't update the adj list since we're resetting it below.
        }

        // Now nodes are set into their slots, without factoring in the bindings.
        // The nodes between the `lastNode` pointer and the TAIL should be the new nodes.
        //
        // The whole rationale here is that, the bindings from client to host app, won't elevate
        // the host app's procstate/adj higher than the client app's state (BIND_ABOVE_CLIENT
        // is a special case here, but client app's raw adj is still no less than the host app's).
        // Therefore, starting from the top to the bottom, for each slot, scan all of the new nodes,
        // check its bindings, elevate its host app's slot if necessary.
        //
        // We'd have to do this in two passes: 1) scan procstate node list; 2) scan adj node list.
        // Because the procstate and adj are not always in sync - there are cases where
        // the processes with lower proc state could be getting a higher oom adj score.
        // And because of this, the procstate and adj node lists are basically two priority heaps.
        //
        // As the 2nd pass with the adj node lists potentially includes a significant amount of
        // duplicated scans as the 1st pass has done, we'll reset the last node pointers for
        // the adj node list before the 1st pass; so during the 1st pass, if any app's adj slot
        // gets bumped, we'll only scan those in 2nd pass.

        mProcessRecordAdjNodes.resetLastNodes();

        // 1st pass, scan each slot in the procstate node list.
        for (int i = 0, end = mProcessRecordProcStateNodes.size() - 1; i < end; i++) {
            mProcessRecordProcStateNodes.forEachNewNode(i, this::computeHostOomAdjLSP);
        }

        // 2nd pass, scan each slot in the adj node list.
        for (int i = 0, end = mProcessRecordAdjNodes.size() - 1; i < end; i++) {
            mProcessRecordAdjNodes.forEachNewNode(i, this::computeHostOomAdjLSP);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void computeInitialOomAdjLSP(OomAdjusterArgs args) {
        final ProcessRecord app = args.mApp;
        final int cachedAdj = args.mCachedAdj;
        final ProcessRecord topApp = args.mTopApp;
        final long now = args.mNow;
        final int oomAdjReason = args.mOomAdjReason;
        final ActiveUids uids = args.mUids;
        final boolean fullUpdate = args.mFullUpdate;

        if (DEBUG_OOM_ADJ) {
            Slog.i(TAG, "OOM ADJ initial args app=" + app
                    + " cachedAdj=" + cachedAdj
                    + " topApp=" + topApp
                    + " now=" + now
                    + " oomAdjReason=" + oomAdjReasonToString(oomAdjReason)
                    + " fullUpdate=" + fullUpdate);
        }

        if (uids != null) {
            final UidRecord uidRec = app.getUidRecord();

            if (uidRec != null) {
                uids.put(uidRec.getUid(), uidRec);
            }
        }

        computeOomAdjLSP(app, cachedAdj, topApp, fullUpdate, now, false, false, oomAdjReason,
                false);
    }

    /**
     * @return The proposed change to the schedGroup.
     */
    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected int setIntermediateAdjLSP(ProcessRecord app, int adj, int prevRawAppAdj,
            int schedGroup) {
        schedGroup = super.setIntermediateAdjLSP(app, adj, prevRawAppAdj, schedGroup);

        updateAdjSlotIfNecessary(app, prevRawAppAdj);

        return schedGroup;
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected void setIntermediateProcStateLSP(ProcessRecord app, int procState,
            int prevProcState) {
        super.setIntermediateProcStateLSP(app, procState, prevProcState);

        updateProcStateSlotIfNecessary(app, prevProcState);
    }

    @GuardedBy({"mService", "mProcLock"})
    private void computeHostOomAdjLSP(OomAdjusterArgs args) {
        final ProcessRecord app = args.mApp;
        final int cachedAdj = args.mCachedAdj;
        final ProcessRecord topApp = args.mTopApp;
        final long now = args.mNow;
        final @OomAdjReason int oomAdjReason = args.mOomAdjReason;
        final boolean fullUpdate = args.mFullUpdate;
        final ActiveUids uids = args.mUids;

        final ProcessServiceRecord psr = app.mServices;
        for (int i = psr.numberOfConnections() - 1; i >= 0; i--) {
            ConnectionRecord cr = psr.getConnectionAt(i);
            ProcessRecord service = cr.hasFlag(ServiceInfo.FLAG_ISOLATED_PROCESS)
                    ? cr.binding.service.isolationHostProc : cr.binding.service.app;
            if (service == null || service == app
                    || (service.mState.getMaxAdj() >= SYSTEM_ADJ
                            && service.mState.getMaxAdj() < FOREGROUND_APP_ADJ)
                    || (service.mState.getCurAdj() <= FOREGROUND_APP_ADJ
                            && service.mState.getCurrentSchedulingGroup() > SCHED_GROUP_BACKGROUND
                            && service.mState.getCurProcState() <= PROCESS_STATE_TOP)) {
                continue;
            }


            computeServiceHostOomAdjLSP(cr, service, app, now, topApp, fullUpdate, false, false,
                    oomAdjReason, cachedAdj, false);
        }

        for (int i = psr.numberOfSdkSandboxConnections() - 1; i >= 0; i--) {
            final ConnectionRecord cr = psr.getSdkSandboxConnectionAt(i);
            final ProcessRecord service = cr.binding.service.app;
            if (service == null || service == app
                    || (service.mState.getMaxAdj() >= SYSTEM_ADJ
                            && service.mState.getMaxAdj() < FOREGROUND_APP_ADJ)
                    || (service.mState.getCurAdj() <= FOREGROUND_APP_ADJ
                            && service.mState.getCurrentSchedulingGroup() > SCHED_GROUP_BACKGROUND
                            && service.mState.getCurProcState() <= PROCESS_STATE_TOP)) {
                continue;
            }

            computeServiceHostOomAdjLSP(cr, service, app, now, topApp, fullUpdate, false, false,
                    oomAdjReason, cachedAdj, false);
        }

        final ProcessProviderRecord ppr = app.mProviders;
        for (int i = ppr.numberOfProviderConnections() - 1; i >= 0; i--) {
            ContentProviderConnection cpc = ppr.getProviderConnectionAt(i);
            ProcessRecord provider = cpc.provider.proc;
            if (provider == null || provider == app
                    || (provider.mState.getMaxAdj() >= ProcessList.SYSTEM_ADJ
                            && provider.mState.getMaxAdj() < FOREGROUND_APP_ADJ)
                    || (provider.mState.getCurAdj() <= FOREGROUND_APP_ADJ
                            && provider.mState.getCurrentSchedulingGroup() > SCHED_GROUP_BACKGROUND
                            && provider.mState.getCurProcState() <= PROCESS_STATE_TOP)) {
                continue;
            }

            computeProviderHostOomAdjLSP(cpc, provider, app, now, topApp, fullUpdate, false, false,
                    oomAdjReason, cachedAdj, false);
        }
    }
}
