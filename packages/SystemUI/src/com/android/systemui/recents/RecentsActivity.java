/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.app.TaskStackBuilder;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.AppWidgetProviderChangedEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationStartedEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.ui.DismissTaskEvent;
import com.android.systemui.recents.events.ui.ResizeTaskEvent;
import com.android.systemui.recents.events.ui.ShowApplicationInfoEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;

import java.util.ArrayList;

/**
 * The main Recents activity that is started from AlternateRecentsComponent.
 */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks {

    public final static int EVENT_BUS_PRIORITY = Recents.EVENT_BUS_PRIORITY + 1;

    RecentsConfiguration mConfig;
    RecentsPackageMonitor mPackageMonitor;
    long mLastTabKeyEventTime;
    boolean mFinishedOnStartup;

    // Top level views
    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    View mEmptyView;

    // Resize task debug
    RecentsResizeTaskDialog mResizeTaskDebugDialog;

    // Search AppWidget
    AppWidgetProviderInfo mSearchWidgetInfo;
    RecentsAppWidgetHost mAppWidgetHost;
    RecentsAppWidgetHostView mSearchWidgetHostView;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    // Runnable to be executed after we paused ourselves
    Runnable mAfterPauseRunnable;

    /**
     * A common Runnable to finish Recents either by calling finish() (with a custom animation) or
     * launching Home with some ActivityOptions.  Generally we always launch home when we exit
     * Recents rather than just finishing the activity since we don't know what is behind Recents in
     * the task stack.  The only case where we finish() directly is when we are cancelling the full
     * screen transition from the app.
     */
    class FinishRecentsRunnable implements Runnable {
        Intent mLaunchIntent;
        ActivityOptions mLaunchOpts;

        /**
         * Creates a finish runnable that starts the specified intent, using the given
         * ActivityOptions.
         */
        public FinishRecentsRunnable(Intent launchIntent, ActivityOptions opts) {
            mLaunchIntent = launchIntent;
            mLaunchOpts = opts;
        }

        @Override
        public void run() {
            try {
                startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(), UserHandle.CURRENT);
            } catch (Exception e) {
                Console.logError(RecentsActivity.this,
                        getString(R.string.recents_launch_error_message, "Home"));
            }
        }
    }

    /**
     * Broadcast receiver to handle messages from the system
     */
    final BroadcastReceiver mSystemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // When the screen turns off, dismiss Recents to Home
                dismissRecentsToHomeIfVisible(false);
            } else if (action.equals(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED)) {
                // When the search activity changes, update the search widget view
                SystemServicesProxy ssp = Recents.getSystemServices();
                mSearchWidgetInfo = ssp.getOrBindSearchAppWidget(context, mAppWidgetHost);
                refreshSearchWidgetView();
            }
        }
    };

    /** Updates the set of recent tasks */
    void updateRecentsTasks() {
        // If AlternateRecentsComponent has preloaded a load plan, then use that to prevent
        // reconstructing the task stack
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = RecentsImpl.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }

        // Start loading tasks according to the load plan
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        if (!plan.hasTasks()) {
            loader.preloadTasks(plan, launchState.launchedFromHome);
        }
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = launchState.launchedNumVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = launchState.launchedNumVisibleThumbnails;
        loader.loadTasks(this, plan, loadOpts);

        TaskStack stack = plan.getTaskStack();
        launchState.launchedWithNoRecentTasks = !plan.hasTasks();
        if (!launchState.launchedWithNoRecentTasks) {
            mRecentsView.setTaskStack(stack);
        }

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent,
            ActivityOptions.makeCustomAnimation(this,
                    launchState.launchedFromSearchHome ? R.anim.recents_to_search_launcher_enter :
                        R.anim.recents_to_launcher_enter,
                    launchState.launchedFromSearchHome ? R.anim.recents_to_search_launcher_exit :
                        R.anim.recents_to_launcher_exit));

        // Mark the task that is the launch target
        int launchTaskIndexInStack = 0;
        if (launchState.launchedToTaskId != -1) {
            ArrayList<Task> tasks = stack.getTasks();
            int taskCount = tasks.size();
            for (int j = 0; j < taskCount; j++) {
                Task t = tasks.get(j);
                if (t.key.id == launchState.launchedToTaskId) {
                    t.isLaunchTarget = true;
                    launchTaskIndexInStack = tasks.size() - j - 1;
                    break;
                }
            }
        }

        // Update the top level view's visibilities
        if (launchState.launchedWithNoRecentTasks) {
            if (mEmptyView == null) {
                mEmptyView = mEmptyViewStub.inflate();
            }
            mEmptyView.setVisibility(View.VISIBLE);
            mRecentsView.setSearchBarVisibility(View.GONE);
        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
            }
            if (mRecentsView.hasValidSearchBar()) {
                mRecentsView.setSearchBarVisibility(View.VISIBLE);
            } else {
                refreshSearchWidgetView();
            }
        }

        // Animate the SystemUI scrims into view
        mScrimViews.prepareEnterRecentsAnimation();

        // Keep track of whether we launched from the nav bar button or via alt-tab
        if (launchState.launchedWithAltTab) {
            MetricsLogger.count(this, "overview_trigger_alttab", 1);
        } else {
            MetricsLogger.count(this, "overview_trigger_nav_btn", 1);
        }
        // Keep track of whether we launched from an app or from home
        if (launchState.launchedFromAppWithThumbnail) {
            MetricsLogger.count(this, "overview_source_app", 1);
            // If from an app, track the stack index of the app in the stack (for affiliated tasks)
            MetricsLogger.histogram(this, "overview_source_app_index", launchTaskIndexInStack);
        } else {
            MetricsLogger.count(this, "overview_source_home", 1);
        }
        // Keep track of the total stack task count
        int taskCount = stack.getTaskCount();
        MetricsLogger.histogram(this, "overview_task_count", taskCount);
    }

    /** Dismisses recents if we are already visible and the intent is to toggle the recents view */
    boolean dismissRecentsToFocusedTaskOrHome(boolean checkFilteredStackState) {
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // If we currently have filtered stacks, then unfilter those first
            if (checkFilteredStackState &&
                mRecentsView.unfilterFilteredStacks()) return true;
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If we launched from Home, then return to Home
            if (launchState.launchedFromHome) {
                dismissRecentsToHome(true);
                return true;
            }
            // Otherwise, try and return to the Task that Recents was launched from
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true);
            return true;
        }
        return false;
    }

    /**
     * Dismisses Recents directly to Home without checking whether it is currently visible.
     */
    void dismissRecentsToHome(boolean animated) {
        if (animated) {
            ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                    null, mFinishLaunchHomeRunnable, null);
            mRecentsView.startExitToHomeAnimation(
                    new ViewAnimation.TaskViewExitContext(exitTrigger));
        } else {
            mFinishLaunchHomeRunnable.run();
        }
    }

    /** Dismisses Recents directly to Home without transition animation. */
    void dismissRecentsToHomeWithoutTransitionAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    /** Dismisses Recents directly to Home if we currently aren't transitioning. */
    boolean dismissRecentsToHomeIfVisible(boolean animated) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // Return to Home
            dismissRecentsToHome(animated);
            return true;
        }
        return false;
    }

    /** Called with the activity is first created. */
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

        // Register this activity with the event bus
        EventBus.getDefault().register(this, EVENT_BUS_PRIORITY);

        // Initialize the widget host (the host id is static and does not change)
        mConfig = RecentsConfiguration.getInstance();
        mAppWidgetHost = new RecentsAppWidgetHost(this, Constants.Values.App.AppWidgetHostId);
        mPackageMonitor = new RecentsPackageMonitor();
        mPackageMonitor.register(this);

        // Set the Recents layout
        setContentView(R.layout.recents);
        mRecentsView = (RecentsView) findViewById(R.id.recents_view);
        mRecentsView.setCallbacks(this);
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mEmptyViewStub = (ViewStub) findViewById(R.id.empty_view_stub);
        mScrimViews = new SystemBarScrimViews(this);

        // Bind the search app widget when we first start up
        mSearchWidgetInfo = ssp.getOrBindSearchAppWidget(this, mAppWidgetHost);

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
        registerReceiver(mSystemBroadcastReceiver, filter);
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
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        boolean wasLaunchedByAm = !launchState.launchedFromHome &&
                !launchState.launchedFromAppWithThumbnail;
        if (launchState.launchedHasConfigurationChanged || wasLaunchedByAm) {
            EventBus.getDefault().send(new EnterRecentsWindowAnimationStartedEvent());
        }

        if (!launchState.launchedHasConfigurationChanged) {
            mRecentsView.disableLayersForOneFrame();
        }

        // Notify that recents is now visible
        SystemServicesProxy ssp = Recents.getSystemServices();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, ssp, true));

        MetricsLogger.visible(this, MetricsLogger.OVERVIEW_ACTIVITY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAfterPauseRunnable != null) {
            mRecentsView.post(mAfterPauseRunnable);
            mAfterPauseRunnable = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Notify that recents is now hidden
        SystemServicesProxy ssp = Recents.getSystemServices();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, ssp, false));

        // Workaround for b/22542869, if the RecentsActivity is started again, but without going
        // through SystemUI, we need to reset the config launch flags to ensure that we do not
        // wait on the system to send a signal that was never queued.
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        launchState.launchedFromHome = false;
        launchState.launchedFromSearchHome = false;
        launchState.launchedFromAppWithThumbnail = false;
        launchState.launchedToTaskId = -1;
        launchState.launchedWithAltTab = false;
        launchState.launchedHasConfigurationChanged = false;

        MetricsLogger.hidden(this, MetricsLogger.OVERVIEW_ACTIVITY);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // In the case that the activity finished on startup, just skip the unregistration below
        if (mFinishedOnStartup) {
            return;
        }

        // Unregister the system broadcast receivers
        unregisterReceiver(mSystemBroadcastReceiver);

        // Unregister any broadcast receivers for the task loader
        mPackageMonitor.unregister();

        // Stop listening for widget package changes if there was one bound
        mAppWidgetHost.stopListening();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(mScrimViews, EVENT_BUS_PRIORITY);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(mScrimViews);
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
            case KeyEvent.KEYCODE_TAB: {
                int altTabKeyDelay = getResources().getInteger(R.integer.recents_alt_tab_key_delay);
                boolean hasRepKeyTimeElapsed = (SystemClock.elapsedRealtime() -
                        mLastTabKeyEventTime) > altTabKeyDelay;
                if (event.getRepeatCount() <= 0 || hasRepKeyTimeElapsed) {
                    // Focus the next task in the stack
                    final boolean backward = event.isShiftPressed();
                    mRecentsView.focusNextTask(!backward);
                    mLastTabKeyEventTime = SystemClock.elapsedRealtime();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP: {
                mRecentsView.focusNextTask(true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                mRecentsView.focusNextTask(false);
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                mRecentsView.dismissFocusedTask();
                // Keep track of deletions by keyboard
                MetricsLogger.histogram(this, "overview_task_dismissed_source",
                        Constants.Metrics.DismissSourceKeyboard);
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
        // Dismiss Recents to the focused Task or Home
        dismissRecentsToFocusedTaskOrHome(true);
    }

    /**** RecentsResizeTaskDialog ****/

    private RecentsResizeTaskDialog getResizeTaskDebugDialog() {
        if (mResizeTaskDebugDialog == null) {
            mResizeTaskDebugDialog = new RecentsResizeTaskDialog(getFragmentManager(), this);
        }
        return mResizeTaskDebugDialog;
    }

    /**** RecentsView.RecentsViewCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked() {
    }

    @Override
    public void onTaskLaunchFailed() {
        // Return to Home
        dismissRecentsToHome(true);
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mFinishLaunchHomeRunnable.run();
    }

    @Override
    public void runAfterPause(Runnable r) {
        mAfterPauseRunnable = r;
    }

    /**** EventBus events ****/

    public final void onBusEvent(ToggleRecentsEvent event) {
        dismissRecentsToFocusedTaskOrHome(true /* checkFilteredStackState */);
    }

    public final void onBusEvent(HideRecentsEvent event) {
        if (event.triggeredFromAltTab) {
            // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
            dismissRecentsToFocusedTaskOrHome(false /* checkFilteredStackState */);
        } else if (event.triggeredFromHomeKey) {
            // Otherwise, dismiss Recents to Home
            dismissRecentsToHome(true /* checkFilteredStackState */);
        } else {
            // Do nothing
        }
    }

    public final void onBusEvent(EnterRecentsWindowAnimationStartedEvent event) {
        // Try and start the enter animation (or restart it on configuration changed)
        ReferenceCountedTrigger t = new ReferenceCountedTrigger(this, null, null, null);
        ViewAnimation.TaskViewEnterContext ctx = new ViewAnimation.TaskViewEnterContext(t);
        mRecentsView.startEnterRecentsAnimation(ctx);
        if (mSearchWidgetInfo != null) {
            ctx.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    // Start listening for widget package changes if there is one bound
                    if (mAppWidgetHost != null) {
                        mAppWidgetHost.startListening();
                    }
                }
            });
        }
    }

    public final void onBusEvent(AppWidgetProviderChangedEvent event) {
        refreshSearchWidgetView();
    }

    public final void onBusEvent(ShowApplicationInfoEvent event) {
        // Create a new task stack with the application info details activity
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", event.task.key.getComponent().getPackageName(), null));
        intent.setComponent(intent.resolveActivity(getPackageManager()));
        TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(intent).startActivities(null,
                new UserHandle(event.task.key.userId));

        // Keep track of app-info invocations
        MetricsLogger.count(this, "overview_app_info", 1);
    }

    public final void onBusEvent(DismissTaskEvent event) {
        // Remove any stored data from the loader
        RecentsTaskLoader loader = Recents.getTaskLoader();
        loader.deleteTaskData(event.task, false);

        // Remove the task from activity manager
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.removeTask(event.task.key.id);
    }

    public final void onBusEvent(ResizeTaskEvent event) {
        getResizeTaskDebugDialog().showResizeTaskDialog(event.task, mRecentsView);
    }

    public final void onBusEvent(DragStartEvent event) {
        // Lock the orientation while dragging
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        // TODO: docking requires custom accessibility actions
    }

    public final void onBusEvent(DragEndEvent event) {
        // Unlock the orientation when dragging completes
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_BEHIND);
    }

    public final void onBusEvent(ScreenPinningRequestEvent event) {
        MetricsLogger.count(this, "overview_screen_pinned", 1);
    }

    private void refreshSearchWidgetView() {
        if (mSearchWidgetInfo != null) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            int searchWidgetId = ssp.getSearchAppWidgetId(this);
            mSearchWidgetHostView = (RecentsAppWidgetHostView) mAppWidgetHost.createView(
                    this, searchWidgetId, mSearchWidgetInfo);
            Bundle opts = new Bundle();
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
            mSearchWidgetHostView.updateAppWidgetOptions(opts);
            // Set the padding to 0 for this search widget
            mSearchWidgetHostView.setPadding(0, 0, 0, 0);
            mRecentsView.setSearchBar(mSearchWidgetHostView);
        } else {
            mRecentsView.setSearchBar(null);
        }
    }
}
