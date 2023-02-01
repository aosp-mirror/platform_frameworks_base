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
import android.graphics.ColorSpace;
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
        public void provideContextImage(int taskId, HardwareBuffer contextImage,
                int colorSpaceId, Bundle imageContextRequestExtras) {
            if (imageContextRequestExtras.containsKey(ContentSuggestionsManager.EXTRA_BITMAP)
                    && contextImage != null) {
                throw new IllegalArgumentException("Two bitmaps provided; expected one.");
            }

            Bitmap wrappedBuffer = null;
            if (imageContextRequestExtras.containsKey(ContentSuggestionsManager.EXTRA_BITMAP)) {
                wrappedBuffer = imageContextRequestExtras.getParcelable(
                        ContentSuggestionsManager.EXTRA_BITMAP);
            } else {
                if (contextImage != null) {
                    ColorSpace colorSpace = null;
                    if (colorSpaceId >= 0 && colorSpaceId < ColorSpace.Named.values().length) {
                        colorSpace = ColorSpace.get(ColorSpace.Named.values()[colorSpaceId]);
                    }
                    wrappedBuffer = Bitmap.wrapHardwareBuffer(contextImage, colorSpace);
                    contextImage.close();
                }
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
    public abstract void onProcessContextImage(
            int taskId, @Nullable Bitmap contextImage, @NonNull Bundle extras);

    /**
     * Content selections have been request through {@link ContentSuggestionsManager}, implementer
     * should reply on the callback with selections.
     */
    public abstract void onSuggestContentSelections(@NonNull SelectionsRequest request,
            @NonNull ContentSuggestionsManager.SelectionsCallback callback);

    /**
     * Content classifications have been request through {@link ContentSuggestionsManager},
     * implementer should reply on the callback with classifications.
     */
    public abstract void onClassifyContentSelections(@NonNull ClassificationsRequest request,
            @NonNull ContentSuggestionsManager.ClassificationsCallback callback);

    /**
     * User interactions have been reported through {@link ContentSuggestionsManager}, implementer
     * should handle those interactions.
     */
    public abstract void onNotifyInteraction(
            @NonNull String requestId, @NonNull Bundle interaction);

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
