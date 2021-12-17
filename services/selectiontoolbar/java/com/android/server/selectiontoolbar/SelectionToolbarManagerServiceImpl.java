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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Slog;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

final class SelectionToolbarManagerServiceImpl extends
        AbstractPerUserSystemService<SelectionToolbarManagerServiceImpl,
                SelectionToolbarManagerService> {

    private static final String TAG = "SelectionToolbarManagerServiceImpl";

    @GuardedBy("mLock")
    @Nullable
    private RemoteSelectionToolbarRenderService mRemoteService;

    protected SelectionToolbarManagerServiceImpl(@NonNull SelectionToolbarManagerService master,
            @NonNull Object lock, int userId) {
        super(master, lock, userId);
        updateRemoteServiceLocked();
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        return getServiceInfoOrThrow(serviceComponent, mUserId);
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        updateRemoteServiceLocked();
        return enabledChanged;
    }

    /**
     * Updates the reference to the remote service.
     */
    @GuardedBy("mLock")
    private void updateRemoteServiceLocked() {
        if (mRemoteService != null) {
            Slog.d(TAG, "updateRemoteService(): destroying old remote service");
            mRemoteService.unbind();
            mRemoteService = null;
        }
    }

    @GuardedBy("mLock")
    void showToolbar(ShowInfo showInfo, ISelectionToolbarCallback callback) {
        final RemoteSelectionToolbarRenderService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onShow(showInfo, callback);
        }
    }

    @GuardedBy("mLock")
    void hideToolbar(long widgetToken) {
        final RemoteSelectionToolbarRenderService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onHide(widgetToken);
        }
    }

    @GuardedBy("mLock")
    void dismissToolbar(long widgetToken) {
        final RemoteSelectionToolbarRenderService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onDismiss(widgetToken);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteSelectionToolbarRenderService ensureRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            mRemoteService = new RemoteSelectionToolbarRenderService(getContext(), serviceComponent,
                    mUserId);
        }
        return mRemoteService;
    }

    private static ServiceInfo getServiceInfoOrThrow(ComponentName comp, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        int flags = PackageManager.GET_META_DATA;

        ServiceInfo si = null;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(comp, flags, userId);
        } catch (RemoteException e) {
        }
        if (si == null) {
            throw new PackageManager.NameNotFoundException("Could not get serviceInfo for "
                    + comp.flattenToShortString());
        }
        return si;
    }
}
