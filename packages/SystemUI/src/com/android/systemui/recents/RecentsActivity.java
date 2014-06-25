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
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import com.android.systemui.R;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.FullscreenTransitionOverlayView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/* Activity */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks,
        FullscreenTransitionOverlayView.FullScreenTransitionViewCallbacks {

    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    View mEmptyView;
    ViewStub mFullscreenOverlayStub;
    FullscreenTransitionOverlayView mFullScreenOverlayView;

    RecentsConfiguration mConfig;

    RecentsAppWidgetHost mAppWidgetHost;
    AppWidgetProviderInfo mSearchAppWidgetInfo;
    AppWidgetHostView mSearchAppWidgetHostView;

    boolean mVisible;
    boolean mTaskLaunched;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishRunnable = new FinishRecentsRunnable(true);
    FinishRecentsRunnable mFinishWithoutAnimationRunnable = new FinishRecentsRunnable(false);
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    /**
     * A Runnable to finish Recents either with/without a transition, and either by calling finish()
     * or just launching the specified intent.
     */
    class FinishRecentsRunnable implements Runnable {
        boolean mUseCustomFinishTransition;
        Intent mLaunchIntent;
        ActivityOptions mLaunchOpts;

        public FinishRecentsRunnable(boolean withTransition) {
            mUseCustomFinishTransition = withTransition;
        }

        public FinishRecentsRunnable(Intent launchIntent, ActivityOptions opts) {
            mLaunchIntent = launchIntent;
            mLaunchOpts = opts;
        }

        @Override
        public void run() {
            // Mark Recents as no longer visible
            AlternateRecentsComponent.notifyVisibilityChanged(false);
            // Finish Recents
            if (mLaunchIntent != null) {
                if (mLaunchOpts != null) {
                    startActivityAsUser(mLaunchIntent, new UserHandle(UserHandle.USER_CURRENT));
                } else {
                    startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(),
                            new UserHandle(UserHandle.USER_CURRENT));
                }
            } else {
                finish();
                if (mUseCustomFinishTransition) {
                    overridePendingTransition(R.anim.recents_to_launcher_enter,
                            R.anim.recents_to_launcher_exit);
                }
            }
        }
    }

    // Broadcast receiver to handle messages from our RecentsService
    BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Console.Enabled) {
                Console.log(Constants.Log.App.SystemUIHandshake,
                        "[RecentsActivity|serviceBroadcast]", action, Console.AnsiRed);
            }
            if (action.equals(RecentsService.ACTION_HIDE_RECENTS_ACTIVITY)) {
                if (intent.getBooleanExtra(RecentsService.EXTRA_TRIGGERED_FROM_ALT_TAB, false)) {
                    // Dismiss recents, launching the focused task
                    dismissRecentsIfVisible();
                } else {
                    // If we are mid-animation into Recents, then reverse it and finish
                    if (mFullScreenOverlayView == null ||
                            !mFullScreenOverlayView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
                        // Otherwise, either finish Recents, or launch Home directly
                        ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(context,
                                null, mFinishLaunchHomeRunnable, null);
                        mRecentsView.startExitToHomeAnimation(
                                new ViewAnimation.TaskViewExitContext(exitTrigger));
                    }
                }
            } else if (action.equals(RecentsService.ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // Try and unfilter and filtered stacks
                if (!mRecentsView.unfilterFilteredStacks()) {
                    // If there are no filtered stacks, dismiss recents and launch the first task
                    dismissRecentsIfVisible();
                }
            } else if (action.equals(RecentsService.ACTION_START_ENTER_ANIMATION)) {
                // Try and start the enter animation (or restart it on configuration changed)
                mRecentsView.startEnterRecentsAnimation(new ViewAnimation.TaskViewEnterContext(mFullScreenOverlayView));
                // Call our callback
                onEnterAnimationTriggered();
            }
        }
    };

    // Broadcast receiver to handle messages from the system
    BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mFinishWithoutAnimationRunnable.run();
        }
    };

    /** Updates the set of recent tasks */
    void updateRecentsTasks(Intent launchIntent) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SpaceNode root = loader.reload(this, Constants.Values.RecentsTaskLoader.PreloadFirstTasksCount);
        ArrayList<TaskStack> stacks = root.getStacks();
        if (!stacks.isEmpty()) {
            mRecentsView.setBSP(root);
        }

        // Update the configuration based on the launch intent
        mConfig.launchedFromHome = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_HOME, false);
        mConfig.launchedFromAppWithThumbnail = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_APP_THUMBNAIL, false);
        mConfig.launchedFromAppWithScreenshot = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_APP_FULL_SCREENSHOT, false);
        mConfig.launchedWithAltTab = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false);
        mConfig.launchedWithNoRecentTasks = !root.hasTasks();

        // Show the scrim if we animate into Recents without window transitions
        mScrimViews.prepareEnterRecentsAnimation();

        // Add the default no-recents layout
        if (mEmptyView == null) {
            mEmptyView = mEmptyViewStub.inflate();
        }
        if (mConfig.launchedWithNoRecentTasks) {
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.GONE);
        }
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
                if (Console.Enabled) {
                    Console.log(Constants.Log.App.SystemUIHandshake,
                            "[RecentsActivity|onCreate|settings|appWidgetId]",
                            "Id: " + appWidgetId,
                            Console.AnsiBlue);
                }
            }

            // If there is no id, then bind a new search app widget
            if (appWidgetId < 0) {
                Pair<Integer, AppWidgetProviderInfo> widgetInfo =
                        ssp.bindSearchAppWidget(mAppWidgetHost);
                if (widgetInfo != null) {
                    if (Console.Enabled) {
                        Console.log(Constants.Log.App.SystemUIHandshake,
                                "[RecentsActivity|onCreate|searchWidget]",
                                "Id: " + widgetInfo.first + " Info: " + widgetInfo.second,
                                Console.AnsiBlue);
                    }

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
                if (Console.Enabled) {
                    Console.log(Constants.Log.App.SystemUIHandshake,
                            "[RecentsActivity|onCreate|addSearchAppWidgetView]",
                            "Id: " + appWidgetId,
                            Console.AnsiBlue);
                }
                mSearchAppWidgetHostView = mAppWidgetHost.createView(this, appWidgetId,
                        mSearchAppWidgetInfo);
                Bundle opts = new Bundle();
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_RECENTS);
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
    boolean dismissRecentsIfVisible() {
        if (mVisible) {
            // If we are mid-animation into Recents, then reverse it and finish
            if (mFullScreenOverlayView == null ||
                    !mFullScreenOverlayView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
                // If we have a focused task, then launch that task
                if (!mRecentsView.launchFocusedTask()) {
                    if (mConfig.launchedFromHome) {
                        // Just start the animation out of recents
                        ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                                null, mFinishLaunchHomeRunnable, null);
                        mRecentsView.startExitToHomeAnimation(
                                new ViewAnimation.TaskViewExitContext(exitTrigger));
                    } else {
                        // Otherwise, try and launch the first task
                        if (!mRecentsView.launchFirstTask()) {
                            // If there are no tasks, then just finish recents
                            mFinishLaunchHomeRunnable.run();
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Console.Enabled) {
            Console.logDivider(Constants.Log.App.SystemUIHandshake);
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onCreate]",
                    getIntent().getAction() + " visible: " + mVisible, Console.AnsiRed);
            Console.logTraceTime(Constants.Log.App.TimeRecentsStartup,
                    Constants.Log.App.TimeRecentsStartupKey, "onCreate");
        }

        // Initialize the loader and the configuration
        RecentsTaskLoader.initialize(this);
        mConfig = RecentsConfiguration.reinitialize(this);

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent,
                ActivityOptions.makeCustomAnimation(this, R.anim.recents_to_launcher_enter,
                        R.anim.recents_to_launcher_exit));

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
        mFullscreenOverlayStub = (ViewStub) findViewById(R.id.fullscreen_overlay_stub);
        mScrimViews = new SystemBarScrimViews(this, mConfig);

        // Update the recent tasks
        updateRecentsTasks(getIntent());

        // Prepare the screenshot transition if necessary
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenOverlayView = (FullscreenTransitionOverlayView) mFullscreenOverlayStub.inflate();
            mFullScreenOverlayView.setCallbacks(this);
            mFullScreenOverlayView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
        }

        // Bind the search app widget when we first start up
        bindSearchBarAppWidget();
        // Add the search bar layout
        addSearchBarAppWidgetView();

        // Update if we are getting a configuration change
        if (savedInstanceState != null) {
            onConfigurationChange();
        }

        // Private API calls to make the shadows look better
        try {
            Utilities.setShadowProperty("ambientShadowStrength", String.valueOf(35f));
            Utilities.setShadowProperty("ambientRatio", String.valueOf(0.5f));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    void onConfigurationChange() {
        // Try and start the enter animation (or restart it on configuration changed)
        mRecentsView.startEnterRecentsAnimation(new ViewAnimation.TaskViewEnterContext(mFullScreenOverlayView));
        // Call our callback
        onEnterAnimationTriggered();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Reset the task launched flag if we encounter an onNewIntent() before onStop()
        mTaskLaunched = false;

        if (Console.Enabled) {
            Console.logDivider(Constants.Log.App.SystemUIHandshake);
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onNewIntent]",
                    intent.getAction() + " visible: " + mVisible, Console.AnsiRed);
            Console.logTraceTime(Constants.Log.App.TimeRecentsStartup,
                    Constants.Log.App.TimeRecentsStartupKey, "onNewIntent");
        }

        // Initialize the loader and the configuration
        RecentsTaskLoader.initialize(this);
        mConfig = RecentsConfiguration.reinitialize(this);

        // Update the recent tasks
        updateRecentsTasks(intent);

        // Prepare the screenshot transition if necessary
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenOverlayView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
        }

        // Don't attempt to rebind the search bar widget, but just add the search bar layout
        addSearchBarAppWidgetView();
    }

    @Override
    protected void onStart() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onStart]", "",
                    Console.AnsiRed);
        }
        super.onStart();

        mVisible = true;
    }

    @Override
    protected void onResume() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onResume]", "",
                    Console.AnsiRed);
        }
        super.onResume();
    }

    @Override
    public void onAttachedToWindow() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake,
                    "[RecentsActivity|onAttachedToWindow]", "",
                    Console.AnsiRed);
        }
        super.onAttachedToWindow();

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(RecentsService.ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(RecentsService.ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(RecentsService.ACTION_START_ENTER_ANIMATION);
        registerReceiver(mServiceBroadcastReceiver, filter);

        // Register the broadcast receiver to handle messages when the screen is turned off
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenOffReceiver, filter);

        // Register any broadcast receivers for the task loader
        RecentsTaskLoader.getInstance().registerReceivers(this, mRecentsView);

        // Start listening for widget package changes if there is one bound
        if (mConfig.searchBarAppWidgetId >= 0) {
            mAppWidgetHost.startListening(this);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake,
                    "[RecentsActivity|onDetachedFromWindow]", "",
                    Console.AnsiRed);
        }
        super.onDetachedFromWindow();

        // Unregister any broadcast receivers we have registered
        unregisterReceiver(mServiceBroadcastReceiver);
        unregisterReceiver(mScreenOffReceiver);
        RecentsTaskLoader.getInstance().unregisterReceivers();

        // Stop listening for widget package changes if there was one bound
        if (mConfig.searchBarAppWidgetId >= 0) {
            mAppWidgetHost.stopListening();
        }
    }

    @Override
    protected void onPause() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onPause]", "",
                    Console.AnsiRed);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onStop]", "",
                    Console.AnsiRed);
        }
        super.onStop();

        mVisible = false;
        mTaskLaunched = false;
    }

    @Override
    protected void onDestroy() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.SystemUIHandshake, "[RecentsActivity|onDestroy]", "",
                    Console.AnsiRed);
        }
        super.onDestroy();
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
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            // Focus the next task in the stack
            final boolean backward = event.isShiftPressed();
            mRecentsView.focusNextTask(!backward);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        mRecentsView.onUserInteraction();
    }

    @Override
    public void onBackPressed() {
        // If we are mid-animation into Recents, then reverse it and finish
        if (mFullScreenOverlayView == null ||
                !mFullScreenOverlayView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
            // If we are currently filtering in any stacks, unfilter them first
            if (!mRecentsView.unfilterFilteredStacks()) {
                if (mConfig.launchedFromHome) {
                    // Just start the animation out of recents
                    ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                            null, mFinishLaunchHomeRunnable, null);
                    mRecentsView.startExitToHomeAnimation(
                            new ViewAnimation.TaskViewExitContext(exitTrigger));
                } else {
                    // Otherwise, try and launch the first task
                    if (!mRecentsView.launchFirstTask()) {
                        // If there are no tasks, then just finish recents
                        mFinishLaunchHomeRunnable.run();
                    }
                }
            }
        }
    }

    public void onEnterAnimationTriggered() {
        // Animate the scrims in
        mScrimViews.startEnterRecentsAnimation();
    }

    /**** FullscreenTransitionOverlayView.FullScreenTransitionViewCallbacks Implementation ****/

    @Override
    public void onEnterAnimationComplete(boolean canceled) {
        if (!canceled) {
            // Reset the full screenshot transition view
            if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
                mFullScreenOverlayView.reset();
            }

            // XXX: We should clean up the screenshot in this case as well, but it needs to happen
            //      after to animate up
        }
        // Recycle the full screen screenshot
        AlternateRecentsComponent.consumeLastScreenshot();
    }

    /**** RecentsView.RecentsViewCallbacks Implementation ****/

    @Override
    public void onExitToHomeAnimationTriggered() {
        // Animate the scrims out
        mScrimViews.startExitRecentsAnimation();
    }

    @Override
    public void onTaskLaunching() {
        mTaskLaunched = true;

        // Mark recents as no longer visible
        AlternateRecentsComponent.notifyVisibilityChanged(false);
    }

    /**** RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks Implementation ****/

    @Override
    public void refreshSearchWidget() {
        // Load the Search widget again
        bindSearchBarAppWidget();
        addSearchBarAppWidgetView();
    }
}
