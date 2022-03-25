/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.games;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.Executor;

/**
 * Callback invoked when an activity launched via
 * {@link GameSession#startActivityFromGameSessionForResult(Intent, Bundle, Executor,
 * GameSessionActivityCallback)}} has returned a result or failed to start.
 *
 * @hide
 */
@SystemApi
public interface GameSessionActivityCallback {
    /**
     * Callback invoked when an activity launched via
     * {@link GameSession#startActivityFromGameSessionForResult(Intent, Bundle, Executor,
     * GameSessionActivityCallback)}} has returned a result.
     *
     * @param resultCode The result code of the launched activity. See {@link
     *                   android.app.Activity#setResult(int)}.
     * @param data       Any data returned by the launched activity. See {@link
     *                   android.app.Activity#setResult(int, Intent)}.
     */
    void onActivityResult(int resultCode, @Nullable Intent data);

    /**
     * Callback invoked when a throwable was thrown when launching the {@link Intent} in
     * {@link GameSession#startActivityFromGameSessionForResult(Intent, Bundle, Executor,
     * GameSessionActivityCallback)}}.
     */
    default void onActivityStartFailed(@NonNull Throwable t) {}
}
