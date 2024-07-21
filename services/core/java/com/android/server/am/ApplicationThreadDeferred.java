/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.IntDef;
import android.app.IApplicationThread;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * A subclass of {@link IApplicationThread} that defers certain binder calls while the process is
 * paused (frozen).  Any deferred calls are executed when the process is unpaused.  In some cases,
 * multiple instances of deferred calls are collapsed into a single call when the process is
 * unpaused.
 *
 * {@hide}
 */
final class ApplicationThreadDeferred extends IApplicationThread.Delegator {

    static final String TAG = TAG_WITH_CLASS_NAME ? "ApplicationThreadDeferred" : TAG_AM;

    // The flag that enables the deferral behavior of this class.  If the flag is disabled then
    // the class behaves exactly like an ApplicationThreadFilter.
    private static boolean deferBindersWhenPaused() {
        return Flags.deferBindersWhenPaused();
    }

    // The list of notifications that may be deferred.
    private static final int CLEAR_DNS_CACHE = 0;
    private static final int UPDATE_TIME_ZONE = 1;
    private static final int SCHEDULE_LOW_MEMORY = 2;
    private static final int UPDATE_HTTP_PROXY = 3;
    private static final int NOTIFICATION_COUNT = 4;

    @IntDef(value = {
                CLEAR_DNS_CACHE,
                UPDATE_TIME_ZONE,
                SCHEDULE_LOW_MEMORY,
                UPDATE_HTTP_PROXY
            })
    @Retention(RetentionPolicy.SOURCE)
    private @interface NotificationType {};

    private final Object mLock = new Object();

    // If this is true, notifications should be queued for later delivery.  If this is false,
    // notifications should be delivered immediately.
    @GuardedBy("mLock")
    private boolean mPaused = false;

    // An operation is a lambda that throws an exception.
    private interface Operation {
        void run() throws RemoteException;
    }

    // The array of operations.
    @GuardedBy("mLock")
    private final Operation[] mOperations = new Operation[NOTIFICATION_COUNT];

    // The array of operations that actually pending right now.
    @GuardedBy("mLock")
    private final boolean[] mPending = new boolean[NOTIFICATION_COUNT];

    // When true, binder calls to paused processes will be deferred until the process is unpaused.
    private final boolean mDefer;

    // The base thread, because Delegator does not expose it.
    private final IApplicationThread mBase;

    /** Create an instance with a base thread and a deferral enable flag. */
    @VisibleForTesting
    public ApplicationThreadDeferred(IApplicationThread thread, boolean defer) {
        super(thread);

        mBase = thread;
        mDefer = defer;

        mOperations[CLEAR_DNS_CACHE] = () -> { super.clearDnsCache(); };
        mOperations[UPDATE_TIME_ZONE] = () -> { super.updateTimeZone(); };
        mOperations[SCHEDULE_LOW_MEMORY] = () -> { super.scheduleLowMemory(); };
        mOperations[UPDATE_HTTP_PROXY] = () -> { super.updateHttpProxy(); };
    }

    /** Create an instance with a base flag, using the system deferral enable flag. */
    public ApplicationThreadDeferred(IApplicationThread thread) {
        this(thread, deferBindersWhenPaused());
    }

    /**
     * Return the implementation's value of asBinder(). super.asBinder() is not a real Binder
     * object.
     */
    @Override
    public  android.os.IBinder asBinder() {
        return mBase.asBinder();
    }

    /** The process is being paused.  Start deferring calls. */
    void onProcessPaused() {
        synchronized (mLock) {
            mPaused = true;
        }
    }

    /** The process is no longer paused.  Drain any deferred calls. */
    void onProcessUnpaused() {
        synchronized (mLock) {
            mPaused = false;
            try {
                for (int i = 0; i < mOperations.length; i++) {
                    if (mPending[i]) {
                        mOperations[i].run();
                    }
                }
            } catch (RemoteException e) {
                // Swallow the exception.  The caller is not expecting it.  Remote exceptions
                // happen if a has process died; there is no need to report it here.
            } finally {
                Arrays.fill(mPending, false);
            }
        }
    }

    /** The pause operation has been canceled.  Drain any deferred calls. */
    void onProcessPausedCancelled() {
        onProcessUnpaused();
    }

    /**
     * If the thread is not paused, execute the operation.  Otherwise, save it to the pending
     * list.
     */
    private void execute(@NotificationType int tag) throws RemoteException {
        synchronized (mLock) {
            if (mPaused && mDefer) {
                mPending[tag] = true;
                return;
            }
        }
        // Outside the synchronization block to avoid contention.
        mOperations[tag].run();
    }

    @Override
    public void clearDnsCache() throws RemoteException {
        execute(CLEAR_DNS_CACHE);
    }

    @Override
    public void updateTimeZone() throws RemoteException {
        execute(UPDATE_TIME_ZONE);
    }

    @Override
    public void scheduleLowMemory() throws RemoteException {
        execute(SCHEDULE_LOW_MEMORY);
    }

    @Override
    public void updateHttpProxy() throws RemoteException {
        execute(UPDATE_HTTP_PROXY);
    }
}
