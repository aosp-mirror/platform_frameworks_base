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

package android.service.contentcapture;

import android.service.contentcapture.ContentCaptureEventsRequest;
import android.service.contentcapture.SnapshotData;
import android.view.contentcapture.ContentCaptureContext;

import java.util.List;

/**
 * Interface from the system to a Content Capture service.
 *
 * @hide
 */
oneway interface IContentCaptureService {

    // Called when session is created (context not null) or destroyed (context null)
    void onSessionLifecycle(in ContentCaptureContext context, String sessionId);

    void onContentCaptureEventsRequest(String sessionId, in ContentCaptureEventsRequest request);

    void onActivitySnapshot(String sessionId, in SnapshotData snapshotData);
}
