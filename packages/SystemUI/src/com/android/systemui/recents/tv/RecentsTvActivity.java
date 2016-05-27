/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout.LayoutParams;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.tv.animations.HomeRecentsEnterExitAnimationHolder;
import com.android.systemui.recents.tv.views.RecentsTvView;
import com.android.systemui.recents.tv.views.TaskCardView;
import com.android.systemui.recents.tv.views.TaskStackHorizontalGridView;
import com.android.systemui.recents.tv.views.TaskStackHorizontalViewAdapter;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.tv.pip.PipManager;
import com.android.systemui.tv.pip.PipRecentsOverlayManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The main TV recents activity started by the RecentsImpl.
 */
public class RecentsTvActivity extends Activity implements OnPreDrawListener {
    private final static String TAG = "RecentsTvActivity";
    private final static boolean DEBUG = false;

    public final static int EVENT_BUS_PRIORITY = Recents.EVENT_BUS_PRIORITY + 1;
    private final static String RECENTS_HOME_INTENT_EXTRA =
            "com.android.systemui.recents.tv.RecentsTvActivity.RECENTS_HOME_INTENT_EXTRA";

    private boolean mFinishedOnStartup;
    private RecentsPackageMonitor mPackageMonitor;
    private long mLastTabKeyEventTime;
    private boolean mIgnoreAltTabRelease;
    private boolean mLaunchedFromHome;
    private boolean mTalkBackEnabled;

    private RecentsTvView mRecentsView;
    private View mPipView;
    private TaskStackHorizontalViewAdapter mTaskStackViewAdapter;
    private TaskStackHorizontalGridView mTaskStackHorizontalGridView;
    private FinishRecentsRunnable mFinishLaunchHomeRunnable;
    private HomeRecentsEnterExitAnimationHolder mHomeRecentsEnterExitAnimationHolder;

    private final PipManager mPipManager = PipManager.getInstance();
    private final PipManager.Listener mPipListener = new PipManager.Listener() {
        @Override
        public void onPipEntered() {
            updatePipUI();
        }

        @Override
        public void onPipActivityClosed() {
            updatePipUI();
        }

        @Override
        public void onShowPipMenu() {
            updatePipUI();
        }

        @Override
        public void onMoveToFullscreen() {
            // Recents should be dismissed when PIP moves to fullscreen. If not, Recents will
            // be unnecessarily shown in the scenario: PIP->Fullscreen->PIP.
            // Do not show Recents close animation because PIP->Fullscreen animation will be shown
            // instead.
            dismissRecentsToLaunchTargetTaskOrHome(false);
        }

        @Override
        public void onPipResizeAboutToStart() { }
    };
    private PipRecentsOverlayManager mPipRecentsOverlayManager;
    private final PipRecentsOverlayManager.Callback mPipRecentsOverlayManagerCallback =
            new PipRecentsOverlayManager.Callback() {
                @Override
                public void onClosed() {
                    dismissRecentsToLaunchTargetTaskOrHome(true);
                }

                @Override
                public void onBackPressed() {
                    RecentsTvActivity.this.onBackPressed();
                }

                @Override
                public void onRecentsFocused() {
                    if (mTalkBackEnabled) {
                        mTaskStackHorizontalGridView.requestFocus();
                        mTaskStackHorizontalGridView.sendAccessibilityEvent(
                                AccessibilityEvent.TYPE_VIEW_FOCUSED);
                    }
                    mTaskStackHorizontalGridView.startFocusGainAnimation();
                }
            };

