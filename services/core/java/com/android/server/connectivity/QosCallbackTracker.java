/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IQosCallback;
import android.net.Network;
import android.net.QosCallbackException;
import android.net.QosFilter;
import android.net.QosSession;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.util.Log;

import com.android.net.module.util.CollectionUtils;
import com.android.server.ConnectivityService;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks qos callbacks and handles the communication between the network agent and application.
 * <p/>
 * Any method prefixed by handle must be called from the
 * {@link com.android.server.ConnectivityService} handler thread.
 *
 * @hide
 */
public class QosCallbackTracker {
    private static final String TAG = QosCallbackTracker.class.getSimpleName();
    private static final boolean DBG = true;

    @NonNull
    private final Handler mConnectivityServiceHandler;

    @NonNull
    private final ConnectivityService.PerUidCounter mNetworkRequestCounter;

    /**
     * Each agent gets a unique callback id that is used to proxy messages back to the original
     * callback.
     * <p/>
     * Note: The fact that this is initialized to 0 is to ensure that the thread running
     * {@link #handleRegisterCallback(IQosCallback, QosFilter, int, NetworkAgentInfo)} sees the
     * initialized value. This would not necessarily be the case if the value was initialized to
     * the non-default value.
     * <p/>
     * Note: The term previous does not apply to the first callback id that is assigned.
     */
    private int mPreviousAgentCallbackId = 0;

    @NonNull
    private final List<QosCallbackAgentConnection> mConnections = new ArrayList<>();

    /**
     *
     * @param connectivityServiceHandler must be the same handler used with
     *                {@link com.android.server.ConnectivityService}
     * @param networkRequestCounter keeps track of the number of open requests under a given
     *                              uid
     */
    public QosCallbackTracker(@NonNull final Handler connectivityServiceHandler,
            final ConnectivityService.PerUidCounter networkRequestCounter) {
        mConnectivityServiceHandler = connectivityServiceHandler;
        mNetworkRequestCounter = networkRequestCounter;
    }

    /**
     * Registers the callback with the tracker
     *
     * @param callback the callback to register
     * @param filter the filter being registered alongside the callback
     */
    public void registerCallback(@NonNull final IQosCallback callback,
            @NonNull final QosFilter filter, @NonNull final NetworkAgentInfo networkAgentInfo) {
        final int uid = Binder.getCallingUid();

        // Enforce that the number of requests under this uid has exceeded the allowed number
        mNetworkRequestCounter.incrementCountOrThrow(uid);

        mConnectivityServiceHandler.post(
                () -> handleRegisterCallback(callback, filter, uid, networkAgentInfo));
    }

    private void handleRegisterCallback(@NonNull final IQosCallback callback,
            @NonNull final QosFilter filter, final int uid,
            @NonNull final NetworkAgentInfo networkAgentInfo) {
        final QosCallbackAgentConnection ac =
                handleRegisterCallbackInternal(callback, filter, uid, networkAgentInfo);
        if (ac != null) {
            if (DBG) log("handleRegisterCallback: added callback " + ac.getAgentCallbackId());
            mConnections.add(ac);
        } else {
            mNetworkRequestCounter.decrementCount(uid);
        }
    }

    private QosCallbackAgentConnection handleRegisterCallbackInternal(
            @NonNull final IQosCallback callback,
            @NonNull final QosFilter filter, final int uid,
            @NonNull final NetworkAgentInfo networkAgentInfo) {
        final IBinder binder = callback.asBinder();
        if (CollectionUtils.any(mConnections, c -> c.getBinder().equals(binder))) {
            // A duplicate registration would have only made this far due to a programming error.
            logwtf("handleRegisterCallback: Callbacks can only be register once.");
            return null;
        }

        mPreviousAgentCallbackId = mPreviousAgentCallbackId + 1;
        final int newCallbackId = mPreviousAgentCallbackId;

        final QosCallbackAgentConnection ac =
                new QosCallbackAgentConnection(this, newCallbackId, callback,
                        filter, uid, networkAgentInfo);

        final int exceptionType = filter.validate();
        if (exceptionType != QosCallbackException.EX_TYPE_FILTER_NONE) {
            ac.sendEventQosCallbackError(exceptionType);
            return null;
        }

        // Only add to the callback maps if the NetworkAgent successfully registered it
        if (!ac.sendCmdRegisterCallback()) {
            // There was an issue when registering the agent
            if (DBG) log("handleRegisterCallback: error sending register callback");
            mNetworkRequestCounter.decrementCount(uid);
            return null;
        }
        return ac;
    }

