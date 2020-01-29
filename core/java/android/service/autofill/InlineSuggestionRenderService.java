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
package android.service.autofill;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.slice.Slice;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;

/**
 * A service that renders an inline presentation given the {@link InlinePresentation} containing
 * a {@link Slice} built using the {@link androidx.autofill.AutofillSliceBuilder}.
 *
 * {@hide}
 */
@SystemApi
@TestApi
public abstract class InlineSuggestionRenderService extends Service {

    private static final String TAG = "InlineSuggestionRenderService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INLINE_SUGGESTION_RENDER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.autofill.InlineSuggestionRenderService";

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);

    private void handleRenderSuggestion(IInlineSuggestionUiCallback callback,
            InlinePresentation presentation, int width, int height) {
        //TODO(b/146453086): implementation in ExtService
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IInlineSuggestionRenderService.Stub() {
                @Override
                public void renderSuggestion(@NonNull IInlineSuggestionUiCallback callback,
                        @NonNull InlinePresentation presentation, int width, int height) {
                    mHandler.sendMessage(obtainMessage(
                            InlineSuggestionRenderService::handleRenderSuggestion,
                            InlineSuggestionRenderService.this, callback, presentation,
                            width, height));
                }
            }.asBinder();
        }

        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Renders the slice into a view.
     */
    @Nullable
    public View onRenderSuggestion(@NonNull InlinePresentation presentation,
            int width, int height) {
        Log.e(TAG, "service implementation (" + getClass() + " does not implement "
                + "onRenderSuggestion()");
        return null;
    }
}
