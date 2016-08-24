/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.net;

import static android.net.TrafficStats.MB_IN_BYTES;
import static com.android.internal.util.Preconditions.checkArgument;

import android.app.usage.NetworkStatsManager;
import android.net.DataUsageRequest;
import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicObserver;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.SparseArray;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.VpnInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages observers of {@link NetworkStats}. Allows observers to be notified when
 * data usage has been reported in {@link NetworkStatsService}. An observer can set
 * a threshold of how much data it cares about to be notified.
 */
class NetworkStatsObservers {
    private static final String TAG = "NetworkStatsObservers";
    private static final boolean LOGV = false;

    private static final long MIN_THRESHOLD_BYTES = 2 * MB_IN_BYTES;

    private static final int MSG_REGISTER = 1;
    private static final int MSG_UNREGISTER = 2;
    private static final int MSG_UPDATE_STATS = 3;

    // All access to this map must be done from the handler thread.
    // indexed by DataUsageRequest#requestId
    private final SparseArray<RequestInfo> mDataUsageRequests = new SparseArray<>();

    // Sequence number of DataUsageRequests
    private final AtomicInteger mNextDataUsageRequestId = new AtomicInteger();

    // Lazily instantiated when an observer is registered.
    private Handler mHandler;

    /**
     * Creates a wrapper that contains the caller context and a normalized request.
     * The request should be returned to the caller app, and the wrapper should be sent to this
     * object through #addObserver by the service handler.
     *
     * <p>It will register the observer asynchronously, so it is safe to call from any thread.
     *
     * @return the normalized request wrapped within {@link RequestInfo}.
     */
    public DataUsageRequest register(DataUsageRequest inputRequest, Messenger messenger,
                IBinder binder, int callingUid, @NetworkStatsAccess.Level int accessLevel) {
        DataUsageRequest request = buildRequest(inputRequest);
        RequestInfo requestInfo = buildRequestInfo(request, messenger, binder, callingUid,
                accessLevel);

        if (LOGV) Slog.v(TAG, "Registering observer for " + request);
        getHandler().sendMessage(mHandler.obtainMessage(MSG_REGISTER, requestInfo));
        return request;
    }

    /**
     * Unregister a data usage observer.
     *
     * <p>It will unregister the observer asynchronously, so it is safe to call from any thread.
     */
    public void unregister(DataUsageRequest request, int callingUid) {
        getHandler().sendMessage(mHandler.obtainMessage(MSG_UNREGISTER, callingUid, 0 /* ignore */,
                request));
    }

    /**
     * Updates data usage statistics of registered observers and notifies if limits are reached.
     *
     * <p>It will update stats asynchronously, so it is safe to call from any thread.
     */
    public void updateStats(NetworkStats xtSnapshot, NetworkStats uidSnapshot,
                ArrayMap<String, NetworkIdentitySet> activeIfaces,
                ArrayMap<String, NetworkIdentitySet> activeUidIfaces,
                VpnInfo[] vpnArray, long currentTime) {
        StatsContext statsContext = new StatsContext(xtSnapshot, uidSnapshot, activeIfaces,
                activeUidIfaces, vpnArray, currentTime);
        getHandler().sendMessage(mHandler.obtainMessage(MSG_UPDATE_STATS, statsContext));
    }

    private Handler getHandler() {
        if (mHandler == null) {
            synchronized (this) {
                if (mHandler == null) {
                    if (LOGV) Slog.v(TAG, "Creating handler");
                    mHandler = new Handler(getHandlerLooperLocked(), mHandlerCallback);
                }
            }
        }
        return mHandler;
    }

