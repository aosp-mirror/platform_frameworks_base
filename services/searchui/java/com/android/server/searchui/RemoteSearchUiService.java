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
package com.android.server.searchui;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.search.ISearchUiService;
import android.text.format.DateUtils;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;


/**
 * Proxy to the {@link android.service.searchui.SearchUiService} implementation in another
 * process.
 */
public class RemoteSearchUiService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteSearchUiService,
                ISearchUiService> {

    private static final String TAG = "RemoteSearchUiService";

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    private final RemoteSearchUiServiceCallbacks mCallback;

    public RemoteSearchUiService(Context context, String serviceInterface,
            ComponentName componentName, int userId,
            RemoteSearchUiServiceCallbacks callback, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, componentName, userId, callback,
                context.getMainThreadHandler(),
                bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0,
                verbose, /* initialCapacity= */ 1);
        mCallback = callback;
    }

    @Override
    protected ISearchUiService getServiceInterface(IBinder service) {
        return ISearchUiService.Stub.asInterface(service);
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
    public void scheduleOnResolvedService(@NonNull AsyncRequest<ISearchUiService> request) {
        scheduleAsyncRequest(request);
    }

    /**
     * Execute async request on remote service immediately instead of sending it to Handler queue.
     */
    public void executeOnResolvedService(@NonNull AsyncRequest<ISearchUiService> request) {
        executeAsyncRequest(request);
    }

    /**
     * Failure callback
     */
    public interface RemoteSearchUiServiceCallbacks
            extends VultureCallback<RemoteSearchUiService> {

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
