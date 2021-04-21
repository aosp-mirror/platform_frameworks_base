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

package android.view.translation;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.assist.ActivityId;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillId;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

// TODO(b/178044703): Describe what UI Translation is.
/**
 * The {@link UiTranslationManager} class provides ways for apps to use the ui translation
 * function in framework.
 */
public final class UiTranslationManager {

    private static final String TAG = "UiTranslationManager";

    /**
     * The tag which uses for enabling debug log dump. To enable it, we can use command "adb shell
     * setprop log.tag.UiTranslation DEBUG".
     *
     * @hide
     */
    public static final String LOG_TAG = "UiTranslation";

    /**
     * The state caller request to disable utranslation,, it is no longer need to ui translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_STARTED = 0;
    /**
     * The state caller request to pause ui translation, it will switch back to the original text.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_PAUSED = 1;
    /**
     * The state caller request to resume the paused ui translation, it will show the translated
     * text again if the text had been translated.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_RESUMED = 2;
    /**
     * The state the caller request to enable ui translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_FINISHED = 3;
    /**
     * @hide
     */
    @IntDef(prefix = {"STATE__TRANSLATION"}, value = {
            STATE_UI_TRANSLATION_STARTED,
            STATE_UI_TRANSLATION_PAUSED,
            STATE_UI_TRANSLATION_RESUMED,
            STATE_UI_TRANSLATION_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UiTranslationState {
    }

    // Keys for the data transmitted in the internal UI Translation state callback.
    /** @hide */
    public static final String EXTRA_STATE = "state";
    /** @hide */
    public static final String EXTRA_SOURCE_LOCALE = "source_locale";
    /** @hide */
    public static final String EXTRA_TARGET_LOCALE = "target_locale";

    @NonNull
    private final Context mContext;

    private final ITranslationManager mService;

    /**
     * @hide
     */
    public UiTranslationManager(@NonNull Context context, ITranslationManager service) {
        mContext = Objects.requireNonNull(context);
        mService = service;
    }

    /**
     * Request ui translation for a given Views.
     *
     * NOTE: Please use {@code startTranslation(TranslationSpec, TranslationSpec, List<AutofillId>,
     * ActivityId)} instead.
     *
     * @param sourceSpec {@link TranslationSpec} for the data to be translated.
     * @param targetSpec {@link TranslationSpec} for the translated data.
     * @param viewIds A list of the {@link View}'s {@link AutofillId} which needs to be translated
     * @param taskId the Activity Task id which needs ui translation
     * @deprecated Use {@code startTranslation(TranslationSpec, TranslationSpec, List<AutofillId>,
     * ActivityId)} instead.
     *
     * @hide
     * @removed
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void startTranslation(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec targetSpec, @NonNull List<AutofillId> viewIds,
            int taskId) {
        Objects.requireNonNull(sourceSpec);
        Objects.requireNonNull(targetSpec);
        Objects.requireNonNull(viewIds);
        if (viewIds.size() == 0) {
            throw new IllegalArgumentException("Invalid empty views: " + viewIds);
        }
        try {
            mService.updateUiTranslationStateByTaskId(STATE_UI_TRANSLATION_STARTED, sourceSpec,
                    targetSpec, viewIds, taskId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request ui translation for a given Views.
     *
     * @param sourceSpec {@link TranslationSpec} for the data to be translated.
     * @param targetSpec {@link TranslationSpec} for the translated data.
     * @param viewIds A list of the {@link View}'s {@link AutofillId} which needs to be translated
     * @param activityId the identifier for the Activity which needs ui translation
     * @throws IllegalArgumentException if the no {@link View}'s {@link AutofillId} in the list
     * @throws NullPointerException the sourceSpec, targetSpec, viewIds, activityId or
     *         {@link android.app.assist.ActivityId#getToken()} is {@code null}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void startTranslation(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec targetSpec, @NonNull List<AutofillId> viewIds,
            @NonNull ActivityId activityId) {
        // TODO(b/177789967): Return result code or find a way to notify the status.
        Objects.requireNonNull(sourceSpec);
        Objects.requireNonNull(targetSpec);
        Objects.requireNonNull(viewIds);
        Objects.requireNonNull(activityId);
        Objects.requireNonNull(activityId.getToken());
        if (viewIds.size() == 0) {
            throw new IllegalArgumentException("Invalid empty views: " + viewIds);
        }
        try {
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_STARTED, sourceSpec,
                    targetSpec, viewIds, activityId.getToken(), activityId.getTaskId(),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to disable the ui translation. It will destroy all the {@link Translator}s and no
     * longer to show to show the translated text.
     *
     * NOTE: Please use {@code finishTranslation(ActivityId)} instead.
     *
     * @param taskId the Activity Task id which needs ui translation
     * @deprecated Use {@code finishTranslation(ActivityId)} instead.
     *
     * @hide
     * @removed
     *
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void finishTranslation(int taskId) {
        try {
            mService.updateUiTranslationStateByTaskId(STATE_UI_TRANSLATION_FINISHED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */, taskId,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to disable the ui translation. It will destroy all the {@link Translator}s and no
     * longer to show to show the translated text.
     *
     * @param activityId the identifier for the Activity which needs ui translation
     * @throws NullPointerException the activityId or
     *         {@link android.app.assist.ActivityId#getToken()} is {@code null}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void finishTranslation(@NonNull ActivityId activityId) {
        try {
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(activityId.getToken());
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_FINISHED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */,
                    activityId.getToken(), activityId.getTaskId(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to pause the current ui translation's {@link Translator} which will switch back to
     * the original language.
     *
     * NOTE: Please use {@code pauseTranslation(ActivityId)} instead.
     *
     * @param taskId the Activity Task id which needs ui translation
     * @deprecated Use {@code pauseTranslation(ActivityId)} instead.
     *
     * @hide
     * @removed
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void pauseTranslation(int taskId) {
        try {
            mService.updateUiTranslationStateByTaskId(STATE_UI_TRANSLATION_PAUSED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */, taskId,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to pause the current ui translation's {@link Translator} which will switch back to
     * the original language.
     *
     * @param activityId the identifier for the Activity which needs ui translation
     * @throws NullPointerException the activityId or
     *         {@link android.app.assist.ActivityId#getToken()} is {@code null}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void pauseTranslation(@NonNull ActivityId activityId) {
        try {
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(activityId.getToken());
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_PAUSED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */,
                    activityId.getToken(), activityId.getTaskId(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to resume the paused ui translation's {@link Translator} which will switch to the
     * translated language if the text had been translated.
     *
     * NOTE: Please use {@code resumeTranslation(ActivityId)} instead.
     *
     * @param taskId the Activity Task id which needs ui translation
     * @deprecated Use {@code resumeTranslation(ActivityId)} instead.
     *
     * @hide
     * @removed
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void resumeTranslation(int taskId) {
        try {
            mService.updateUiTranslationStateByTaskId(STATE_UI_TRANSLATION_RESUMED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */,
                    taskId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to resume the paused ui translation's {@link Translator} which will switch to the
     * translated language if the text had been translated.
     *
     * @param activityId the identifier for the Activity which needs ui translation
     * @throws NullPointerException the activityId or
     *         {@link android.app.assist.ActivityId#getToken()} is {@code null}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void resumeTranslation(@NonNull ActivityId activityId) {
        try {
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(activityId.getToken());
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_RESUMED,
                    null /* sourceSpec */, null /* targetSpec */, null /* viewIds */,
                    activityId.getToken(), activityId.getTaskId(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/178044703): Fix the View API link when it becomes public.
    /**
     * Register for notifications of UI Translation state changes on the foreground activity. This
     * is available to the owning application itself and also the current input method.
     * <p>
     * The application whose UI is being translated can use this to customize the UI Translation
     * behavior in ways that aren't made easy by methods like
     * View#onCreateTranslationRequest().
     * <p>
     * Input methods can use this to offer complementary features to UI Translation; for example,
     * enabling outgoing message translation when the system is translating incoming messages in a
     * communication app.
     *
     * @param callback the callback to register for receiving the state change
     *         notifications
     */
    public void registerUiTranslationStateCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull UiTranslationStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        synchronized (mCallbacks) {
            if (mCallbacks.containsKey(callback)) {
                Log.w(TAG, "registerUiTranslationStateCallback: callback already registered;"
                        + " ignoring.");
                return;
            }
            final IRemoteCallback remoteCallback =
                    new UiTranslationStateRemoteCallback(executor, callback);
            try {
                mService.registerUiTranslationStateCallback(remoteCallback, mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCallbacks.put(callback, remoteCallback);
        }
    }

    /**
     * Unregister {@code callback}.
     *
     * @see #registerUiTranslationStateCallback(Executor, UiTranslationStateCallback)
     */
    public void unregisterUiTranslationStateCallback(@NonNull UiTranslationStateCallback callback) {
        Objects.requireNonNull(callback);

        synchronized (mCallbacks) {
            final IRemoteCallback remoteCallback = mCallbacks.get(callback);
            if (remoteCallback == null) {
                Log.w(TAG, "unregisterUiTranslationStateCallback: callback not found; ignoring.");
                return;
            }
            try {
                mService.unregisterUiTranslationStateCallback(remoteCallback, mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCallbacks.remove(callback);
        }
    }

    @NonNull
    @GuardedBy("mCallbacks")
    private final Map<UiTranslationStateCallback, IRemoteCallback> mCallbacks = new ArrayMap<>();

    private static class UiTranslationStateRemoteCallback extends IRemoteCallback.Stub {
        private final Executor mExecutor;
        private final UiTranslationStateCallback mCallback;

        UiTranslationStateRemoteCallback(Executor executor,
                UiTranslationStateCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void sendResult(Bundle bundle) {
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> onStateChange(bundle)));
        }

        private void onStateChange(Bundle bundle) {
            int state = bundle.getInt(EXTRA_STATE);
            switch (state) {
                case STATE_UI_TRANSLATION_STARTED:
                case STATE_UI_TRANSLATION_RESUMED:
                    mCallback.onStarted(
                            bundle.getString(EXTRA_SOURCE_LOCALE),
                            bundle.getString(EXTRA_TARGET_LOCALE));
                    break;
                case STATE_UI_TRANSLATION_PAUSED:
                    mCallback.onPaused();
                    break;
                case STATE_UI_TRANSLATION_FINISHED:
                    mCallback.onFinished();
                    break;
                default:
                    Log.wtf(TAG, "Unexpected translation state:" + state);
            }
        }
    }
}
