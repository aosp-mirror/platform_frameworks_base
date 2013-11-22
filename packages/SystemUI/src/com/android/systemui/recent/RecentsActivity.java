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
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarPanel;

import java.util.List;

public class RecentsActivity extends Activity {
    public static final String TOGGLE_RECENTS_INTENT = "com.android.systemui.recent.action.TOGGLE_RECENTS";
    public static final String PRELOAD_INTENT = "com.android.systemui.recent.action.PRELOAD";
    public static final String CANCEL_PRELOAD_INTENT = "com.android.systemui.recent.CANCEL_PRELOAD";
    public static final String CLOSE_RECENTS_INTENT = "com.android.systemui.recent.action.CLOSE";
    public static final String WINDOW_ANIMATION_START_INTENT = "com.android.systemui.recent.action.WINDOW_ANIMATION_START";
    public static final String PRELOAD_PERMISSION = "com.android.systemui.recent.permission.PRELOAD";
    public static final String WAITING_FOR_WINDOW_ANIMATION_PARAM = "com.android.systemui.recent.WAITING_FOR_WINDOW_ANIMATION";
    private static final String WAS_SHOWING = "was_showing";

    private RecentsPanelView mRecentsPanel;
    private IntentFilter mIntentFilter;
    private boolean mShowing;
    private boolean mForeground;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CLOSE_RECENTS_INTENT.equals(intent.getAction())) {
                if (mRecentsPanel != null && mRecentsPanel.isShowing()) {
                    if (mShowing && !mForeground) {
                        // Captures the case right before we transition to another activity
                        mRecentsPanel.show(false);
                    }
                }
            } else if (WINDOW_ANIMATION_START_INTENT.equals(intent.getAction())) {
                if (mRecentsPanel != null) {
                    mRecentsPanel.onWindowAnimationStart();
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
        overridePendingTransition(
                R.anim.recents_return_to_launcher_enter,
                R.anim.recents_return_to_launcher_exit);
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

    private void updateWallpaperVisibility(boolean visible) {
        int wpflags = visible ? WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER : 0;
        int curflags = getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        if (wpflags != curflags) {
            getWindow().setFlags(wpflags, WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        }
    }

    public static boolean forceOpaqueBackground(Context context) {
        return WallpaperManager.getInstance(context).getWallpaperInfo() != null;
    }

    @Override
    public void onStart() {
        // Hide wallpaper if it's not a static image
        if (forceOpaqueBackground(this)) {
            updateWallpaperVisibility(false);
        } else {
            updateWallpaperVisibility(true);
        }
        mShowing = true;
        if (mRecentsPanel != null) {
            // Call and refresh the recent tasks list in case we didn't preload tasks
            // or in case we don't get an onNewIntent
            mRecentsPanel.refreshRecentTasksList();
            mRecentsPanel.refreshViews();
        }
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
            final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

            final List<ActivityManager.RecentTaskInfo> recentTasks =
                    am.getRecentTasks(2,
                            ActivityManager.RECENT_WITH_EXCLUDED |
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            if (recentTasks.size() > 1 &&
                    mRecentsPanel.simulateClick(recentTasks.get(1).persistentId)) {
                // recents panel will take care of calling show(false) through simulateClick
                return;
            }
            mRecentsPanel.show(false);
        }
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addPrivateFlags(
                WindowManager.LayoutParams.PRIVATE_FLAG_INHERIT_TRANSLUCENT_DECOR);
        setContentView(R.layout.status_bar_recent_panel);
        mRecentsPanel = (RecentsPanelView) findViewById(R.id.recents_root);
        mRecentsPanel.setOnTouchListener(new TouchOutsideListener(mRecentsPanel));
        mRecentsPanel.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        final RecentTasksLoader recentTasksLoader = RecentTasksLoader.getInstance(this);
        recentTasksLoader.setRecentsPanel(mRecentsPanel, mRecentsPanel);
        mRecentsPanel.setMinSwipeAlpha(
                getResources().getInteger(R.integer.config_recent_item_min_alpha) / 100f);

        if (savedInstanceState == null ||
                savedInstanceState.getBoolean(WAS_SHOWING)) {
            handleIntent(getIntent(), (savedInstanceState == null));
        }
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(CLOSE_RECENTS_INTENT);
        mIntentFilter.addAction(WINDOW_ANIMATION_START_INTENT);
        registerReceiver(mIntentReceiver, mIntentFilter);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(WAS_SHOWING, mRecentsPanel.isShowing());
    }

    @Override
    protected void onDestroy() {
        RecentTasksLoader.getInstance(this).setRecentsPanel(null, mRecentsPanel);
        unregisterReceiver(mIntentReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent, true);
    }

    private void handleIntent(Intent intent, boolean checkWaitingForAnimationParam) {
        super.onNewIntent(intent);

        if (TOGGLE_RECENTS_INTENT.equals(intent.getAction())) {
            if (mRecentsPanel != null) {
                if (mRecentsPanel.isShowing()) {
                    dismissAndGoBack();
                } else {
                    final RecentTasksLoader recentTasksLoader = RecentTasksLoader.getInstance(this);
                    boolean waitingForWindowAnimation = checkWaitingForAnimationParam &&
                            intent.getBooleanExtra(WAITING_FOR_WINDOW_ANIMATION_PARAM, false);
                    mRecentsPanel.show(true, recentTasksLoader.getLoadedTasks(),
                            recentTasksLoader.isFirstScreenful(), waitingForWindowAnimation);
                }
            }
        }
    }

    boolean isForeground() {
        return mForeground;
    }

    boolean isActivityShowing() {
         return mShowing;
    }
}
