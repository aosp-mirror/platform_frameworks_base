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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.app.ActivityManager;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class TaskOrganizerMultiWindowTest extends Activity {
    class TaskLaunchingView extends TaskView {
        TaskLaunchingView(Context c, ITaskOrganizer o, int windowingMode) {
            super(c, o, windowingMode);
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            startCalculatorActivity(width, height);
        }
    }
    TaskView mView;

    class Organizer extends ITaskOrganizer.Stub {
        @Override
        public void taskAppeared(IWindowContainer wc, ActivityManager.RunningTaskInfo ti) {
            mView.reparentTask(wc);
        }
        public void taskVanished(IWindowContainer wc) {
        }
        public void transactionReady(int id, SurfaceControl.Transaction t) {
        }
    }

    Organizer mOrganizer = new Organizer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mView = new TaskLaunchingView(this, mOrganizer, WINDOWING_MODE_MULTI_WINDOW);
        setContentView(mView);
    }

    Intent makeCalculatorIntent() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    Bundle makeLaunchOptions(int width, int height) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        o.setLaunchBounds(new Rect(0, 0, width, height));
        return o.toBundle();
    }

    void startCalculatorActivity(int width, int height) {
        startActivity(makeCalculatorIntent(), makeLaunchOptions(width, height));
    }
}
