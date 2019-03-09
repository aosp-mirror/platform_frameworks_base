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

package android.service.contentsuggestions;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.ContentSuggestionsManager;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

/**
 * @hide
 */
@SystemApi
public abstract class ContentSuggestionsService extends Service {

    private static final String TAG = ContentSuggestionsService.class.getSimpleName();

    private Handler mHandler;

    /**
     * The action for the intent used to define the content suggestions service.
     *
     * <p>To be supported, the service must also require the
     * * {@link android.Manifest.permission#BIND_CONTENT_SUGGESTIONS_SERVICE} permission so
     * * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.contentsuggestions.ContentSuggestionsService";

    private final IContentSuggestionsService mInterface = new IContentSuggestionsService.Stub() {
        @Override
        public void provideContextImage(int taskId, GraphicBuffer contextImage,
                Bundle imageContextRequestExtras) {

            Bitmap wrappedBuffer = null;
            if (contextImage != null) {
                wrappedBuffer = Bitmap.wrapHardwareBuffer(
                        HardwareBuffer.createFromGraphicBuffer(contextImage), null);
            }

            mHandler.sendMessage(
                    obtainMessage(ContentSuggestionsService::onProcessContextImage,
                            ContentSuggestionsService.this, taskId,
                            wrappedBuffer,
                            imageContextRequestExtras));
        }

        @Override
        public void suggestContentSelections(SelectionsRequest request,
                ISelectionsCallback callback) {
            mHandler.sendMessage(obtainMessage(
                    ContentSuggestionsService::onSuggestContentSelections,
                    ContentSuggestionsService.this, request, wrapSelectionsCallback(callback)));

        }

        @Override
        public void classifyContentSelections(ClassificationsRequest request,
                IClassificationsCallback callback) {
            mHandler.sendMessage(obtainMessage(
                    ContentSuggestionsService::onClassifyContentSelections,
                    ContentSuggestionsService.this, request, wrapClassificationCallback(callback)));
        }

        @Override
        public void notifyInteraction(String requestId, Bundle interaction) {
            mHandler.sendMessage(
                    obtainMessage(ContentSuggestionsService::onNotifyInteraction,
                            ContentSuggestionsService.this, requestId, interaction));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Called by the system to provide the snapshot for the task associated with the given
     * {@param taskId}.
     */
    public void onProcessContextImage(
            int taskId, @Nullable Bitmap contextImage, @NonNull Bundle extras) {
        // TODO(b/127532182): remove after next prebuilt drop.
        processContextImage(taskId, contextImage, extras);
    }

    /**
     * Content selections have been request through {@link ContentSuggestionsManager}, implementer
     * should reply on the callback with selections.
     */
    public void onSuggestContentSelections(@NonNull SelectionsRequest request,
            @NonNull ContentSuggestionsManager.SelectionsCallback callback) {
        // TODO(b/127532182): remove after next prebuilt drop.
        suggestContentSelections(request, callback);
    }

    /**
     * Content classifications have been request through {@link ContentSuggestionsManager},
     * implementer should reply on the callback with classifications.
     */
    public void onClassifyContentSelections(@NonNull ClassificationsRequest request,
            @NonNull ContentSuggestionsManager.ClassificationsCallback callback) {
        // TODO(b/127532182): remove after next prebuilt drop.
        classifyContentSelections(request, callback);
    }

    /**
     * User interactions have been reported through {@link ContentSuggestionsManager}, implementer
     * should handle those interactions.
     */
    public void onNotifyInteraction(
            @NonNull String requestId, @NonNull Bundle interaction) {
        // TODO(b/127532182): remove after next prebuilt drop.
        notifyInteraction(requestId, interaction);
    }

    private ContentSuggestionsManager.SelectionsCallback wrapSelectionsCallback(
            ISelectionsCallback callback) {
        return (statusCode, selections) -> {
            try {
                callback.onContentSelectionsAvailable(statusCode, selections);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result: " + e);
            }
        };
    }

    private ContentSuggestionsManager.ClassificationsCallback wrapClassificationCallback(
            IClassificationsCallback callback) {
        return ((statusCode, classifications) -> {
            try {
                callback.onContentClassificationsAvailable(statusCode, classifications);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result: " + e);
            }
        });
    }


    /**
     * For temporary compat reason, remove with b/127532182
     * @deprecated use {@link #onProcessContextImage(int, Bitmap, Bundle)} instead.
     */
    @Deprecated
    public void processContextImage(
            int taskId, @Nullable Bitmap contextImage, @NonNull Bundle extras) {
    }

    /**
     * For temporary compat reason, remove with b/127532182
     * @deprecated use {@link #onSuggestContentSelections(SelectionsRequest,
     * ContentSuggestionsManager.SelectionsCallback)} instead.
     */
    @Deprecated
    public void suggestContentSelections(@NonNull SelectionsRequest request,
            @NonNull ContentSuggestionsManager.SelectionsCallback callback) {
    }

    /**
     * For temporary compat reason, remove with b/127532182
     * @deprecated use {@link #onClassifyContentSelections(ClassificationsRequest,
     * ContentSuggestionsManager.ClassificationsCallback)} instead.
     */
    @Deprecated
    public void classifyContentSelections(@NonNull ClassificationsRequest request,
            @NonNull ContentSuggestionsManager.ClassificationsCallback callback) {
    }

    /**
     * For temporary compat reason, remove with b/127532182
     * @deprecated use {@link #onNotifyInteraction(String, Bundle)} instead.
     */
    @Deprecated
    public void notifyInteraction(@NonNull String requestId, @NonNull Bundle interaction) {
    }
}
