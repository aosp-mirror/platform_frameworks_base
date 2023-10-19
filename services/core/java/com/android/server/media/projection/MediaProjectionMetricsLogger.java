/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media.projection;

import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED;

import android.content.Context;

import com.android.internal.util.FrameworkStatsLog;

import java.time.Duration;

/** Class for emitting logs describing a MediaProjection session. */
public class MediaProjectionMetricsLogger {
    private static final int TARGET_UID_UNKNOWN = -2;
    private static final int TIME_SINCE_LAST_ACTIVE_UNKNOWN = -1;

    private static MediaProjectionMetricsLogger sSingleton = null;

    private final FrameworkStatsLogWrapper mFrameworkStatsLogWrapper;
    private final MediaProjectionSessionIdGenerator mSessionIdGenerator;
    private final MediaProjectionTimestampStore mTimestampStore;

    private int mPreviousState =
            FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN;

    MediaProjectionMetricsLogger(
            FrameworkStatsLogWrapper frameworkStatsLogWrapper,
            MediaProjectionSessionIdGenerator sessionIdGenerator,
            MediaProjectionTimestampStore timestampStore) {
        mFrameworkStatsLogWrapper = frameworkStatsLogWrapper;
        mSessionIdGenerator = sessionIdGenerator;
        mTimestampStore = timestampStore;
    }

    /** Returns a singleton instance of {@link MediaProjectionMetricsLogger}. */
    public static MediaProjectionMetricsLogger getInstance(Context context) {
        if (sSingleton == null) {
            sSingleton =
                    new MediaProjectionMetricsLogger(
                            new FrameworkStatsLogWrapper(),
                            MediaProjectionSessionIdGenerator.getInstance(context),
                            MediaProjectionTimestampStore.getInstance(context));
        }
        return sSingleton;
    }

    /**
     * Logs that the media projection session was initiated by the app requesting the user's consent
     * to capture. Should be sent even if the permission dialog is not shown.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param sessionCreationSource Where this session started. One of:
     *     <ul>
     *       <li>{@link
     *           FrameworkStatsLog#MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_APP}
     *       <li>{@link
     *           FrameworkStatsLog#MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_CAST}
     *       <li>{@link
     *           FrameworkStatsLog#MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER}
     *       <li>{@link
     *           FrameworkStatsLog#MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN}
     *     </ul>
     */
    public void logInitiated(int hostUid, int sessionCreationSource) {
        Duration durationSinceLastActiveSession = mTimestampStore.timeSinceLastActiveSession();
        int timeSinceLastActiveInSeconds =
                durationSinceLastActiveSession == null
                        ? TIME_SINCE_LAST_ACTIVE_UNKNOWN
                        : (int) durationSinceLastActiveSession.toSeconds();
        write(
                mSessionIdGenerator.createAndGetNewSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED,
                hostUid,
                TARGET_UID_UNKNOWN,
                timeSinceLastActiveInSeconds,
                sessionCreationSource);
    }

    /** Logs that the virtual display is created and capturing the selected region begins. */
    public void logInProgress(int hostUid, int targetUid) {
        write(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS,
                hostUid,
                targetUid,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    /** Logs that the capturing stopped, either normally or because of error. */
    public void logStopped(int hostUid, int targetUid) {
        write(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED,
                hostUid,
                targetUid,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
        mTimestampStore.registerActiveSessionEnded();
    }

    public void notifyProjectionStateChange(int hostUid, int state, int sessionCreationSource) {
        write(hostUid, state, sessionCreationSource);
    }

    private void write(int hostUid, int state, int sessionCreationSource) {
        mFrameworkStatsLogWrapper.write(
                /* code */ FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED,
                /* session_id */ 123,
                /* state */ state,
                /* previous_state */ FrameworkStatsLog
                        .MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN,
                /* host_uid */ hostUid,
                /* target_uid */ -1,
                /* time_since_last_active */ 0,
                /* creation_source */ sessionCreationSource);
    }

    private void write(
            int sessionId,
            int state,
            int hostUid,
            int targetUid,
            int timeSinceLastActive,
            int creationSource) {
        mFrameworkStatsLogWrapper.write(
                /* code */ FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED,
                sessionId,
                state,
                mPreviousState,
                hostUid,
                targetUid,
                timeSinceLastActive,
                creationSource);
        mPreviousState = state;
    }
}
