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

package android.view.intelligence;

import android.content.ComponentName;
import android.os.IBinder;
import android.service.intelligence.InteractionSessionId;
import android.view.intelligence.ContentCaptureEvent;

import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * {@hide}
 */
oneway interface IIntelligenceManager {
    /**
      * Starts a session, sending the "remote" sessionId to the receiver.
      */
    void startSession(int userId, IBinder activityToken, in ComponentName componentName,
                      in InteractionSessionId sessionId, int flags, in IResultReceiver result);

    /**
      * Finishes a session.
      */
    void finishSession(int userId, in InteractionSessionId sessionId);

    /**
      * Sends a batch of events
      */
    void sendEvents(int userId, in InteractionSessionId sessionId,
                    in List<ContentCaptureEvent> events);
}
