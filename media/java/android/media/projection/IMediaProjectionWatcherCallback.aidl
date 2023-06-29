/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.media.projection.MediaProjectionInfo;
import android.view.ContentRecordingSession;

/** {@hide} */
oneway interface IMediaProjectionWatcherCallback {
    void onStart(in MediaProjectionInfo info);
    void onStop(in MediaProjectionInfo info);
    /**
     * Called when the {@link ContentRecordingSession} was set for the current media
     * projection.
     *
     * @param info    always present and contains information about the media projection host.
     * @param session the recording session for the current media projection. Can be
     *                {@code null} when the recording will stop.
     */
    void onRecordingSessionSet(
        in MediaProjectionInfo info,
        in @nullable ContentRecordingSession session
    );
}
