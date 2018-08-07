/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.soundtrigger;

import android.media.soundtrigger.ISoundTriggerDetectionServiceClient;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Bundle;
import android.os.ParcelUuid;

/**
 * AIDL for the SoundTriggerDetectionService to run detection operations when triggered.
 *
 * {@hide}
 */
oneway interface ISoundTriggerDetectionService {
    void setClient(in ParcelUuid uuid, in Bundle params, in ISoundTriggerDetectionServiceClient client);
    void removeClient(in ParcelUuid uuid);
    void onGenericRecognitionEvent(in ParcelUuid uuid, int opId, in SoundTrigger.GenericRecognitionEvent event);
    void onError(in ParcelUuid uuid, int opId, int status);
    void onStopOperation(in ParcelUuid uuid, int opId);
}
