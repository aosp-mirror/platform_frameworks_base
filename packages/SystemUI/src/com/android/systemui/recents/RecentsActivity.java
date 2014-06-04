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
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsView;

import java.util.ArrayList;
import java.util.Set;

/** Our special app widget host */
class RecentsAppWidgetHost extends AppWidgetHost {
    /* Callbacks to notify when an app package changes */
    interface RecentsAppWidgetHostCallbacks {
        public void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidgetInfo);
    }

    RecentsAppWidgetHostCallbacks mCb;

    public RecentsAppWidgetHost(Context context, int hostId, RecentsAppWidgetHostCallbacks cb) {
        super(context, hostId);
        mCb = cb;
    }

    @Override
    protected void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidget) {
        mCb.onProviderChanged(appWidgetId, appWidget);
    }
}

/* Activity */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks {
    FrameLayout mContainerView;
    RecentsView mRecentsView;
    View mEmptyView;
    View mNavBarScrimView;

    AppWidgetHost mAppWidgetHost;
    AppWidgetProviderInfo mSearchAppWidgetInfo;
    AppWidgetHostView mSearchAppWidgetHostView;

    boolean mVisible;
    boolean mTaskLaunched;

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
                    // Otherwise, just finish the activity without launching any other activities
                    finish();
                }
            } else if (action.equals(RecentsService.ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // Try and unfilter and filtered stacks
                if (!mRecentsView.unfilterFilteredStacks()) {
                    // If there are no filtered stacks, dismiss recents and launch the first task
                    dismissRecentsIfVisible();
                }
            } else if (action.equals(RecentsService.ACTION_START_ENTER_ANIMATION)) {
                // Try and start the enter animation (or restart it on configuration changed)
                mRecentsView.startOnEnterAnimation();
            }
        }
    };

    // Broadcast receiver to handle messages from the system
    BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    /** Updates the set of recent tasks */
    void updateRecentsTasks(Intent launchIntent) {
        // Update the configuration based on the launch intent
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        config.launchedWithThumbnailAnimation = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_ANIMATING_WITH_THUMBNAIL, false);
        config.launchedFromAltTab = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_ALT_TAB, false);

        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SpaceNode root = loader.reload(this, Constants.Values.RecentsTaskLoader.PreloadFirstTasksCount);
        ArrayList<TaskStack> stacks = root.getStacks();
        if (!stacks.isEmpty()) {
            mRecentsView.setBSP(root);
        }

        // Hide the scrim by default when we enter recents
        mNavBarScrimView.setVisibility(View.INVISIBLE);

        // Add the default no-recents layout
        if (stacks.size() == 1 && stacks.get(0).getTaskCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);

            // Dim the background even more
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.dimAmount = Constants.Values.Window.DarkBackgroundDim;
            getWindow().setAttributes(wlp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } else {
            mEmptyView.setVisibility(View.GONE);

            // Un-dim the background
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.dimAmount = 0f;
            getWindow().setAttributes(wlp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    /** Attempts to allocate and bind the search bar app widget */
    void bindSearchBarAppWidget() {
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            RecentsConfiguration config = RecentsConfiguration.getInstance();
            SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();

            // Reset the host view and widget info
            mSearchAppWidgetHostView = null;
            mSearchAppWidgetInfo = null;

            // Try and load the app widget id from the settings
            int appWidgetId = config.searchBarAppWidgetId;
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
                    config.updateSearchBarAppWidgetId(this, widgetInfo.first);
                    mSearchAppWidgetInfo = widgetInfo.second;
                }
            }
        }
    }

    /** Creates the search bar app widget view */
    void addSearchBarAppWidgetView() {
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            RecentsConfiguration config = RecentsConfiguration.getInstance();
            int appWidgetId = config.searchBarAppWidgetId;
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
            if (!mRecentsView.launchFocusedTask()) {
                if (!mRecentsView.launchFirstTask()) {
                    finish();
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
        RecentsConfiguration.reinitialize(this);

        // Initialize the widget host (the host id is static and does not change)
        mAppWidgetHost = new RecentsAppWidgetHost(this, Constants.Values.App.AppWidgetHostId, this);

        // Create the view hierarchy
        mRecentsView = new RecentsView(this);
        mRecentsView.setCallbacks(this);
        mRecentsView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // Create the empty view
        LayoutInflater inflater = LayoutInflater.from(this);
        mEmptyView = inflater.inflate(R.layout.recents_empty, mContainerView, false);
        mNavBarScrimView = inflater.inflate(R.layout.recents_nav_bar_scrim, mContainerView, false);

        mContainerView = new FrameLayout(this);
        mContainerView.addView(mRecentsView);
        mContainerView.addView(mEmptyView);
        mContainerView.addView(mNavBarScrimView);
        setContentView(mContainerView);

        // Update the recent tasks
        updateRecentsTasks(getIntent());

        // Bind the search app widget when we first start up
        bindSearchBarAppWidget();
        // Add the search bar layout
        addSearchBarAppWidgetView();

        // Update if we are getting a configuration change
        if (savedInstanceState != null) {
            onConfigurationChange();
        }
    }

    void onConfigurationChange() {
        // Try and start the enter animation (or restart it on configuration changed)
        mRecentsView.startOnEnterAnimation();
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
        RecentsConfiguration.reinitialize(this);

        // Update the recent tasks
        updateRecentsTasks(intent);

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

        // Start listening for widget package changes if there is one bound
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        if (config.searchBarAppWidgetId >= 0) {
            mAppWidgetHost.startListening();
        }

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

        // Stop listening for widget package changes if there was one bound
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        if (config.searchBarAppWidgetId >= 0) {
            mAppWidgetHost.stopListening();
        }

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
    public void onBackPressed() {
        // Unfilter any stacks
        if (!mRecentsView.unfilterFilteredStacks()) {
            super.onBackPressed();
        }
    }

    @Override
    public void onEnterAnimationTriggered() {
        // Fade in the scrim
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        if (config.hasNavBarScrim()) {
            mNavBarScrimView.setVisibility(View.VISIBLE);
            mNavBarScrimView.setAlpha(0f);
            mNavBarScrimView.animate().alpha(1f)
                    .setStartDelay(config.taskBarEnterAnimDelay)
                    .setDuration(config.navBarScrimEnterDuration)
                    .setInterpolator(config.fastOutSlowInInterpolator)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onTaskLaunching(boolean isTaskInStackBounds) {
        mTaskLaunched = true;

        // Fade out the scrim
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        if (!isTaskInStackBounds && config.hasNavBarScrim()) {
            mNavBarScrimView.animate().alpha(0f)
                    .setStartDelay(0)
                    .setDuration(config.taskBarExitAnimDuration)
                    .setInterpolator(config.fastOutSlowInInterpolator)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidgetInfo) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (appWidgetId > -1 && appWidgetId == config.searchBarAppWidgetId) {
            // The search provider may have changed, so just delete the old widget and bind it again
            ssp.unbindSearchAppWidget(mAppWidgetHost, appWidgetId);
            config.updateSearchBarAppWidgetId(this, -1);
            // Load the widget again
            bindSearchBarAppWidget();
            addSearchBarAppWidgetView();
        }
    }
}
