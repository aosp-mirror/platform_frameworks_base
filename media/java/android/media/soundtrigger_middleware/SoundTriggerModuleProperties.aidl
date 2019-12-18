/*
 * Copyright (C) 2019 The Android Open Source Project
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

/**
 * Capabilities of a sound trigger module.
 * {@hide}
 */
parcelable SoundTriggerModuleProperties {
    /** Implementor name */
    String   implementor;
    /** Implementation description */
    String   description;
    /** Implementation version */
    int version;
    /**
     * Unique implementation ID. The UUID must change with each version of
       the engine implementation */
    String     uuid;
    /** Maximum number of concurrent sound models loaded */
    int maxSoundModels;
    /** Maximum number of key phrases */
    int maxKeyPhrases;
    /** Maximum number of concurrent users detected */
    int maxUsers;
    /** All supported modes. e.g RecognitionMode.VOICE_TRIGGER */
    int recognitionModes;
    /** Supports seamless transition from detection to capture */
    boolean     captureTransition;
    /** Maximum buffering capacity in ms if captureTransition is true */
    int maxBufferMs;
    /** Supports capture by other use cases while detection is active */
    boolean     concurrentCapture;
    /** Returns the trigger capture in event */
    boolean     triggerInEvent;
    /**
     * Rated power consumption when detection is active with TDB
     * silence/sound/speech ratio */
    int powerConsumptionMw;
}
