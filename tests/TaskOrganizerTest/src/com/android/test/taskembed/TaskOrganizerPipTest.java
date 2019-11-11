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

package com.android.test.taskembed;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.WindowContainerTransaction;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class TaskOrganizerPipTest extends Service {
    static final int PIP_WIDTH  = 640;
    static final int PIP_HEIGHT = 360;

    class PipOrgView extends SurfaceView implements SurfaceHolder.Callback {
        PipOrgView(Context c) {
            super(c);
            getHolder().addCallback(this);
            setZOrderOnTop(true);
        }
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                ActivityTaskManager.getService().registerTaskOrganizer(mOrganizer,
                        WindowConfiguration.WINDOWING_MODE_PINNED);
            } catch (Exception e) {
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }

        void reparentTask(IWindowContainer wc) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            SurfaceControl leash = null;
            try {
                leash = wc.getLeash();
            } catch (Exception e) {
                // System server died.. oh well
            }
            t.reparent(leash, getSurfaceControl())
                .setPosition(leash, 0, 0)
                .apply();
        }
    }

    PipOrgView mPipView;

    class Organizer extends ITaskOrganizer.Stub {
        public void taskAppeared(IWindowContainer wc, ActivityManager.RunningTaskInfo ti) {
            mPipView.reparentTask(wc);

            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.scheduleFinishEnterPip(wc, new Rect(0, 0, PIP_WIDTH, PIP_HEIGHT));
            try {
                ActivityTaskManager.getService().applyContainerTransaction(wct);
            } catch (Exception e) {
            }
        }
        public void taskVanished(IWindowContainer wc) {
        }
        public void transactionReady(int id, SurfaceControl.Transaction t) {
        }
    }

    Organizer mOrganizer = new Organizer();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        final WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
        wlp.setTitle("TaskOrganizerPipTest");
        wlp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        wlp.width = wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;

        FrameLayout layout = new FrameLayout(this);
        ViewGroup.LayoutParams lp =
            new ViewGroup.LayoutParams(PIP_WIDTH, PIP_HEIGHT);
        mPipView = new PipOrgView(this);
        layout.addView(mPipView, lp);

        WindowManager wm = getSystemService(WindowManager.class);
        wm.addView(layout, wlp);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
