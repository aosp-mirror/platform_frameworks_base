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

package com.android.server.am;

import android.content.ContentResolver;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Queue of broadcast intents and associated bookkeeping.
 */
public abstract class BroadcastQueue {
    public static final String TAG = "BroadcastQueue";

    final ActivityManagerService mService;
    final Handler mHandler;
    final BroadcastConstants mConstants;
    final BroadcastSkipPolicy mSkipPolicy;
    final String mQueueName;

    BroadcastQueue(ActivityManagerService service, Handler handler,
            String name, BroadcastConstants constants) {
        mService = service;
        mHandler = handler;
        mQueueName = name;
        mConstants = constants;
        mSkipPolicy = new BroadcastSkipPolicy(service);
    }

    void start(ContentResolver resolver) {
        mConstants.startObserving(mHandler, resolver);
    }

    @Override
    public String toString() {
        return mQueueName;
    }

    public abstract boolean isDelayBehindServices();

    public abstract BroadcastRecord getPendingBroadcastLocked();

    public abstract BroadcastRecord getActiveBroadcastLocked();

    public abstract void enqueueParallelBroadcastLocked(BroadcastRecord r);

    public abstract void enqueueOrderedBroadcastLocked(BroadcastRecord r);

    public abstract BroadcastRecord replaceParallelBroadcastLocked(BroadcastRecord r);

    public abstract BroadcastRecord replaceOrderedBroadcastLocked(BroadcastRecord r);

    public abstract void updateUidReadyForBootCompletedBroadcastLocked(int uid);

    public abstract boolean sendPendingBroadcastsLocked(ProcessRecord app);

    public abstract void skipPendingBroadcastLocked(int pid);

    public abstract void skipCurrentReceiverLocked(ProcessRecord app);

    public abstract void scheduleBroadcastsLocked();

    public abstract BroadcastRecord getMatchingOrderedReceiver(IBinder receiver);

    /**
     * Signal delivered back from a {@link BroadcastReceiver} to indicate that
     * it's finished processing the current broadcast being dispatched to it.
     * <p>
     * If this signal isn't delivered back in a timely fashion, we assume the
     * receiver has somehow wedged and we trigger an ANR.
     */
    public abstract boolean finishReceiverLocked(BroadcastRecord r, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices);

    public abstract void backgroundServicesFinishedLocked(int userId);

    public abstract void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver,
            Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser,
            int receiverUid, int callingUid, long dispatchDelay,
            long receiveDelay) throws RemoteException;

    public abstract void processNextBroadcastLocked(boolean fromMsg, boolean skipOomAdj);

    /**
     * Signal from OS internals that the given package (or some subset of that
     * package) has been disabled or uninstalled, and that any pending
     * broadcasts should be cleaned up.
     */
    public abstract boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit);

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     *
     * @see #flush()
     */
    public abstract boolean isIdle();

    /**
     * Brief summary of internal state, useful for debugging purposes.
     */
    public abstract String describeState();

    /**
     * Flush any broadcasts still waiting to be delivered, causing them to be
     * delivered as soon as possible.
     *
     * @see #isIdle()
     */
    public abstract void flush();

    public abstract void dumpDebug(ProtoOutputStream proto, long fieldId);

    public abstract boolean dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage, boolean needSep);
}
