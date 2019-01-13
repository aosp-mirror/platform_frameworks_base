/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.contentsuggestions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.GraphicBuffer;
import android.os.Bundle;
import android.os.IBinder;
import android.service.contentsuggestions.ContentSuggestionsService;
import android.service.contentsuggestions.IContentSuggestionsService;
import android.text.format.DateUtils;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;

/**
 * Delegates calls from {@link ContentSuggestionsPerUserService} to the remote actual implementation
 * of the suggestion selection and classification service.
 */
public class RemoteContentSuggestionsService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteContentSuggestionsService,
                                IContentSuggestionsService> {

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    RemoteContentSuggestionsService(Context context, ComponentName serviceName,
            int userId, Callbacks callbacks,
            boolean bindInstantServiceAllowed, boolean verbose) {
        super(context, ContentSuggestionsService.SERVICE_INTERFACE, serviceName, userId, callbacks,
                bindInstantServiceAllowed, verbose, /* initialCapacity= */ 1);
    }

    @Override
    protected IContentSuggestionsService getServiceInterface(IBinder service) {
        return IContentSuggestionsService.Stub.asInterface(service);
    }

    @Override
    protected long getTimeoutIdleBindMillis() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    @Override
    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    void provideContextImage(int taskId, @Nullable GraphicBuffer contextImage,
            @NonNull Bundle imageContextRequestExtras) {
        scheduleAsyncRequest((s) -> s.provideContextImage(taskId, contextImage,
                imageContextRequestExtras));
    }

    void suggestContentSelections(
            @NonNull SelectionsRequest selectionsRequest,
            @NonNull ISelectionsCallback selectionsCallback) {
        scheduleAsyncRequest(
                (s) -> s.suggestContentSelections(selectionsRequest, selectionsCallback));
    }

    void classifyContentSelections(
            @NonNull ClassificationsRequest classificationsRequest,
            @NonNull IClassificationsCallback callback) {
        scheduleAsyncRequest((s) -> s.classifyContentSelections(classificationsRequest, callback));
    }

    void notifyInteraction(@NonNull String requestId, @NonNull Bundle bundle) {
        scheduleAsyncRequest((s) -> s.notifyInteraction(requestId, bundle));
    }

    interface Callbacks
            extends VultureCallback<RemoteContentSuggestionsService> {
        // NOTE: so far we don't need to notify the callback implementation
        // (ContentSuggestionsManager) of the request results (success, timeouts, etc..), so this
        // callback interface is empty.
    }
}
