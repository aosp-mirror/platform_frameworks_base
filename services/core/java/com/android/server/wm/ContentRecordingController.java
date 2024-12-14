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
import com.android.internal.protolog.ProtoLog;

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
     * Updates the current recording session.
     * <p>Handles the following scenarios:
     * <ul>
     *         <li>Invalid scenarios: The incoming session is malformed.</li>
     *         <li>Ignored scenario: the incoming session is identical to the current session.</li>
     *         <li>Start Scenario: Starting a new session. Recording begins immediately.</li>
     *         <li>Takeover Scenario: Occurs during a Start Scenario, if a pre-existing session was
     *         in-progress. For example, recording on VirtualDisplay "app_foo" was ongoing. A
     *         session for VirtualDisplay "app_bar" arrives. The controller stops the session on
     *         VirtualDisplay "app_foo" and allows the session for VirtualDisplay "app_bar" to
     *         begin.</li>
     *         <li>Stopping scenario: The incoming session is null and there is currently an ongoing
     *         session. The controller stops recording.</li>
     *         <li>Updating scenario: There is an update for the same display, where recording
     *         was previously not taking place but is now permitted to go ahead.</li>
     * </ul>
     *
     * @param incomingSession The incoming recording session (either an update to a current session
     *                        or a new session), or null to stop the current session.
     * @param wmService       The window manager service.
     */
    void setContentRecordingSessionLocked(@Nullable ContentRecordingSession incomingSession,
            @NonNull WindowManagerService wmService) {
        // Invalid scenario: ignore invalid incoming session.
        if (incomingSession != null && !ContentRecordingSession.isValid(incomingSession)) {
            return;
        }
        final boolean hasSessionUpdatedWithConsent =
                mSession != null && incomingSession != null && mSession.isWaitingForConsent()
                        && !incomingSession.isWaitingForConsent();
        if (ContentRecordingSession.isProjectionOnSameDisplay(mSession, incomingSession)) {
            if (hasSessionUpdatedWithConsent) {
                // Updating scenario: accept an incoming session updating the current display.
                ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                        "Content Recording: Accept session updating same display %d with granted "
                                + "consent, with an existing session %s",
                        incomingSession.getVirtualDisplayId(), mSession.getVirtualDisplayId());
            } else {
                // Ignored scenario: ignore identical incoming session.
                ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                        "Content Recording: Ignoring session on same display %d, with an existing "
                                + "session %s",
                        incomingSession.getVirtualDisplayId(), mSession.getVirtualDisplayId());
                return;
            }
        }
        DisplayContent incomingDisplayContent = null;
        if (incomingSession != null) {
            // Start scenario: recording begins immediately.
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Content Recording: Handle incoming session on display %d, with a "
                            + "pre-existing session %s", incomingSession.getVirtualDisplayId(),
                    mSession == null ? null : mSession.getVirtualDisplayId());
            incomingDisplayContent = wmService.mRoot.getDisplayContentOrCreate(
                    incomingSession.getVirtualDisplayId());
            if (incomingDisplayContent == null) {
                ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                        "Content Recording: Incoming session on display %d can't be set since it "
                                + "is already null; the corresponding VirtualDisplay must have "
                                + "already been removed.", incomingSession.getVirtualDisplayId());
                return;
            }
            incomingDisplayContent.setContentRecordingSession(incomingSession);
            // Updating scenario: Explicitly ask ContentRecorder to update, since no config or
            // display change will trigger an update from the DisplayContent. There exists a
            // scenario where a DisplayContent is created, but it's ContentRecordingSession hasn't
            // been set yet due to a race condition. On creation, updateRecording fails to start
            // recording, so now this call guarantees recording will be started from somewhere.
            incomingDisplayContent.updateRecording();
        }
        // Takeover and stopping scenario: stop recording on the pre-existing session.
        if (mSession != null && !hasSessionUpdatedWithConsent) {
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Content Recording: Pause the recording session on display %s",
                    mDisplayContent.getDisplayId());
            mDisplayContent.pauseRecording();
            mDisplayContent.setContentRecordingSession(null);
        }
        // Update the cached states.
        mDisplayContent = incomingDisplayContent;
        mSession = incomingSession;
    }
}
