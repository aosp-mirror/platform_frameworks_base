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
import android.view.contentcapture.UserDataRemovalRequest;
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
    /**
     * Starts a new session for the calling user running as part of the
     * app's activity identified by {@code activityToken}/{@code componentName}.
     *
     * @param sessionId Unique session id as provided by the app.
     * @param flags Meta flags that enable or disable content capture (see
     *     {@link IContentCaptureContext#flags}).
     */
    void startSession(IBinder activityToken, in ComponentName componentName,
                      String sessionId, int flags, in IResultReceiver result);

    /**
     * Marks the end of a session for the calling user identified by
     * the corresponding {@code startSession}'s {@code sessionId}.
     */
    void finishSession(String sessionId);

    /**
     * Returns the content capture service's component name (if enabled and
     * connected).
     * @param Receiver of the content capture service's @{code ComponentName}
     *     provided {@code Bundle} with key "{@code EXTRA}".
     */
    void getServiceComponentName(in IResultReceiver result);

    /**
     * Requests the removal of user data for the calling user.
     */
    void removeUserData(in UserDataRemovalRequest request);

    /**
     * Returns whether the content capture feature is enabled for the calling user.
     */
    void isContentCaptureFeatureEnabled(in IResultReceiver result);
}
