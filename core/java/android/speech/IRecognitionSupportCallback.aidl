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

import android.os.Bundle;
import android.speech.RecognitionSupport;

/**
 *  Callback for speech recognition support checks, used with RecognitionService.
 *  This provides the {@link RecognitionSupport} for a given recognition request, callers can use
 *  it to check whether RecognitionService can fulfill a given recognition request.
 *  {@hide}
 */
oneway interface IRecognitionSupportCallback {
    void onSupportResult(in RecognitionSupport recognitionSupport);

    /**
     * A network or recognition error occurred.
     *
     * @param error code is defined in {@link SpeechRecognizer}
     */
    void onError(in int error);
}
