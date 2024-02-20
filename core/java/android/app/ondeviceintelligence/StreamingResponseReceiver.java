/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.OutcomeReceiver;

/**
 * Streaming variant of outcome receiver to populate response while processing a given request,
 * possibly in
 * chunks to provide a async processing behaviour to the caller.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public interface StreamingResponseReceiver<R, T, E extends Throwable> extends
        OutcomeReceiver<R, E> {
    /**
     * Callback to be invoked when a part of the response i.e. some {@link Content} is already
     * processed and
     * needs to be passed onto the caller.
     */
    void onNewContent(@NonNull T content);
}
