/*
 * Copyright (C) 2016 The Android Open Source Project
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

 package com.android.internal.app;

 oneway interface IVoiceInteractionSessionListener {
    /**
     * Called when a voice session is shown.
     */
    void onVoiceSessionShown();

    /**
     * Called when a voice session is hidden.
     */
    void onVoiceSessionHidden();

    /**
     * Called when voice assistant transcription has been updated to the given string.
     */
    void onTranscriptionUpdate(in String transcription);

    /**
     * Called when voice transcription is completed.
     */
    void onTranscriptionComplete(in boolean immediate);

    /**
     * Called when the voice assistant's state has changed. Values are from
     * VoiceInteractionService's VOICE_STATE* constants.
     */
    void onVoiceStateChange(in int state);
 }