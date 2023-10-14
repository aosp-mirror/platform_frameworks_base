/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view.displayhash;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Rect;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Use when calling {@link View#generateDisplayHash(String, Rect, Executor,
 * DisplayHashResultCallback)}.
 *
 * The callback will only invoke either {@link #onDisplayHashResult} when the system successfully
 * generated the {@link DisplayHash} or {@link #onDisplayHashError(int)} when it failed.
 */
public interface DisplayHashResultCallback {
    /**
     * @hide
     */
    String EXTRA_DISPLAY_HASH = "DISPLAY_HASH";

    /**
     * @hide
     */
    String EXTRA_DISPLAY_HASH_ERROR_CODE = "DISPLAY_HASH_ERROR_CODE";

    /**
     * An unknown error occurred.
     */
    int DISPLAY_HASH_ERROR_UNKNOWN = -1;

    /**
     * The bounds used when requesting the hash hash were invalid or empty.
     */
    int DISPLAY_HASH_ERROR_INVALID_BOUNDS = -2;

    /**
     * The window for the view that requested the hash is no longer around. This can happen if the
     * window is getting torn down.
     */
    int DISPLAY_HASH_ERROR_MISSING_WINDOW = -3;

    /**
     * The view that requested the hash is not visible on screen. This could either mean
     * that the view bounds are offscreen, window bounds are offscreen, view is not visible, or
     * window is not visible.
     */
    int DISPLAY_HASH_ERROR_NOT_VISIBLE_ON_SCREEN = -4;

    /**
     * The hash algorithm sent to generate the hash was invalid. This means the value is not one
     * of the supported values in {@link DisplayHashManager#getSupportedHashAlgorithms()}
     */
    int DISPLAY_HASH_ERROR_INVALID_HASH_ALGORITHM = -5;

    /**
     * The caller requested to generate the hash too frequently. The caller should try again at a
     * after some time has passed to ensure the system isn't overloaded.
     */
    int DISPLAY_HASH_ERROR_TOO_MANY_REQUESTS = -6;

    /** @hide */
    @IntDef(prefix = {"DISPLAY_HASH_ERROR_"}, value = {
            DISPLAY_HASH_ERROR_UNKNOWN,
            DISPLAY_HASH_ERROR_INVALID_BOUNDS,
            DISPLAY_HASH_ERROR_MISSING_WINDOW,
            DISPLAY_HASH_ERROR_NOT_VISIBLE_ON_SCREEN,
            DISPLAY_HASH_ERROR_INVALID_HASH_ALGORITHM,
            DISPLAY_HASH_ERROR_TOO_MANY_REQUESTS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DisplayHashErrorCode {
    }

    /**
     * Callback invoked when calling
     * {@link android.view.View#generateDisplayHash(String, Rect, Executor,
     * DisplayHashResultCallback)}
     *
     * @param displayHash The DisplayHash generated. If the hash cannot be generated,
     *                    {@link #onDisplayHashError(int)} will be called instead
     */
    void onDisplayHashResult(@NonNull DisplayHash displayHash);

    /**
     * Callback invoked when
     * {@link android.view.View#generateDisplayHash(String, Rect, Executor,
     * DisplayHashResultCallback)} results in an error and cannot generate a display hash.
     *
     * @param errorCode the error code
     */
    void onDisplayHashError(@DisplayHashErrorCode int errorCode);
}
