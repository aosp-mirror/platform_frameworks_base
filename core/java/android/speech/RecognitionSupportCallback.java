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

package android.speech;

import android.annotation.NonNull;

/**
 * Used for receiving notifications from the SpeechRecognizer about the device support status for
 * the given recognition request.
 */
public interface RecognitionSupportCallback {

    /** Notifies the caller about the support for the given request. */
    void onSupportResult(@NonNull RecognitionSupport recognitionSupport);

    /** Notifies the caller about an error during the recognition support request */
    void onError(@SpeechRecognizer.RecognitionError int error);
}
