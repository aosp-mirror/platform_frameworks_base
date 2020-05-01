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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.window.TaskOrganizer;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

public class TaskOrganizerMultiWindowTest extends Activity {
    static class SplitLayout extends LinearLayout implements View.OnTouchListener {
        View mView1;
        View mView2;
        View mDividerView;

        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) {
                return true;
            }

            float x = e.getRawX(0);
            float ratio = (float) x / (float) getWidth() ;
            ratio = 1-ratio;

            LinearLayout.LayoutParams lp1 =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ratio-0.02f);
            LinearLayout.LayoutParams lp2 =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1-ratio-0.02f);
            updateViewLayout(mView1, lp2);
            updateViewLayout(mView2, lp1);
            return true;
        }

        SplitLayout(Context c, View v1, View v2) {
            super(c);
            LinearLayout.LayoutParams lp1 =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f);
            LinearLayout.LayoutParams lp3 =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f);
            LinearLayout.LayoutParams lp2 =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.FILL_PARENT, 0.04f);
            lp2.gravity = Gravity.CENTER;

            setWeightSum(1);

            mView1 = v1;
            mView2 = v2;
            addView(mView1, lp1);

            mDividerView = new View(getContext());
            mDividerView.setBackgroundColor(Color.BLACK);
            addView(mDividerView, lp2);
            mDividerView.setOnTouchListener(this);

            addView(mView2, lp3);
        }
    }

    class ResizingTaskView extends TaskView {
        final Intent mIntent;
        boolean launched = false;
        ResizingTaskView(Context c, Intent i) {
            super(c);
            mIntent = i;
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            if (!launched) {
                launchOrganizedActivity(mIntent, width, height);
                launched = true;
            } else {
                resizeTask(width, height);
            }
        }

        void resizeTask(int width, int height) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mWc, new Rect(0, 0, width, height));
            try {
                mOrganizer.applySyncTransaction(wct, mOrganizer.mTransactionCallback);
            } catch (Exception e) {
                // Oh well
            }
        }
    }

    private TaskView mTaskView1;
    private TaskView mTaskView2;
    private boolean mGotFirstTask = false;

    class Organizer extends TaskOrganizer {
        private int receivedTransactions = 0;
        SurfaceControl.Transaction mergedTransaction = new SurfaceControl.Transaction();
        WindowContainerTransactionCallback mTransactionCallback =
                new WindowContainerTransactionCallback() {
            @Override
            public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                mergedTransaction.merge(t);
                receivedTransactions++;
                if (receivedTransactions == 2) {
                    mergedTransaction.apply();
                    receivedTransactions = 0;
                }
            }
        };

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo ti, SurfaceControl leash) {
            if (!mGotFirstTask) {
                mTaskView1.reparentTask(ti.token, leash);
                mGotFirstTask = true;
            } else {
                mTaskView2.reparentTask(ti.token, leash);
            }
        }
    }

    private Organizer mOrganizer = new Organizer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mOrganizer.registerOrganizer(WINDOWING_MODE_MULTI_WINDOW);

        mTaskView1 = new ResizingTaskView(this, makeSettingsIntent());
        mTaskView2 = new ResizingTaskView(this, makeContactsIntent());
        View splitView = new SplitLayout(this, mTaskView1, mTaskView2);

        setContentView(splitView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mOrganizer.unregisterOrganizer();
    }

    private void addFlags(Intent intent) {
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    private Intent makeSettingsIntent() {
        Intent intent = new Intent();
        intent.setAction(android.provider.Settings.ACTION_SETTINGS);
        addFlags(intent);
        return intent;
    }

    private Intent makeContactsIntent() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_CONTACTS);
        addFlags(intent);
        return intent;
    }

    private Bundle makeLaunchOptions(int width, int height) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        o.setLaunchBounds(new Rect(0, 0, width, height));
        return o.toBundle();
    }

    private void launchOrganizedActivity(Intent i, int width, int height) {
        startActivity(i, makeLaunchOptions(width, height));
    }
}
