/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.smartspace;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.smartspace.ISmartspaceService;
import android.text.format.DateUtils;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;


/**
 * Proxy to the {@link android.service.smartspace.SmartspaceService} implementation in another
 * process.
 */
public class RemoteSmartspaceService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteSmartspaceService,
                ISmartspaceService> {

    private static final String TAG = "RemoteSmartspaceService";

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    private final RemoteSmartspaceServiceCallbacks mCallback;

    public RemoteSmartspaceService(Context context, String serviceInterface,
            ComponentName componentName, int userId,
            RemoteSmartspaceServiceCallbacks callback, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, componentName, userId, callback,
                context.getMainThreadHandler(),
                bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0,
                verbose, /* initialCapacity= */ 1);
        mCallback = callback;
    }

    @Override
    protected ISmartspaceService getServiceInterface(IBinder service) {
        return ISmartspaceService.Stub.asInterface(service);
    }

    @Override
    protected long getTimeoutIdleBindMillis() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    @Override
    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    /**
     * Schedules a request to bind to the remote service.
     */
    public void reconnect() {
        super.scheduleBind();
    }

    /**
     * Schedule async request on remote service.
     */
    public void scheduleOnResolvedService(@NonNull AsyncRequest<ISmartspaceService> request) {
        scheduleAsyncRequest(request);
    }

    /**
     * Execute async request on remote service immediately instead of sending it to Handler queue.
     */
    public void executeOnResolvedService(@NonNull AsyncRequest<ISmartspaceService> request) {
        executeAsyncRequest(request);
    }

    /**
     * Failure callback
     */
    public interface RemoteSmartspaceServiceCallbacks
            extends VultureCallback<RemoteSmartspaceService> {

        /**
         * Notifies a the failure or timeout of a remote call.
         */
        void onFailureOrTimeout(boolean timedOut);

        /**
         * Notifies change in connected state of the remote service.
         */
        void onConnectedStateChanged(boolean connected);
    }

    @Override // from AbstractRemoteService
    protected void handleOnConnectedStateChanged(boolean connected) {
        if (mCallback != null) {
            mCallback.onConnectedStateChanged(connected);
        }
    }
}
