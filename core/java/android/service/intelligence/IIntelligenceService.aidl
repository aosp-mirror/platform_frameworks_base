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

import android.os.IBinder;
import android.service.intelligence.ContentCaptureEventsRequest;
import android.service.intelligence.InteractionSessionId;
import android.service.intelligence.InteractionContext;
import android.service.intelligence.SnapshotData;

import android.view.autofill.AutofillId;
import android.view.intelligence.ContentCaptureEvent;

import java.util.List;

/**
 * Interface from the system to an intelligence service.
 *
 * @hide
 */
 // TODO(b/111276913): rename / update javadoc (once the final name is defined)
oneway interface IIntelligenceService {

    // Called when session is created (context not null) or destroyed (context null)
    void onSessionLifecycle(in InteractionContext context, in InteractionSessionId sessionId);

    void onContentCaptureEventsRequest(in InteractionSessionId sessionId,
                                in ContentCaptureEventsRequest request);

    void onActivitySnapshot(in InteractionSessionId sessionId,
                            in SnapshotData snapshotData);

    void onAutofillRequest(in InteractionSessionId sessionId, in IBinder autofillManagerClient,
                           int autofilSessionId, in AutofillId focusedId);

    void onDestroyAutofillWindowsRequest(in InteractionSessionId sessionId);
}
