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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.ContentSuggestionsManager;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * Per user delegate of {@link ContentSuggestionsManagerService}.
 *
 * <p>Main job is to forward calls to the remote implementation that can provide suggestion
 * selections and classifications.
 */
public final class ContentSuggestionsPerUserService extends
        AbstractPerUserSystemService<
                        ContentSuggestionsPerUserService, ContentSuggestionsManagerService> {
    private static final String TAG = ContentSuggestionsPerUserService.class.getSimpleName();

    @Nullable
    @GuardedBy("mLock")
    private RemoteContentSuggestionsService mRemoteService;

    @NonNull
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;

    ContentSuggestionsPerUserService(
            ContentSuggestionsManagerService master, Object lock, int userId) {
        super(master, lock, userId);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        if (!Manifest.permission.BIND_CONTENT_SUGGESTIONS_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "ContentSuggestionsService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_CONTENT_SUGGESTIONS_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_CONTENT_SUGGESTIONS_SERVICE);
        }
        return si;
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        updateRemoteServiceLocked();
        return enabledChanged;
    }

    @GuardedBy("mLock")
    void provideContextImageLocked(int taskId, @NonNull Bundle imageContextRequestExtras) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            GraphicBuffer snapshotBuffer = null;
            int colorSpaceId = 0;

            // Skip taking TaskSnapshot when bitmap is provided.
            if (!imageContextRequestExtras.containsKey(ContentSuggestionsManager.EXTRA_BITMAP)) {
                ActivityManager.TaskSnapshot snapshot =
                        mActivityTaskManagerInternal.getTaskSnapshotNoRestore(taskId, false);
                if (snapshot != null) {
                    snapshotBuffer = snapshot.getSnapshot();
                    ColorSpace colorSpace = snapshot.getColorSpace();
                    if (colorSpace != null) {
                        colorSpaceId = colorSpace.getId();
                    }
                }
            }

            service.provideContextImage(taskId, snapshotBuffer, colorSpaceId,
                    imageContextRequestExtras);
        }
    }

    @GuardedBy("mLock")
    void suggestContentSelectionsLocked(
            @NonNull SelectionsRequest selectionsRequest,
            @NonNull ISelectionsCallback selectionsCallback) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.suggestContentSelections(selectionsRequest, selectionsCallback);
        }
    }

    @GuardedBy("mLock")
    void classifyContentSelectionsLocked(
            @NonNull ClassificationsRequest classificationsRequest,
            @NonNull IClassificationsCallback callback) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.classifyContentSelections(classificationsRequest, callback);
        }
    }

    @GuardedBy("mLock")
    void notifyInteractionLocked(@NonNull String requestId, @NonNull Bundle bundle) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.notifyInteraction(requestId, bundle);
        }
    }

    @GuardedBy("mLock")
    private void updateRemoteServiceLocked() {
        if (mRemoteService != null) {
            mRemoteService.destroy();
            mRemoteService = null;
        }
    }


    @GuardedBy("mLock")
    @Nullable
    private RemoteContentSuggestionsService ensureRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteContentSuggestionsService(getContext(),
                    serviceComponent, mUserId,
                    new RemoteContentSuggestionsService.Callbacks() {
                        @Override
                        public void onServiceDied(
                                @NonNull RemoteContentSuggestionsService service) {
                            Slog.w(TAG, "remote content suggestions service died");
                            updateRemoteServiceLocked();
                        }
                    }, mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }
}
