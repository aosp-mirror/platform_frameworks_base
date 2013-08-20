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

package com.google.android.search.service;

/**
 * Interface implemented by users of Hotword service to get callbacks
 * for hotword events.
 */
oneway interface IHotwordServiceCallback {

    /** Hotword detection start/stop callbacks */
    void onHotwordDetectionStarted();
    void onHotwordDetectionStopped();

    /**
     * Called back when hotword is detected.
     * The action tells the client what action to take, post hotword-detection.
     */
    void onHotwordDetected(in String action);
}
