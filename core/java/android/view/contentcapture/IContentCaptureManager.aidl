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

package android.view.contentcapture;

import android.content.ComponentName;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.os.IBinder;

import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
  * Interface between an app (ContentCaptureManager / ContentCaptureSession) and the system-server
  * implementation service (ContentCaptureManagerService).
  *
  * @hide
  */
oneway interface IContentCaptureManager {
    void startSession(int userId, IBinder activityToken, in ComponentName componentName,
                      String sessionId, int flags, in IResultReceiver result);
    void finishSession(int userId, String sessionId);
}
