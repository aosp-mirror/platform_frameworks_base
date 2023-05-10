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
 * Listener for model download events. It makes {@link RecognitionService} let callers know about
 * the progress of model download for a single recognition request.
 */
public interface ModelDownloadListener {
    /**
     * Called by {@link RecognitionService} only if the download has started after the request.
     *
     * <p> The number of calls to this method varies depending of the {@link RecognitionService}
     * implementation. If the download finished quickly enough, {@link #onSuccess()} may be called
     * directly. In other cases, this method may be called any number of times during the download.
     *
     * @param completedPercent the percentage of download that is completed
     */
    void onProgress(int completedPercent);

    /**
     * This method is called:
     * <li> if the model is already available;
     * <li> if the {@link RecognitionService} has started and completed the download.
     *
     * <p> Once this method is called, the model can be safely used to satisfy recognition requests.
     */
    void onSuccess();

    /**
     * Called when {@link RecognitionService} scheduled the download, but won't satisfy it
     * immediately. There will be no further updates on this listener.
     */
    void onScheduled();

    /**
     * A network or scheduling error occurred.
     *
     * @param error code is defined in {@link SpeechRecognizer}
     */
    void onError(@SpeechRecognizer.RecognitionError int error);
}
