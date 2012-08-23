/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.recent;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.tablet.StatusBarPanel;

public class RecentsActivity extends Activity {
    public static final String TOGGLE_RECENTS_INTENT = "com.android.systemui.TOGGLE_RECENTS";
    public static final String CLOSE_RECENTS_INTENT = "com.android.systemui.CLOSE_RECENTS";

    private RecentsPanelView mRecentsPanel;
    private IntentFilter mIntentFilter;
    private boolean mShowing;
    private boolean mForeground;
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mRecentsPanel != null && mRecentsPanel.isShowing()) {
                if (mShowing && !mForeground) {
                    // Captures the case right before we transition to another activity
                    mRecentsPanel.show(false);
                }
            }
        }
    };

    public class TouchOutsideListener implements View.OnTouchListener {
        private StatusBarPanel mPanel;

        public TouchOutsideListener(StatusBarPanel panel) {
            mPanel = panel;
        }

        public boolean onTouch(View v, MotionEvent ev) {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_OUTSIDE
                    || (action == MotionEvent.ACTION_DOWN
                    && !mPanel.isInContentArea((int) ev.getX(), (int) ev.getY()))) {
                dismissAndGoHome();
                return true;
            }
            return false;
        }
    }

    @Override
    public void onPause() {
        mForeground = false;
        super.onPause();
    }

    @Override
    public void onStop() {
        mShowing = false;
        if (mRecentsPanel != null) {
            mRecentsPanel.onUiHidden();
        }
        super.onStop();
    }

    @Override
    public void onStart() {
        mShowing = true;
        super.onStart();
    }

    @Override
    public void onResume() {
        mForeground = true;
        super.onResume();
    }

    @Override
    public void onBackPressed() {
        dismissAndGoBack();
    }

    public void dismissAndGoHome() {
        if (mRecentsPanel != null) {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivityAsUser(homeIntent, new UserHandle(UserHandle.USER_CURRENT));
            mRecentsPanel.show(false);
        }
    }

    public void dismissAndGoBack() {
        if (mRecentsPanel != null) {
            final SystemUIApplication app = (SystemUIApplication) getApplication();
            final RecentTasksLoader recentTasksLoader = app.getRecentTasksLoader();
            TaskDescription firstTask = recentTasksLoader.getFirstTask();
            if (firstTask != null && mRecentsPanel.simulateClick(firstTask)) {
                // recents panel will take care of calling show(false);
                return;
            }
            mRecentsPanel.show(false);
        }
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final SystemUIApplication app = (SystemUIApplication) getApplication();
        final RecentTasksLoader recentTasksLoader = app.getRecentTasksLoader();

        setContentView(R.layout.status_bar_recent_panel);
        mRecentsPanel = (RecentsPanelView) findViewById(R.id.recents_root);
        mRecentsPanel.setOnTouchListener(new TouchOutsideListener(mRecentsPanel));
        mRecentsPanel.setRecentTasksLoader(recentTasksLoader);
        recentTasksLoader.setRecentsPanel(mRecentsPanel, mRecentsPanel);

        handleIntent(getIntent());
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(CLOSE_RECENTS_INTENT);
        registerReceiver(mIntentReceiver, mIntentFilter);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        final SystemUIApplication app = (SystemUIApplication) getApplication();
        final RecentTasksLoader recentTasksLoader = app.getRecentTasksLoader();
        recentTasksLoader.setRecentsPanel(null, mRecentsPanel);
        unregisterReceiver(mIntentReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        super.onNewIntent(intent);

        if (TOGGLE_RECENTS_INTENT.equals(intent.getAction())) {
            if (mRecentsPanel != null && !mRecentsPanel.isShowing()) {
                final SystemUIApplication app = (SystemUIApplication) getApplication();
                final RecentTasksLoader recentTasksLoader = app.getRecentTasksLoader();
                mRecentsPanel.show(true, recentTasksLoader.getLoadedTasks(),
                        recentTasksLoader.isFirstScreenful());
            } else if ((mRecentsPanel != null && mRecentsPanel.isShowing())) {
                dismissAndGoBack();
            }
        }
    }

}
