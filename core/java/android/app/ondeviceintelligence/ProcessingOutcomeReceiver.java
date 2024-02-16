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

import java.util.function.Consumer;

/**
 * Response Callback to populate the processed response or any error that occurred during the
 * request processing. This callback also provides a method to request additional data to be
 * augmented to the request-processing, using  the partial {@link Content} that was already
 * processed in the remote implementation.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public interface ProcessingOutcomeReceiver extends
        OutcomeReceiver<Content,
                OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> {
    /**
     * Callback to be invoked in cases where the remote service needs to perform retrieval or
     * transformation operations based on a partially processed request, in order to augment the
     * final response, by using the additional context sent via this callback.
     *
     * @param content         The content payload that should be used to augment ongoing request.
     * @param contentConsumer The augmentation data that should be sent to remote
     *                        service for further processing a request.
     */
    default void onDataAugmentRequest(@NonNull Content content,
            @NonNull Consumer<Content> contentConsumer) {
        contentConsumer.accept(null);
    }
}
