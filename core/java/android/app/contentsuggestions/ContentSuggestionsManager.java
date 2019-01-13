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

package android.app.contentsuggestions;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * When provided with content from an app, can suggest selections and classifications of that
 * content.
 *
 * <p>The content is mainly a snapshot of a running task, the selections will be text and image
 * selections with that image content. These mSelections can then be classified to find actions and
 * entities on those selections.
 *
 * <p>Only accessible to blessed components such as Overview.
 *
 * @hide
 */
@SystemApi
public final class ContentSuggestionsManager {
    private static final String TAG = ContentSuggestionsManager.class.getSimpleName();

    @Nullable
    private final IContentSuggestionsManager mService;

    /** @hide */
    public ContentSuggestionsManager(@Nullable IContentSuggestionsManager service) {
        mService = service;
    }

    /**
     * Hints to the system that a new context image for the provided task should be sent to the
     * system content suggestions service.
     *
     * @param taskId of the task to snapshot.
     * @param imageContextRequestExtras sent with with request to provide implementation specific
     *                                  extra information.
     */
    public void provideContextImage(int taskId, @NonNull Bundle imageContextRequestExtras) {
        if (mService == null) {
            Log.e(TAG, "provideContextImage called, but no ContentSuggestionsManager configured");
            return;
        }

        try {
            mService.provideContextImage(taskId, imageContextRequestExtras);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Suggest content selections, based on the provided task id and optional
     * location on screen provided in the request. Called after provideContextImage().
     * The result can be passed to
     * {@link #classifyContentSelections(ClassificationsRequest, Executor, ClassificationsCallback)}
     *  to classify actions and entities on these selections.
     *
     * @param request containing the task and point location.
     * @param callbackExecutor to execute the provided callback on.
     * @param callback to receive the selections.
     */
    public void suggestContentSelections(
            @NonNull SelectionsRequest request,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull SelectionsCallback callback) {
        if (mService == null) {
            Log.e(TAG,
                    "suggestContentSelections called, but no ContentSuggestionsManager configured");
            return;
        }

        try {
            mService.suggestContentSelections(
                    request, new SelectionsCallbackWrapper(callback, callbackExecutor));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Classify actions and entities in content selections, as returned from
     * suggestContentSelections. Note these selections may be modified by the
     * caller before being passed here.
     *
     * @param request containing the selections to classify.
     * @param callbackExecutor to execute the provided callback on.
     * @param callback to receive the classifications.
     */
    public void classifyContentSelections(
            @NonNull ClassificationsRequest request,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull ClassificationsCallback callback) {
        if (mService == null) {
            Log.e(TAG, "classifyContentSelections called, "
                    + "but no ContentSuggestionsManager configured");
            return;
        }

        try {
            mService.classifyContentSelections(
                    request, new ClassificationsCallbackWrapper(callback, callbackExecutor));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Report telemetry for interaction with suggestions / classifications.
     *
     * @param requestId the id for the associated interaction
     * @param interaction to report back to the system content suggestions service.
     */
    public void notifyInteraction(@NonNull String requestId, @NonNull Bundle interaction) {
        if (mService == null) {
            Log.e(TAG, "notifyInteraction called, but no ContentSuggestionsManager configured");
            return;
        }

        try {
            mService.notifyInteraction(requestId, interaction);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback to receive content selections from
     *  {@link #suggestContentSelections(SelectionsRequest, Executor, SelectionsCallback)}.
     */
    public interface SelectionsCallback {
        /**
         * Async callback called when the content suggestions service has selections available.
         * These can be modified and sent back to the manager for classification. The contents of
         * the selection is implementation dependent.
         *
         * @param statusCode as defined by the implementation of content suggestions service.
         * @param selections not {@code null}, but can be size {@code 0}.
         */
        void onContentSelectionsAvailable(
                int statusCode, @NonNull List<ContentSelection> selections);
    }

    /**
     * Callback to receive classifications from
     * {@link #classifyContentSelections(ClassificationsRequest, Executor, ClassificationsCallback)}
     */
    public interface ClassificationsCallback {
        /**
         * Async callback called when the content suggestions service has classified selections. The
         * contents of the classification is implementation dependent.
         *
         * @param statusCode as defined by the implementation of content suggestions service.
         * @param classifications not {@code null}, but can be size {@code 0}.
         */
        void onContentClassificationsAvailable(int statusCode,
                @NonNull List<ContentClassification> classifications);
    }

    private static class SelectionsCallbackWrapper extends ISelectionsCallback.Stub {
        private final SelectionsCallback mCallback;
        private final Executor mExecutor;

        SelectionsCallbackWrapper(
                @NonNull SelectionsCallback callback, @NonNull Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onContentSelectionsAvailable(
                int statusCode, List<ContentSelection> selections) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mCallback.onContentSelectionsAvailable(statusCode, selections));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static final class ClassificationsCallbackWrapper extends
            IClassificationsCallback.Stub {
        private final ClassificationsCallback mCallback;
        private final Executor mExecutor;

        ClassificationsCallbackWrapper(@NonNull ClassificationsCallback callback,
                @NonNull Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onContentClassificationsAvailable(
                int statusCode, List<ContentClassification> classifications) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mCallback.onContentClassificationsAvailable(statusCode, classifications));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
