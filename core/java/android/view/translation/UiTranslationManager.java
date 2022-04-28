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
import android.content.ComponentName;
import android.content.Context;
import android.icu.util.ULocale;
import android.os.Binder;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillId;
import android.widget.TextView;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * <p>The {@link UiTranslationManager} class provides ways for apps to use the ui translation
 * function in framework.
 *
 * <p> The UI translation provides ways for apps to support inline translation for the views. For
 * example the system supports text translation for {@link TextView}. To support UI translation for
 * your views, you should override the following methods to provide the content to be translated
 * and deal with the translated result. Here is an example for {@link TextView}-like views:
 *
 * <pre><code>
 * public class MyTextView extends View {
 *     public MyTextView(...) {
 *         // implements how to show the translated result in your View in
 *         // ViewTranslationCallback and set it by setViewTranslationCallback()
 *         setViewTranslationCallback(new MyViewTranslationCallback());
 *     }
 *
 *     public void onCreateViewTranslationRequest(int[] supportedFormats,
 *             Consumer<ViewTranslationRequest> requestsCollector) {
 *        // collect the information that needs to be translated
 *        ViewTranslationRequest.Builder requestBuilder =
 *                     new ViewTranslationRequest.Builder(getAutofillId());
 *        requestBuilder.setValue(ViewTranslationRequest.ID_TEXT,
 *                         TranslationRequestValue.forText(etText()));
 *        requestsCollector.accept(requestBuilder.build());
 *     }
 *
 *     public void onProvideContentCaptureStructure(
 *             ViewStructure structure, int flags) {
 *         // set ViewTranslationResponse
 *         super.onViewTranslationResponse(response);
 *     }
 * }
 * </code></pre>
 *
 * <p>If your view provides its own virtual hierarchy (for example, if it's a browser that draws the
 * HTML using {@link android.graphics.Canvas} or native libraries in a different render process),
 * you must override {@link View#onCreateVirtualViewTranslationRequests(long[], int[], Consumer)} to
 * provide the content to be translated and implement
 * {@link View#onVirtualViewTranslationResponses(android.util.LongSparseArray)} for the translated
 * result. You also need to implement {@link android.view.translation.ViewTranslationCallback} to
 * handle the translated information show or hide in your {@link View}.
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
     * The state the caller requests to enable UI translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_STARTED = 0;
    /**
     * The state caller requests to pause UI translation. It will switch back to the original text.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_PAUSED = 1;
    /**
     * The state caller requests to resume the paused UI translation. It will show the translated
     * text again if the text had been translated.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_RESUMED = 2;
    /**
     * The state caller requests to disable UI translation when it no longer needs translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_FINISHED = 3;

    /** @hide */
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
    /** @hide */
    public static final String EXTRA_PACKAGE_NAME = "package_name";

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
     * @removed Use {@link #startTranslation(TranslationSpec, TranslationSpec, List, ActivityId,
     * UiTranslationSpec)} instead.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @Deprecated
    @SystemApi
    public void startTranslation(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec targetSpec, @NonNull List<AutofillId> viewIds,
            @NonNull ActivityId activityId) {
        startTranslation(
                sourceSpec, targetSpec, viewIds, activityId,
                new UiTranslationSpec.Builder().setShouldPadContentForCompat(true).build());
    }

    /**
     * Request ui translation for a given Views.
     *
     * @param sourceSpec        {@link TranslationSpec} for the data to be translated.
     * @param targetSpec        {@link TranslationSpec} for the translated data.
     * @param viewIds           A list of the {@link View}'s {@link AutofillId} which needs to be
     *                          translated
     * @param activityId        the identifier for the Activity which needs ui translation
     * @param uiTranslationSpec configuration for translation of the specified views
     * @throws IllegalArgumentException if the no {@link View}'s {@link AutofillId} in the list
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    @SystemApi
    public void startTranslation(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec targetSpec, @NonNull List<AutofillId> viewIds,
            @NonNull ActivityId activityId, @NonNull UiTranslationSpec uiTranslationSpec) {
        // TODO(b/177789967): Return result code or find a way to notify the status.
        Objects.requireNonNull(sourceSpec);
        Objects.requireNonNull(targetSpec);
        Objects.requireNonNull(viewIds);
        Objects.requireNonNull(activityId);
        Objects.requireNonNull(activityId.getToken());
        Objects.requireNonNull(uiTranslationSpec);
        if (viewIds.size() == 0) {
            throw new IllegalArgumentException("Invalid empty views: " + viewIds);
        }
        try {
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_STARTED, sourceSpec,
                    targetSpec, viewIds, activityId.getToken(), activityId.getTaskId(),
                    uiTranslationSpec,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to disable the ui translation. It will destroy all the {@link Translator}s and no
     * longer to show the translated text.
     *
     * @param activityId the identifier for the Activity which needs ui translation
     * @throws NullPointerException the activityId or
     *                              {@link android.app.assist.ActivityId#getToken()} is {@code null}
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
                    activityId.getToken(), activityId.getTaskId(), null /* uiTranslationSpec */,
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
     *                              {@link android.app.assist.ActivityId#getToken()} is {@code null}
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
                    activityId.getToken(), activityId.getTaskId(), null /* uiTranslationSpec */,
                    mContext.getUserId());
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
     *                              {@link android.app.assist.ActivityId#getToken()} is {@code null}
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
                    activityId.getToken(), activityId.getTaskId(), null /* uiTranslationSpec */,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register for notifications of UI Translation state changes on the foreground Activity. This
     * is available to the owning application itself and also the current input method.
     * <p>
     * The application whose UI is being translated can use this to customize the UI Translation
     * behavior in ways that aren't made easy by methods like
     * {@link View#onCreateViewTranslationRequest(int[], Consumer)}.
     * <p>
     * Input methods can use this to offer complementary features to UI Translation; for example,
     * enabling outgoing message translation when the system is translating incoming messages in a
     * communication app.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#TIRAMISU}, if Activities are already
     * being translated when a callback is registered, methods on the callback will be invoked for
     * each translated activity, depending on the state of translation:
     * <ul>
     *     <li>If translation is <em>not</em> paused,
     *     {@link UiTranslationStateCallback#onStarted} will be invoked.</li>
     *     <li>If translation <em>is</em> paused, {@link UiTranslationStateCallback#onStarted}
     *     will first be invoked, followed by {@link UiTranslationStateCallback#onPaused}.</li>
     * </ul>
     *
     * @param callback the callback to register for receiving the state change
     *                 notifications
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

    /**
     * Notify apps the translation is finished because {@link #finishTranslation(ActivityId)} is
     * called or Activity is destroyed.
     *
     * @param activityDestroyed if the ui translation is finished because of activity destroyed.
     * @param activityId        the identifier for the Activity which needs ui translation
     * @param componentName     the ui translated Activity componentName.
     * @hide
     */
    public void onTranslationFinished(boolean activityDestroyed, ActivityId activityId,
            ComponentName componentName) {
        try {
            mService.onTranslationFinished(activityDestroyed, activityId.getToken(), componentName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    @GuardedBy("mCallbacks")
    private final Map<UiTranslationStateCallback, IRemoteCallback> mCallbacks = new ArrayMap<>();

    private static class UiTranslationStateRemoteCallback extends IRemoteCallback.Stub {
        private final Executor mExecutor;
        private final UiTranslationStateCallback mCallback;
        private ULocale mSourceLocale;
        private ULocale mTargetLocale;

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
            String packageName = bundle.getString(EXTRA_PACKAGE_NAME);
            switch (state) {
                case STATE_UI_TRANSLATION_STARTED:
                    mSourceLocale = bundle.getSerializable(EXTRA_SOURCE_LOCALE, ULocale.class);
                    mTargetLocale = bundle.getSerializable(EXTRA_TARGET_LOCALE, ULocale.class);
                    mCallback.onStarted(mSourceLocale, mTargetLocale, packageName);
                    break;
                case STATE_UI_TRANSLATION_RESUMED:
                    mCallback.onResumed(mSourceLocale, mTargetLocale, packageName);
                    break;
                case STATE_UI_TRANSLATION_PAUSED:
                    mCallback.onPaused(packageName);
                    break;
                case STATE_UI_TRANSLATION_FINISHED:
                    mCallback.onFinished(packageName);
                    break;
                default:
                    Log.wtf(TAG, "Unexpected translation state:" + state);
            }
        }
    }
}
