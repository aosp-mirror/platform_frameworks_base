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

import static android.Manifest.permission.MANAGE_UI_TRANSLATION;
import static android.content.Context.TRANSLATION_MANAGER_SERVICE;
import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_FAIL;

import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.translation.ITranslationManager;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;

import com.android.internal.os.IResultReceiver;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.util.List;

/**
 * Entry point service for translation management.
 *
 * <p>This service provides the {@link ITranslationManager} implementation and keeps a list of
 * {@link TranslationManagerServiceImpl} per user; the real work is done by
 * {@link TranslationManagerServiceImpl} itself.
 */
public final class TranslationManagerService
        extends AbstractMasterSystemService<TranslationManagerService,
        TranslationManagerServiceImpl> {

    private static final String TAG = "TranslationManagerService";

    public TranslationManagerService(Context context) {
        // TODO: Discuss the disallow policy
        super(context, new FrameworkResourcesServiceNameResolver(context,
                        com.android.internal.R.string.config_defaultTranslationService),
                /* disallowProperty */ null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected TranslationManagerServiceImpl newServiceLocked(int resolvedUserId, boolean disabled) {
        return new TranslationManagerServiceImpl(this, mLock, resolvedUserId, disabled);
    }

    private void enforceCallerHasPermission(String permission) {
        final String msg = "Permission Denial from pid =" + Binder.getCallingPid() + ", uid="
                + Binder.getCallingUid() + " doesn't hold " + permission;
        getContext().enforceCallingPermission(permission, msg);
    }

    final class TranslationManagerServiceStub extends ITranslationManager.Stub {
        @Override
        public void getSupportedLocales(IResultReceiver receiver, int userId)
                throws RemoteException {
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.getSupportedLocalesLocked(receiver);
                } else {
                    Slog.v(TAG, "getSupportedLocales(): no service for " + userId);
                    receiver.send(STATUS_SYNC_CALL_FAIL, null);
                }
            }
        }

        @Override
        public void onSessionCreated(TranslationSpec sourceSpec, TranslationSpec destSpec,
                int sessionId, IResultReceiver receiver, int userId) throws RemoteException {
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.onSessionCreatedLocked(sourceSpec, destSpec, sessionId, receiver);
                } else {
                    Slog.v(TAG, "onSessionCreated(): no service for " + userId);
                    receiver.send(STATUS_SYNC_CALL_FAIL, null);
                }
            }
        }

        @Override
        public void updateUiTranslationState(@UiTranslationState int state,
                TranslationSpec sourceSpec, TranslationSpec destSpec, List<AutofillId> viewIds,
                int taskId, int userId) {
            enforceCallerHasPermission(MANAGE_UI_TRANSLATION);
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.updateUiTranslationState(state, sourceSpec, destSpec, viewIds,
                            taskId);
                }
            }
        }
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(TRANSLATION_MANAGER_SERVICE,
                new TranslationManagerService.TranslationManagerServiceStub());
    }
}
