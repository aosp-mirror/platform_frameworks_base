/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

/**
 * Defines additional methods the synthesis callback must implement that
 * are private to the TTS service implementation.
 */
abstract class AbstractSynthesisCallback implements SynthesisCallback {
    /**
     * Checks whether the synthesis request completed successfully.
     */
    abstract boolean isDone();

    /**
     * Aborts the speech request.
     *
     * Can be called from multiple threads.
     */
    abstract void stop();
}
