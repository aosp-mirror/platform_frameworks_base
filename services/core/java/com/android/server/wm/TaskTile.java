/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * A Tile. Right now this acts as a proxy for manipulating non-child stacks. Eventually, this
 * can become an actual parent.
 */
// TODO(task-hierarchy): Remove when tasks can nest >2 or when single tasks can handle their
//                       own lifecycles.
public class TaskTile extends ActivityStack {
    private static final String TAG = "TaskTile";
    final ArrayList<WindowContainer> mChildren = new ArrayList<>();

    private static ActivityInfo createEmptyActivityInfo() {
        ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        return info;
    }

    TaskTile(ActivityTaskManagerService atmService, int id, int windowingMode) {
        super(atmService, id, new Intent() /*intent*/,  null /*affinityIntent*/, null /*affinity*/,
                null /*rootAffinity*/, null /*realActivity*/, null /*origActivity*/,
                false /*rootWasReset*/, false /*autoRemoveRecents*/, false /*askedCompatMode*/,
                0 /*userId*/, 0 /*effectiveUid*/, null /*lastDescription*/,
                System.currentTimeMillis(), true /*neverRelinquishIdentity*/,
                new ActivityManager.TaskDescription(), id, INVALID_TASK_ID, INVALID_TASK_ID,
                0 /*taskAffiliationColor*/, 0 /*callingUid*/, "" /*callingPackage*/,
                null /*callingFeatureId*/, RESIZE_MODE_RESIZEABLE,
                false /*supportsPictureInPicture*/, false /*_realActivitySuspended*/,
                false /*userSetupComplete*/, INVALID_MIN_SIZE, INVALID_MIN_SIZE,
                createEmptyActivityInfo(), null /*voiceSession*/, null /*voiceInteractor*/,
                null /*stack*/);
        getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        mDisplayContent = null;
        if (dc != null) {
            dc.getPendingTransaction().merge(getPendingTransaction());
        }
        mDisplayContent = dc;
        // Virtual parent, so don't notify children.
    }

    @Override
    TaskTile asTile() {
        return this;
    }

    @Override
    protected void addChild(WindowContainer child, Comparator<WindowContainer> comparator) {
        throw new RuntimeException("Improper use of addChild() on Tile");
    }

    @Override
    void addChild(WindowContainer child, int index) {
        mChildren.add(child);
        if (child instanceof ActivityStack) {
            ((ActivityStack) child).setTile(this);
        }
        mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(
                this, false /* force */);
    }

    @Override
    void removeChild(WindowContainer child) {
        if (child instanceof ActivityStack) {
            ((ActivityStack) child).setTile(null);
        }
        mChildren.remove(child);
        mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(
                this, false /* force */);
    }

    void removeAllChildren() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child instanceof ActivityStack) {
                ((ActivityStack) child).setTile(null);
            }
        }
        mChildren.clear();
        mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(
                this, false /* force */);
    }

    @Override
    protected int getChildCount() {
        // Currently 0 as this isn't a proper hierarchy member yet.
        return 0;
    }

    @Override
    public void setWindowingMode(/*@WindowConfiguration.WindowingMode*/ int windowingMode) {
        Configuration c = new Configuration(getRequestedOverrideConfiguration());
        c.windowConfiguration.setWindowingMode(windowingMode);
        onRequestedOverrideConfigurationChanged(c);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            child.onConfigurationChanged(child.getParent().getConfiguration());
        }
    }

    void forAllTileActivities(Consumer<ActivityRecord> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).forAllActivities(callback, true /* traverseTopToBottom */);
        }
    }

    /**
     * Until this can be part of the hierarchy, the Stack level can use this utility during
     * resolveOverrideConfig to simulate inheritance.
     */
    void updateResolvedConfig(Configuration inOutResolvedConfig) {
        Rect resolveBounds = inOutResolvedConfig.windowConfiguration.getBounds();
        if (resolveBounds.isEmpty()) {
            resolveBounds.set(getRequestedOverrideBounds());
        }
        int stackMode = inOutResolvedConfig.windowConfiguration.getWindowingMode();
        if (stackMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                || stackMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
            // Also replace FULLSCREEN because we interpret FULLSCREEN as "fill parent"
            inOutResolvedConfig.windowConfiguration.setWindowingMode(
                    getRequestedOverrideWindowingMode());
        }
        if (inOutResolvedConfig.smallestScreenWidthDp
                == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            inOutResolvedConfig.smallestScreenWidthDp =
                    getRequestedOverrideConfiguration().smallestScreenWidthDp;
        }
        if (inOutResolvedConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            inOutResolvedConfig.screenWidthDp = getRequestedOverrideConfiguration().screenWidthDp;
        }
        if (inOutResolvedConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            inOutResolvedConfig.screenHeightDp = getRequestedOverrideConfiguration().screenHeightDp;
        }
        Rect resolveAppBounds = inOutResolvedConfig.windowConfiguration.getAppBounds();
        if (resolveAppBounds == null || resolveAppBounds.isEmpty()) {
            inOutResolvedConfig.windowConfiguration.setAppBounds(
                    getRequestedOverrideConfiguration().windowConfiguration.getAppBounds());
        }
    }

    @Override
    void fillTaskInfo(TaskInfo info) {
        super.fillTaskInfo(info);
        WindowContainer top = null;
        // Check mChildren.isEmpty directly because hasChild() -> getChildCount() always returns 0
        if (!mChildren.isEmpty()) {
            // Find the top-most root task which is a virtual child of this Tile. Because this is a
            // virtual parent, the mChildren order here isn't changed during hierarchy operations.
            WindowContainer parent = mChildren.get(0).getParent();
            for (int i = parent.getChildCount() - 1; i >= 0; --i) {
                if (mChildren.contains(parent.getChildAt(i))) {
                    top = parent.getChildAt(i);
                    break;
                }
            }
        }
        final Task topTask = top == null ? null : top.getTopMostTask();
        boolean isResizable = topTask == null || topTask.isResizeable();
        info.resizeMode = isResizable ? RESIZE_MODE_RESIZEABLE : RESIZE_MODE_UNRESIZEABLE;
        info.topActivityType = top == null ? ACTIVITY_TYPE_UNDEFINED : top.getActivityType();
    }

    @Override
    void removeImmediately() {
        removeAllChildren();
        super.removeImmediately();
    }

    @Override
    void taskOrganizerDied() {
        super.taskOrganizerDied();
        removeImmediately();
    }

    static TaskTile forToken(IBinder token) {
        try {
            return (TaskTile) ((RemoteToken) token).getContainer();
        } catch (ClassCastException e) {
            Slog.w(TAG, "Bad tile token: " + token, e);
            return null;
        }
    }
}
