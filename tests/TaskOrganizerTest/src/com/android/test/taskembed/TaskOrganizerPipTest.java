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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.window.TaskOrganizer;
import android.window.WindowContainerTransaction;

public class TaskOrganizerPipTest extends Service {
    private static final int PIP_WIDTH  = 640;
    private static final int PIP_HEIGHT = 360;

    private TaskView mTaskView;

    class Organizer extends TaskOrganizer {
        public void onTaskAppeared(ActivityManager.RunningTaskInfo ti, SurfaceControl leash) {
            mTaskView.reparentTask(ti.token, leash);

            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.scheduleFinishEnterPip(ti.token, new Rect(0, 0, PIP_WIDTH, PIP_HEIGHT));
            applyTransaction(wct);
        }
    }

    private Organizer mOrganizer = new Organizer();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mOrganizer.registerOrganizer(WINDOWING_MODE_PINNED);

        final WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
        wlp.setTitle("TaskOrganizerPipTest");
        wlp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        wlp.width = wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;

        FrameLayout layout = new FrameLayout(this);
        ViewGroup.LayoutParams lp =
            new ViewGroup.LayoutParams(PIP_WIDTH, PIP_HEIGHT);
        mTaskView = new TaskView(this);
        layout.addView(mTaskView, lp);

        WindowManager wm = getSystemService(WindowManager.class);
        wm.addView(layout, wlp);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mOrganizer.unregisterOrganizer();
    }
}
