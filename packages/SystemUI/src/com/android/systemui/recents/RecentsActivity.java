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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.FullScreenTransitionView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.ViewAnimation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

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
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks,
        FullScreenTransitionView.FullScreenTransitionViewCallbacks {

    FrameLayout mContainerView;
    RecentsView mRecentsView;
    View mEmptyView;
    View mNavBarScrimView;
    FullScreenTransitionView mFullScreenshotView;

    RecentsConfiguration mConfig;

    AppWidgetHost mAppWidgetHost;
    AppWidgetProviderInfo mSearchAppWidgetInfo;
    AppWidgetHostView mSearchAppWidgetHostView;

    boolean mVisible;
    boolean mTaskLaunched;

    private static Method sPropertyMethod;
    static {
        try {
            Class<?> c = Class.forName("android.view.GLES20Canvas");
            sPropertyMethod = c.getDeclaredMethod("setProperty", String.class, String.class);
            if (!sPropertyMethod.isAccessible()) sPropertyMethod.setAccessible(true);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
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
                    if (mFullScreenshotView == null ||
                            !mFullScreenshotView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
                        // Otherwise, just finish the activity without launching any other activities
                        ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(context,
                                null, mFinishRunnable, null);
                        mRecentsView.startOnExitAnimation(
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
                mRecentsView.startOnEnterAnimation(new ViewAnimation.TaskViewEnterContext(mFullScreenshotView));
                // Call our callback
                onEnterAnimationTriggered();
            }
        }
    };

    // Broadcast receiver to handle messages from the system
    BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Mark recents as no longer visible
            AlternateRecentsComponent.notifyVisibilityChanged(false);
            // Finish without an animations
            finish();
        }
    };

    // A runnable to finish the Recents activity
    Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            // Mark recents as no longer visible
            AlternateRecentsComponent.notifyVisibilityChanged(false);
            // Finish with an animations
            finish();
            overridePendingTransition(R.anim.recents_to_launcher_enter,
                    R.anim.recents_to_launcher_exit);
        }
    };

    /** Updates the set of recent tasks */
    void updateRecentsTasks(Intent launchIntent) {
        // Update the configuration based on the launch intent
        mConfig.launchedFromHome = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_HOME, false);
        mConfig.launchedFromAppWithThumbnail = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_APP_THUMBNAIL, false);
        mConfig.launchedFromAppWithScreenshot = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_FROM_APP_FULL_SCREENSHOT, false);
        mConfig.launchedWithAltTab = launchIntent.getBooleanExtra(
                AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false);

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
        } else {
            mEmptyView.setVisibility(View.GONE);
        }

        // Dim the background
        mRecentsView.setBackgroundColor(0x80000000);
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
            if (mFullScreenshotView == null ||
                    !mFullScreenshotView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
                // If we have a focused task, then launch that task
                if (!mRecentsView.launchFocusedTask()) {
                    // If there are any tasks, then launch the first task
                    if (!mRecentsView.launchFirstTask()) {
                        // We really shouldn't hit this, but if we do, just animate out (aka. finish)
                        ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                                null, mFinishRunnable, null);
                        mRecentsView.startOnExitAnimation(
                                new ViewAnimation.TaskViewExitContext(exitTrigger));
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
        mNavBarScrimView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenshotView = new FullScreenTransitionView(this, this);
            mFullScreenshotView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        mContainerView = new FrameLayout(this);
        mContainerView.addView(mRecentsView);
        mContainerView.addView(mEmptyView);
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mContainerView.addView(mFullScreenshotView);
        }
        mContainerView.addView(mNavBarScrimView);
        setContentView(mContainerView);

        // Update the recent tasks
        updateRecentsTasks(getIntent());

        // Prepare the screenshot transition if necessary
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            mFullScreenshotView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
        }

        // Bind the search app widget when we first start up
        bindSearchBarAppWidget();
        // Add the search bar layout
        addSearchBarAppWidgetView();

        // Update if we are getting a configuration change
        if (savedInstanceState != null) {
            onConfigurationChange();
        }

        // XXX: Update the shadows
        try {
            sPropertyMethod.invoke(null, "ambientShadowStrength", String.valueOf(35f));
            sPropertyMethod.invoke(null, "ambientRatio", String.valueOf(0.5f));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    void onConfigurationChange() {
        // Try and start the enter animation (or restart it on configuration changed)
        mRecentsView.startOnEnterAnimation(new ViewAnimation.TaskViewEnterContext(mFullScreenshotView));
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
            mFullScreenshotView.prepareAnimateOnEnterRecents(AlternateRecentsComponent.getLastScreenshot());
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

        // Start listening for widget package changes if there is one bound
        if (mConfig.searchBarAppWidgetId >= 0) {
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
        if (mConfig.searchBarAppWidgetId >= 0) {
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
        // If we are mid-animation into Recents, then reverse it and finish
        if (mFullScreenshotView == null ||
                !mFullScreenshotView.cancelAnimateOnEnterRecents(mFinishRunnable)) {
            // If we are currently filtering in any stacks, unfilter them first
            if (!mRecentsView.unfilterFilteredStacks()) {
                if (mConfig.launchedFromHome) {
                    // Just start the animation out of recents
                    ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                            null, mFinishRunnable, null);
                    mRecentsView.startOnExitAnimation(
                            new ViewAnimation.TaskViewExitContext(exitTrigger));
                } else {
                    // Otherwise, try and launch the first task
                    if (!mRecentsView.launchFirstTask()) {
                        // If there are no tasks, then just finish recents
                        ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                                null, mFinishRunnable, null);
                        mRecentsView.startOnExitAnimation(
                                new ViewAnimation.TaskViewExitContext(exitTrigger));
                    }
                }
            }
        }
    }

    public void onEnterAnimationTriggered() {
        // Fade in the scrim
        if (mConfig.hasNavBarScrim()) {
            mNavBarScrimView.setVisibility(View.VISIBLE);
            mNavBarScrimView.setAlpha(0f);
            mNavBarScrimView.animate().alpha(1f)
                    .setStartDelay(mConfig.taskBarEnterAnimDelay)
                    .setDuration(mConfig.navBarScrimEnterDuration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onEnterAnimationComplete(boolean canceled) {
        if (!canceled) {
            // Reset the full screenshot transition view
            if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
                mFullScreenshotView.reset();
            }

            // XXX: We should clean up the screenshot in this case as well, but it needs to happen
            //      after to animate up
        }
        // Recycle the full screen screenshot
        AlternateRecentsComponent.consumeLastScreenshot();
    }

    @Override
    public void onTaskLaunching(boolean isTaskInStackBounds) {
        mTaskLaunched = true;

        // Fade out the scrim
        if (!isTaskInStackBounds && mConfig.hasNavBarScrim()) {
            mNavBarScrimView.animate().alpha(0f)
                    .setStartDelay(0)
                    .setDuration(mConfig.taskBarExitAnimDuration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .withLayer()
                    .start();
        }

        // Mark recents as no longer visible
        AlternateRecentsComponent.notifyVisibilityChanged(false);
    }

    @Override
    public void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidgetInfo) {
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (appWidgetId > -1 && appWidgetId == mConfig.searchBarAppWidgetId) {
            // The search provider may have changed, so just delete the old widget and bind it again
            ssp.unbindSearchAppWidget(mAppWidgetHost, appWidgetId);
            mConfig.updateSearchBarAppWidgetId(this, -1);
            // Load the widget again
            bindSearchBarAppWidget();
            addSearchBarAppWidgetView();
        }
    }
}
