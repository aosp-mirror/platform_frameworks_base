/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS;

import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

/**
 * Class used by {@link RecentsAnimationController} to create a surface control with taking
 * screenshot of task when canceling recents animation.
 *
 * @see {@link RecentsAnimationController#cancelOnNextTransitionStart}
 */
class TaskScreenshotAnimatable implements SurfaceAnimator.Animatable {
    private static final String TAG = "TaskScreenshotAnim";
    private Task mTask;
    private SurfaceControl mSurfaceControl;
    private int mWidth;
    private int mHeight;

    public static TaskScreenshotAnimatable create(Task task) {
        return new TaskScreenshotAnimatable(task, getBufferFromTask(task));
    }

    private static GraphicBuffer getBufferFromTask(Task task) {
        if (task == null) {
            return null;
        }
        final Rect tmpRect = task.getBounds();
        tmpRect.offset(0, 0);
        return SurfaceControl.captureLayers(
                task.getSurfaceControl().getHandle(), tmpRect, 1f);
    }

    private TaskScreenshotAnimatable(Task task, GraphicBuffer buffer) {
        mTask = task;
        mWidth = (buffer != null) ? buffer.getWidth() : 1;
        mHeight = (buffer != null) ? buffer.getHeight() : 1;
        if (DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "Creating TaskScreenshotAnimatable: task: " + task
                    + "width: " + mWidth + "height: " + mHeight);
        }
        mSurfaceControl = new SurfaceControl.Builder(new SurfaceSession())
                .setName("RecentTaskScreenshotSurface")
                .setBufferSize(mWidth, mHeight)
                .build();
        if (buffer != null) {
            final Surface surface = new Surface();
            surface.copyFrom(mSurfaceControl);
            surface.attachAndQueueBuffer(buffer);
            surface.release();
        }
        getPendingTransaction().show(mSurfaceControl);
    }

    @Override
    public SurfaceControl.Transaction getPendingTransaction() {
        return mTask.mPendingTransaction;
    }

    @Override
    public void commitPendingTransaction() {
        mTask.commitPendingTransaction();
    }

    @Override
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        t.setLayer(leash, 1);
    }

    @Override
    public void onAnimationLeashDestroyed(SurfaceControl.Transaction t) {
        t.remove(mSurfaceControl);
    }

    @Override
    public SurfaceControl.Builder makeAnimationLeash() {
        return mTask.makeAnimationLeash();
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return mTask.getAnimationLeashParent();
    }

    @Override
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        return mTask.mSurfaceAnimator.mLeash;
    }

    @Override
    public int getSurfaceWidth() {
        return mWidth;
    }

    @Override
    public int getSurfaceHeight() {
        return mHeight;
    }
}
