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
package android.media.soundtrigger_middleware;

import android.media.soundtrigger.PhraseRecognitionEvent;

/**
 * Wrapper to android.media.soundtrigger.RecognitionEvent providing additional fields used by the
 * framework.
 */
parcelable PhraseRecognitionEventSys {

    PhraseRecognitionEvent phraseRecognitionEvent;
    /**
     * Timestamp of when the trigger event from SoundTriggerHal was received by the
     * framework.
     *
     * <p>same units and timebase as {@link SystemClock#elapsedRealtime()}.
     * The value will be -1 if the event was not generated from the HAL.
     */
    // @ElapsedRealtimeLong
    long halEventReceivedMillis = -1;
    /**
     * Token relating this event to a particular recognition session, returned by
     * {@link ISoundTriggerModule.startRecognition(int, RecognitionConfig}
     */
    IBinder token;
}
