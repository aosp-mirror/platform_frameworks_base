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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.window.TaskOrganizer;
import android.window.WindowContainerTransactionCallback;

import java.util.concurrent.CountDownLatch;

public class TaskOrganizerMultiWindowTest extends Activity {
    private CountDownLatch mTasksReadyLatch;
    private CountDownLatch mTasksResizeLatch;

    class Organizer extends TaskOrganizer {
        private int mReceivedTransactions = 0;
        private SurfaceControl.Transaction mMergedTransaction = new SurfaceControl.Transaction();
        WindowContainerTransactionCallback mTransactionCallback =
                new WindowContainerTransactionCallback() {
            @Override
            public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                mMergedTransaction.merge(t);
                mReceivedTransactions++;
                if (mReceivedTransactions == 2) {
                    mReceivedTransactions = 0;
                    mMergedTransaction.apply(true);
                    if (mTasksResizeLatch != null) {
                        mTasksResizeLatch.countDown();
                    }
                }
            }
        };

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo ti, SurfaceControl leash) {
            if (ti.baseActivity == null) {
                return;
            }

            final String clsName = ti.baseActivity.getClassName();
            if (clsName.contentEquals(TestActivity1.class.getName())) {
                mTaskView1.reparentTask(ti.token, leash);
                mOrganizer.setInterceptBackPressedOnTaskRoot(ti.token, true);
                mTasksReadyLatch.countDown();
            } else if (clsName.contentEquals(TestActivity2.class.getName())) {
                mTaskView2.reparentTask(ti.token, leash);
                mOrganizer.setInterceptBackPressedOnTaskRoot(ti.token, true);
                mTasksReadyLatch.countDown();
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
            getMainThreadHandler().post(() -> {
                finish();
            });
        }
    }

    private Organizer mOrganizer = new Organizer();
    private FrameLayout mTasksLayout;
    private TaskView mTaskView1;
    private TaskView mTaskView2;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        mTasksLayout = new FrameLayout(this);
        setContentView(mTasksLayout);

        mOrganizer.registerOrganizer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mOrganizer.unregisterOrganizer();
        mTasksLayout.removeAllViews();
    }

    private Intent makeActivityIntent(final Class<?> clazz) {
        Intent intent = new Intent(this, clazz);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    public CountDownLatch openTaskView(Rect firstBounds, Rect secondBounds) {
        mTasksReadyLatch = new CountDownLatch(2);
        mTaskView1 = new TaskView(this, mOrganizer, makeActivityIntent(TestActivity1.class));
        mTaskView1.setBackgroundColor(Color.DKGRAY);

        FrameLayout.LayoutParams viewLayout1 =
                new FrameLayout.LayoutParams(firstBounds.width(), firstBounds.height(),
                        Gravity.TOP | Gravity.LEFT);
        viewLayout1.setMargins(firstBounds.left, firstBounds.top, 0, 0);
        mTasksLayout.addView(mTaskView1, viewLayout1);

        mTaskView2 = new TaskView(this, mOrganizer, makeActivityIntent(TestActivity2.class));
        mTaskView2.setBackgroundColor(Color.LTGRAY);
        FrameLayout.LayoutParams viewLayout2 =
                new FrameLayout.LayoutParams(secondBounds.width(), secondBounds.height(),
                        Gravity.TOP | Gravity.LEFT);
        viewLayout2.setMargins(secondBounds.left, secondBounds.top, 0, 0);
        mTasksLayout.addView(mTaskView2, viewLayout2);
        return mTasksReadyLatch;
    }

    public CountDownLatch resizeTaskView(Rect firstBounds, Rect secondBounds) {
        mTasksResizeLatch = new CountDownLatch(1);

        mTaskView1.resizeTask(firstBounds.width(), firstBounds.height());
        mTaskView2.resizeTask(secondBounds.width(), secondBounds.height());

        return mTasksResizeLatch;
    }

    static class InstrumentedTextView extends TextView {
        private final boolean mSlowDraw;
        InstrumentedTextView(Context context, boolean slowDraw) {
            super(context);
            mSlowDraw = slowDraw;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mSlowDraw) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static class TestActivity1 extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            TextView v = new InstrumentedTextView(this, true);
            v.setText("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
                    + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            v.setBackgroundColor(Color.RED);
            v.setTextColor(Color.BLACK);
            setContentView(v);
        }
    }

    public static class TestActivity2 extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            TextView v = new InstrumentedTextView(this, false);
            v.setText("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ");
            v.setBackgroundColor(Color.GREEN);
            v.setTextColor(Color.BLACK);
            setContentView(v);
        }
    }
}
