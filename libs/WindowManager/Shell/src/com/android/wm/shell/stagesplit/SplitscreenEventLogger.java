/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.stagesplit;

import static com.android.internal.util.FrameworkStatsLog.SPLITSCREEN_UICHANGED__ENTER_REASON__OVERVIEW;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.util.FrameworkStatsLog;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;

/**
 * Helper class that to log Drag & Drop UIEvents for a single session, see also go/uievent
 */
public class SplitscreenEventLogger {

    // Used to generate instance ids for this drag if one is not provided
    private final InstanceIdSequence mIdSequence;

    // The instance id for the current splitscreen session (from start to end)
    private InstanceId mLoggerSessionId;

    // Drag info
    private @SplitPosition int mDragEnterPosition;
    private InstanceId mDragEnterSessionId;

    // For deduping async events
    private int mLastMainStagePosition = -1;
    private int mLastMainStageUid = -1;
    private int mLastSideStagePosition = -1;
    private int mLastSideStageUid = -1;
    private float mLastSplitRatio = -1f;

    public SplitscreenEventLogger() {
        mIdSequence = new InstanceIdSequence(Integer.MAX_VALUE);
    }

    /**
     * Return whether a splitscreen session has started.
     */
    public boolean hasStartedSession() {
        return mLoggerSessionId != null;
    }

    /**
     * May be called before logEnter() to indicate that the session was started from a drag.
     */
    public void enterRequestedByDrag(@SplitPosition int position, InstanceId dragSessionId) {
        mDragEnterPosition = position;
        mDragEnterSessionId = dragSessionId;
    }

