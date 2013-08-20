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

import com.google.android.search.service.IHotwordServiceCallback;

/**
 * Interface exposing hotword detector as a service.
 */
oneway interface IHotwordService {

    /**
     * Indicates a desire to start hotword detection.
     * It's best-effort and the client should rely on
     * the callbacks to figure out if hotwording was actually
     * started or not.
     *
     * @param a callback to notify of hotword events.
     */
    void requestHotwordDetection(in IHotwordServiceCallback callback);
}
