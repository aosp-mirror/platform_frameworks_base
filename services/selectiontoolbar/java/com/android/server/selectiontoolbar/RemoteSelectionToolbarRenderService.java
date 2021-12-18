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

package com.android.server.selectiontoolbar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.selectiontoolbar.ISelectionToolbarRenderService;
import android.service.selectiontoolbar.SelectionToolbarRenderService;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;

import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.ServiceConnector;

final class RemoteSelectionToolbarRenderService extends
        ServiceConnector.Impl<ISelectionToolbarRenderService> {
    private static final String TAG = "RemoteSelectionToolbarRenderService";

    private static final long TIMEOUT_IDLE_UNBIND_MS =
            AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;

    private final ComponentName mComponentName;


    RemoteSelectionToolbarRenderService(Context context, ComponentName serviceName, int userId) {
        super(context, new Intent(SelectionToolbarRenderService.SERVICE_INTERFACE).setComponent(
                serviceName), 0, userId, ISelectionToolbarRenderService.Stub::asInterface);
        mComponentName = serviceName;
        // Bind right away.
        connect();
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return TIMEOUT_IDLE_UNBIND_MS;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public void onShow(ShowInfo showInfo, ISelectionToolbarCallback callback) {
        run((s) -> s.onShow(showInfo, callback));
    }

    public void onHide(long widgetToken) {
        run((s) -> s.onHide(widgetToken));
    }

    public void onDismiss(long widgetToken) {
        run((s) -> s.onDismiss(widgetToken));
    }
}
