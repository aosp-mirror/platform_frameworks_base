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

import static android.view.translation.TranslationManager.EXTRA_CAPABILITIES;
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
import android.view.translation.ITranslationServiceCallback;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;
import android.view.translation.UiTranslationSpec;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.TransferPipe;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ActivityTokens;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
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

    @GuardedBy("mLock")
    private TranslationServiceInfo mTranslationServiceInfo;

    @GuardedBy("mLock")
    private WeakReference<ActivityTokens> mLastActivityTokens;

    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    private final TranslationServiceRemoteCallback mRemoteServiceCallback =
            new TranslationServiceRemoteCallback();
    private final RemoteCallbackList<IRemoteCallback> mTranslationCapabilityCallbacks =
            new RemoteCallbackList<>();

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
        mTranslationServiceInfo = new TranslationServiceInfo(getContext(),
                serviceComponent, isTemporaryServiceSetLocked(), mUserId);
        mRemoteTranslationServiceInfo = mTranslationServiceInfo.getServiceInfo();
        return mTranslationServiceInfo.getServiceInfo();
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
            mRemoteTranslationService = new RemoteTranslationService(getContext(), serviceComponent,
                    mUserId, /* isInstantAllowed= */ false, mRemoteServiceCallback);
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

    public void registerTranslationCapabilityCallback(IRemoteCallback callback, int sourceUid) {
        mTranslationCapabilityCallbacks.register(callback, sourceUid);
        ensureRemoteServiceLocked();
    }

    public void unregisterTranslationCapabilityCallback(IRemoteCallback callback) {
        mTranslationCapabilityCallbacks.unregister(callback);
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
            IBinder token, int taskId, UiTranslationSpec uiTranslationSpec) {
        // Get top activity for a given task id
        final ActivityTokens taskTopActivityTokens =
                mActivityTaskManagerInternal.getTopActivityForTask(taskId);
        if (taskTopActivityTokens == null
                || taskTopActivityTokens.getShareableActivityToken() != token) {
            Slog.w(TAG, "Unknown activity or it was finished to query for update "
                    + "translation state for token=" + token + " taskId=" + taskId);
            return;
        }
        try {
            taskTopActivityTokens.getApplicationThread().updateUiTranslationState(
                    taskTopActivityTokens.getActivityToken(), state, sourceSpec, targetSpec,
                    viewIds, uiTranslationSpec);
            mLastActivityTokens = new WeakReference<>(taskTopActivityTokens);
        } catch (RemoteException e) {
            Slog.w(TAG, "Update UiTranslationState fail: " + e);
        }
        invokeCallbacks(state, sourceSpec, targetSpec);
    }

    @GuardedBy("mLock")
    public void dumpLocked(String prefix, FileDescriptor fd, PrintWriter pw) {
        if (mLastActivityTokens != null) {
            ActivityTokens activityTokens = mLastActivityTokens.get();
            if (activityTokens == null) {
                return;
            }
            try (TransferPipe tp = new TransferPipe()) {
                activityTokens.getApplicationThread().dumpActivity(tp.getWriteFd(),
                        activityTokens.getActivityToken(), prefix,
                        new String[]{"--translation"});
                tp.go(fd);
            } catch (IOException e) {
                pw.println(prefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(prefix + "Got a RemoteException while dumping the activity");
            }
        } else {
            pw.print(prefix); pw.println("No requested UiTranslation Activity.");
        }
    }

    private void invokeCallbacks(
            int state, TranslationSpec sourceSpec, TranslationSpec targetSpec) {
        Bundle res = new Bundle();
        res.putInt(EXTRA_STATE, state);
        // TODO(177500482): Store the locale pair so it can be sent for RESUME events.
        if (sourceSpec != null) {
            res.putSerializable(EXTRA_SOURCE_LOCALE, sourceSpec.getLocale());
            res.putSerializable(EXTRA_TARGET_LOCALE, targetSpec.getLocale());
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

    public ComponentName getServiceSettingsActivityLocked() {
        if (mTranslationServiceInfo == null) {
            return null;
        }
        final String activityName = mTranslationServiceInfo.getSettingsActivity();
        if (activityName == null) {
            return null;
        }
        final String packageName = mTranslationServiceInfo.getServiceInfo().packageName;
        return new ComponentName(packageName, activityName);
    }

    private void notifyClientsTranslationCapability(TranslationCapability capability) {
        final Bundle res = new Bundle();
        res.putParcelable(EXTRA_CAPABILITIES, capability);
        mTranslationCapabilityCallbacks.broadcast((callback, uid) -> {
            try {
                callback.sendResult(res);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke UiTranslationStateCallback: " + e);
            }
        });
    }

    private final class TranslationServiceRemoteCallback extends
            ITranslationServiceCallback.Stub {

        @Override
        public void updateTranslationCapability(TranslationCapability capability) {
            if (capability == null) {
                Slog.wtf(TAG, "received a null TranslationCapability from TranslationService.");
                return;
            }
            notifyClientsTranslationCapability(capability);
        }
    }
}
