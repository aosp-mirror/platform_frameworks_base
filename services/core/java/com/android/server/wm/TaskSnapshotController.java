/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.ENABLE_TASK_SNAPSHOTS;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_NEVER;
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_NEVER;
import static android.graphics.PixelFormat.RGBA_8888;

import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.GraphicBuffer;
import android.util.ArraySet;
import android.view.WindowManagerPolicy.StartingSurface;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) of the corresponding task and
 * put it into our cache. Internally we use gralloc buffers to be able to draw them wherever we
 * like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of a task, and draw
 * them in their own process.
 * <p>
 * When we task becomes visible again, we show a starting window with the snapshot as the content to
 * make app transitions more responsive.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class TaskSnapshotController {

    private final WindowManagerService mService;
    private final TaskSnapshotCache mCache = new TaskSnapshotCache();

    private final ArraySet<Task> mTmpTasks = new ArraySet<>();

    TaskSnapshotController(WindowManagerService service) {
        mService = service;
    }

    void onTransitionStarting() {
        if (!ENABLE_TASK_SNAPSHOTS) {
            return;
        }

        // We need to take a snapshot of the task if and only if all activities of the task are
        // either closing or hidden.
        getClosingTasks(mService.mClosingApps, mTmpTasks);
        for (int i = mTmpTasks.size() - 1; i >= 0; i--) {
            final Task task = mTmpTasks.valueAt(i);
            if (!canSnapshotTask(task)) {
                continue;
            }
            final TaskSnapshot snapshot = snapshotTask(task);
            if (snapshot != null) {
                mCache.putSnapshot(task, snapshot);
            }
        }
    }

    @Nullable TaskSnapshot getSnapshot(Task task) {
        return mCache.getSnapshot(task);
    }

    /**
     * Creates a starting surface for {@param token} with {@param snapshot}. DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    StartingSurface createStartingSurface(AppWindowToken token,
            GraphicBuffer snapshot) {
        return TaskSnapshotSurface.create(mService, token, snapshot);
    }

    private TaskSnapshot snapshotTask(Task task) {
        final AppWindowToken top = task.getTopChild();
        if (top == null) {
            return null;
        }
        final Bitmap bmp = top.mDisplayContent.screenshotApplications(top.token, -1, -1, false,
                1.0f, ARGB_8888, false, true, false);
        if (bmp == null) {
            return null;
        }
        // TODO: Already use a GraphicBuffer when snapshotting the content.
        final GraphicBuffer buffer = GraphicBuffer.create(bmp.getWidth(), bmp.getHeight(),
                RGBA_8888, USAGE_HW_TEXTURE | USAGE_SW_WRITE_NEVER | USAGE_SW_READ_NEVER);
        final Canvas c = buffer.lockCanvas();
        c.drawBitmap(bmp, 0, 0, null);
        buffer.unlockCanvasAndPost(c);
        return new TaskSnapshot(buffer, top.getConfiguration().orientation,
                top.findMainWindow().mStableInsets);
    }

    /**
     * Retrieves all closing tasks based on the list of closing apps during an app transition.
     */
    @VisibleForTesting
    void getClosingTasks(ArraySet<AppWindowToken> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final AppWindowToken atoken = closingApps.valueAt(i);

            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (atoken.mTask != null && !atoken.mTask.isVisible()) {
                outClosingTasks.add(closingApps.valueAt(i).mTask);
            }
        }
    }

    private boolean canSnapshotTask(Task task) {
        return !StackId.isHomeOrRecentsStack(task.mStack.mStackId);
    }

    /**
     * Called when an {@link AppWindowToken} has been removed.
     */
    void onAppRemoved(AppWindowToken wtoken) {
        // TODO: Clean from both recents and running cache.
        mCache.cleanCache(wtoken);
    }

    /**
     * Called when the process of an {@link AppWindowToken} has died.
     */
    void onAppDied(AppWindowToken wtoken) {

        // TODO: Only clean from running cache.
        mCache.cleanCache(wtoken);
    }

    void dump(PrintWriter pw, String prefix) {
        mCache.dump(pw, prefix);
    }
}
