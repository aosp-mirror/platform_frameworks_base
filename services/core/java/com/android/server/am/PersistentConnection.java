/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Connects to a given service component on a given user.
 *
 * - Call {@link #bind()} to create a connection.
 * - Call {@link #unbind()} to disconnect.  Make sure to disconnect when the user stops.
 *
 * Add onConnected/onDisconnected callbacks as needed.
 *
 * When the target process gets killed (by OOM-killer, etc), then the activity manager will
 * re-connect the connection automatically, in which case onServiceDisconnected() gets called
 * and then onServiceConnected().
 *
 * However sometimes the activity manager just "kills" the connection -- like when the target
 * package gets updated or the target process crashes multiple times in a row, in which case
 * onBindingDied() gets called.  This class handles this case by re-connecting in the time
 * {@link #mRebindBackoffMs}.  If this happens again, this class increases the back-off time
 * by {@link #mRebindBackoffIncrease} and retry.  The back-off time is capped at
 * {@link #mRebindMaxBackoffMs}.
 *
 * The back-off time will never be reset until {@link #unbind()} and {@link #bind()} are called
 * explicitly.
 *
 * NOTE: This class does *not* handle package-updates -- i.e. even if the binding dies due to
 * the target package being updated, this class won't reconnect.  This is because this class doesn't
 * know what to do when the service component has gone missing, for example.  If the user of this
 * class wants to restore the connection, then it should call {@link #unbind()} and {@link #bind}
 * explicitly.
 */
public abstract class PersistentConnection<T> {
    private final Object mLock = new Object();

    private final static boolean DEBUG = false;

    private final String mTag;
    private final Context mContext;
    private final Handler mHandler;
    private final int mUserId;
    private final ComponentName mComponentName;

    private long mNextBackoffMs;

    private final long mRebindBackoffMs;
    private final double mRebindBackoffIncrease;
    private final long mRebindMaxBackoffMs;

    private long mReconnectTime;

    // TODO too many booleans... Should clean up.

    @GuardedBy("mLock")
    private boolean mBound;

    /**
     * Whether {@link #bind()} has been called and {@link #unbind()} hasn't been yet; meaning this
     * is the expected bind state from the caller's point of view.
     */
    @GuardedBy("mLock")
    private boolean mShouldBeBound;

    @GuardedBy("mLock")
    private boolean mRebindScheduled;

    @GuardedBy("mLock")
    private boolean mIsConnected;

    @GuardedBy("mLock")
    private T mService;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (!mBound) {
                    // Callback came in after PersistentConnection.unbind() was called.
                    // We just ignore this.
                    // (We've already called unbindService() already in unbind)
                    Slog.w(mTag, "Connected: " + mComponentName.flattenToShortString()
                            + " u" + mUserId + " but not bound, ignore.");
                    return;
                }
                Slog.i(mTag, "Connected: " + mComponentName.flattenToShortString()
                        + " u" + mUserId);

                mIsConnected = true;
                mService = asInterface(service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Slog.i(mTag, "Disconnected: " + mComponentName.flattenToShortString()
                        + " u" + mUserId);

                cleanUpConnectionLocked();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Activity manager gave up; we'll schedule a re-connect by ourselves.
            synchronized (mLock) {
                if (!mBound) {
                    // Callback came in late?
                    Slog.w(mTag, "Binding died: " + mComponentName.flattenToShortString()
                            + " u" + mUserId + " but not bound, ignore.");
                    return;
                }

                Slog.w(mTag, "Binding died: " + mComponentName.flattenToShortString()
                        + " u" + mUserId);
                scheduleRebindLocked();
            }
        }
    };

    private final Runnable mBindForBackoffRunnable = () -> bindForBackoff();

    public PersistentConnection(@NonNull String tag, @NonNull Context context,
            @NonNull Handler handler, int userId, @NonNull ComponentName componentName,
            long rebindBackoffSeconds, double rebindBackoffIncrease, long rebindMaxBackoffSeconds) {
        mTag = tag;
        mContext = context;
        mHandler = handler;
        mUserId = userId;
        mComponentName = componentName;

        mRebindBackoffMs = rebindBackoffSeconds * 1000;
        mRebindBackoffIncrease = rebindBackoffIncrease;
        mRebindMaxBackoffMs = rebindMaxBackoffSeconds * 1000;

        mNextBackoffMs = mRebindBackoffMs;
    }

    public final ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * @return whether {@link #bind()} has been called and {@link #unbind()} hasn't.
     *
     * Note when the AM gives up on connection, this class detects it and un-bind automatically,
     * and schedule rebind, and {@link #isBound} returns false when it's waiting for a retry.
     */
    public final boolean isBound() {
        synchronized (mLock) {
            return mBound;
        }
    }

    /**
     * @return whether re-bind is scheduled after the AM gives up on a connection.
     */
    public final boolean isRebindScheduled() {
        synchronized (mLock) {
            return mRebindScheduled;
        }
    }

    /**
     * @return whether connected.
     */
    public final boolean isConnected() {
        synchronized (mLock) {
            return mIsConnected;
        }
    }

    /**
     * @return the service binder interface.
     */
    public final T getServiceBinder() {
        synchronized (mLock) {
            return mService;
        }
    }

    /**
     * Connects to the service.
     */
    public final void bind() {
        synchronized (mLock) {
            mShouldBeBound = true;

            bindInnerLocked(/* resetBackoff= */ true);
        }
    }

    public final void bindInnerLocked(boolean resetBackoff) {
        unscheduleRebindLocked();

        if (mBound) {
            return;
        }
        mBound = true;

        if (resetBackoff) {
            // Note this is the only place we reset the backoff time.
            mNextBackoffMs = mRebindBackoffMs;
        }

        final Intent service = new Intent().setComponent(mComponentName);

        if (DEBUG) {
            Slog.d(mTag, "Attempting to connect to " + mComponentName);
        }

        final boolean success = mContext.bindServiceAsUser(service, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                mHandler, UserHandle.of(mUserId));

        if (!success) {
            Slog.e(mTag, "Binding: " + service.getComponent() + " u" + mUserId
                    + " failed.");
        }
    }

    final void bindForBackoff() {
        synchronized (mLock) {
            if (!mShouldBeBound) {
                // Race condition -- by the time we got here, unbind() has already been called.
                return;
            }

            bindInnerLocked(/* resetBackoff= */ false);
        }
    }

    private void cleanUpConnectionLocked() {
        mIsConnected = false;
        mService = null;
    }

    /**
     * Disconnect from the service.
     */
    public final void unbind() {
        synchronized (mLock) {
            mShouldBeBound = false;

            unbindLocked();
        }
    }

    private final void unbindLocked() {
        unscheduleRebindLocked();

        if (!mBound) {
            return;
        }
        Slog.i(mTag, "Stopping: " + mComponentName.flattenToShortString() + " u" + mUserId);
        mBound = false;
        mContext.unbindService(mServiceConnection);

        cleanUpConnectionLocked();
    }

    void unscheduleRebindLocked() {
        injectRemoveCallbacks(mBindForBackoffRunnable);
        mRebindScheduled = false;
    }

    void scheduleRebindLocked() {
        unbindLocked();

        if (!mRebindScheduled) {
            Slog.i(mTag, "Scheduling to reconnect in " + mNextBackoffMs + " ms (uptime)");

            mReconnectTime = injectUptimeMillis() + mNextBackoffMs;

            injectPostAtTime(mBindForBackoffRunnable, mReconnectTime);

            mNextBackoffMs = Math.min(mRebindMaxBackoffMs,
                    (long) (mNextBackoffMs * mRebindBackoffIncrease));

            mRebindScheduled = true;
        }
    }

    /** Must be implemented by a subclass to convert an {@link IBinder} to a stub. */
    protected abstract T asInterface(IBinder binder);

    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            pw.print(prefix);
            pw.print(mComponentName.flattenToShortString());
            pw.print(mBound ? "  [bound]" : "  [not bound]");
            pw.print(mIsConnected ? "  [connected]" : "  [not connected]");
            if (mRebindScheduled) {
                pw.print("  reconnect in ");
                TimeUtils.formatDuration((mReconnectTime - injectUptimeMillis()), pw);
            }
            pw.println();

            pw.print(prefix);
            pw.print("  Next backoff(sec): ");
            pw.print(mNextBackoffMs / 1000);
        }
    }

    @VisibleForTesting
    void injectRemoveCallbacks(Runnable r) {
        mHandler.removeCallbacks(r);
    }

    @VisibleForTesting
    void injectPostAtTime(Runnable r, long uptimeMillis) {
        mHandler.postAtTime(r, uptimeMillis);
    }

    @VisibleForTesting
    long injectUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    @VisibleForTesting
    long getNextBackoffMsForTest() {
        return mNextBackoffMs;
    }

    @VisibleForTesting
    long getReconnectTimeForTest() {
        return mReconnectTime;
    }

    @VisibleForTesting
    ServiceConnection getServiceConnectionForTest() {
        return mServiceConnection;
    }

    @VisibleForTesting
    Runnable getBindForBackoffRunnableForTest() {
        return mBindForBackoffRunnable;
    }

    @VisibleForTesting
    boolean shouldBeBoundForTest() {
        return mShouldBeBound;
    }
}
