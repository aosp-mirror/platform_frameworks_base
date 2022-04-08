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

package com.android.test.taskembed;

import android.content.Context;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.window.WindowContainerToken;

/**
 * Simple SurfaceView wrapper which registers a TaskOrganizer
 * after it's Surface is ready.
 */
class TaskView extends SurfaceView implements SurfaceHolder.Callback {
    WindowContainerToken mWc;
    private SurfaceControl mLeash;

    private boolean mSurfaceCreated = false;
    private boolean mNeedsReparent;

    TaskView(Context c) {
        super(c);
        getHolder().addCallback(this);
        setZOrderOnTop(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        if (mNeedsReparent) {
            mNeedsReparent = false;
            reparentLeash();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    void reparentTask(WindowContainerToken wc, SurfaceControl leash) {
        mWc = wc;
        mLeash = leash;
        if (!mSurfaceCreated) {
            mNeedsReparent = true;
        } else {
            reparentLeash();
        }
    }

    private void reparentLeash() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        if (mLeash == null) {
            return;
        }

        t.reparent(mLeash, getSurfaceControl())
            .setPosition(mLeash, 0, 0)
            .show(mLeash)
            .apply();
    }
}
