/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONTENT_RECORDING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.ContentRecordingSession;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

/**
 * Orchestrates the handoff between displays if the recording session changes, and keeps track of
 * the current recording session state. Only supports one content recording session on the device at
 * once.
 */
final class ContentRecordingController {

    /**
     * The current recording session.
     */
    @Nullable
    private ContentRecordingSession mSession = null;

    @Nullable
    private DisplayContent mDisplayContent = null;

    /**
     * Returns the current recording session. If returns {@code null}, then recording is not taking
     * place.
     */
    @Nullable
    @VisibleForTesting
    ContentRecordingSession getContentRecordingSessionLocked() {
        // Copy out the session, to allow it to be modified without updating this reference.
        return mSession;
    }

    /**
     * Updates the current recording session. If a new display is taking over recording, then
     * stops the prior display from recording.
     *
     * @param incomingSession the new recording session. Should either be {@code null}, to stop
     *                        the current session, or a session on a new/different display than the
     *                        current session.
     * @param wmService       the window manager service
     */
    void setContentRecordingSessionLocked(@Nullable ContentRecordingSession incomingSession,
            @NonNull WindowManagerService wmService) {
        if (incomingSession != null && (!ContentRecordingSession.isValid(incomingSession)
                || ContentRecordingSession.isSameDisplay(mSession, incomingSession))) {
            // Ignore an invalid session, or a session for the same display as currently recording.
            return;
        }
        DisplayContent incomingDisplayContent = null;
        if (incomingSession != null) {
            // Recording will start on a new display, possibly taking over from a current session.
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Handle incoming session on display %d, with a pre-existing session %s",
                    incomingSession.getDisplayId(),
                    mSession == null ? null : mSession.getDisplayId());
            incomingDisplayContent = wmService.mRoot.getDisplayContentOrCreate(
                    incomingSession.getDisplayId());
            incomingDisplayContent.setContentRecordingSession(incomingSession);
        }
        if (mSession != null) {
            // Update the pre-existing display about the new session.
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Pause the recording session on display %s",
                    mDisplayContent.getDisplayId());
            mDisplayContent.pauseRecording();
            mDisplayContent.setContentRecordingSession(null);
        }
        // Update the cached states.
        mDisplayContent = incomingDisplayContent;
        mSession = incomingSession;
    }
}
