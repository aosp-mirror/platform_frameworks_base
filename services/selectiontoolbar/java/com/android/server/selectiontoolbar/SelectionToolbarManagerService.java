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

import android.content.Context;
import android.util.Slog;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ISelectionToolbarManager;
import android.view.selectiontoolbar.ShowInfo;

import com.android.internal.util.DumpUtils;
import com.android.server.infra.AbstractMasterSystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Entry point service for selection toolbar management.
 */
public final class SelectionToolbarManagerService extends
        AbstractMasterSystemService<SelectionToolbarManagerService,
                SelectionToolbarManagerServiceImpl> {

    private static final String TAG = "SelectionToolbarManagerService";

    @Override
    public void onStart() {
        publishBinderService(Context.SELECTION_TOOLBAR_SERVICE,
                new SelectionToolbarManagerService.SelectionToolbarManagerServiceStub());
    }

    public SelectionToolbarManagerService(Context context) {
        super(context, new SelectionToolbarServiceNameResolver(), /* disallowProperty= */
                null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected SelectionToolbarManagerServiceImpl newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new SelectionToolbarManagerServiceImpl(this, mLock, resolvedUserId);
    }

    final class SelectionToolbarManagerServiceStub extends ISelectionToolbarManager.Stub {

        @Override
        public void showToolbar(ShowInfo showInfo, ISelectionToolbarCallback callback, int userId) {
            synchronized (mLock) {
                SelectionToolbarManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.showToolbar(showInfo, callback);
                } else {
                    Slog.v(TAG, "showToolbar(): no service for " + userId);
                }
            }
        }

        @Override
        public void hideToolbar(long widgetToken, int userId) {
            synchronized (mLock) {
                SelectionToolbarManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.hideToolbar(widgetToken);
                } else {
                    Slog.v(TAG, "hideToolbar(): no service for " + userId);
                }
            }
        }

        @Override
        public void dismissToolbar(long widgetToken, int userId) {
            synchronized (mLock) {
                SelectionToolbarManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.dismissToolbar(widgetToken);
                } else {
                    Slog.v(TAG, "dismissToolbar(): no service for " + userId);
                }
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (mLock) {
                dumpLocked("", pw);
            }
        }
    }
}
