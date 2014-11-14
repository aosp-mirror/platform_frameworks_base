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
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.recents.misc.DebugTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.DebugOverlayView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.SystemUIApplication;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * The main Recents activity that is started from AlternateRecentsComponent.
 */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks,
        DebugOverlayView.DebugOverlayViewCallbacks {

    RecentsConfiguration mConfig;
    boolean mVisible;
    long mLastTabKeyEventTime;

    // Top level views
    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    ViewStub mDebugOverlayStub;
    View mEmptyView;
    DebugOverlayView mDebugOverlay;

    // Search AppWidget
    RecentsAppWidgetHost mAppWidgetHost;
    AppWidgetProviderInfo mSearchAppWidgetInfo;
    AppWidgetHostView mSearchAppWidgetHostView;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    private PhoneStatusBar mStatusBar;

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
            // Mark Recents as no longer visible
            onRecentsActivityVisibilityChanged(false);
            // Finish Recents
            if (mLaunchIntent != null) {
                if (mLaunchOpts != null) {
                    startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(), UserHandle.CURRENT);
                } else {
                    startActivityAsUser(mLaunchIntent, UserHandle.CURRENT);
                }
            } else {
                finish();
                overridePendingTransition(R.anim.recents_to_launcher_enter,
                        R.anim.recents_to_launcher_exit);
            }
        }
    }

    /**
     * Broadcast receiver to handle messages from AlternateRecentsComponent.
     */
    final BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AlternateRecentsComponent.ACTION_HIDE_RECENTS_ACTIVITY)) {
                // Mark Recents as no longer visible
                AlternateRecentsComponent.notifyVisibilityChanged(false);
                if (intent.getBooleanExtra(AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false)) {
                    // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
                    dismissRecentsToFocusedTaskOrHome(false);
                } else if (intent.getBooleanExtra(AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_HOME_KEY, false)) {
                    // Otherwise, dismiss Recents to Home
                    dismissRecentsToHome(true);
                } else {
                    // Do nothing, another activity is being launched on top of Recents
                }
            } else if (action.equals(AlternateRecentsComponent.ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // If we are toggling Recents, then first unfilter any filtered stacks first
                dismissRecentsToFocusedTaskOrHome(true);
            } else if (action.equals(AlternateRecentsComponent.ACTION_START_ENTER_ANIMATION)) {
                // Trigger the enter animation
                onEnterAnimationTriggered();
                // Notify the fallback receiver that we have successfully got the broadcast
                // See AlternateRecentsComponent.onAnimationStarted()
                setResultCode(Activity.RESULT_OK);
            }
        }
    };

    /**
     * Broadcast receiver to handle messages from the system
     */
    final BroadcastReceiver mSystemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // When the screen turns off, dismiss Recents to Home
                dismissRecentsToHome(false);
                // Preload the metadata for all tasks in the background
                RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
                RecentsTaskLoadPlan plan = loader.createLoadPlan(context);
                loader.preloadTasks(plan, true /* isTopTaskHome */);
            } else if (action.equals(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED)) {
                // When the search activity changes, update the Search widget
                refreshSearchWidget();
            }
        }
    };

    /**
     * A custom debug trigger to listen for a debug key chord.
     */
    final DebugTrigger mDebugTrigger = new DebugTrigger(new Runnable() {
        @Override
        public void run() {
            onDebugModeTriggered();
        }
    });

    /** Updates the set of recent tasks */
    void updateRecentsTasks(Intent launchIntent) {
        // Update the configuration based on the launch intent
        boolean fromSearchHome = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_SEARCH_HOME, false);
        int numVisibleTasks = launchIntent.getIntExtra(
                AlternateRecentsComponent.EXTRA_NUM_VISIBLE_TASKS, 0);
        int numVisibleThumbnails = launchIntent.getIntExtra(
                AlternateRecentsComponent.EXTRA_NUM_VISIBLE_THUMBNAILS, 0);
        mConfig.launchedFromHome = fromSearchHome || launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_HOME, false);
        mConfig.launchedFromAppWithThumbnail = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_APP_THUMBNAIL, false);
        mConfig.launchedToTaskId = launchIntent.getIntExtra(
                AlternateRecentsComponent.EXTRA_FROM_TASK_ID, -1);
        mConfig.launchedWithAltTab = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false);
        mConfig.launchedReuseTaskStackViews = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_REUSE_TASK_STACK_VIEWS, false);

        // If AlternateRecentsComponent has preloaded a load plan, then use that to prevent
        // reconstructing the task stack
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        RecentsTaskLoadPlan plan = AlternateRecentsComponent.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }

        // Start loading tasks according to the load plan
        if (plan.getTaskStack() == null) {
            loader.preloadTasks(plan, mConfig.launchedFromHome);
        }
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = mConfig.launchedToTaskId;
        loadOpts.numVisibleTasks = numVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = numVisibleThumbnails;
        loader.loadTasks(this, plan, loadOpts);

        SpaceNode root = plan.getSpaceNode();
        ArrayList<TaskStack> stacks = root.getStacks();
        boolean hasTasks = root.hasTasks();
        if (hasTasks) {
            mRecentsView.setTaskStacks(stacks);
        }
        mConfig.launchedWithNoRecentTasks = !hasTasks;

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent,
            ActivityOptions.makeCustomAnimation(this,
                fromSearchHome ? R.anim.recents_to_search_launcher_enter :
                        R.anim.recents_to_launcher_enter,
                fromSearchHome ? R.anim.recents_to_search_launcher_exit :
                        R.anim.recents_to_launcher_exit));

        // Mark the task that is the launch target
        int taskStackCount = stacks.size();
        if (mConfig.launchedToTaskId != -1) {
            for (int i = 0; i < taskStackCount; i++) {
                TaskStack stack = stacks.get(i);
                ArrayList<Task> tasks = stack.getTasks();
                int taskCount = tasks.size();
                for (int j = 0; j < taskCount; j++) {
                    Task t = tasks.get(j);
                    if (t.key.id == mConfig.launchedToTaskId) {
                        t.isLaunchTarget = true;
                        break;
                    }
                }
            }
        }

        // Update the top level view's visibilities
        if (mConfig.launchedWithNoRecentTasks) {
            if (mEmptyView == null) {
                mEmptyView = mEmptyViewStub.inflate();
            }
            mEmptyView.setVisibility(View.VISIBLE);
            mRecentsView.setSearchBarVisibility(View.GONE);
        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
            }
            if (mRecentsView.hasSearchBar()) {
                mRecentsView.setSearchBarVisibility(View.VISIBLE);
            } else {
                addSearchBarAppWidgetView();
            }
        }

        // Animate the SystemUI scrims into view
        mScrimViews.prepareEnterRecentsAnimation();
    }

    /** Attempts to allocate and bind the search bar app widget */
    void bindSearchBarAppWidget() {
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();

            // Reset the host view and widget info
            mSearchAppWidgetHostView = null;
            mSearchAppWidgetInfo = null;

            // Try and load the app widget id from the settings
            int appWidgetId = mConfig.searchBarAppWidgetId;
            if (appWidgetId >= 0) {
                mSearchAppWidgetInfo = ssp.getAppWidgetInfo(appWidgetId);
                if (mSearchAppWidgetInfo == null) {
                    // If there is no actual widget associated with that id, then delete it and
                    // prepare to bind another app widget in its place
                    ssp.unbindSearchAppWidget(mAppWidgetHost, appWidgetId);
                    appWidgetId = -1;
                }
            }

            // If there is no id, then bind a new search app widget
            if (appWidgetId < 0) {
                Pair<Integer, AppWidgetProviderInfo> widgetInfo =
                        ssp.bindSearchAppWidget(mAppWidgetHost);
                if (widgetInfo != null) {
                    // Save the app widget id into the settings
                    mConfig.updateSearchBarAppWidgetId(this, widgetInfo.first);
                    mSearchAppWidgetInfo = widgetInfo.second;
                }
            }
        }
    }

    /** Creates the search bar app widget view */
    void addSearchBarAppWidgetView() {
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            int appWidgetId = mConfig.searchBarAppWidgetId;
            if (appWidgetId >= 0) {
                mSearchAppWidgetHostView = mAppWidgetHost.createView(this, appWidgetId,
                        mSearchAppWidgetInfo);
                Bundle opts = new Bundle();
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
                mSearchAppWidgetHostView.updateAppWidgetOptions(opts);
                // Set the padding to 0 for this search widget
                mSearchAppWidgetHostView.setPadding(0, 0, 0, 0);
                mRecentsView.setSearchBar(mSearchAppWidgetHostView);
            } else {
                mRecentsView.setSearchBar(null);
            }
        }
    }

    /** Dismisses recents if we are already visible and the intent is to toggle the recents view */
    boolean dismissRecentsToFocusedTaskOrHome(boolean checkFilteredStackState) {
        if (mVisible) {
            // If we currently have filtered stacks, then unfilter those first
            if (checkFilteredStackState &&
                mRecentsView.unfilterFilteredStacks()) return true;
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If we launched from Home, then return to Home
            if (mConfig.launchedFromHome) {
                dismissRecentsToHomeRaw(true);
                return true;
            }
            // Otherwise, try and return to the Task that Recents was launched from
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHomeRaw(true);
            return true;
        }
        return false;
    }

    /** Dismisses Recents directly to Home. */
    void dismissRecentsToHomeRaw(boolean animated) {
        if (animated) {
            ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                    null, mFinishLaunchHomeRunnable, null);
            mRecentsView.startExitToHomeAnimation(
                    new ViewAnimation.TaskViewExitContext(exitTrigger));
        } else {
            mFinishLaunchHomeRunnable.run();
        }
    }

    /** Dismisses Recents directly to Home if we currently aren't transitioning. */
    boolean dismissRecentsToHome(boolean animated) {
        if (mVisible) {
            // Return to Home
            dismissRecentsToHomeRaw(animated);
            return true;
        }
        return false;
    }

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // For the non-primary user, ensure that the SystemSericesProxy is initialized
        RecentsTaskLoader.initialize(this);

        // Initialize the loader and the configuration
        mConfig = RecentsConfiguration.reinitialize(this,
                RecentsTaskLoader.getInstance().getSystemServicesProxy());

        // Initialize the widget host (the host id is static and does not change)
        mAppWidgetHost = new RecentsAppWidgetHost(this, Constants.Values.App.AppWidgetHostId);

        // Set the Recents layout
        setContentView(R.layout.recents);
        mRecentsView = (RecentsView) findViewById(R.id.recents_view);
        mRecentsView.setCallbacks(this);
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mEmptyViewStub = (ViewStub) findViewById(R.id.empty_view_stub);
        mDebugOverlayStub = (ViewStub) findViewById(R.id.debug_overlay_stub);
        mScrimViews = new SystemBarScrimViews(this, mConfig);
        inflateDebugOverlay();

        // Bind the search app widget when we first start up
        bindSearchBarAppWidget();
        // Update the recent tasks
        updateRecentsTasks(getIntent());

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
        registerReceiver(mSystemBroadcastReceiver, filter);

        // Private API calls to make the shadows look better
        try {
            Utilities.setShadowProperty("ambientRatio", String.valueOf(1.5f));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        // Update if we are getting a configuration change
        if (savedInstanceState != null) {
            // Update RecentsConfiguration
            mConfig = RecentsConfiguration.reinitialize(this,
                    RecentsTaskLoader.getInstance().getSystemServicesProxy());
            mConfig.updateOnConfigurationChange();
            // Trigger the enter animation
            onEnterAnimationTriggered();
        }

        // Start listening for widget package changes if there is one bound, post it since we don't
        // want it stalling the startup
        if (mConfig.searchBarAppWidgetId >= 0) {
            final WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks> callback =
                    new WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks>(this);
            mRecentsView.post(new Runnable() {
                @Override
                public void run() {
                    RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks cb = callback.get();
                    if (cb != null) {
                        mAppWidgetHost.startListening(cb);
                    }
                }
            });
        }

        mStatusBar = ((SystemUIApplication) getApplication())
                .getComponent(PhoneStatusBar.class);
    }

    /** Inflates the debug overlay if debug mode is enabled. */
    void inflateDebugOverlay() {
        if (!Constants.DebugFlags.App.EnableDebugMode) return;

        if (mConfig.debugModeEnabled && mDebugOverlay == null) {
            // Inflate the overlay and seek bars
            mDebugOverlay = (DebugOverlayView) mDebugOverlayStub.inflate();
            mDebugOverlay.setCallbacks(this);
            mRecentsView.setDebugOverlay(mDebugOverlay);
        }
    }

    /** Handles changes to the activity visibility. */
    void onRecentsActivityVisibilityChanged(boolean visible) {
        if (!visible) {
            AlternateRecentsComponent.notifyVisibilityChanged(visible);
        }
        mVisible = visible;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Reinitialize the configuration
        RecentsConfiguration.reinitialize(this, RecentsTaskLoader.getInstance().getSystemServicesProxy());

        // Clear any debug rects
        if (mDebugOverlay != null) {
            mDebugOverlay.clear();
        }

        // Update the recent tasks
        updateRecentsTasks(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(AlternateRecentsComponent.ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(AlternateRecentsComponent.ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(AlternateRecentsComponent.ACTION_START_ENTER_ANIMATION);
        registerReceiver(mServiceBroadcastReceiver, filter);

        // Register any broadcast receivers for the task loader
        RecentsTaskLoader.getInstance().registerReceivers(this, mRecentsView);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Mark Recents as visible
        onRecentsActivityVisibilityChanged(true);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Notify the views that we are no longer visible
        mRecentsView.onRecentsHidden();

        // Unregister the RecentsService receiver
        unregisterReceiver(mServiceBroadcastReceiver);

        // Unregister any broadcast receivers for the task loader
        RecentsTaskLoader.getInstance().unregisterReceivers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister the system broadcast receivers
        unregisterReceiver(mSystemBroadcastReceiver);

        // Stop listening for widget package changes if there was one bound
        if (mAppWidgetHost.isListening()) {
            mAppWidgetHost.stopListening();
        }
    }

    public void onEnterAnimationTriggered() {
        // Try and start the enter animation (or restart it on configuration changed)
        ReferenceCountedTrigger t = new ReferenceCountedTrigger(this, null, null, null);
        mRecentsView.startEnterRecentsAnimation(new ViewAnimation.TaskViewEnterContext(t));

        // Animate the SystemUI scrim views
        mScrimViews.startEnterRecentsAnimation();
    }

    @Override
    public void onTrimMemory(int level) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        if (loader != null) {
            loader.onTrimMemory(level);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB: {
                boolean hasRepKeyTimeElapsed = (System.currentTimeMillis() -
                        mLastTabKeyEventTime) > mConfig.altTabKeyDelay;
                if (event.getRepeatCount() <= 0 || hasRepKeyTimeElapsed) {
                    // Focus the next task in the stack
                    final boolean backward = event.isShiftPressed();
                    mRecentsView.focusNextTask(!backward);
                    mLastTabKeyEventTime = System.currentTimeMillis();
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
                return true;
            }
            default:
                break;
        }
        // Pass through the debug trigger
        mDebugTrigger.onKeyEvent(keyCode);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        mRecentsView.onUserInteraction();
    }

    @Override
    public void onBackPressed() {
        // Test mode where back does not do anything
        if (mConfig.debugModeEnabled) return;

        // Dismiss Recents to the focused Task or Home
        dismissRecentsToFocusedTaskOrHome(true);
    }

    /** Called when debug mode is triggered */
    public void onDebugModeTriggered() {
        if (mConfig.developerOptionsEnabled) {
            SharedPreferences settings = getSharedPreferences(getPackageName(), 0);
            if (settings.getBoolean(Constants.Values.App.Key_DebugModeEnabled, false)) {
                // Disable the debug mode
                settings.edit().remove(Constants.Values.App.Key_DebugModeEnabled).apply();
                mConfig.debugModeEnabled = false;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.disable();
                }
            } else {
                // Enable the debug mode
                settings.edit().putBoolean(Constants.Values.App.Key_DebugModeEnabled, true).apply();
                mConfig.debugModeEnabled = true;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.enable();
                }
            }
            Toast.makeText(this, "Debug mode (" + Constants.Values.App.DebugModeVersion + ") " +
                (mConfig.debugModeEnabled ? "Enabled" : "Disabled") + ", please restart Recents now",
                Toast.LENGTH_SHORT).show();
        }
    }

    /**** RecentsView.RecentsViewCallbacks Implementation ****/

    @Override
    public void onExitToHomeAnimationTriggered() {
        // Animate the SystemUI scrim views out
        mScrimViews.startExitRecentsAnimation();
    }

    @Override
    public void onTaskViewClicked() {
        // Mark recents as no longer visible
        onRecentsActivityVisibilityChanged(false);
    }

    @Override
    public void onTaskLaunchFailed() {
        // Return to Home
        dismissRecentsToHomeRaw(true);
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mFinishLaunchHomeRunnable.run();
    }

    @Override
    public void onScreenPinningRequest() {
        if (mStatusBar != null) {
            mStatusBar.showScreenPinningRequest(false);
        }
    }

    /**** RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks Implementation ****/

    @Override
    public void refreshSearchWidget() {
        bindSearchBarAppWidget();
        addSearchBarAppWidgetView();
    }

    /**** DebugOverlayView.DebugOverlayViewCallbacks ****/

    @Override
    public void onPrimarySeekBarChanged(float progress) {
        // Do nothing
    }

    @Override
    public void onSecondarySeekBarChanged(float progress) {
        // Do nothing
    }
}
