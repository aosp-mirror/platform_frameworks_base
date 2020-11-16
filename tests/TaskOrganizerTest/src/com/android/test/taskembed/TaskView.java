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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

/**
 * Simple SurfaceView wrapper which registers a TaskOrganizer
 * after it's Surface is ready.
 */
class TaskView extends SurfaceView {
    private WindowContainerToken mWc;
    private Context mContext;
    private SurfaceControl mLeash;
    private TaskOrganizerMultiWindowTest.Organizer mOrganizer;
    private Intent mIntent;
    private boolean mLaunched = false;

    TaskView(Context c, TaskOrganizerMultiWindowTest.Organizer organizer,
            Intent intent) {
        super(c);
        mContext = c;
        mOrganizer = organizer;
        mIntent = intent;
        getHolder().addCallback(
                new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {}

                    @Override
                    public void surfaceChanged(SurfaceHolder holder,
                            int format, int width, int height) {
                        if (!mLaunched) {
                            launchOrganizedActivity(mIntent, width, height);
                            mLaunched = true;
                        } else {
                            resizeTask(width, height);
                        }
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {}
                }
        );
        setZOrderOnTop(true);
    }

    private void launchOrganizedActivity(Intent i, int width, int height) {
        mContext.startActivity(i, makeLaunchOptions(width, height));
    }

    private Bundle makeLaunchOptions(int width, int height) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        o.setLaunchBounds(new Rect(0, 0, width, height));
        o.setTaskOverlay(true, true);
        o.setTaskAlwaysOnTop(true);
        return o.toBundle();
    }

    void resizeTask(int width, int height) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mWc, new Rect(0, 0, width, height)).setHidden(mWc, false);
        try {
            mOrganizer.applySyncTransaction(wct, mOrganizer.mTransactionCallback);
        } catch (Exception e) {
            // Oh well
        }
    }

    void hideTask() {
        if (mWc == null) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(mWc, WINDOWING_MODE_UNDEFINED).setHidden(mWc, true);
        try {
            mOrganizer.applySyncTransaction(wct, mOrganizer.mTransactionCallback);
        } catch (Exception e) {
            // Oh well
        }
        releaseLeash();
    }

    void reparentTask(WindowContainerToken wc, SurfaceControl leash) {
        mWc = wc;
        mLeash = leash;
        reparentLeash();
    }

    void reparentLeash() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(mLeash, getSurfaceControl())
            .show(mLeash)
            .apply();
    }

    void releaseLeash() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.remove(mLeash).apply();
    }
}
