/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 with the License.
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

package com.android.coretests.apps.testapp;

import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.security.intrusiondetection.IntrusionDetectionEventTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * A class that extends {@link IntrusionDetectionEventTransport} to provide a
 * local transport mechanism for testing purposes. This implementation overrides
 * the {@link #initialize()}, {@link #addData(List)}, and {@link #release()} methods
 * to manage events locally within the test environment.
 *
 * For now, the implementation returns true for all methods since we don't
 * have a real data source to send events to.
 */
public class LocalIntrusionDetectionEventTransport extends IntrusionDetectionEventTransport {
    private List<IntrusionDetectionEvent> mEvents = new ArrayList<>();

    @Override
    public boolean initialize() {
        return true;
    }

    @Override
    public boolean addData(List<IntrusionDetectionEvent> events) {
        mEvents.addAll(events);
        return true;
    }

    @Override
    public boolean release() {
        return true;
    }

    public List<IntrusionDetectionEvent> getEvents() {
        return mEvents;
    }
}