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

package android.service.intelligence;

import android.service.intelligence.InteractionSessionId;
import android.service.intelligence.InteractionContext;

import android.view.intelligence.ContentCaptureEvent;

import java.util.List;


/**
 * Interface from the system to an intelligence service.
 *
 * @hide
 */
oneway interface IIntelligenceService {

    // Called when session is created (context not null) or destroyed (context null)
    void onSessionLifecycle(in InteractionContext context, in InteractionSessionId sessionId);

    void onContentCaptureEvents(in InteractionSessionId sessionId,
                                in List<ContentCaptureEvent> events);
}
