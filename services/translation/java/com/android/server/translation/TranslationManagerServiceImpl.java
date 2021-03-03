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

package com.android.server.translation;

import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.translation.TranslationServiceInfo;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.SyncResultReceiver;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ActivityTokens;

import java.util.ArrayList;
import java.util.List;

final class TranslationManagerServiceImpl extends
        AbstractPerUserSystemService<TranslationManagerServiceImpl, TranslationManagerService> {

    private static final String TAG = "TranslationManagerServiceImpl";

    @GuardedBy("mLock")
    @Nullable
    private RemoteTranslationService mRemoteTranslationService;

    @GuardedBy("mLock")
    @Nullable
    private ServiceInfo mRemoteTranslationServiceInfo;

    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    protected TranslationManagerServiceImpl(
            @NonNull TranslationManagerService master,
            @NonNull Object lock, int userId, boolean disabled) {
        super(master, lock, userId);
        updateRemoteServiceLocked();
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        final TranslationServiceInfo info = new TranslationServiceInfo(getContext(),
                serviceComponent, isTemporaryServiceSetLocked(), mUserId);
        mRemoteTranslationServiceInfo = info.getServiceInfo();
        return info.getServiceInfo();
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
        if (mRemoteTranslationService != null) {
            if (mMaster.debug) Slog.d(TAG, "updateRemoteService(): destroying old remote service");
            mRemoteTranslationService.unbind();
            mRemoteTranslationService = null;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteTranslationService ensureRemoteServiceLocked() {
        if (mRemoteTranslationService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): no service component name.");
                }
                return null;
            }
            final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            mRemoteTranslationService = new RemoteTranslationService(getContext(),
                    serviceComponent, mUserId, /* isInstantAllowed= */ false);
        }
        return mRemoteTranslationService;
    }

    @GuardedBy("mLock")
    void getSupportedLocalesLocked(@NonNull IResultReceiver resultReceiver) {
        // TODO: implement this
        try {
            resultReceiver.send(STATUS_SYNC_CALL_SUCCESS,
                    SyncResultReceiver.bundleFor(new ArrayList<>()));
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException returning supported locales: " + e);
        }
    }

    @GuardedBy("mLock")
    void onSessionCreatedLocked(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec destSpec, int sessionId, IResultReceiver resultReceiver) {
        final RemoteTranslationService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onSessionCreated(sourceSpec, destSpec, sessionId, resultReceiver);
        }
    }

    @GuardedBy("mLock")
    public void updateUiTranslationStateLocked(@UiTranslationState int state,
            TranslationSpec sourceSpec, TranslationSpec destSpec, List<AutofillId> viewIds,
            int taskId) {
        // deprecated
        final ActivityTokens taskTopActivityTokens =
                mActivityTaskManagerInternal.getTopActivityForTask(taskId);
        if (taskTopActivityTokens == null) {
            Slog.w(TAG, "Unknown activity to query for update translation state.");
            return;
        }
        updateUiTranslationStateByActivityTokens(taskTopActivityTokens, state, sourceSpec, destSpec,
                viewIds);
    }

    @GuardedBy("mLock")
    public void updateUiTranslationStateLocked(@UiTranslationState int state,
            TranslationSpec sourceSpec, TranslationSpec destSpec, List<AutofillId> viewIds,
            IBinder token, int taskId) {
        // Get top activity for a given task id
        final ActivityTokens taskTopActivityTokens =
                mActivityTaskManagerInternal.getTopActivityForTask(taskId);
        if (taskTopActivityTokens == null
                || taskTopActivityTokens.getShareableActivityToken() != token) {
            Slog.w(TAG, "Unknown activity or it was finished to query for update "
                    + "translation state for token=" + token + " taskId=" + taskId);
            return;
        }
        updateUiTranslationStateByActivityTokens(taskTopActivityTokens, state, sourceSpec, destSpec,
                viewIds);
    }

    private void updateUiTranslationStateByActivityTokens(ActivityTokens tokens,
            @UiTranslationState int state, TranslationSpec sourceSpec, TranslationSpec destSpec,
            List<AutofillId> viewIds) {
        try {
            tokens.getApplicationThread().updateUiTranslationState(tokens.getActivityToken(), state,
                    sourceSpec, destSpec, viewIds);
        } catch (RemoteException e) {
            Slog.w(TAG, "Update UiTranslationState fail: " + e);
        }
    }
}
