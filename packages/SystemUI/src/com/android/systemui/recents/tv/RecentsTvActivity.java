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
import com.android.systemui.recents.events.activity.EnterRecentsWindowLastAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.UpdateFreeformTaskViewVisibilityEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.tv.views.RecentsTvView;
import com.android.systemui.recents.tv.views.TaskStackHorizontalViewAdapter;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.tv.pip.PipManager;

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

    private boolean mFinishedOnStartup;
    private RecentsPackageMonitor mPackageMonitor;
    private long mLastTabKeyEventTime;
    private boolean mIgnoreAltTabRelease;

    private RecentsTvView mRecentsView;
    private View mPipView;
    private View mPipShadeView;
    private TaskStackHorizontalViewAdapter mTaskStackViewAdapter;
    private FinishRecentsRunnable mFinishLaunchHomeRunnable;

    private PipManager mPipManager;
    private PipManager.Listener mPipListener = new PipManager.Listener() {
        @Override
        public void onPipEntered() {
            updatePipUI();
        }

        @Override
        public void onPipActivityClosed() {
            updatePipUI();
        }

        @Override
        public void onShowPipMenu() { }

        @Override
        public void onMoveToFullscreen() { }

        @Override
        public void onPipResizeAboutToStart() { }

        @Override
        public void onMediaControllerChanged() { }
    };
    private boolean mHasPip;

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
                RecentsActivityLaunchState launchState =
                        Recents.getConfiguration().getLaunchState();
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(RecentsTvActivity.this,
                        launchState.launchedFromSearchHome ?
                                R.anim.recents_to_search_launcher_enter :
                                R.anim.recents_to_launcher_enter,
                        launchState.launchedFromSearchHome ?
                                R.anim.recents_to_search_launcher_exit :
                                R.anim.recents_to_launcher_exit);
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
            loader.preloadTasks(plan, -1, launchState.launchedFromHome);
        }
        TaskStack stack = plan.getTaskStack();
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = stack.getStackTaskCount();
        loadOpts.numVisibleTaskThumbnails = stack.getStackTaskCount();
        loader.loadTasks(this, plan, loadOpts);


        mRecentsView.setTaskStack(stack);
        List stackTasks = stack.getStackTasks();
        Collections.reverse(stackTasks);
        if (mTaskStackViewAdapter == null) {
            mTaskStackViewAdapter = new TaskStackHorizontalViewAdapter(stackTasks);
            mRecentsView.setTaskStackViewAdapter(mTaskStackViewAdapter);
        } else {
            mTaskStackViewAdapter.setNewStackTasks(stackTasks);
        }

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

    boolean dismissRecentsToLaunchTargetTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true /* animateTaskViews */);
        }
        return false;
    }

    boolean dismissRecentsToFocusedTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true /* animateTaskViews */);
            return true;
        }
        return false;
    }

    void dismissRecentsToHome(boolean animateTaskViews) {
        DismissRecentsToHomeAnimationStarted dismissEvent =
                new DismissRecentsToHomeAnimationStarted(animateTaskViews);
        dismissEvent.addPostAnimationCallback(mFinishLaunchHomeRunnable);
        dismissEvent.addPostAnimationCallback(new Runnable() {
            @Override
            public void run() {
                Recents.getSystemServices().sendCloseSystemWindows(
                        BaseStatusBar.SYSTEM_DIALOG_REASON_HOME_KEY);
            }
        });
        EventBus.getDefault().send(dismissEvent);
    }

    boolean dismissRecentsToHomeIfVisible(boolean animated) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
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
        mPipManager = PipManager.getInstance();

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
        mPipShadeView = findViewById(R.id.pip_shade);
        getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent);

        mHasPip = false;
        updatePipUI();
        mPipManager.addListener(mPipListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Update the recent tasks
        updateRecentsTasks();

        // If this is a new instance from a configuration change, then we have to manually trigger
        // the enter animation state, or if recents was relaunched by AM, without going through
        // the normal mechanisms
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        boolean wasLaunchedByAm = !launchState.launchedFromHome &&
                !launchState.launchedFromApp;
        if (launchState.launchedHasConfigurationChanged || wasLaunchedByAm) {
            EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());
        }

        // Notify that recents is now visible
        SystemServicesProxy ssp = Recents.getSystemServices();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, true));

        mPipManager.onRecentsStarted();
        // Give focus to the recents row whenever its visible to an user.
        mRecentsView.requestFocus();
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());
    }

    @Override
    protected void onStop() {
        super.onStop();

        mPipManager.onRecentsStopped();
        mIgnoreAltTabRelease = false;
        // Notify that recents is now hidden
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, false));

        // Workaround for b/22542869, if the RecentsActivity is started again, but without going
        // through SystemUI, we need to reset the config launch flags to ensure that we do not
        // wait on the system to send a signal that was never queued.
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        launchState.reset();
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
            dismissRecentsToLaunchTargetTaskOrHome();
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

    public final void onBusEvent(EnterRecentsWindowLastAnimationFrameEvent event) {
        EventBus.getDefault().send(new UpdateFreeformTaskViewVisibilityEvent(true));
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
        mRecentsView.invalidate();
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
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasDockedTask()) {
            mRecentsView.showEmptyView();
        } else {
            // Just go straight home (no animation necessary because there are no more task views)
            dismissRecentsToHome(false /* animateTaskViews */);
        }
    }

    public final void onBusEvent(LaunchTaskFailedEvent event) {
        // Return to Home
        dismissRecentsToHome(true /* animateTaskViews */);
    }

    @Override
    public boolean onPreDraw() {
        mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
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
        if (mHasPip == mPipManager.isPipShown()) {
            return;
        }
        mHasPip = mPipManager.isPipShown();
        if (mHasPip) {
            // Place mPipView at the PIP bounds for fine tuned focus handling.
            Rect pipBounds = mPipManager.getPipBounds();
            LayoutParams lp = (LayoutParams) mPipView.getLayoutParams();
            lp.width = pipBounds.width();
            lp.height = pipBounds.height();
            lp.leftMargin = pipBounds.left;
            lp.topMargin = pipBounds.top;
            mPipView.setLayoutParams(lp);

            mPipView.setVisibility(View.VISIBLE);
            mPipView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mPipManager.resizePinnedStack(PipManager.STATE_PIP_MENU);
                }
            });
            mPipView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    mPipManager.onPipViewFocusChangedInRecents(hasFocus);
                    mPipShadeView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
                }
            });
            mPipShadeView.setVisibility(View.GONE);
        } else {
            mPipView.setVisibility(View.GONE);
            mPipShadeView.setVisibility(View.GONE);
        }
    }
}
