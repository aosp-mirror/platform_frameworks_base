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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;

import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CANCELLED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_DEVICE_LOCK;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_ERROR;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_FOREGROUND_SERVICE_CHANGE;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_HOST_APP_STOP;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_NEW_MEDIA_ROUTE;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_NEW_PROJECTION;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_QS_TILE;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_STATUS_BAR_CHIP_STOP;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_TASK_APP_CLOSE;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_USER_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_BOUNDS;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_POSITION;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_WINDOWING_MODE;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_APP_TASK;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_DISPLAY;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_TYPE__TARGET_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FREEFORM;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_FULLSCREEN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_SPLIT_SCREEN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_TARGET_CHANGED__TARGET_WINDOWING_MODE__WINDOWING_MODE_UNKNOWN;

import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.projection.StopReason;
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

    private final Rect mPreviousTargetBounds = new Rect();
    private int mPreviousTargetWindowingMode = WINDOWING_MODE_UNDEFINED;
    private int mPreviousProjectionState =
            MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN;

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
                sessionCreationSource,
                MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN);
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
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN);
    }

    /**
     * Logs that requesting permission for media projection was cancelled by the user.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     */
    public void logProjectionPermissionRequestCancelled(int hostUid) {
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CANCELLED,
                hostUid,
                TARGET_UID_UNKNOWN,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN);
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
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN);
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
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN);
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
        Log.d(TAG, "logChangedWindowingMode: windowingMode= "
                + WindowConfiguration.windowingModeToString(windowingMode));
        Log.d(TAG, "targetChangeType= changeWindowingMode");
        writeTargetChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                contentToRecordToTargetType(contentToRecord),
                hostUid,
                targetUid,
                windowingModeToTargetWindowingMode(windowingMode),
                mPreviousTargetBounds.width(),
                mPreviousTargetBounds.height(),
                mPreviousTargetBounds.centerX(),
                mPreviousTargetBounds.centerY(),
                MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_WINDOWING_MODE);
        mPreviousTargetWindowingMode = windowingMode;
    }

    /**
     * Logs that the bounds of projection's capture target has changed.
     *
     * @param contentToRecord ContentRecordingSession.RecordContent indicating whether it is a
     *                        task capture or display capture - gets converted to the corresponding
     *                        TargetType before being logged.
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param targetUid UID of the package that is captured if selected.
     * @param captureBounds Updated bounds of the captured region.
     */
    public void logChangedCaptureBounds(
            int contentToRecord, int hostUid, int targetUid, Rect captureBounds) {
        final Point capturePosition = new Point(captureBounds.centerX(), captureBounds.centerY());
        Log.d(TAG, "logChangedCaptureBounds: captureBounds= " + captureBounds + " position= "
                + capturePosition);

        writeTargetChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                contentToRecordToTargetType(contentToRecord),
                hostUid,
                targetUid,
                mPreviousTargetWindowingMode,
                captureBounds.width(),
                captureBounds.height(),
                captureBounds.centerX(),
                captureBounds.centerY(),
                captureBoundsToTargetChangeType(captureBounds));
        mPreviousTargetBounds.set(captureBounds);
    }

    private int captureBoundsToTargetChangeType(Rect captureBounds) {
        final boolean hasChangedSize = captureBounds.width() != mPreviousTargetBounds.width()
                && captureBounds.height() != mPreviousTargetBounds.height();

        if (hasChangedSize) {
            Log.d(TAG, "targetChangeType= changeBounds");
            return MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_BOUNDS;
        }
        Log.d(TAG, "targetChangeType= changePosition");
        return MEDIA_PROJECTION_TARGET_CHANGED__TARGET_CHANGE_TYPE__TARGET_CHANGE_POSITION;
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

    @VisibleForTesting
    public int stopReasonToSessionStopSource(@StopReason int stopReason) {
        return switch (stopReason) {
            case StopReason.STOP_HOST_APP ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_HOST_APP_STOP;
            case StopReason.STOP_TARGET_REMOVED ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_TASK_APP_CLOSE;
            case StopReason.STOP_DEVICE_LOCKED->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_DEVICE_LOCK;
            case StopReason.STOP_PRIVACY_CHIP ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_STATUS_BAR_CHIP_STOP;
            case StopReason.STOP_QS_TILE ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_QS_TILE;
            case StopReason.STOP_USER_SWITCH ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_USER_SWITCH;
            case StopReason.STOP_FOREGROUND_SERVICE_CHANGE ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_FOREGROUND_SERVICE_CHANGE;
            case StopReason.STOP_NEW_PROJECTION ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_NEW_PROJECTION;
            case StopReason.STOP_NEW_MEDIA_ROUTE ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_NEW_MEDIA_ROUTE;
            case StopReason.STOP_ERROR ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_ERROR;
            default ->
                    MEDIA_PROJECTION_STATE_CHANGED__STOP_SOURCE__STOP_SOURCE_UNKNOWN;
        };
    }

    /**
     * Logs that the capturing stopped, either normally or because of error.
     *
     * @param hostUid UID of the package that initiates MediaProjection.
     * @param targetUid UID of the package that is captured if selected.
     */
    public void logStopped(int hostUid, int targetUid, int stopReason) {
        boolean wasCaptureInProgress =
                mPreviousProjectionState
                        == MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
        Log.d(TAG, "logStopped: wasCaptureInProgress=" + wasCaptureInProgress +
                " stopReason=" + stopReason);
        writeStateChanged(
                mSessionIdGenerator.getCurrentSessionId(),
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED,
                hostUid,
                targetUid,
                TIME_SINCE_LAST_ACTIVE_UNKNOWN,
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN,
                stopReasonToSessionStopSource(stopReason));

        if (wasCaptureInProgress) {
            mTimestampStore.registerActiveSessionEnded();
        }
    }

    public void notifyProjectionStateChange(
            int hostUid,
            int state,
            int sessionCreationSource,
            int sessionStopSource
    ) {
        writeStateChanged(hostUid, state, sessionCreationSource, sessionStopSource);
    }

    private void writeStateChanged(
            int hostUid,
            int state,
            int sessionCreationSource,
            int sessionStopSource
    ) {
        mFrameworkStatsLogWrapper.writeStateChanged(
                /* code */ MEDIA_PROJECTION_STATE_CHANGED,
                /* session_id */ 123,
                /* state */ state,
                /* previous_state */ MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN,
                /* host_uid */ hostUid,
                /* target_uid */ -1,
                /* time_since_last_active */ 0,
                /* creation_source */ sessionCreationSource,
                /* stop_source */ sessionStopSource);
    }

    private void writeStateChanged(
            int sessionId,
            int state,
            int hostUid,
            int targetUid,
            int timeSinceLastActive,
            int creationSource,
            int stopSource) {
        mFrameworkStatsLogWrapper.writeStateChanged(
                /* code */ MEDIA_PROJECTION_STATE_CHANGED,
                sessionId,
                state,
                mPreviousProjectionState,
                hostUid,
                targetUid,
                timeSinceLastActive,
                creationSource,
                stopSource);
        mPreviousProjectionState = state;
    }

    private void writeTargetChanged(
            int sessionId,
            int targetType,
            int hostUid,
            int targetUid,
            int targetWindowingMode,
            int width,
            int height,
            int centerX,
            int centerY,
            int targetChangeType) {
        mFrameworkStatsLogWrapper.writeTargetChanged(
                /* code */ MEDIA_PROJECTION_TARGET_CHANGED,
                sessionId,
                targetType,
                hostUid,
                targetUid,
                targetWindowingMode,
                width,
                height,
                centerX,
                centerY,
                targetChangeType);
    }
}
