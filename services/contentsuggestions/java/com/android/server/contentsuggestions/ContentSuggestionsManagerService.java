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

import static android.Manifest.permission.MANAGE_CONTENT_SUGGESTIONS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.IContentSuggestionsManager;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;

/**
 * The system service for providing recents / overview with content suggestion selections and
 * classifications.
 *
 * <p>Calls are received here from
 * {@link android.app.contentsuggestions.ContentSuggestionsManager} then delegated to
 * a per user version of the service. From there they are routed to the remote actual implementation
 * that provides the suggestion selections and classifications.
 */
public class ContentSuggestionsManagerService extends
        AbstractMasterSystemService<
                        ContentSuggestionsManagerService, ContentSuggestionsPerUserService> {

    private static final String TAG = ContentSuggestionsManagerService.class.getSimpleName();
    private static final boolean VERBOSE = false; // TODO: make dynamic

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public ContentSuggestionsManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                com.android.internal.R.string.config_defaultContentSuggestionsService),
                UserManager.DISALLOW_CONTENT_SUGGESTIONS);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    protected ContentSuggestionsPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new ContentSuggestionsPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(
                Context.CONTENT_SUGGESTIONS_SERVICE, new ContentSuggestionsManagerStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_CONTENT_SUGGESTIONS, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private boolean isCallerRecents(int userId) {
        if (mServiceNameResolver.isTemporary(userId)) {
            // If a temporary service is set then skip the recents check
            return true;
        }
        return mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid());
    }

    private void enforceCallerIsRecents(int userId, String func) {
        if (isCallerRecents(userId)) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " expected caller is recents";
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    private class ContentSuggestionsManagerStub extends IContentSuggestionsManager.Stub {
        @Override
        public void provideContextImage(
                int userId,
                int taskId,
                @NonNull Bundle imageContextRequestExtras) {
            if (imageContextRequestExtras == null) {
                throw new IllegalArgumentException("Expected non-null imageContextRequestExtras");
            }
            enforceCallerIsRecents(UserHandle.getCallingUserId(), "provideContextImage");

            synchronized (mLock) {
                final ContentSuggestionsPerUserService service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.provideContextImageLocked(taskId, imageContextRequestExtras);
                } else {
                    if (VERBOSE) {
                        Slog.v(TAG, "provideContextImageLocked: no service for " + userId);
                    }
                }
            }
        }

        @Override
        public void suggestContentSelections(
                int userId,
                @NonNull SelectionsRequest selectionsRequest,
                @NonNull ISelectionsCallback selectionsCallback) {
            enforceCallerIsRecents(UserHandle.getCallingUserId(), "suggestContentSelections");

            synchronized (mLock) {
                final ContentSuggestionsPerUserService service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.suggestContentSelectionsLocked(selectionsRequest, selectionsCallback);
                } else {
                    if (VERBOSE) {
                        Slog.v(TAG, "suggestContentSelectionsLocked: no service for " + userId);
                    }
                }
            }
        }

        @Override
        public void classifyContentSelections(
                int userId,
                @NonNull ClassificationsRequest classificationsRequest,
                @NonNull IClassificationsCallback callback) {
            enforceCallerIsRecents(UserHandle.getCallingUserId(), "classifyContentSelections");

            synchronized (mLock) {
                final ContentSuggestionsPerUserService service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.classifyContentSelectionsLocked(classificationsRequest, callback);
                } else {
                    if (VERBOSE) {
                        Slog.v(TAG, "classifyContentSelectionsLocked: no service for " + userId);
                    }
                }
            }
        }

        @Override
        public void notifyInteraction(
                int userId, @NonNull String requestId, @NonNull Bundle bundle) {
            enforceCallerIsRecents(UserHandle.getCallingUserId(), "notifyInteraction");

            synchronized (mLock) {
                final ContentSuggestionsPerUserService service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.notifyInteractionLocked(requestId, bundle);
                } else {
                    if (VERBOSE) {
                        Slog.v(TAG, "reportInteractionLocked: no service for " + userId);
                    }
                }
            }
        }

        @Override
        public void isEnabled(int userId, @NonNull IResultReceiver receiver)
                throws RemoteException {
            enforceCallerIsRecents(UserHandle.getCallingUserId(), "isEnabled");

            boolean isDisabled;
            synchronized (mLock) {
                isDisabled = isDisabledLocked(userId);
            }
            receiver.send(isDisabled ? 0 : 1, null);
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            // Ensure that the caller is the shell process
            final int callingUid = Binder.getCallingUid();
            if (callingUid != android.os.Process.SHELL_UID
                    && callingUid != android.os.Process.ROOT_UID) {
                Slog.e(TAG, "Expected shell caller");
                return;
            }
            new ContentSuggestionsManagerServiceShellCommand(ContentSuggestionsManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
}