    @VisibleForTesting
    protected Looper getHandlerLooperLocked() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        return handlerThread.getLooper();
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER: {
                    handleRegister((RequestInfo) msg.obj);
                    return true;
                }
                case MSG_UNREGISTER: {
                    handleUnregister((DataUsageRequest) msg.obj, msg.arg1 /* callingUid */);
                    return true;
                }
                case MSG_UPDATE_STATS: {
                    handleUpdateStats((StatsContext) msg.obj);
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

    /**
     * Adds a {@link RequestInfo} as an observer.
     * Should only be called from the handler thread otherwise there will be a race condition
     * on mDataUsageRequests.
     */
    private void handleRegister(RequestInfo requestInfo) {
        mDataUsageRequests.put(requestInfo.mRequest.requestId, requestInfo);
    }

    /**
     * Removes a {@link DataUsageRequest} if the calling uid is authorized.
     * Should only be called from the handler thread otherwise there will be a race condition
     * on mDataUsageRequests.
     */
    private void handleUnregister(DataUsageRequest request, int callingUid) {
        RequestInfo requestInfo;
        requestInfo = mDataUsageRequests.get(request.requestId);
        if (requestInfo == null) {
            if (LOGV) Slog.v(TAG, "Trying to unregister unknown request " + request);
            return;
        }
        if (Process.SYSTEM_UID != callingUid && requestInfo.mCallingUid != callingUid) {
            Slog.w(TAG, "Caller uid " + callingUid + " is not owner of " + request);
            return;
        }

        if (LOGV) Slog.v(TAG, "Unregistering " + request);
        mDataUsageRequests.remove(request.requestId);
        requestInfo.unlinkDeathRecipient();
        requestInfo.callCallback(NetworkStatsManager.CALLBACK_RELEASED);
    }

    private void handleUpdateStats(StatsContext statsContext) {
        if (mDataUsageRequests.size() == 0) {
            return;
        }

        for (int i = 0; i < mDataUsageRequests.size(); i++) {
            RequestInfo requestInfo = mDataUsageRequests.valueAt(i);
            requestInfo.updateStats(statsContext);
        }
    }

    private DataUsageRequest buildRequest(DataUsageRequest request) {
        // Cap the minimum threshold to a safe default to avoid too many callbacks
        long thresholdInBytes = Math.max(MIN_THRESHOLD_BYTES, request.thresholdInBytes);
        if (thresholdInBytes < request.thresholdInBytes) {
            Slog.w(TAG, "Threshold was too low for " + request
                    + ". Overriding to a safer default of " + thresholdInBytes + " bytes");
        }
        return new DataUsageRequest(mNextDataUsageRequestId.incrementAndGet(),
                request.template, thresholdInBytes);
    }

    private RequestInfo buildRequestInfo(DataUsageRequest request,
                Messenger messenger, IBinder binder, int callingUid,
                @NetworkStatsAccess.Level int accessLevel) {
        if (accessLevel <= NetworkStatsAccess.Level.USER) {
            return new UserUsageRequestInfo(this, request, messenger, binder, callingUid,
                    accessLevel);
        } else {
            // Safety check in case a new access level is added and we forgot to update this
            checkArgument(accessLevel >= NetworkStatsAccess.Level.DEVICESUMMARY);
            return new NetworkUsageRequestInfo(this, request, messenger, binder, callingUid,
                    accessLevel);
        }
    }

    /**
     * Tracks information relevant to a data usage observer.
     * It will notice when the calling process dies so we can self-expire.
     */
    private abstract static class RequestInfo implements IBinder.DeathRecipient {
        private final NetworkStatsObservers mStatsObserver;
        protected final DataUsageRequest mRequest;
        private final Messenger mMessenger;
        private final IBinder mBinder;
        protected final int mCallingUid;
        protected final @NetworkStatsAccess.Level int mAccessLevel;
        protected NetworkStatsRecorder mRecorder;
        protected NetworkStatsCollection mCollection;

        RequestInfo(NetworkStatsObservers statsObserver, DataUsageRequest request,
                    Messenger messenger, IBinder binder, int callingUid,
                    @NetworkStatsAccess.Level int accessLevel) {
            mStatsObserver = statsObserver;
            mRequest = request;
            mMessenger = messenger;
            mBinder = binder;
            mCallingUid = callingUid;
            mAccessLevel = accessLevel;

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        @Override
        public void binderDied() {
            if (LOGV) Slog.v(TAG, "RequestInfo binderDied("
                    + mRequest + ", " + mBinder + ")");
            mStatsObserver.unregister(mRequest, Process.SYSTEM_UID);
            callCallback(NetworkStatsManager.CALLBACK_RELEASED);
        }

        @Override
        public String toString() {
            return "RequestInfo from uid:" + mCallingUid
                    + " for " + mRequest + " accessLevel:" + mAccessLevel;
        }

        private void unlinkDeathRecipient() {
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }

        /**
         * Update stats given the samples and interface to identity mappings.
         */
        private void updateStats(StatsContext statsContext) {
            if (mRecorder == null) {
                // First run; establish baseline stats
                resetRecorder();
                recordSample(statsContext);
                return;
            }
            recordSample(statsContext);

            if (checkStats()) {
                resetRecorder();
                callCallback(NetworkStatsManager.CALLBACK_LIMIT_REACHED);
            }
        }

        private void callCallback(int callbackType) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(DataUsageRequest.PARCELABLE_KEY, mRequest);
            Message msg = Message.obtain();
            msg.what = callbackType;
            msg.setData(bundle);
            try {
                if (LOGV) {
                    Slog.v(TAG, "sending notification " + callbackTypeToName(callbackType)
                            + " for " + mRequest);
                }
                mMessenger.send(msg);
            } catch (RemoteException e) {
                // May occur naturally in the race of binder death.
                Slog.w(TAG, "RemoteException caught trying to send a callback msg for " + mRequest);
            }
        }

        private void resetRecorder() {
            mRecorder = new NetworkStatsRecorder();
            mCollection = mRecorder.getSinceBoot();
        }

        protected abstract boolean checkStats();

        protected abstract void recordSample(StatsContext statsContext);

        private String callbackTypeToName(int callbackType) {
            switch (callbackType) {
                case NetworkStatsManager.CALLBACK_LIMIT_REACHED:
                    return "LIMIT_REACHED";
                case NetworkStatsManager.CALLBACK_RELEASED:
                    return "RELEASED";
                default:
                    return "UNKNOWN";
            }
        }
    }

    private static class NetworkUsageRequestInfo extends RequestInfo {
        NetworkUsageRequestInfo(NetworkStatsObservers statsObserver, DataUsageRequest request,
                    Messenger messenger, IBinder binder, int callingUid,
                    @NetworkStatsAccess.Level int accessLevel) {
            super(statsObserver, request, messenger, binder, callingUid, accessLevel);
        }

        @Override
        protected boolean checkStats() {
            long bytesSoFar = getTotalBytesForNetwork(mRequest.template);
            if (LOGV) {
                Slog.v(TAG, bytesSoFar + " bytes so far since notification for "
                        + mRequest.template);
            }
            if (bytesSoFar > mRequest.thresholdInBytes) {
                return true;
            }
            return false;
        }

        @Override
        protected void recordSample(StatsContext statsContext) {
            // Recorder does not need to be locked in this context since only the handler
            // thread will update it. We pass a null VPN array because usage is aggregated by uid
            // for this snapshot, so VPN traffic can't be reattributed to responsible apps.
            mRecorder.recordSnapshotLocked(statsContext.mXtSnapshot, statsContext.mActiveIfaces,
                    null /* vpnArray */, statsContext.mCurrentTime);
        }

        /**
         * Reads stats matching the given template. {@link NetworkStatsCollection} will aggregate
         * over all buckets, which in this case should be only one since we built it big enough
         * that it will outlive the caller. If it doesn't, then there will be multiple buckets.
         */
        private long getTotalBytesForNetwork(NetworkTemplate template) {
            NetworkStats stats = mCollection.getSummary(template,
                    Long.MIN_VALUE /* start */, Long.MAX_VALUE /* end */,
                    mAccessLevel, mCallingUid);
            return stats.getTotalBytes();
        }
    }

    private static class UserUsageRequestInfo extends RequestInfo {
        UserUsageRequestInfo(NetworkStatsObservers statsObserver, DataUsageRequest request,
                    Messenger messenger, IBinder binder, int callingUid,
                    @NetworkStatsAccess.Level int accessLevel) {
            super(statsObserver, request, messenger, binder, callingUid, accessLevel);
        }

        @Override
        protected boolean checkStats() {
            int[] uidsToMonitor = mCollection.getRelevantUids(mAccessLevel, mCallingUid);

            for (int i = 0; i < uidsToMonitor.length; i++) {
                long bytesSoFar = getTotalBytesForNetworkUid(mRequest.template, uidsToMonitor[i]);
                if (bytesSoFar > mRequest.thresholdInBytes) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void recordSample(StatsContext statsContext) {
            // Recorder does not need to be locked in this context since only the handler
            // thread will update it. We pass the VPN info so VPN traffic is reattributed to
            // responsible apps.
            mRecorder.recordSnapshotLocked(statsContext.mUidSnapshot, statsContext.mActiveUidIfaces,
                    statsContext.mVpnArray, statsContext.mCurrentTime);
        }

        /**
         * Reads all stats matching the given template and uid. Ther history will likely only
         * contain one bucket per ident since we build it big enough that it will outlive the
         * caller lifetime.
         */
        private long getTotalBytesForNetworkUid(NetworkTemplate template, int uid) {
            try {
                NetworkStatsHistory history = mCollection.getHistory(template, uid,
                        NetworkStats.SET_ALL, NetworkStats.TAG_NONE,
                        NetworkStatsHistory.FIELD_ALL,
                        Long.MIN_VALUE /* start */, Long.MAX_VALUE /* end */,
                        mAccessLevel, mCallingUid);
                return history.getTotalBytes();
            } catch (SecurityException e) {
                if (LOGV) {
                    Slog.w(TAG, "CallerUid " + mCallingUid + " may have lost access to uid "
                            + uid);
                }
                return 0;
            }
        }
    }

    private static class StatsContext {
        NetworkStats mXtSnapshot;
        NetworkStats mUidSnapshot;
        ArrayMap<String, NetworkIdentitySet> mActiveIfaces;
        ArrayMap<String, NetworkIdentitySet> mActiveUidIfaces;
        VpnInfo[] mVpnArray;
        long mCurrentTime;

        StatsContext(NetworkStats xtSnapshot, NetworkStats uidSnapshot,
                ArrayMap<String, NetworkIdentitySet> activeIfaces,
                ArrayMap<String, NetworkIdentitySet> activeUidIfaces,
                VpnInfo[] vpnArray, long currentTime) {
            mXtSnapshot = xtSnapshot;
            mUidSnapshot = uidSnapshot;
            mActiveIfaces = activeIfaces;
            mActiveUidIfaces = activeUidIfaces;
            mVpnArray = vpnArray;
            mCurrentTime = currentTime;
        }
    }
}
