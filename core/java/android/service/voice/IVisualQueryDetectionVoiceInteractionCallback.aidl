/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.voice;

import android.media.AudioFormat;

/**
 * Callback for returning the detected result from the VisualQueryDetectionService.
 *
 * @hide
 */
oneway interface IVisualQueryDetectionVoiceInteractionCallback {

    /**
     * Called when the detected query is streamed
     */
    void onQueryDetected(in String partialQuery);

    /**
     * Called when the detected result is valid.
     */
    void onQueryFinished();

    /**
     * Called when the detected result is invalid.
     */
    void onQueryRejected();

    /**
     * Called when the detection fails due to an error.
     */
    void onError();

}
