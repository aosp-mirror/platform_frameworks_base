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
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.DebugTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.FullscreenTransitionOverlayView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * The main Recents activity that is started from AlternateRecentsComponent.
 */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks,
        FullscreenTransitionOverlayView.FullScreenTransitionViewCallbacks {

    // Actions and Extras sent from AlternateRecentsComponent
    final static String EXTRA_TRIGGERED_FROM_ALT_TAB = "extra_triggered_from_alt_tab";
    final static String ACTION_START_ENTER_ANIMATION = "action_start_enter_animation";
    final static String ACTION_TOGGLE_RECENTS_ACTIVITY = "action_toggle_recents_activity";
    final static String ACTION_HIDE_RECENTS_ACTIVITY = "action_hide_recents_activity";

    RecentsConfiguration mConfig;
    boolean mVisible;

    // Top level views
    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    View mEmptyView;
    ViewStub mFullscreenOverlayStub;
    FullscreenTransitionOverlayView mFullScreenOverlayView;

    // Search AppWidget
    RecentsAppWidgetHost mAppWidgetHost;
    AppWidgetProviderInfo mSearchAppWidgetInfo;
    AppWidgetHostView mSearchAppWidgetHostView;


    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishRunnable = new FinishRecentsRunnable();
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

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

        public FinishRecentsRunnable() {
            // Do nothing
        }

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
            AlternateRecentsComponent.notifyVisibilityChanged(false);
            mVisible = false;
            // Finish Recents
            if (mLaunchIntent != null) {
                if (mLaunchOpts != null) {
                    startActivityAsUser(mLaunchIntent, UserHandle.CURRENT);
                } else {
                    startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(), UserHandle.CURRENT);
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
            if (action.equals(ACTION_HIDE_RECENTS_ACTIVITY)) {
                if (intent.getBooleanExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, false)) {
                    // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
                    dismissRecentsToFocusedTaskOrHome(false);
                } else {
                    // Otherwise, dismiss Recents to Home
                    dismissRecentsToHome(true);
                }
            } else if (action.equals(ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // If we are toggling Recents, then first unfilter any filtered stacks first
                dismissRecentsToFocusedTaskOrHome(true);
            } else if (action.equals(ACTION_START_ENTER_ANIMATION)) {
                // Try and start the enter animation (or restart it on configuration changed)
                ReferenceCountedTrigger t = new ReferenceCountedTrigger(context, null, null, null);
                mRecentsView.startEnterRecentsAnimation(new ViewAnimation.TaskViewEnterContext(
                        mFullScreenOverlayView, t));
                onEnterAnimationTriggered();
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
        mConfig.launchedToTaskId = launchIntent.getIntExtra(
                AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_TASK_ID, -1);

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
    boolean dismissRecentsToFocusedTaskOrHome(boolean checkFilteredStackState) {
        if (mVisible) {
            // If we are mid-animation into Recents, reverse the animation now
            if (mFullScreenOverlayView != null &&
                mFullScreenOverlayView.cancelAnimateOnEnterRecents(mFinishRunnable)) return true;
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
            // Otherwise, try and return to the first Task in the stack
            if (mRecentsView.launchFirstTask()) return true;
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
            // If we are mid-animation into Recents, reverse the animation now
            if (mFullScreenOverlayView != null &&
                mFullScreenOverlayView.cancelAnimateOnEnterRecents(mFinishRunnable)) return true;
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

        // Bind the search app widget when we first start up
        bindSearchBarAppWidget();
        // Update the recent tasks
        updateRecentsTasks(getIntent());

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
        registerReceiver(mSystemBroadcastReceiver, filter);

        // Register any broadcast receivers for the task loader
        RecentsTaskLoader.getInstance().registerReceivers(this, mRecentsView);

        // Private API calls to make the shadows look better
        try {
            Utilities.setShadowProperty("ambientShadowStrength", String.valueOf(35f));
            Utilities.setShadowProperty("ambientRatio", String.valueOf(0.5f));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        // Prepare the screenshot transition if necessary
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenOverlayView = (FullscreenTransitionOverlayView) mFullscreenOverlayStub.inflate();
            mFullScreenOverlayView.setCallbacks(this);
            mFullScreenOverlayView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
        }

        // Update if we are getting a configuration change
        if (savedInstanceState != null) {
            mConfig.updateOnConfigurationChange();
            onConfigurationChange();
        }
    }

    void onConfigurationChange() {
        // Update RecentsConfiguration
        mConfig = RecentsConfiguration.reinitialize(this);

        // Try and start the enter animation (or restart it on configuration changed)
        ReferenceCountedTrigger t = new ReferenceCountedTrigger(this, null, null, null);
        mRecentsView.startEnterRecentsAnimation(new ViewAnimation.TaskViewEnterContext(
                mFullScreenOverlayView, t));
        onEnterAnimationTriggered();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Update the recent tasks
        updateRecentsTasks(intent);

        // Prepare the screenshot transition if necessary
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenOverlayView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(ACTION_START_ENTER_ANIMATION);
        registerReceiver(mServiceBroadcastReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Start listening for widget package changes if there is one bound, post it since we don't
        // want it stalling the startup
        if (mConfig.searchBarAppWidgetId >= 0) {
            final WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks> callback =
                    new WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks>(this);
            mRecentsView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks cb = callback.get();
                    if (cb != null) {
                        mAppWidgetHost.startListening(cb);
                    }
                }
            }, 1);
        }

        // Mark Recents as visible
        mVisible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Unregister the RecentsService receiver
        unregisterReceiver(mServiceBroadcastReceiver);

        // Stop listening for widget package changes if there was one bound
        if (mAppWidgetHost.isListening()) {
            mAppWidgetHost.stopListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister the system broadcast receivers
        unregisterReceiver(mSystemBroadcastReceiver);
        RecentsTaskLoader.getInstance().unregisterReceivers();
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
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            mRecentsView.focusNextTask(true);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            mRecentsView.focusNextTask(false);
            return true;
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
            } else {
                // Enable the debug mode
                settings.edit().putBoolean(Constants.Values.App.Key_DebugModeEnabled, true).apply();
            }
            Toast.makeText(this, "Debug mode (" + Constants.Values.App.DebugModeVersion +
                    ") toggled, please restart Recents now", Toast.LENGTH_SHORT).show();
        }
    }

    /** Called when the enter recents animation is triggered. */
    public void onEnterAnimationTriggered() {
        // Animate the SystemUI scrim views
        mScrimViews.startEnterRecentsAnimation();
    }

    /**** FullscreenTransitionOverlayView.FullScreenTransitionViewCallbacks Implementation ****/

    @Override
    public void onEnterAnimationComplete() {
        // Reset the full screenshot transition view
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenOverlayView.reset();

            // Recycle the full screen screenshot
            AlternateRecentsComponent.consumeLastScreenshot();
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
        AlternateRecentsComponent.notifyVisibilityChanged(false);
        mVisible = false;
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mFinishLaunchHomeRunnable.run();
    }

    /**** RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks Implementation ****/

    @Override
    public void refreshSearchWidget() {
        bindSearchBarAppWidget();
        addSearchBarAppWidgetView();
    }
}
