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

import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
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
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiConsumer;
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
            resetLastNodes();
        }

        int size() {
            return mProcessRecordNodes.length;
        }

        @VisibleForTesting
        void reset() {
            for (int i = 0; i < mProcessRecordNodes.length; i++) {
                mProcessRecordNodes[i].reset();
                setLastNodeToHead(i);
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
            }
            // node will be firstly unlinked in the append.
            append(node, newSlot);
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
            append(app.mLinkedNodes[mType], targetSlot);
        }

        void append(@NonNull ProcessRecordNode node, int targetSlot) {
            node.unlink();
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
                if (HEAD.mNext != TAIL) {
                    HEAD.mNext.mPrev = TAIL.mPrev.mNext = null;
                }
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
     * A {@link Connection} represents any connection between two processes that can cause a
     * change in importance in the host process based on the client process and connection state.
     */
    public interface Connection {
        /**
         * Compute the impact this connection has on the host's importance values.
         */
        void computeHostOomAdjLSP(OomAdjuster oomAdjuster, ProcessRecord host, ProcessRecord client,
                long now, ProcessRecord topApp, boolean doingAll, int oomAdjReason, int cachedAdj);

        /**
         * Returns true if this connection can propagate capabilities.
         */
        boolean canAffectCapabilities();
    }

    /**
     * A helper consumer for marking and collecting reachable processes.
     */
    private static class ReachableCollectingConsumer implements
            BiConsumer<Connection, ProcessRecord> {
        ArrayList<ProcessRecord> mReachables = null;

        public void init(ArrayList<ProcessRecord> reachables) {
            mReachables = reachables;
        }

        @Override
        public void accept(Connection unused, ProcessRecord host) {
            if (host.mState.isReachable()) {
                return;
            }
            host.mState.setReachable(true);
            mReachables.add(host);
        }
    }

    private final ReachableCollectingConsumer mReachableCollectingConsumer =
            new ReachableCollectingConsumer();

    /**
     * A helper consumer for computing the importance of a connection from a client.
     * Connections for clients marked reachable will be ignored.
     */
    private class ComputeConnectionIgnoringReachableClientsConsumer implements
            BiConsumer<Connection, ProcessRecord> {
        private OomAdjusterArgs mArgs = null;
        public boolean hasReachableClient = false;

        public void init(OomAdjusterArgs args) {
            mArgs = args;
            hasReachableClient = false;
        }

        @Override
        public void accept(Connection conn, ProcessRecord client) {
            final ProcessRecord host = mArgs.mApp;
            final ProcessRecord topApp = mArgs.mTopApp;
            final long now = mArgs.mNow;
            final @OomAdjReason int oomAdjReason = mArgs.mOomAdjReason;

            if (client.mState.isReachable()) {
                hasReachableClient = true;
                return;
            }

            if (unimportantConnectionLSP(conn, host, client)) {
                return;
            }

            conn.computeHostOomAdjLSP(OomAdjusterModernImpl.this, host, client, now, topApp, false,
                    oomAdjReason, UNKNOWN_ADJ);
        }
    }

    private final ComputeConnectionIgnoringReachableClientsConsumer
            mComputeConnectionIgnoringReachableClientsConsumer =
            new ComputeConnectionIgnoringReachableClientsConsumer();

    /**
     * A helper consumer for computing host process importance from a connection from a client app.
     */
    private class ComputeHostConsumer implements BiConsumer<Connection, ProcessRecord> {
        public OomAdjusterArgs args = null;

        @Override
        public void accept(Connection conn, ProcessRecord host) {
            final ProcessRecord client = args.mApp;
            final int cachedAdj = args.mCachedAdj;
            final ProcessRecord topApp = args.mTopApp;
            final long now = args.mNow;
            final @OomAdjReason int oomAdjReason = args.mOomAdjReason;
            final boolean fullUpdate = args.mFullUpdate;

            final int prevProcState = host.mState.getCurProcState();
            final int prevAdj = host.mState.getCurRawAdj();

            if (unimportantConnectionLSP(conn, host, client)) {
                return;
            }

            conn.computeHostOomAdjLSP(OomAdjusterModernImpl.this, host, client, now, topApp,
                    fullUpdate, oomAdjReason, cachedAdj);

            updateProcStateSlotIfNecessary(host, prevProcState);
            updateAdjSlotIfNecessary(host, prevAdj);
        }
    }
    private final ComputeHostConsumer mComputeHostConsumer = new ComputeHostConsumer();

    /**
     * A helper consumer for computing all connections from an app.
     */
    private class ComputeConnectionsConsumer implements Consumer<OomAdjusterArgs> {
        @Override
        public void accept(OomAdjusterArgs args) {
            final ProcessRecord app = args.mApp;
            final ActiveUids uids = args.mUids;

            // This process was updated in some way, mark that it was last calculated this sequence.
            app.mState.setCompletedAdjSeq(mAdjSeq);
            if (uids != null) {
                final UidRecord uidRec = app.getUidRecord();

                if (uidRec != null) {
                    uids.put(uidRec.getUid(), uidRec);
                }
            }
            mComputeHostConsumer.args = args;
            forEachConnectionLSP(app, mComputeHostConsumer);
        }
    }
    private final ComputeConnectionsConsumer mComputeConnectionsConsumer =
            new ComputeConnectionsConsumer();

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

    private void updateAdjSlot(ProcessRecord app, int prevRawAdj) {
        final int slot = adjToSlot(app.mState.getCurRawAdj());
        final int prevSlot = adjToSlot(prevRawAdj);
        mProcessRecordAdjNodes.moveAppTo(app, prevSlot, slot);
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

    private void updateProcStateSlot(ProcessRecord app, int prevProcState) {
        final int slot = processStateToSlot(app.mState.getCurProcState());
        final int prevSlot = processStateToSlot(prevProcState);
        mProcessRecordProcStateNodes.moveAppTo(app, prevSlot, slot);
    }

    @Override
    protected void performUpdateOomAdjLSP(@OomAdjReason int oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();
        mProcessStateCurTop = mService.mAtmInternal.getTopProcessState();
        // Clear any pending ones because we are doing a full update now.
        mPendingProcessSet.clear();
        mService.mAppProfiler.mHasPreviousProcess = mService.mAppProfiler.mHasHomeProcess = false;

        mLastReason = oomAdjReason;
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));

        fullUpdateLSP(oomAdjReason);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected boolean performUpdateOomAdjLSP(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        mPendingProcessSet.add(app);
        performUpdateOomAdjPendingTargetsLocked(oomAdjReason);
        return true;
    }

    @GuardedBy("mService")
    @Override
    protected void performUpdateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason) {
        mLastReason = oomAdjReason;
        mProcessStateCurTop = enqueuePendingTopAppIfNecessaryLSP();
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));

        synchronized (mProcLock) {
            partialUpdateLSP(oomAdjReason, mPendingProcessSet);
        }
        mPendingProcessSet.clear();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Perform a full update on the entire process list.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void fullUpdateLSP(@OomAdjReason int oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long oldTime = now - mConstants.mMaxEmptyTimeMillis;

        mAdjSeq++;

        mNewNumServiceProcs = 0;
        mNewNumAServiceProcs = 0;

        // Clear the priority queues.
        mProcessRecordProcStateNodes.reset();
        mProcessRecordAdjNodes.reset();

        final ArrayList<ProcessRecord> lru = mProcessList.getLruProcessesLOSP();
        for (int i = lru.size() - 1; i >= 0; i--) {
            final ProcessRecord app = lru.get(i);
            final int prevProcState = app.mState.getCurProcState();
            final int prevAdj = app.mState.getCurRawAdj();
            app.mState.resetCachedInfo();
            final UidRecord uidRec = app.getUidRecord();
            if (uidRec != null) {
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
                }
                uidRec.reset();
            }

            // Compute initial values, the procState and adj priority queues will be populated here.
            computeOomAdjLSP(app, UNKNOWN_ADJ, topApp, true, now, false, false, oomAdjReason,
                    false);
            updateProcStateSlot(app, prevProcState);
            updateAdjSlot(app, prevAdj);
        }

        // Set adj last nodes now, this way a process will only be reevaluated during the adj node
        // iteration if they adj score changed during the procState node iteration.
        mProcessRecordAdjNodes.resetLastNodes();
        mTmpOomAdjusterArgs.update(topApp, now, UNKNOWN_ADJ, oomAdjReason, null, true);
        computeConnectionsLSP();

        assignCachedAdjIfNecessary(mProcessList.getLruProcessesLOSP());
        postUpdateOomAdjInnerLSP(oomAdjReason, mActiveUids, now, nowElapsed, oldTime, true);
    }

    /**
     * Traverse the process graph and update processes based on changes in connection importances.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void computeConnectionsLSP() {
        // 1st pass, scan each slot in the procstate node list.
        for (int i = 0, end = mProcessRecordProcStateNodes.size() - 1; i < end; i++) {
            mProcessRecordProcStateNodes.forEachNewNode(i, mComputeConnectionsConsumer);
        }

        // 2nd pass, scan each slot in the adj node list.
        for (int i = 0, end = mProcessRecordAdjNodes.size() - 1; i < end; i++) {
            mProcessRecordAdjNodes.forEachNewNode(i, mComputeConnectionsConsumer);
        }
    }

    /**
     * Perform a partial update on the target processes and their reachable processes.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void partialUpdateLSP(@OomAdjReason int oomAdjReason, ArraySet<ProcessRecord> targets) {
        final ProcessRecord topApp = mService.getTopApp();
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long oldTime = now - mConstants.mMaxEmptyTimeMillis;

        ActiveUids activeUids = mTmpUidRecords;
        activeUids.clear();
        mTmpOomAdjusterArgs.update(topApp, now, UNKNOWN_ADJ, oomAdjReason, activeUids, false);

        mAdjSeq++;

        final ArrayList<ProcessRecord> reachables = mTmpProcessList;
        reachables.clear();

        for (int i = 0, size = targets.size(); i < size; i++) {
            final ProcessRecord target = targets.valueAtUnchecked(i);
            target.mState.resetCachedInfo();
            target.mState.setReachable(true);
            reachables.add(target);
        }

        // Collect all processes that are reachable.
        // Any process not found in this step will not change in importance during this update.
        collectAndMarkReachableProcessesLSP(reachables);

        // Initialize the reachable processes based on their own values plus any
        // connections from processes not found in the previous step. Since those non-reachable
        // processes cannot change as a part of this update, their current values can be used
        // right now.
        mProcessRecordProcStateNodes.resetLastNodes();
        initReachableStatesLSP(reachables, targets.size(), mTmpOomAdjusterArgs);

        // Set adj last nodes now, this way a process will only be reevaluated during the adj node
        // iteration if they adj score changed during the procState node iteration.
        mProcessRecordAdjNodes.resetLastNodes();
        // Now traverse and compute the connections of processes with changed importance.
        computeConnectionsLSP();

        boolean unassignedAdj = false;
        for (int i = 0, size = reachables.size(); i < size; i++) {
            final ProcessStateRecord state = reachables.get(i).mState;
            state.setReachable(false);
            state.setCompletedAdjSeq(mAdjSeq);
            if (state.getCurAdj() >= UNKNOWN_ADJ) {
                unassignedAdj = true;
            }
        }

        // If all processes have an assigned adj, no need to calculate and assign cached adjs.
        if (unassignedAdj) {
            // TODO: b/319163103 - optimize cache adj assignment to not require the whole lru list.
            assignCachedAdjIfNecessary(mProcessList.getLruProcessesLOSP());
        }

        // Repopulate any uid record that may have changed.
        for (int i = 0, size = activeUids.size(); i < size; i++) {
            final UidRecord ur = activeUids.valueAt(i);
            ur.reset();
            for (int j = ur.getNumOfProcs() - 1; j >= 0; j--) {
                final ProcessRecord proc = ur.getProcessRecordByIndex(j);
                updateAppUidRecIfNecessaryLSP(proc);
            }
        }

        postUpdateOomAdjInnerLSP(oomAdjReason, activeUids, now, nowElapsed, oldTime, false);
    }

    /**
     * Mark all processes reachable from the {@code reachables} processes and add them to the
     * provided {@code reachables} list (targets excluded).
     */
    @GuardedBy({"mService", "mProcLock"})
    private void collectAndMarkReachableProcessesLSP(ArrayList<ProcessRecord> reachables) {
        mReachableCollectingConsumer.init(reachables);
        for (int i = 0; i < reachables.size(); i++) {
            ProcessRecord pr = reachables.get(i);
            forEachConnectionLSP(pr, mReachableCollectingConsumer);
        }
    }

    /**
     * Calculate initial importance states for {@code reachables} and update their slot position
     * if necessary.
     */
    private void initReachableStatesLSP(ArrayList<ProcessRecord> reachables, int targetCount,
            OomAdjusterArgs args) {
        int i = 0;
        boolean initReachables = !Flags.skipUnimportantConnections();
        for (; i < targetCount && !initReachables; i++) {
            final ProcessRecord target = reachables.get(i);
            final int prevProcState = target.mState.getCurProcState();
            final int prevAdj = target.mState.getCurRawAdj();
            final int prevCapability = target.mState.getCurCapability();
            final boolean prevShouldNotFreeze = target.mOptRecord.shouldNotFreeze();

            args.mApp = target;
            // If target client is a reachable, reachables need to be reinited in case this
            // client is important enough to change this target in the computeConnection step.
            initReachables |= computeOomAdjIgnoringReachablesLSP(args);
            // If target lowered in importance, reachables need to be reinited because this
            // target may have been the source of a reachable's current importance.
            initReachables |= selfImportanceLoweredLSP(target, prevProcState, prevAdj,
                    prevCapability, prevShouldNotFreeze);

            updateProcStateSlot(target, prevProcState);
            updateAdjSlot(target, prevAdj);
        }

        if (!initReachables) {
            return;
        }

        for (int size = reachables.size(); i < size; i++) {
            final ProcessRecord reachable = reachables.get(i);
            final int prevProcState = reachable.mState.getCurProcState();
            final int prevAdj = reachable.mState.getCurRawAdj();

            args.mApp = reachable;
            computeOomAdjIgnoringReachablesLSP(args);

            updateProcStateSlot(reachable, prevProcState);
            updateAdjSlot(reachable, prevAdj);
        }
    }

    /**
     * Calculate initial importance states for {@code app}.
     * Processes not marked reachable cannot change as a part of this update, so connections from
     * those process can be calculated now.
     *
     * Returns true if any client connection was skipped due to a reachablity cycle.
     */
    @GuardedBy({"mService", "mProcLock"})
    private boolean computeOomAdjIgnoringReachablesLSP(OomAdjusterArgs args) {
        final ProcessRecord app = args.mApp;
        final ProcessRecord topApp = args.mTopApp;
        final long now = args.mNow;
        final @OomAdjReason int oomAdjReason = args.mOomAdjReason;

        computeOomAdjLSP(app, UNKNOWN_ADJ, topApp, false, now, false, false, oomAdjReason, false);

        mComputeConnectionIgnoringReachableClientsConsumer.init(args);
        forEachClientConnectionLSP(app, mComputeConnectionIgnoringReachableClientsConsumer);
        return mComputeConnectionIgnoringReachableClientsConsumer.hasReachableClient;
    }

    /**
     * Stream the connections with {@code app} as a client to
     * {@code connectionConsumer}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static void forEachConnectionLSP(ProcessRecord app,
            BiConsumer<Connection, ProcessRecord> connectionConsumer) {
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
                    && service.mState.getCurProcState() <= PROCESS_STATE_TOP)
                    || (service.isSdkSandbox && cr.binding.attributedClient != null)) {
                continue;
            }
            connectionConsumer.accept(cr, service);
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
            connectionConsumer.accept(cr, service);
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
            connectionConsumer.accept(cpc, provider);
        }
    }

    /**
     * Stream the connections from clients with {@code app} as the host to {@code
     * connectionConsumer}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static void forEachClientConnectionLSP(ProcessRecord app,
            BiConsumer<Connection, ProcessRecord> connectionConsumer) {
        final ProcessServiceRecord psr = app.mServices;

        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            final ServiceRecord s = psr.getRunningServiceAt(i);
            final ArrayMap<IBinder, ArrayList<ConnectionRecord>> serviceConnections =
                    s.getConnections();
            for (int j = serviceConnections.size() - 1; j >= 0; j--) {
                final ArrayList<ConnectionRecord> clist = serviceConnections.valueAt(j);
                for (int k = clist.size() - 1; k >= 0; k--) {
                    final ConnectionRecord cr = clist.get(k);
                    final ProcessRecord client;
                    if (app.isSdkSandbox && cr.binding.attributedClient != null) {
                        client = cr.binding.attributedClient;
                    } else {
                        client = cr.binding.client;
                    }
                    if (client == null || client == app) continue;
                    connectionConsumer.accept(cr, client);
                }
            }
        }

        final ProcessProviderRecord ppr = app.mProviders;
        for (int i = ppr.numberOfProviders() - 1; i >= 0; i--) {
            final ContentProviderRecord cpr = ppr.getProviderAt(i);
            for (int j = cpr.connections.size() - 1; j >= 0; j--) {
                final ContentProviderConnection conn = cpr.connections.get(j);
                connectionConsumer.accept(conn, conn.client);
            }
        }
    }

    /**
     * Returns true if at least one the provided values is more important than those in {@code app}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static boolean selfImportanceLoweredLSP(ProcessRecord app, int prevProcState,
            int prevAdj, int prevCapability, boolean prevShouldNotFreeze) {
        if (app.mState.getCurProcState() > prevProcState) {
            return true;
        }
        if (app.mState.getCurRawAdj() > prevAdj)  {
            return true;
        }
        if ((app.mState.getCurCapability() & prevCapability) != prevCapability)  {
            return true;
        }
        if (!app.mOptRecord.shouldNotFreeze() && prevShouldNotFreeze) {
            // No long marked as should not freeze.
            return true;
        }
        return false;
    }

    /**
     * Returns whether a host connection evaluation can be skipped due to lack of importance.
     * Note: the client and host need to be provided as well for the isolated and sandbox
     * scenarios.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static boolean unimportantConnectionLSP(Connection conn,
            ProcessRecord host, ProcessRecord client) {
        if (!Flags.skipUnimportantConnections()) {
            // Feature not enabled, just return false so the connection is evaluated.
            return false;
        }
        if (host.mState.getCurProcState() > client.mState.getCurProcState()) {
            return false;
        }
        if (host.mState.getCurRawAdj() > client.mState.getCurRawAdj())  {
            return false;
        }
        final int serviceCapability = host.mState.getCurCapability();
        final int clientCapability = client.mState.getCurCapability();
        if ((serviceCapability & clientCapability) != clientCapability) {
            // Client has a capability the host does not have.
            if ((clientCapability & PROCESS_CAPABILITY_BFSL) == PROCESS_CAPABILITY_BFSL
                    && (serviceCapability & PROCESS_CAPABILITY_BFSL) == 0) {
                // The BFSL capability does not need a flag to propagate.
                return false;
            }
            if (conn.canAffectCapabilities()) {
                // One of these bind flags may propagate that capability.
                return false;
            }
        }

        if (!host.mOptRecord.shouldNotFreeze() && client.mOptRecord.shouldNotFreeze()) {
            // If the client is marked as should not freeze, so should the host.
            return false;
        }
        return true;
    }
}
