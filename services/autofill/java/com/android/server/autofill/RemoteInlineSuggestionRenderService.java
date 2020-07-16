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
package com.android.server.autofill;

import static com.android.server.autofill.Helper.sVerbose;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.service.autofill.IInlineSuggestionRenderService;
import android.service.autofill.IInlineSuggestionUiCallback;
import android.service.autofill.InlinePresentation;
import android.service.autofill.InlineSuggestionRenderService;
import android.util.Slog;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;

/**
 * Remote service to help connect to InlineSuggestionRenderService in ExtServices.
 */
public final class RemoteInlineSuggestionRenderService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteInlineSuggestionRenderService,
                IInlineSuggestionRenderService> {

    private static final String TAG = "RemoteInlineSuggestionRenderService";

    private final long mIdleUnbindTimeoutMs = PERMANENT_BOUND_TIMEOUT_MS;

    RemoteInlineSuggestionRenderService(Context context, ComponentName componentName,
            String serviceInterface, int userId, InlineSuggestionRenderCallbacks callback,
            boolean bindInstantServiceAllowed, boolean verbose) {
        super(context, serviceInterface, componentName, userId, callback,
                context.getMainThreadHandler(),
                bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0, verbose,
                /* initialCapacity= */ 2);

        ensureBound();
    }

    @Override // from AbstractRemoteService
    protected IInlineSuggestionRenderService getServiceInterface(@NonNull IBinder service) {
        return IInlineSuggestionRenderService.Stub.asInterface(service);
    }

    @Override // from AbstractRemoteService
    protected long getTimeoutIdleBindMillis() {
        return mIdleUnbindTimeoutMs;
    }

    @Override // from AbstractRemoteService
    protected void handleOnConnectedStateChanged(boolean connected) {
        if (connected && getTimeoutIdleBindMillis() != PERMANENT_BOUND_TIMEOUT_MS) {
            scheduleUnbind();
        }
        super.handleOnConnectedStateChanged(connected);
    }

    public void ensureBound() {
        scheduleBind();
    }

    /**
     * Called by {@link Session} to generate a call to the
     * {@link RemoteInlineSuggestionRenderService} to request rendering a slice .
     */
    public void renderSuggestion(@NonNull IInlineSuggestionUiCallback callback,
            @NonNull InlinePresentation presentation, int width, int height,
            @Nullable IBinder hostInputToken, int displayId, int userId, int sessionId) {
        scheduleAsyncRequest((s) -> s.renderSuggestion(callback, presentation, width, height,
                hostInputToken, displayId, userId, sessionId));
    }

    /**
     * Gets the inline suggestions renderer info as a {@link Bundle}.
     */
    public void getInlineSuggestionsRendererInfo(@NonNull RemoteCallback callback) {
        scheduleAsyncRequest((s) -> s.getInlineSuggestionsRendererInfo(callback));
    }

    /**
     * Destroys the remote inline suggestion views associated with the given user id and session id.
     */
    public void destroySuggestionViews(int userId, int sessionId) {
        scheduleAsyncRequest((s) -> s.destroySuggestionViews(userId, sessionId));
    }

    @Nullable
    private static ServiceInfo getServiceInfo(Context context, int userId) {
        final String packageName =
                context.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }

        final Intent intent = new Intent(InlineSuggestionRenderService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = context.getPackageManager().resolveServiceAsUser(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA, userId);
        final ServiceInfo serviceInfo = resolveInfo == null ? null : resolveInfo.serviceInfo;
        if (resolveInfo == null || serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }

        if (!Manifest.permission.BIND_INLINE_SUGGESTION_RENDER_SERVICE
                .equals(serviceInfo.permission)) {
            Slog.w(TAG, serviceInfo.name + " does not require permission "
                    + Manifest.permission.BIND_INLINE_SUGGESTION_RENDER_SERVICE);
            return null;
        }

        return serviceInfo;
    }

    @Nullable
    public static ComponentName getServiceComponentName(Context context, @UserIdInt int userId) {
        final ServiceInfo serviceInfo = getServiceInfo(context, userId);
        if (serviceInfo == null) return null;

        final ComponentName componentName = new ComponentName(serviceInfo.packageName,
                serviceInfo.name);

        if (sVerbose) Slog.v(TAG, "getServiceComponentName(): " + componentName);
        return componentName;
    }

    interface InlineSuggestionRenderCallbacks
            extends VultureCallback<RemoteInlineSuggestionRenderService> {
        // NOTE: so far we don't need to notify the callback implementation
        // (AutofillManagerServiceImpl) of the request results (success, timeouts, etc..), so this
        // callback interface is empty.
    }
}
