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

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;

import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import com.android.server.protolog.common.ProtoLog;

import java.util.function.Function;

/**
 * Class used by {@link RecentsAnimationController} to create a surface control with taking
 * screenshot of task when canceling recents animation.
 *
 * @see {@link RecentsAnimationController#setCancelOnNextTransitionStart}
 */
class TaskScreenshotAnimatable implements SurfaceAnimator.Animatable {
    private static final String TAG = "TaskScreenshotAnim";
    private Task mTask;
    private SurfaceControl mSurfaceControl;
    private int mWidth;
    private int mHeight;

    TaskScreenshotAnimatable(Function<SurfaceSession, SurfaceControl.Builder> surfaceControlFactory,
            Task task, SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer) {
        HardwareBuffer buffer = screenshotBuffer == null
                ? null : screenshotBuffer.getHardwareBuffer();
        mTask = task;
        mWidth = (buffer != null) ? buffer.getWidth() : 1;
        mHeight = (buffer != null) ? buffer.getHeight() : 1;
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                "Creating TaskScreenshotAnimatable: task: %s width: %d height: %d",
                        task, mWidth, mHeight);
        mSurfaceControl = surfaceControlFactory.apply(new SurfaceSession())
                .setName("RecentTaskScreenshotSurface")
                .setBufferSize(mWidth, mHeight)
                .build();
        if (buffer != null) {
            final Surface surface = new Surface();
            surface.copyFrom(mSurfaceControl);
            surface.attachAndQueueBufferWithColorSpace(buffer, screenshotBuffer.getColorSpace());
            surface.release();
            final float scale = 1.0f * mTask.getBounds().width() / mWidth;
            mSurfaceControl.setMatrix(scale, 0, 0, scale);
        }
        getPendingTransaction().show(mSurfaceControl);
    }

    @Override
    public SurfaceControl.Transaction getPendingTransaction() {
        return mTask.getPendingTransaction();
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
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        if (mSurfaceControl != null) {
            t.remove(mSurfaceControl);
            mSurfaceControl = null;
        }
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
        return mTask.mSurfaceControl;
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