    /**
     * Logs when the user enters splitscreen.
     */
    public void logEnter(float splitRatio,
            @SplitPosition int mainStagePosition, int mainStageUid,
            @SplitPosition int sideStagePosition, int sideStageUid,
            boolean isLandscape) {
        mLoggerSessionId = mIdSequence.newInstanceId();
        int enterReason = mDragEnterPosition != SPLIT_POSITION_UNDEFINED
                ? getDragEnterReasonFromSplitPosition(mDragEnterPosition, isLandscape)
                : SPLITSCREEN_UICHANGED__ENTER_REASON__OVERVIEW;
        updateMainStageState(getMainStagePositionFromSplitPosition(mainStagePosition, isLandscape),
                mainStageUid);
        updateSideStageState(getSideStagePositionFromSplitPosition(sideStagePosition, isLandscape),
                sideStageUid);
        updateSplitRatioState(splitRatio);
        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__ENTER,
                enterReason,
                0 /* exitReason */,
                splitRatio,
                mLastMainStagePosition,
                mLastMainStageUid,
                mLastSideStagePosition,
                mLastSideStageUid,
                mDragEnterSessionId != null ? mDragEnterSessionId.getId() : 0,
                mLoggerSessionId.getId());
    }

    /**
     * Logs when the user exits splitscreen.  Only one of the main or side stages should be
     * specified to indicate which position was focused as a part of exiting (both can be unset).
     */
    public void logExit(int exitReason, @SplitPosition int mainStagePosition, int mainStageUid,
            @SplitPosition int sideStagePosition, int sideStageUid, boolean isLandscape) {
        if (mLoggerSessionId == null) {
            // Ignore changes until we've started logging the session
            return;
        }
        if ((mainStagePosition != SPLIT_POSITION_UNDEFINED
                && sideStagePosition != SPLIT_POSITION_UNDEFINED)
                        || (mainStageUid != 0 && sideStageUid != 0)) {
            throw new IllegalArgumentException("Only main or side stage should be set");
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__EXIT,
                0 /* enterReason */,
                exitReason,
                0f /* splitRatio */,
                getMainStagePositionFromSplitPosition(mainStagePosition, isLandscape),
                mainStageUid,
                getSideStagePositionFromSplitPosition(sideStagePosition, isLandscape),
                sideStageUid,
                0 /* dragInstanceId */,
                mLoggerSessionId.getId());

        // Reset states
        mLoggerSessionId = null;
        mDragEnterPosition = SPLIT_POSITION_UNDEFINED;
        mDragEnterSessionId = null;
        mLastMainStagePosition = -1;
        mLastMainStageUid = -1;
        mLastSideStagePosition = -1;
        mLastSideStageUid = -1;
    }

    /**
     * Logs when an app in the main stage changes.
     */
    public void logMainStageAppChange(@SplitPosition int mainStagePosition, int mainStageUid,
            boolean isLandscape) {
        if (mLoggerSessionId == null) {
            // Ignore changes until we've started logging the session
            return;
        }
        if (!updateMainStageState(getMainStagePositionFromSplitPosition(mainStagePosition,
                isLandscape), mainStageUid)) {
            // Ignore if there are no user perceived changes
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__APP_CHANGE,
                0 /* enterReason */,
                0 /* exitReason */,
                0f /* splitRatio */,
                mLastMainStagePosition,
                mLastMainStageUid,
                0 /* sideStagePosition */,
                0 /* sideStageUid */,
                0 /* dragInstanceId */,
                mLoggerSessionId.getId());
    }

    /**
     * Logs when an app in the side stage changes.
     */
    public void logSideStageAppChange(@SplitPosition int sideStagePosition, int sideStageUid,
            boolean isLandscape) {
        if (mLoggerSessionId == null) {
            // Ignore changes until we've started logging the session
            return;
        }
        if (!updateSideStageState(getSideStagePositionFromSplitPosition(sideStagePosition,
                isLandscape), sideStageUid)) {
            // Ignore if there are no user perceived changes
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__APP_CHANGE,
                0 /* enterReason */,
                0 /* exitReason */,
                0f /* splitRatio */,
                0 /* mainStagePosition */,
                0 /* mainStageUid */,
                mLastSideStagePosition,
                mLastSideStageUid,
                0 /* dragInstanceId */,
                mLoggerSessionId.getId());
    }

    /**
     * Logs when the splitscreen ratio changes.
     */
    public void logResize(float splitRatio) {
        if (mLoggerSessionId == null) {
            // Ignore changes until we've started logging the session
            return;
        }
        if (splitRatio <= 0f || splitRatio >= 1f) {
            // Don't bother reporting resizes that end up dismissing the split, that will be logged
            // via the exit event
            return;
        }
        if (!updateSplitRatioState(splitRatio)) {
            // Ignore if there are no user perceived changes
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__RESIZE,
                0 /* enterReason */,
                0 /* exitReason */,
                mLastSplitRatio,
                0 /* mainStagePosition */, 0 /* mainStageUid */,
                0 /* sideStagePosition */, 0 /* sideStageUid */,
                0 /* dragInstanceId */,
                mLoggerSessionId.getId());
    }

    /**
     * Logs when the apps in splitscreen are swapped.
     */
    public void logSwap(@SplitPosition int mainStagePosition, int mainStageUid,
            @SplitPosition int sideStagePosition, int sideStageUid, boolean isLandscape) {
        if (mLoggerSessionId == null) {
            // Ignore changes until we've started logging the session
            return;
        }

        updateMainStageState(getMainStagePositionFromSplitPosition(mainStagePosition, isLandscape),
                mainStageUid);
        updateSideStageState(getSideStagePositionFromSplitPosition(sideStagePosition, isLandscape),
                sideStageUid);
        FrameworkStatsLog.write(FrameworkStatsLog.SPLITSCREEN_UI_CHANGED,
                FrameworkStatsLog.SPLITSCREEN_UICHANGED__ACTION__SWAP,
                0 /* enterReason */,
                0 /* exitReason */,
                0f /* splitRatio */,
                mLastMainStagePosition,
                mLastMainStageUid,
                mLastSideStagePosition,
                mLastSideStageUid,
                0 /* dragInstanceId */,
                mLoggerSessionId.getId());
    }

    private boolean updateMainStageState(int mainStagePosition, int mainStageUid) {
        boolean changed = (mLastMainStagePosition != mainStagePosition)
                || (mLastMainStageUid != mainStageUid);
        if (!changed) {
            return false;
        }

        mLastMainStagePosition = mainStagePosition;
        mLastMainStageUid = mainStageUid;
        return true;
    }

    private boolean updateSideStageState(int sideStagePosition, int sideStageUid) {
        boolean changed = (mLastSideStagePosition != sideStagePosition)
                || (mLastSideStageUid != sideStageUid);
        if (!changed) {
            return false;
        }

        mLastSideStagePosition = sideStagePosition;
        mLastSideStageUid = sideStageUid;
        return true;
    }

    private boolean updateSplitRatioState(float splitRatio) {
        boolean changed = Float.compare(mLastSplitRatio, splitRatio) != 0;
        if (!changed) {
            return false;
        }

        mLastSplitRatio = splitRatio;
        return true;
    }

    public int getDragEnterReasonFromSplitPosition(@SplitPosition int position,
            boolean isLandscape) {
        if (isLandscape) {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__ENTER_REASON__DRAG_LEFT
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__ENTER_REASON__DRAG_RIGHT;
        } else {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__ENTER_REASON__DRAG_TOP
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__ENTER_REASON__DRAG_BOTTOM;
        }
    }

    private int getMainStagePositionFromSplitPosition(@SplitPosition int position,
            boolean isLandscape) {
        if (position == SPLIT_POSITION_UNDEFINED) {
            return 0;
        }
        if (isLandscape) {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__MAIN_STAGE_POSITION__LEFT
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__MAIN_STAGE_POSITION__RIGHT;
        } else {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__MAIN_STAGE_POSITION__TOP
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__MAIN_STAGE_POSITION__BOTTOM;
        }
    }

    private int getSideStagePositionFromSplitPosition(@SplitPosition int position,
            boolean isLandscape) {
        if (position == SPLIT_POSITION_UNDEFINED) {
            return 0;
        }
        if (isLandscape) {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__SIDE_STAGE_POSITION__LEFT
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__SIDE_STAGE_POSITION__RIGHT;
        } else {
            return position == SPLIT_POSITION_TOP_OR_LEFT
                    ? FrameworkStatsLog.SPLITSCREEN_UICHANGED__SIDE_STAGE_POSITION__TOP
                    : FrameworkStatsLog.SPLITSCREEN_UICHANGED__SIDE_STAGE_POSITION__BOTTOM;
        }
    }
}
