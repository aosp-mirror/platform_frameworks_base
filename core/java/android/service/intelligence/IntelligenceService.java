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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.view.intelligence.ContentCaptureEvent;

import java.util.List;

/**
 * A service used to captures the content of the screen.
 *
 * <p>The data collected by this service can be analyzed and combined with other sources to provide
 * contextual data in other areas of the system such as Autofill.
 *
 * @hide
 */
@SystemApi
public abstract class IntelligenceService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INTELLIGENCE_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.intelligence.IntelligenceService";

    /**
     * Creates a new interaction session.
     *
     * @param context interaction context
     * @param sessionId the session's Id
     */
    public void onCreateInteractionSession(@NonNull InteractionContext context,
            @NonNull InteractionSessionId sessionId) {}

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param events the events
     */
    public abstract void onContentCaptureEvent(@NonNull InteractionSessionId sessionId,
            @NonNull List<ContentCaptureEvent> events);

    /**
     * Destroys the content capture session identified by the specified {@code sessionId}.
     *
     * @param sessionId the id of the session to destroy
     */
    public void onDestroyInteractionSession(@NonNull InteractionSessionId sessionId) {}
}
