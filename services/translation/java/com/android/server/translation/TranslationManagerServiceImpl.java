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

import static android.view.translation.UiTranslationManager.EXTRA_SOURCE_LOCALE;
import static android.view.translation.UiTranslationManager.EXTRA_STATE;
import static android.view.translation.UiTranslationManager.EXTRA_TARGET_LOCALE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.translation.TranslationServiceInfo;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InputMethodInfo;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ActivityTokens;

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
    void onTranslationCapabilitiesRequestLocked(@TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int destFormat,
            @NonNull ResultReceiver resultReceiver) {
        final RemoteTranslationService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onTranslationCapabilitiesRequest(sourceFormat, destFormat,
                    resultReceiver);
        }
    }

    @GuardedBy("mLock")
    void onSessionCreatedLocked(@NonNull TranslationContext translationContext, int sessionId,
            IResultReceiver resultReceiver) {
        final RemoteTranslationService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.onSessionCreated(translationContext, sessionId, resultReceiver);
        }
    }

    @GuardedBy("mLock")
    public void updateUiTranslationStateLocked(@UiTranslationState int state,
            TranslationSpec sourceSpec, TranslationSpec targetSpec, List<AutofillId> viewIds,
            int taskId) {
        // deprecated
        final ActivityTokens taskTopActivityTokens =
                mActivityTaskManagerInternal.getTopActivityForTask(taskId);
        if (taskTopActivityTokens == null) {
            Slog.w(TAG, "Unknown activity to query for update translation state.");
            return;
        }
        updateUiTranslationStateByActivityTokens(taskTopActivityTokens, state, sourceSpec,
                targetSpec, viewIds);
    }

    @GuardedBy("mLock")
    public void updateUiTranslationStateLocked(@UiTranslationState int state,
            TranslationSpec sourceSpec, TranslationSpec targetSpec, List<AutofillId> viewIds,
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
        updateUiTranslationStateByActivityTokens(taskTopActivityTokens, state, sourceSpec,
                targetSpec, viewIds);
    }

    private void updateUiTranslationStateByActivityTokens(ActivityTokens tokens,
            @UiTranslationState int state, TranslationSpec sourceSpec, TranslationSpec targetSpec,
            List<AutofillId> viewIds) {
        try {
            tokens.getApplicationThread().updateUiTranslationState(tokens.getActivityToken(), state,
                    sourceSpec, targetSpec, viewIds);
        } catch (RemoteException e) {
            Slog.w(TAG, "Update UiTranslationState fail: " + e);
        }
        invokeCallbacks(state, sourceSpec, targetSpec);
    }

    private void invokeCallbacks(
            int state, TranslationSpec sourceSpec, TranslationSpec targetSpec) {
        Bundle res = new Bundle();
        res.putInt(EXTRA_STATE, state);
        // TODO(177500482): Store the locale pair so it can be sent for RESUME events.
        if (sourceSpec != null) {
            res.putString(EXTRA_SOURCE_LOCALE, sourceSpec.getLanguage());
            res.putString(EXTRA_TARGET_LOCALE, targetSpec.getLanguage());
        }
        // TODO(177500482): Only support the *current* Input Method.
        List<InputMethodInfo> enabledInputMethods =
                LocalServices.getService(InputMethodManagerInternal.class)
                        .getEnabledInputMethodListAsUser(mUserId);
        mCallbacks.broadcast((callback, uid) -> {
            // Code here is non-optimal since it's temporary..
            boolean isIme = false;
            for (InputMethodInfo inputMethod : enabledInputMethods) {
                if ((int) uid == inputMethod.getServiceInfo().applicationInfo.uid) {
                    isIme = true;
                }
            }
            // TODO(177500482): Invoke it for the application being translated too.
            if (!isIme) {
                return;
            }
            try {
                callback.sendResult(res);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke UiTranslationStateCallback: " + e);
            }
        });
    }

    public void registerUiTranslationStateCallback(IRemoteCallback callback, int sourceUid) {
        mCallbacks.register(callback, sourceUid);
        // TODO(177500482): trigger the callback here if we're already translating the UI.
    }

    public void unregisterUiTranslationStateCallback(IRemoteCallback callback) {
        mCallbacks.unregister(callback);
    }

    private final RemoteCallbackList<IRemoteCallback> mCallbacks = new RemoteCallbackList<>();
}
