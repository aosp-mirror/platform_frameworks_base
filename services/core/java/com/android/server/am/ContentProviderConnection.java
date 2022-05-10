/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROVIDER;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.AssociationState;
import com.android.internal.app.procstats.ProcessStats;

/**
 * Represents a link between a content provider and client.
 */
public final class ContentProviderConnection extends Binder {
    public final ContentProviderRecord provider;
    public final ProcessRecord client;
    public final String clientPackage;
    public AssociationState.SourceState association;
    public final long createTime;
    private Object mProcStatsLock;  // Internal lock for accessing AssociationState

    /**
     * Internal lock that guards access to the two counters.
     */
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mStableCount;
    @GuardedBy("mLock")
    private int mUnstableCount;
    // The client of this connection is currently waiting for the provider to appear.
    // Protected by the provider lock.
    public boolean waiting;
    // The provider of this connection is now dead.
    public boolean dead;

    // The original user id when this connection was requested, it could be different from
    // the client's user id because the client could request to access a content provider
    // living in a different user if it has the permission.
    @UserIdInt final int mExpectedUserId;

    // For debugging.
    private int mNumStableIncs;
    private int mNumUnstableIncs;

    public ContentProviderConnection(ContentProviderRecord _provider, ProcessRecord _client,
            String _clientPackage, @UserIdInt int _expectedUserId) {
        provider = _provider;
        client = _client;
        clientPackage = _clientPackage;
        mExpectedUserId = _expectedUserId;
        createTime = SystemClock.elapsedRealtime();
    }

    public void startAssociationIfNeeded() {
        // If we don't already have an active association, create one...  but only if this
        // is an association between two different processes.
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS
                && association == null && provider.proc != null
                && (provider.appInfo.uid != client.uid
                        || !provider.info.processName.equals(client.processName))) {
            ProcessStats.ProcessStateHolder holder = provider.proc.getPkgList().get(
                    provider.name.getPackageName());
            if (holder == null) {
                Slog.wtf(TAG_AM, "No package in referenced provider "
                        + provider.name.toShortString() + ": proc=" + provider.proc);
            } else if (holder.pkg == null) {
                Slog.wtf(TAG_AM, "Inactive holder in referenced provider "
                        + provider.name.toShortString() + ": proc=" + provider.proc);
            } else {
                mProcStatsLock = provider.proc.mService.mProcessStats.mLock;
                synchronized (mProcStatsLock) {
                    association = holder.pkg.getAssociationStateLocked(holder.state,
                            provider.name.getClassName()).startSource(client.uid,
                            client.processName, clientPackage);
                }
            }
        }
    }

    /**
     * Track the given proc state change.
     */
    public void trackProcState(int procState, int seq) {
        if (association != null) {
            synchronized (mProcStatsLock) {
                association.trackProcState(procState, seq, SystemClock.uptimeMillis());
            }
        }
    }

    public void stopAssociation() {
        if (association != null) {
            synchronized (mProcStatsLock) {
                association.stop();
            }
            association = null;
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ContentProviderConnection{");
        toShortString(sb);
        sb.append('}');
        return sb.toString();
    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder(128);
        toShortString(sb);
        return sb.toString();
    }

    public String toClientString() {
        StringBuilder sb = new StringBuilder(128);
        toClientString(sb);
        return sb.toString();
    }

    public void toShortString(StringBuilder sb) {
        sb.append(provider.toShortString());
        sb.append("->");
        toClientString(sb);
    }

    public void toClientString(StringBuilder sb) {
        sb.append(client.toShortString());
        synchronized (mLock) {
            sb.append(" s");
            sb.append(mStableCount);
            sb.append("/");
            sb.append(mNumStableIncs);
            sb.append(" u");
            sb.append(mUnstableCount);
            sb.append("/");
            sb.append(mNumUnstableIncs);
        }
        if (waiting) {
            sb.append(" WAITING");
        }
        if (dead) {
            sb.append(" DEAD");
        }
        long nowReal = SystemClock.elapsedRealtime();
        sb.append(" ");
        TimeUtils.formatDuration(nowReal-createTime, sb);
    }

    /**
     * Initializes the reference counts.  Either the stable or unstable count
     * is set to 1; the other reference count is set to zero.
     */
    public void initializeCount(boolean stable) {
        synchronized (mLock) {
            if (stable) {
                mStableCount = 1;
                mNumStableIncs = 1;
                mUnstableCount = 0;
                mNumUnstableIncs = 0;
            } else {
                mStableCount = 0;
                mNumStableIncs = 0;
                mUnstableCount = 1;
                mNumUnstableIncs = 1;
            }
        }
    }

    /**
     * Increments the stable or unstable reference count and return the total
     * number of references.
     */
    public int incrementCount(boolean stable) {
        synchronized (mLock) {
            if (DEBUG_PROVIDER) {
                final ContentProviderRecord cpr = provider;
                Slog.v(TAG_AM,
                       "Adding provider requested by "
                       + client.processName + " from process "
                       + cpr.info.processName + ": " + cpr.name.flattenToShortString()
                       + " scnt=" + mStableCount + " uscnt=" + mUnstableCount);
            }
            if (stable) {
                mStableCount++;
                mNumStableIncs++;
            } else {
                mUnstableCount++;
                mNumUnstableIncs++;
            }
            return mStableCount + mUnstableCount;
        }
    }

    /**
     * Decrements either the stable or unstable count and return the total
     * number of references.
     */
    public int decrementCount(boolean stable) {
        synchronized (mLock) {
            if (DEBUG_PROVIDER) {
                final ContentProviderRecord cpr = provider;
                Slog.v(TAG_AM,
                       "Removing provider requested by "
                       + client.processName + " from process "
                       + cpr.info.processName + ": " + cpr.name.flattenToShortString()
                       + " scnt=" + mStableCount + " uscnt=" + mUnstableCount);
            }
            if (stable) {
                mStableCount--;
            } else {
                mUnstableCount--;
            }
            return mStableCount + mUnstableCount;
        }
    }

    /**
     * Adjusts the reference counts up or down (the inputs may be positive,
     * zero, or negative.  This method does not return a total count because
     * a return is not needed for the current use case.
    */
    public void adjustCounts(int stableIncrement, int unstableIncrement) {
        synchronized (mLock) {
            if (stableIncrement > 0) {
                mNumStableIncs += stableIncrement;
            }
            final int stable = mStableCount + stableIncrement;
            if (stable < 0) {
                throw new IllegalStateException("stableCount < 0: " + stable);
            }
            if (unstableIncrement > 0) {
                mNumUnstableIncs += unstableIncrement;
            }
            final int unstable = mUnstableCount + unstableIncrement;
            if (unstable < 0) {
                throw new IllegalStateException("unstableCount < 0: " + unstable);
            }
            if ((stable + unstable) <= 0) {
                throw new IllegalStateException("ref counts can't go to zero here: stable="
                                                + stable + " unstable=" + unstable);
            }
            mStableCount = stable;
            mUnstableCount = unstable;
        }
    }

    /**
     * Returns the number of stable references.
     */
    public int stableCount() {
        synchronized (mLock) {
            return mStableCount;
        }
    }

    /**
     * Returns the number of unstable references.
     */
    public int unstableCount() {
        synchronized (mLock) {
            return mUnstableCount;
        }
    }

    /**
     * Returns the total number of stable and unstable references.
     */
    int totalRefCount() {
        synchronized (mLock) {
            return mStableCount + mUnstableCount;
        }
    }
}
