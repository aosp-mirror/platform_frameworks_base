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
                    obtainMessage(ContentSuggestionsService::processContextImage,
                            ContentSuggestionsService.this, taskId,
                            wrappedBuffer,
                            imageContextRequestExtras));
        }

        @Override
        public void suggestContentSelections(SelectionsRequest request,
                ISelectionsCallback callback) {
            mHandler.sendMessage(obtainMessage(ContentSuggestionsService::suggestContentSelections,
                    ContentSuggestionsService.this, request, wrapSelectionsCallback(callback)));

        }

        @Override
        public void classifyContentSelections(ClassificationsRequest request,
                IClassificationsCallback callback) {
            mHandler.sendMessage(obtainMessage(ContentSuggestionsService::classifyContentSelections,
                    ContentSuggestionsService.this, request, wrapClassificationCallback(callback)));
        }

        @Override
        public void notifyInteraction(String requestId, Bundle interaction) {
            mHandler.sendMessage(
                    obtainMessage(ContentSuggestionsService::notifyInteraction,
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
    public abstract void processContextImage(
            int taskId, @Nullable Bitmap contextImage, @NonNull Bundle extras);

    /**
     * Called by a client app to make a request for content selections.
     */
    public abstract void suggestContentSelections(@NonNull SelectionsRequest request,
            @NonNull ContentSuggestionsManager.SelectionsCallback callback);

    /**
     * Called by a client app to classify the provided content selections.
     */
    public abstract void classifyContentSelections(@NonNull ClassificationsRequest request,
            @NonNull ContentSuggestionsManager.ClassificationsCallback callback);

    /**
     * Called by a client app to report an interaction.
     */
    public abstract void notifyInteraction(@NonNull String requestId, @NonNull Bundle interaction);

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
}
