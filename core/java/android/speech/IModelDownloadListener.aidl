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

package android.speech;

/**
 * Listener for model download events. It makes RecognitionService let callers know about the
 * progress of model download requests.
 *
 * {@hide}
 */
oneway interface IModelDownloadListener {
    /**
     * Called by RecognitionService when there's an update on the download progress.
     */
    void onProgress(in int completedPercent);

    /**
     * Called when RecognitionService completed the download and it can now be used to satisfy
     * recognition requests.
     */
     void onSuccess();

    /**
     * Called when RecognitionService scheduled the download but won't satisfy it immediately.
     * There will be no further updates on this callback.
     */
    void onScheduled();

    /**
     * A network or scheduling error occurred.
     *
     * @param error code is defined in {@link SpeechRecognizer}
     */
    void onError(in int error);
}
