/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerService.TAG;
import static com.android.server.wm.WindowManagerService.DEBUG_STACK;

import android.util.EventLog;
import android.util.Slog;
import com.android.server.EventLogTags;

class Task {
    TaskStack mStack;
    final AppTokenList mAppTokens = new AppTokenList();
    final int mTaskId;
    final int mUserId;
    boolean mDeferRemoval = false;
    final WindowManagerService mService;

    Task(int taskId, TaskStack stack, int userId, WindowManagerService service) {
        mTaskId = taskId;
        mStack = stack;
        mUserId = userId;
        mService = service;
    }

    DisplayContent getDisplayContent() {
        return mStack.getDisplayContent();
    }

    void addAppToken(int addPos, AppWindowToken wtoken) {
        final int lastPos = mAppTokens.size();
        if (addPos >= lastPos) {
            addPos = lastPos;
        } else {
            for (int pos = 0; pos < lastPos && pos < addPos; ++pos) {
                if (mAppTokens.get(pos).removed) {
                    // addPos assumes removed tokens are actually gone.
                    ++addPos;
                }
            }
        }
        mAppTokens.add(addPos, wtoken);
        wtoken.mTask = this;
        mDeferRemoval = false;
    }

    void removeLocked() {
        if (!mAppTokens.isEmpty() && mStack.isAnimating()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            mDeferRemoval = true;
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "removeTask");
        mDeferRemoval = false;
        mStack.removeTask(this);
        mService.mTaskIdToTask.delete(mTaskId);
    }

    void moveTaskToStack(TaskStack stack, boolean toTop) {
        if (stack == mStack) {
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "moveTaskToStack: removing taskId=" + mTaskId
                + " from stack=" + mStack);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "moveTask");
        if (mStack != null) {
            mStack.removeTask(this);
        }
        stack.addTask(this, toTop);
    }

    boolean removeAppToken(AppWindowToken wtoken) {
        boolean removed = mAppTokens.remove(wtoken);
        if (mAppTokens.size() == 0) {
            EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId,
                    "removeAppToken: last token");
            if (mDeferRemoval) {
                removeLocked();
            }
        }
        wtoken.mTask = null;
        /* Leave mTaskId for now, it might be useful for debug
        wtoken.mTaskId = -1;
         */
        return removed;
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mAppTokens.size(); appTokenNdx++) {
            mAppTokens.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    /**
     * Cancels any running app transitions associated with the task.
     */
    void cancelTaskWindowTransition() {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            mAppTokens.get(activityNdx).mAppAnimator.clearAnimation();
        }
    }

    void cancelTaskThumbnailTransition() {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            mAppTokens.get(activityNdx).mAppAnimator.clearThumbnail();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mAppTokens.size();
        return (tokensCount != 0) && mAppTokens.get(tokensCount - 1).showForAllUsers;
    }

    @Override
    public String toString() {
        return "{taskId=" + mTaskId + " appTokens=" + mAppTokens + " mdr=" + mDeferRemoval + "}";
    }
}
