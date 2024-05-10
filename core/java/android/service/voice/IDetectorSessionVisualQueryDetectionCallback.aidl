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

/**
 * Callback for returning the detected result from the {@link VisualQueryDetectionService}.
 *
 * The {@link VisualQueryDetectorSession} will overrides this interface to reach query egression
 * control within each callback methods.
 *
 * @hide
 */
oneway interface IDetectorSessionVisualQueryDetectionCallback {

    /**
     * Called when the user attention is gained and intent to show the assistant icon in SysUI.
     */
    void onAttentionGained();

    /**
     * Called when the user attention is lost and intent to hide the assistant icon in SysUI.
     */
    void onAttentionLost();

    /**
     * Called when the detected query is streamed.
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
}
