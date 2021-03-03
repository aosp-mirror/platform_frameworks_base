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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.translation.ITranslationManager;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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

    private static final int MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS = 2 * 60_000; // 2 minutes

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

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_UI_TRANSLATION, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS;
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
    }

    private void enforceCallerHasPermission(String permission) {
        final String msg = "Permission Denial from pid =" + Binder.getCallingPid() + ", uid="
                + Binder.getCallingUid() + " doesn't hold " + permission;
        getContext().enforceCallingPermission(permission, msg);
    }

    /** True if the currently set handler service is not overridden by the shell. */
    @GuardedBy("mLock")
    private boolean isDefaultServiceLocked(int userId) {
        final String defaultServiceName = mServiceNameResolver.getDefaultServiceName(userId);
        if (defaultServiceName == null) {
            return false;
        }

        final String currentServiceName = mServiceNameResolver.getServiceName(userId);
        return defaultServiceName.equals(currentServiceName);
    }

    /** True if the caller of the api is the same app which hosts the TranslationService. */
    @GuardedBy("mLock")
    private boolean isCalledByServiceAppLocked(int userId, @NonNull String methodName) {
        final int callingUid = Binder.getCallingUid();

        final String serviceName = mServiceNameResolver.getServiceName(userId);
        if (serviceName == null) {
            Slog.e(TAG, methodName + ": called by UID " + callingUid
                    + ", but there's no service set for user " + userId);
            return false;
        }

        final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
        if (serviceComponent == null) {
            Slog.w(TAG, methodName + ": invalid service name: " + serviceName);
            return false;
        }

        final String servicePackageName = serviceComponent.getPackageName();
        final PackageManager pm = getContext().getPackageManager();
        final int serviceUid;
        try {
            serviceUid = pm.getPackageUidAsUser(servicePackageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, methodName + ": could not verify UID for " + serviceName);
            return false;
        }
        if (callingUid != serviceUid) {
            Slog.e(TAG, methodName + ": called by UID " + callingUid + ", but service UID is "
                    + serviceUid);
            return false;
        }
        return true;
    }

    final class TranslationManagerServiceStub extends ITranslationManager.Stub {
        @Override
        public void getSupportedLocales(IResultReceiver receiver, int userId)
                throws RemoteException {
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "getSupportedLocales"))) {
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
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "onSessionCreated"))) {
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
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "updateUiTranslationState"))) {
                    service.updateUiTranslationState(state, sourceSpec, destSpec, viewIds,
                            taskId);
                }
            }
        }

        /**
         * Dump the service state into the given stream. You run "adb shell dumpsys translation".
        */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            synchronized (mLock) {
                dumpLocked("", pw);
            }
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args,
                @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            new TranslationManagerServiceShellCommand(
                    TranslationManagerService.this).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(TRANSLATION_MANAGER_SERVICE,
                new TranslationManagerService.TranslationManagerServiceStub());
    }
}
