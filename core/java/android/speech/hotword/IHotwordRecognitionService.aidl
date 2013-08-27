/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.speech.hotword;

import android.speech.hotword.IHotwordRecognitionListener;

/**
 * A service interface to Hotword recognition.
 * Call startHotwordDetection with a listener when you want to begin detecting
 * hotword;
 * The service would automatically stop detection when hotword is detected;
 * So it's a create-once use-once service.
 * The service doesn't support nested calls to start detection and disallows them.
 * {@hide}
 */
oneway interface IHotwordRecognitionService {
    /**
     * Start hotword recognition.
     * The clients should rely on the callback to figure out if the detection was
     * started.
     *
     * @param listener a listener to notify of hotword events.
     */
    void startHotwordRecognition(in IHotwordRecognitionListener listener);

    /**
     * Stop hotword recognition.
     * Stops the recognition only if it was started by the same caller.
     *
     * @param listener a listener to notify of hotword events.
     */
    void stopHotwordRecognition(in IHotwordRecognitionListener listener);
}