    private final View.OnFocusChangeListener mPipViewFocusChangeListener =
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        requestPipControlsFocus();
                    }
                }
            };

    /**
     * A common Runnable to finish Recents by launching Home with an animation depending on the
     * last activity launch state.  Generally we always launch home when we exit Recents rather than
     * just finishing the activity since we don't know what is behind Recents in the task stack.
     */
    class FinishRecentsRunnable implements Runnable {
        Intent mLaunchIntent;

        /**
         * Creates a finish runnable that starts the specified intent.
         */
        public FinishRecentsRunnable(Intent launchIntent) {
            mLaunchIntent = launchIntent;
        }

        @Override
        public void run() {
            try {
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(RecentsTvActivity.this,
                        R.anim.recents_to_launcher_enter, R.anim.recents_to_launcher_exit);
                startActivityAsUser(mLaunchIntent, opts.toBundle(), UserHandle.CURRENT);
            } catch (Exception e) {
                Log.e(TAG, getString(R.string.recents_launch_error_message, "Home"), e);
            }
        }
    }

    private void updateRecentsTasks() {
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = RecentsImpl.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }

        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        if (!plan.hasTasks()) {
            loader.preloadTasks(plan, -1, !launchState.launchedFromHome);
        }

        int numVisibleTasks = TaskCardView.getNumberOfVisibleTasks(getApplicationContext());
        mLaunchedFromHome = launchState.launchedFromHome;
        TaskStack stack = plan.getTaskStack();
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = numVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = numVisibleTasks;
        loader.loadTasks(this, plan, loadOpts);

        List stackTasks = stack.getStackTasks();
        Collections.reverse(stackTasks);
        if (mTaskStackViewAdapter == null) {
            mTaskStackViewAdapter = new TaskStackHorizontalViewAdapter(stackTasks);
            mTaskStackHorizontalGridView = mRecentsView
                    .setTaskStackViewAdapter(mTaskStackViewAdapter);
            mHomeRecentsEnterExitAnimationHolder = new HomeRecentsEnterExitAnimationHolder(
                    getApplicationContext(), mTaskStackHorizontalGridView);
        } else {
            mTaskStackViewAdapter.setNewStackTasks(stackTasks);
        }
        mRecentsView.init(stack);

        if (launchState.launchedToTaskId != -1) {
            ArrayList<Task> tasks = stack.getStackTasks();
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task t = tasks.get(i);
                if (t.key.id == launchState.launchedToTaskId) {
                    t.isLaunchTarget = true;
                    break;
                }
            }
        }
    }

    boolean dismissRecentsToLaunchTargetTaskOrHome(boolean animate) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchPreviousTask(animate)) {
              return true;
            }
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(animate /* animateTaskViews */);
        }
        return false;
    }

    boolean dismissRecentsToFocusedTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true /* animateTaskViews */);
            return true;
        }
        return false;
    }

    void dismissRecentsToHome(boolean animateTaskViews) {
        Runnable closeSystemWindows = new Runnable() {
            @Override
            public void run() {
                Recents.getSystemServices().sendCloseSystemWindows(
                        BaseStatusBar.SYSTEM_DIALOG_REASON_HOME_KEY);
            }
        };
        DismissRecentsToHomeAnimationStarted dismissEvent =
                new DismissRecentsToHomeAnimationStarted(animateTaskViews);
        dismissEvent.addPostAnimationCallback(mFinishLaunchHomeRunnable);
        dismissEvent.addPostAnimationCallback(closeSystemWindows);

        if (mTaskStackHorizontalGridView.getChildCount() > 0 && animateTaskViews) {
            mHomeRecentsEnterExitAnimationHolder.startExitAnimation(dismissEvent);
        } else {
            closeSystemWindows.run();
            mFinishLaunchHomeRunnable.run();
        }
    }

    boolean dismissRecentsToHomeIfVisible(boolean animated) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // Return to Home
            dismissRecentsToHome(animated);
            return true;
        }
        return false;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFinishedOnStartup = false;

        // In the case that the activity starts up before the Recents component has initialized
        // (usually when debugging/pushing the SysUI apk), just finish this activity.
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp == null) {
            mFinishedOnStartup = true;
            finish();
            return;
        }
        mPipRecentsOverlayManager = PipManager.getInstance().getPipRecentsOverlayManager();

        // Register this activity with the event bus
        EventBus.getDefault().register(this, EVENT_BUS_PRIORITY);

        mPackageMonitor = new RecentsPackageMonitor();
        mPackageMonitor.register(this);

        // Set the Recents layout
        setContentView(R.layout.recents_on_tv);

        mRecentsView = (RecentsTvView) findViewById(R.id.recents_view);
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        mPipView = findViewById(R.id.pip);
        mPipView.setOnFocusChangeListener(mPipViewFocusChangeListener);
        // Place mPipView at the PIP bounds for fine tuned focus handling.
        Rect pipBounds = mPipManager.getRecentsFocusedPipBounds();
        LayoutParams lp = (LayoutParams) mPipView.getLayoutParams();
        lp.width = pipBounds.width();
        lp.height = pipBounds.height();
        lp.leftMargin = pipBounds.left;
        lp.topMargin = pipBounds.top;
        mPipView.setLayoutParams(lp);

        mPipRecentsOverlayManager.setCallback(mPipRecentsOverlayManagerCallback);

        getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        homeIntent.putExtra(RECENTS_HOME_INTENT_EXTRA, true);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent);

        mPipManager.addListener(mPipListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        if(mLaunchedFromHome) {
            mHomeRecentsEnterExitAnimationHolder.startEnterAnimation(mPipManager.isPipShown());
        }
        EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());
    }

    @Override
    public void onResume() {
        super.onResume();
        mPipRecentsOverlayManager.onRecentsResumed();
        // Update the recent tasks
        updateRecentsTasks();

        // If this is a new instance from a configuration change, then we have to manually trigger
        // the enter animation state, or if recents was relaunched by AM, without going through
        // the normal mechanisms
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        boolean wasLaunchedByAm = !launchState.launchedFromHome &&
                !launchState.launchedFromApp;
        if (wasLaunchedByAm) {
            EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());
        }

        // Notify that recents is now visible
        SystemServicesProxy ssp = Recents.getSystemServices();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, true));
        if(mTaskStackHorizontalGridView.getStack().getTaskCount() > 1 && !mLaunchedFromHome) {
            // If there are 2 or more tasks, and we are not launching from home
            // set the selected position to the 2nd task to allow for faster app switching
            mTaskStackHorizontalGridView.setSelectedPosition(1);
        } else {
            mTaskStackHorizontalGridView.setSelectedPosition(0);
        }
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);

        View dismissPlaceholder = findViewById(R.id.dismiss_placeholder);
        mTalkBackEnabled = ssp.isTouchExplorationEnabled();
        if (mTalkBackEnabled) {
            dismissPlaceholder.setAccessibilityTraversalBefore(R.id.task_list);
            dismissPlaceholder.setAccessibilityTraversalAfter(R.id.dismiss_placeholder);
            mTaskStackHorizontalGridView.setAccessibilityTraversalAfter(R.id.dismiss_placeholder);
            mTaskStackHorizontalGridView.setAccessibilityTraversalBefore(R.id.pip);
            dismissPlaceholder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mTaskStackHorizontalGridView.requestFocus();
                    mTaskStackHorizontalGridView.
                            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                    Task focusedTask = mTaskStackHorizontalGridView.getFocusedTask();
                    if (focusedTask != null) {
                        mTaskStackViewAdapter.removeTask(focusedTask);
                        EventBus.getDefault().send(new DeleteTaskDataEvent(focusedTask));
                    }
                }
            });
        }

        // Initialize PIP UI
        if (mPipManager.isPipShown()) {
            if (mTalkBackEnabled) {
                // If talkback is on, use the mPipView to handle focus changes
                // between recents row and PIP controls.
                mPipView.setVisibility(View.VISIBLE);
            } else {
                mPipView.setVisibility(View.GONE);
            }
            // When PIP view has focus, recents overlay view will takes the focus
            // as if it's the part of the Recents UI.
            mPipRecentsOverlayManager.requestFocus(mTaskStackViewAdapter.getItemCount() > 0);
        } else {
            mPipView.setVisibility(View.GONE);
            mPipRecentsOverlayManager.removePipRecentsOverlayView();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mPipRecentsOverlayManager.onRecentsPaused();
    }

    @Override
    protected void onStop() {
        super.onStop();

        mIgnoreAltTabRelease = false;
        // Notify that recents is now hidden
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, false));

        // Workaround for b/22542869, if the RecentsActivity is started again, but without going
        // through SystemUI, we need to reset the config launch flags to ensure that we do not
        // wait on the system to send a signal that was never queued.
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        launchState.reset();

        // Workaround for b/28333917.
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mPipManager.removeListener(mPipListener);
        // In the case that the activity finished on startup, just skip the unregistration below
        if (mFinishedOnStartup) {
            return;
        }

        // Unregister any broadcast receivers for the task loader
        mPackageMonitor.unregister();

        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onTrimMemory(int level) {
        RecentsTaskLoader loader = Recents.getTaskLoader();
        if (loader != null) {
            loader.onTrimMemory(level);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                EventBus.getDefault().send(new DismissFocusedTaskViewEvent());
                return true;
            }
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        EventBus.getDefault().send(new UserInteractionEvent());
    }

    @Override
    public void onBackPressed() {
        // Back behaves like the recents button so just trigger a toggle event
        EventBus.getDefault().send(new ToggleRecentsEvent());
    }

    /**** EventBus events ****/

    public final void onBusEvent(ToggleRecentsEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (launchState.launchedFromHome) {
            dismissRecentsToHome(true /* animateTaskViews */);
        } else {
            dismissRecentsToLaunchTargetTaskOrHome(true);
        }
    }

    public final void onBusEvent(HideRecentsEvent event) {
        if (event.triggeredFromAltTab) {
            // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
            if (!mIgnoreAltTabRelease) {
                dismissRecentsToFocusedTaskOrHome();
            }
        } else if (event.triggeredFromHomeKey) {
                dismissRecentsToHome(true /* animateTaskViews */);
        } else {
            // Do nothing
        }
    }

    public final void onBusEvent(CancelEnterRecentsWindowAnimationEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        int launchToTaskId = launchState.launchedToTaskId;
        if (launchToTaskId != -1 &&
                (event.launchTask == null || launchToTaskId != event.launchTask.key.id)) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ssp.cancelWindowTransition(launchState.launchedToTaskId);
            ssp.cancelThumbnailTransition(getTaskId());
        }
    }

    public final void onBusEvent(DeleteTaskDataEvent event) {
        // Remove any stored data from the loader
        RecentsTaskLoader loader = Recents.getTaskLoader();
        loader.deleteTaskData(event.task, false);

        // Remove the task from activity manager
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.removeTask(event.task.key.id);
    }

    public final void onBusEvent(AllTaskViewsDismissedEvent event) {
        if (mPipManager.isPipShown()) {
            mRecentsView.showEmptyView();
            mPipRecentsOverlayManager.requestFocus(false);
        } else {
            dismissRecentsToHome(false);
        }
    }

    public final void onBusEvent(LaunchTaskFailedEvent event) {
        // Return to Home
        dismissRecentsToHome(true /* animateTaskViews */);
    }

    @Override
    public boolean onPreDraw() {
        mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
        // Sets the initial values for enter animation.
        // Animation will be started in {@link #onEnterAnimationComplete()}
        if (mLaunchedFromHome) {
            mHomeRecentsEnterExitAnimationHolder
                    .setEnterFromHomeStartingAnimationValues(mPipManager.isPipShown());
        } else {
            mHomeRecentsEnterExitAnimationHolder
                    .setEnterFromAppStartingAnimationValues(mPipManager.isPipShown());
        }
        // We post to make sure that this information is delivered after this traversals is
        // finished.
        mRecentsView.post(new Runnable() {
            @Override
            public void run() {
                Recents.getSystemServices().endProlongedAnimations();
            }
        });
        return true;
    }

    private void updatePipUI() {
        if (!mPipManager.isPipShown()) {
            mPipRecentsOverlayManager.removePipRecentsOverlayView();
            mTaskStackHorizontalGridView.startFocusLossAnimation();
        } else {
            Log.w(TAG, "An activity entered PIP mode while Recents is shown");
        }
    }

    /**
     * Requests the focus to the PIP controls.
     * This starts the relevant recents row animation
     * and give focus to the recents overlay if needed.
     */
    public void requestPipControlsFocus() {
        if (!mPipManager.isPipShown()) {
            return;
        }

        mTaskStackHorizontalGridView.startFocusLossAnimation();
        mPipRecentsOverlayManager.requestFocus(mTaskStackViewAdapter.getItemCount() > 0);
    }
}