    /**
     * Unregisters callback
     * @param callback callback to unregister
     */
    public void unregisterCallback(@NonNull final IQosCallback callback) {
        mConnectivityServiceHandler.post(() -> handleUnregisterCallback(callback.asBinder(), true));
    }

    private void handleUnregisterCallback(@NonNull final IBinder binder,
            final boolean sendToNetworkAgent) {
        final int connIndex =
                CollectionUtils.indexOf(mConnections, c -> c.getBinder().equals(binder));
        if (connIndex < 0) {
            logw("handleUnregisterCallback: no matching agentConnection");
            return;
        }
        final QosCallbackAgentConnection agentConnection = mConnections.get(connIndex);

        if (DBG) {
            log("handleUnregisterCallback: unregister "
                    + agentConnection.getAgentCallbackId());
        }

        mNetworkRequestCounter.decrementCount(agentConnection.getUid());
        mConnections.remove(agentConnection);

        if (sendToNetworkAgent) {
            agentConnection.sendCmdUnregisterCallback();
        }
        agentConnection.unlinkToDeathRecipient();
    }

    /**
     * Called when the NetworkAgent sends the qos session available event
     *
     * @param qosCallbackId the callback id that the qos session is now available to
     * @param session the qos session that is now available
     * @param attributes the qos attributes that are now available on the qos session
     */
    public void sendEventQosSessionAvailable(final int qosCallbackId,
            final QosSession session,
            final EpsBearerQosSessionAttributes attributes) {
        runOnAgentConnection(qosCallbackId, "sendEventQosSessionAvailable: ",
                ac -> ac.sendEventQosSessionAvailable(session, attributes));
    }

    /**
     * Called when the NetworkAgent sends the qos session lost event
     *
     * @param qosCallbackId the callback id that lost the qos session
     * @param session the corresponding qos session
     */
    public void sendEventQosSessionLost(final int qosCallbackId,
            final QosSession session) {
        runOnAgentConnection(qosCallbackId, "sendEventQosSessionLost: ",
                ac -> ac.sendEventQosSessionLost(session));
    }

    /**
     * Called when the NetworkAgent sends the qos session on error event
     *
     * @param qosCallbackId the callback id that should receive the exception
     * @param exceptionType the type of exception that caused the callback to error
     */
    public void sendEventQosCallbackError(final int qosCallbackId,
            @QosCallbackException.ExceptionType final int exceptionType) {
        runOnAgentConnection(qosCallbackId, "sendEventQosCallbackError: ",
                ac -> {
                    ac.sendEventQosCallbackError(exceptionType);
                    handleUnregisterCallback(ac.getBinder(), false);
                });
    }

    /**
     * Unregisters all callbacks associated to this network agent
     *
     * Note: Must be called on the connectivity service handler thread
     *
     * @param network the network that was released
     */
    public void handleNetworkReleased(@Nullable final Network network) {
        // Iterate in reverse order as agent connections will be removed when unregistering
        for (int i = mConnections.size() - 1; i >= 0; i--) {
            final QosCallbackAgentConnection agentConnection = mConnections.get(i);
            if (!agentConnection.getNetwork().equals(network)) continue;
            agentConnection.sendEventQosCallbackError(
                    QosCallbackException.EX_TYPE_FILTER_NETWORK_RELEASED);

            // Call unregister workflow w\o sending anything to agent since it is disconnected.
            handleUnregisterCallback(agentConnection.getBinder(), false);
        }
    }

    private interface AgentConnectionAction {
        void execute(@NonNull QosCallbackAgentConnection agentConnection);
    }

    @Nullable
    private void runOnAgentConnection(final int qosCallbackId,
            @NonNull final String logPrefix,
            @NonNull final AgentConnectionAction action) {
        mConnectivityServiceHandler.post(() -> {
            final int acIndex = CollectionUtils.indexOf(mConnections,
                            c -> c.getAgentCallbackId() == qosCallbackId);
            if (acIndex == -1) {
                loge(logPrefix + ": " + qosCallbackId + " missing callback id");
                return;
            }

            action.execute(mConnections.get(acIndex));
        });
    }

    private static void log(final String msg) {
        Log.d(TAG, msg);
    }

    private static void logw(final String msg) {
        Log.w(TAG, msg);
    }

    private static void loge(final String msg) {
        Log.e(TAG, msg);
    }

    private static void logwtf(final String msg) {
        Log.wtf(TAG, msg);
    }
}
