/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.SharedMemory;
import android.service.voice.IDspHotwordDetectionCallback;

import com.android.internal.app.IHotwordRecognitionStatusCallback;

/**
 * Provide the interface to communicate with hotword detection service.
 *
 * @hide
 */
oneway interface IHotwordDetectionService {
    void detectFromDspSource(
        in ParcelFileDescriptor audioStream,
        in AudioFormat audioFormat,
        long timeoutMillis,
        in IDspHotwordDetectionCallback callback);

    void detectFromMicrophoneSource(
        in ParcelFileDescriptor audioStream,
        int audioSource,
        in AudioFormat audioFormat,
        in PersistableBundle options,
        in IDspHotwordDetectionCallback callback);

    void updateState(in PersistableBundle options, in SharedMemory sharedMemory,
            in IHotwordRecognitionStatusCallback callback);
}
