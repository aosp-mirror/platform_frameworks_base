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

import android.content.ComponentName;
import android.os.IBinder;
import android.service.contentcapture.ActivityEvent;
import android.service.contentcapture.SnapshotData;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.UserDataRemovalRequest;

import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * Interface from the system to a Content Capture service.
 *
 * @hide
 */
oneway interface IContentCaptureService {
    void onConnected(IBinder callback, boolean verbose, boolean debug);
    void onDisconnected();
    void onSessionStarted(in ContentCaptureContext context, String sessionId, int uid,
                          in IResultReceiver clientReceiver);
    void onSessionFinished(String sessionId);
    void onActivitySnapshot(String sessionId, in SnapshotData snapshotData);
    void onUserDataRemovalRequest(in UserDataRemovalRequest request);
    void onActivityEvent(in ActivityEvent event);
}
