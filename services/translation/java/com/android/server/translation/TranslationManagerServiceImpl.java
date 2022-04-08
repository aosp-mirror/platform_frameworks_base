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
import static android.view.translation.UiTranslationManager.EXTRA_PACKAGE_NAME;
import static android.view.translation.UiTranslationManager.EXTRA_SOURCE_LOCALE;
import static android.view.translation.UiTranslationManager.EXTRA_STATE;
import static android.view.translation.UiTranslationManager.EXTRA_TARGET_LOCALE;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_FINISHED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_PAUSED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_RESUMED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_STARTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.translation.TranslationServiceInfo;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InputMethodInfo;
import android.view.translation.ITranslationServiceCallback;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationController;
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
    @SuppressLint("IsLoggableTagLength")
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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

    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;

    private final TranslationServiceRemoteCallback mRemoteServiceCallback =
            new TranslationServiceRemoteCallback();
    private final RemoteCallbackList<IRemoteCallback> mTranslationCapabilityCallbacks =
            new RemoteCallbackList<>();
    private final ArraySet<IBinder> mWaitingFinishedCallbackActivities = new ArraySet<>();

    /**
     * Key is translated activity uid, value is the specification and state for the translation.
     */
    @GuardedBy("mLock")
    private final SparseArray<ActiveTranslation> mActiveTranslations = new SparseArray<>();

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

    private int getActivityUidByComponentName(Context context, ComponentName componentName,
            int userId) {
        int translationActivityUid = -1;
        try {
            if (componentName != null) {
                translationActivityUid = context.getPackageManager().getApplicationInfoAsUser(
                        componentName.getPackageName(), 0, userId).uid;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "Cannot find packageManager for" + componentName);
        }
        return translationActivityUid;
    }

    @GuardedBy("mLock")
    public void onTranslationFinishedLocked(boolean activityDestroyed, IBinder token,
            ComponentName componentName) {
        final int translationActivityUid =
                getActivityUidByComponentName(getContext(), componentName, getUserId());
        final String packageName = componentName.getPackageName();
        if (activityDestroyed) {
            // In the Activity destroy case, we only calls onTranslationFinished() in
            // non-finisTranslation() state. If there is a finisTranslation() calls by apps, we
            // should remove the waiting callback to avoid callback twice.
            invokeCallbacks(STATE_UI_TRANSLATION_FINISHED,
                    /* sourceSpec= */ null, /* targetSpec= */ null,
                    packageName, translationActivityUid);
            mWaitingFinishedCallbackActivities.remove(token);
        } else {
            if (mWaitingFinishedCallbackActivities.contains(token)) {
                invokeCallbacks(STATE_UI_TRANSLATION_FINISHED,
                        /* sourceSpec= */ null, /* targetSpec= */ null,
                        packageName, translationActivityUid);
                mWaitingFinishedCallbackActivities.remove(token);
            }
        }
    }

    @GuardedBy("mLock")
    public void updateUiTranslationStateLocked(@UiTranslationState int state,
            TranslationSpec sourceSpec, TranslationSpec targetSpec, List<AutofillId> viewIds,
            IBinder token, int taskId, UiTranslationSpec uiTranslationSpec) {
        // If the app starts a new Activity in the same task then the finish or pause API
        // is called, the operation doesn't work if we only check task top Activity. The top
        // Activity is the new Activity, the original Activity is paused in the same task.
        // To make sure the operation still work, we use the token to find the target Activity in
        // this task, not the top Activity only.
        ActivityTokens candidateActivityTokens =
                mActivityTaskManagerInternal.getAttachedNonFinishingActivityForTask(taskId, token);
        if (candidateActivityTokens == null) {
            Slog.w(TAG, "Unknown activity or it was finished to query for update "
                    + "translation state for token=" + token + " taskId=" + taskId + " for "
                    + "state= " + state);
            return;
        }
        mLastActivityTokens = new WeakReference<>(candidateActivityTokens);
        if (state == STATE_UI_TRANSLATION_FINISHED) {
            mWaitingFinishedCallbackActivities.add(token);
        }
        IBinder activityToken = candidateActivityTokens.getActivityToken();
        try {
            candidateActivityTokens.getApplicationThread().updateUiTranslationState(
                    activityToken, state, sourceSpec, targetSpec,
                    viewIds, uiTranslationSpec);
        } catch (RemoteException e) {
            Slog.w(TAG, "Update UiTranslationState fail: " + e);
        }

        ComponentName componentName = mActivityTaskManagerInternal.getActivityName(activityToken);
        int translationActivityUid =
                getActivityUidByComponentName(getContext(), componentName, getUserId());
        String packageName = componentName.getPackageName();
        if (state != STATE_UI_TRANSLATION_FINISHED) {
            invokeCallbacks(state, sourceSpec, targetSpec, packageName, translationActivityUid);
            updateActiveTranslations(state, sourceSpec, targetSpec, packageName,
                    translationActivityUid);
        } else {
            if (mActiveTranslations.contains(translationActivityUid)) {
                mActiveTranslations.delete(translationActivityUid);
            } else {
                Slog.w(TAG, "Finishing translation for activity with uid=" + translationActivityUid
                        + " but no active translation was found for it");
            }
        }
    }

    @GuardedBy("mLock")
    private void updateActiveTranslations(int state, TranslationSpec sourceSpec,
            TranslationSpec targetSpec, String packageName, int translationActivityUid) {
        // Keep track of active translations so that we can trigger callbacks that are
        // registered after translation has started.
        switch (state) {
            case STATE_UI_TRANSLATION_STARTED: {
                ActiveTranslation activeTranslation = new ActiveTranslation(sourceSpec,
                        targetSpec, packageName);
                mActiveTranslations.put(translationActivityUid, activeTranslation);
                break;
            }
            case STATE_UI_TRANSLATION_PAUSED:
            case STATE_UI_TRANSLATION_RESUMED: {
                ActiveTranslation activeTranslation = mActiveTranslations.get(
                        translationActivityUid);
                if (activeTranslation != null) {
                    activeTranslation.isPaused = (state == STATE_UI_TRANSLATION_PAUSED);
                } else {
                    Slog.w(TAG, "Pausing or resuming translation for activity with uid="
                            + translationActivityUid
                            + " but no active translation was found for it");
                }
                break;
            }
        }
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
                        new String[]{
                                Activity.DUMP_ARG_DUMP_DUMPABLE,
                                UiTranslationController.DUMPABLE_NAME
                        });
                tp.go(fd);
            } catch (IOException e) {
                pw.println(prefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(prefix + "Got a RemoteException while dumping the activity");
            }
        } else {
            pw.print(prefix);
            pw.println("No requested UiTranslation Activity.");
        }
        final int waitingFinishCallbackSize = mWaitingFinishedCallbackActivities.size();
        if (waitingFinishCallbackSize > 0) {
            pw.print(prefix);
            pw.print("number waiting finish callback activities: ");
            pw.println(waitingFinishCallbackSize);
            for (IBinder activityToken : mWaitingFinishedCallbackActivities) {
                pw.print(prefix);
                pw.print("activityToken: ");
                pw.println(activityToken);
            }
        }
    }

    private void invokeCallbacks(
            int state, TranslationSpec sourceSpec, TranslationSpec targetSpec, String packageName,
            int translationActivityUid) {
        Bundle result = createResultForCallback(state, sourceSpec, targetSpec, packageName);
        if (mCallbacks.getRegisteredCallbackCount() == 0) {
            return;
        }
        List<InputMethodInfo> enabledInputMethods = getEnabledInputMethods();
        mCallbacks.broadcast((callback, uid) -> {
            invokeCallback((int) uid, translationActivityUid, callback, result,
                    enabledInputMethods);
        });
    }

    private List<InputMethodInfo> getEnabledInputMethods() {
        return LocalServices.getService(InputMethodManagerInternal.class)
                .getEnabledInputMethodListAsUser(mUserId);
    }

    private Bundle createResultForCallback(
            int state, TranslationSpec sourceSpec, TranslationSpec targetSpec, String packageName) {
        Bundle result = new Bundle();
        result.putInt(EXTRA_STATE, state);
        // TODO(177500482): Store the locale pair so it can be sent for RESUME events.
        if (sourceSpec != null) {
            result.putSerializable(EXTRA_SOURCE_LOCALE, sourceSpec.getLocale());
            result.putSerializable(EXTRA_TARGET_LOCALE, targetSpec.getLocale());
        }
        result.putString(EXTRA_PACKAGE_NAME, packageName);
        return result;
    }

    private void invokeCallback(
            int callbackSourceUid, int translationActivityUid, IRemoteCallback callback,
            Bundle result, List<InputMethodInfo> enabledInputMethods) {
        if (callbackSourceUid == translationActivityUid) {
            // Invoke callback for the application being translated.
            try {
                callback.sendResult(result);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke UiTranslationStateCallback: " + e);
            }
            return;
        }

        // TODO(177500482): Only support the *current* Input Method.
        // Code here is non-optimal since it's temporary..
        boolean isIme = false;
        for (InputMethodInfo inputMethod : enabledInputMethods) {
            if (callbackSourceUid == inputMethod.getServiceInfo().applicationInfo.uid) {
                isIme = true;
                break;
            }
        }

        if (!isIme) {
            return;
        }
        try {
            callback.sendResult(result);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to invoke UiTranslationStateCallback: " + e);
        }
    }

    @GuardedBy("mLock")
    public void registerUiTranslationStateCallbackLocked(IRemoteCallback callback, int sourceUid) {
        mCallbacks.register(callback, sourceUid);

        if (mActiveTranslations.size() == 0) {
            return;
        }

        // Trigger the callback for already active translations.
        List<InputMethodInfo> enabledInputMethods = getEnabledInputMethods();
        for (int i = 0; i < mActiveTranslations.size(); i++) {
            int activeTranslationUid = mActiveTranslations.keyAt(i);
            ActiveTranslation activeTranslation = mActiveTranslations.valueAt(i);
            if (activeTranslation == null) {
                continue;
            }
            String packageName = activeTranslation.packageName;
            if (DEBUG) {
                Slog.d(TAG, "Triggering callback for sourceUid=" + sourceUid
                        + " for translated activity with uid=" + activeTranslationUid
                        + "packageName=" + packageName + " isPaused=" + activeTranslation.isPaused);
            }

            Bundle startedResult = createResultForCallback(STATE_UI_TRANSLATION_STARTED,
                    activeTranslation.sourceSpec, activeTranslation.targetSpec,
                    packageName);
            invokeCallback(sourceUid, activeTranslationUid, callback, startedResult,
                    enabledInputMethods);
            if (activeTranslation.isPaused) {
                // Also send event so callback owners know that translation was started then paused.
                Bundle pausedResult = createResultForCallback(STATE_UI_TRANSLATION_PAUSED,
                        activeTranslation.sourceSpec, activeTranslation.targetSpec,
                        packageName);
                invokeCallback(sourceUid, activeTranslationUid, callback, pausedResult,
                        enabledInputMethods);
            }
        }
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

    private static final class ActiveTranslation {
        public final TranslationSpec sourceSpec;
        public final TranslationSpec targetSpec;
        public final String packageName;
        public boolean isPaused = false;

        private ActiveTranslation(TranslationSpec sourceSpec, TranslationSpec targetSpec,
                String packageName) {
            this.sourceSpec = sourceSpec;
            this.targetSpec = targetSpec;
            this.packageName = packageName;
        }
    }
}
