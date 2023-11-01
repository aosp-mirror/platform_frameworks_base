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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;

import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_APP_TASK;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_DISPLAY;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FREEFORM;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FULLSCREEN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_SPLIT_SCREEN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_UNKNOWN;

import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.util.Log;
import android.view.ContentRecordingSession.RecordContent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.time.Duration;

/** Class for emitting logs describing a MediaProjection session. */
public class MediaProjectionMetricsLogger {
    private static final String TAG = "MediaProjectionMetricsLogger";

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
        Log.d(TAG, "logInitiated");
        Duration durationSinceLastActiveSession = mTimestampStore.timeSinceLastActiveSession();
        int timeSinceLastActiveInSeconds =
                durationSinceLastActiveSession == null
                        ? TIME_SINCE_LAST_ACTIVE_UNKNOWN
                        : (int) durationSinceLastActiveSession.toSeconds();
        writeStateChanged(
                mSessionIdGenerator.createAndGetNewSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED,
                hostUid,
                TARGET_UID_UNKNOWN,
                timeSinceLastActiveInSeconds,
                sessionCreationSource);
    }

    /**
     * Logs that the user entered the setup flow and permission dialog is displayed. This state is
     * not sent when the permission is already granted, and we skipped showing the permission dialog.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     */
    public void logPermissionRequestDisplayed(int hostUid) {
        Log.d(TAG, "logPermissionRequestDisplayed");
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED,
                hostUid,
                TARGET_UID_UNKNOWN,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    /**
     * Logs that requesting permission for media projection was cancelled by the user.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     */
    public void logProjectionPermissionRequestCancelled(int hostUid) {
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                FrameworkStatsLog
                        .MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CANCELLED,
                hostUid,
                TARGET_UID_UNKNOWN,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                FrameworkStatsLog
                        .MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    /**
     * Logs that the app selector dialog is shown for the user.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     */
    public void logAppSelectorDisplayed(int hostUid) {
        Log.d(TAG, "logAppSelectorDisplayed");
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED,
                hostUid,
                TARGET_UID_UNKNOWN,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    /**
     * Logs that the virtual display is created and capturing the selected region begins.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param targetUid UID of the package that is captured if selected.
     */
    public void logInProgress(int hostUid, int targetUid) {
        Log.d(TAG, "logInProgress");
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS,
                hostUid,
                targetUid,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    /**
     * Logs that the windowing mode of a projection has changed.
     *
     * @param contentToRecord ContentRecordingSession.RecordContent indicating whether it is a
     *                        task capture or display capture - gets converted to the corresponding
     *                        TargetType before being logged.
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param targetUid UID of the package that is captured if selected.
     * @param windowingMode Updated WindowConfiguration.WindowingMode of the captured region - gets
     *                      converted to the corresponding TargetWindowingMode before being logged.
     */
    public void logChangedWindowingMode(
            int contentToRecord, int hostUid, int targetUid, int windowingMode) {
        Log.d(TAG, "logChangedWindowingMode");
        writeTargetChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                contentToRecordToTargetType(contentToRecord),
                hostUid,
                targetUid,
                windowingModeToTargetWindowingMode(windowingMode));

    }

    @VisibleForTesting
    public int contentToRecordToTargetType(@RecordContent int recordContentType) {
        return switch (recordContentType) {
            case RECORD_CONTENT_DISPLAY ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_DISPLAY;
            case RECORD_CONTENT_TASK ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_APP_TASK;
            default -> MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_UNKNOWN;
        };
    }

    @VisibleForTesting
    public int windowingModeToTargetWindowingMode(@WindowingMode int windowingMode) {
        return switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FULLSCREEN;
            case WINDOWING_MODE_FREEFORM ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FREEFORM;
            case WINDOWING_MODE_MULTI_WINDOW ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_SPLIT_SCREEN;
            default ->
                    MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_UNKNOWN;
        };
    }

    /**
     * Logs that the capturing stopped, either normally or because of error.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param targetUid UID of the package that is captured if selected.
     */
    public void logStopped(int hostUid, int targetUid) {
        boolean wasCaptureInProgress =
                mPreviousState
                        == MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
        Log.d(TAG, "logStopped: wasCaptureInProgress=" + wasCaptureInProgress);
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED,
                hostUid,
                targetUid,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);

        if (wasCaptureInProgress) {
            mTimestampStore.registerActiveSessionEnded();
        }
    }

    public void notifyProjectionStateChange(int hostUid, int state, int sessionCreationSource) {
        writeStateChanged(hostUid, state, sessionCreationSource);
    }

    private void writeStateChanged(int hostUid, int state, int sessionCreationSource) {
        mFrameworkStatsLogWrapper.writeStateChanged(
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

    private void writeStateChanged(
            int sessionId,
            int state,
            int hostUid,
            int targetUid,
            int timeSinceLastActive,
            int creationSource) {
        mFrameworkStatsLogWrapper.writeStateChanged(
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

    private void writeTargetChanged(
            int sessionId,
            int targetType,
            int hostUid,
            int targetUid,
            int targetWindowingMode) {
        mFrameworkStatsLogWrapper.writeTargetChanged(
                /* code */ FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED,
                sessionId,
                targetType,
                hostUid,
                targetUid,
                targetWindowingMode);
    }
}
