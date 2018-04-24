/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents;

import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_HOME_KEY;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.TaskStackBuilder;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowLastAnimationFrameEvent;
import com.android.systemui.recents.events.activity.ExitRecentsWindowFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.LaunchTaskSucceededEvent;
import com.android.systemui.recents.events.activity.MultiWindowStateChangedEvent;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.ActivityUnpinnedEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.component.SetWaitingForTransitionStartEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.HideIncompatibleAppOverlayEvent;
import com.android.systemui.recents.events.ui.RecentsDrawnEvent;
import com.android.systemui.recents.events.ui.ShowApplicationInfoEvent;
import com.android.systemui.recents.events.ui.ShowIncompatibleAppOverlayEvent;
import com.android.systemui.recents.events.ui.StackViewScrolledEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusNextTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusPreviousTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.NavigateTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.NavigateTaskViewEvent.Direction;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.shared.recents.model.RecentsTaskLoader;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import com.android.systemui.shared.system.WindowManagerWrapper;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The main Recents activity that is started from RecentsComponent.
 */
public class RecentsActivity extends Activity implements ViewTreeObserver.OnPreDrawListener,
        ColorExtractor.OnColorsChangedListener {

    private final static String TAG = "RecentsActivity";
    private final static boolean DEBUG = false;

    public final static int EVENT_BUS_PRIORITY = Recents.EVENT_BUS_PRIORITY + 1;
    public final static int INCOMPATIBLE_APP_ALPHA_DURATION = 150;

    private PackageMonitor mPackageMonitor = new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                RecentsActivity.this.onPackageChanged(packageName, getChangingUserId());
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                RecentsActivity.this.onPackageChanged(packageName, getChangingUserId());
                return true;
            }

            @Override
            public void onPackageModified(String packageName) {
                RecentsActivity.this.onPackageChanged(packageName, getChangingUserId());
            }
        };
    private Handler mHandler = new Handler();
    private long mLastTabKeyEventTime;
    private boolean mFinishedOnStartup;
    private boolean mIgnoreAltTabRelease;
    private boolean mIsVisible;
    private boolean mRecentsStartRequested;
    private Configuration mLastConfig;

    // Top level views
    private RecentsView mRecentsView;
    private SystemBarScrimViews mScrimViews;
    private View mIncompatibleAppOverlay;

    // Runnables to finish the Recents activity
    private Intent mHomeIntent;

    // The trigger to automatically launch the current task
    private int mFocusTimerDuration;
    private final UserInteractionEvent mUserInteractionEvent = new UserInteractionEvent();

    // Theme and colors
    private SysuiColorExtractor mColorExtractor;
    private boolean mUsingDarkText;

    /**
     * A common Runnable to finish Recents by launching Home with an animation depending on the
     * last activity launch state. Generally we always launch home when we exit Recents rather than
     * just finishing the activity since we don't know what is behind Recents in the task stack.
     */
    class LaunchHomeRunnable implements Runnable {

        Intent mLaunchIntent;
        ActivityOptions mOpts;

        /**
         * Creates a finish runnable that starts the specified intent.
         */
        public LaunchHomeRunnable(Intent launchIntent, ActivityOptions opts) {
            mLaunchIntent = launchIntent;
            mOpts = opts;
        }

        @Override
        public void run() {
            try {
                mHandler.post(() -> {
                    ActivityOptions opts = mOpts;
                    if (opts == null) {
                        opts = ActivityOptions.makeCustomAnimation(RecentsActivity.this,
                                R.anim.recents_to_launcher_enter, R.anim.recents_to_launcher_exit);
                    }
                    startActivityAsUser(mLaunchIntent, opts.toBundle(), UserHandle.CURRENT);
                });
            } catch (Exception e) {
                Log.e(TAG, getString(R.string.recents_launch_error_message, "Home"), e);
            }
        }
    }

    /**
     * Broadcast receiver to handle messages from the system
     */
    final BroadcastReceiver mSystemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // When the screen turns off, dismiss Recents to Home
                dismissRecentsToHomeIfVisible(false);
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                // When switching users, dismiss Recents to Home similar to screen off
                finish();
            }
        }
    };

    private final OnPreDrawListener mRecentsDrawnEventListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
                    EventBus.getDefault().post(new RecentsDrawnEvent());
                    if (LatencyTracker.isEnabled(getApplicationContext())) {
                        DejankUtils.postAfterTraversal(() -> LatencyTracker.getInstance(
                                getApplicationContext()).onActionEnd(
                                LatencyTracker.ACTION_TOGGLE_RECENTS));
                    }
                    DejankUtils.postAfterTraversal(() -> {
                        Recents.getTaskLoader().startLoader(RecentsActivity.this);
                        Recents.getTaskLoader().getHighResThumbnailLoader().setVisible(true);
                    });
                    return true;
                }
            };

    /**
     * Dismisses recents if we are already visible and the intent is to toggle the recents view.
     */
    boolean dismissRecentsToFocusedTask(int logCategory) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask(logCategory)) return true;
        }
        return false;
    }

    /**
     * Dismisses recents back to the launch target task.
     */
    boolean dismissRecentsToLaunchTargetTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true /* animateTaskViews */);
        }
        return false;
    }

    /**
     * Dismisses recents if we are already visible and the intent is to toggle the recents view.
     */
    boolean dismissRecentsToFocusedTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask(0 /* logCategory */)) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHome(true /* animateTaskViews */);
            return true;
        }
        return false;
    }

    /**
     * Dismisses Recents directly to Home without checking whether it is currently visible.
     */
    void dismissRecentsToHome(boolean animateTaskViews) {
        dismissRecentsToHome(animateTaskViews, null);
    }

    /**
     * Dismisses Recents directly to Home without checking whether it is currently visible.
     *
     * @param overrideAnimation If not null, will override the default animation that is based on
     *                          how Recents was launched.
     */
    void dismissRecentsToHome(boolean animateTaskViews, ActivityOptions overrideAnimation) {
        DismissRecentsToHomeAnimationStarted dismissEvent =
                new DismissRecentsToHomeAnimationStarted(animateTaskViews);
        dismissEvent.addPostAnimationCallback(new LaunchHomeRunnable(mHomeIntent,
                overrideAnimation));
        ActivityManagerWrapper.getInstance().closeSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);
        EventBus.getDefault().send(dismissEvent);
    }

    /** Dismisses Recents directly to Home if we currently aren't transitioning. */
    boolean dismissRecentsToHomeIfVisible(boolean animated) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
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

        // Initialize the package monitor
        mPackageMonitor.register(this, Looper.getMainLooper(), UserHandle.ALL,
                true /* externalStorage */);

        // Select theme based on wallpaper colors
        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);
        mUsingDarkText = mColorExtractor.getColors(ColorExtractor.TYPE_DARK,
                WallpaperManager.FLAG_SYSTEM, true).supportsDarkText();
        setTheme(mUsingDarkText ? R.style.RecentsTheme_Wallpaper_Light
                : R.style.RecentsTheme_Wallpaper);

        // Set the Recents layout
        setContentView(R.layout.recents);
        takeKeyEvents(true);
        mRecentsView = findViewById(R.id.recents_view);
        mScrimViews = new SystemBarScrimViews(this);
        getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;
        if (Recents.getConfiguration().isLowRamDevice) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        mLastConfig = new Configuration(Utilities.getAppConfiguration(this));

        // Set the window background
        mRecentsView.updateBackgroundScrim(getWindow(), isInMultiWindowMode());

        // Create the home intent runnable
        mHomeIntent = new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(mSystemBroadcastReceiver, filter);

        getWindow().addPrivateFlags(LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Reload the stack view whenever we are made visible again
        reloadStackView();

        // Notify that recents is now visible
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, true));
        MetricsLogger.visible(this, MetricsEvent.OVERVIEW_ACTIVITY);

        // Getting system scrim colors ignoring wallpaper visibility since it should never be grey.
        ColorExtractor.GradientColors systemColors = mColorExtractor.getColors(
                ColorExtractor.TYPE_DARK, WallpaperManager.FLAG_SYSTEM, true);
        // We don't want to interpolate colors because we're defining the initial state.
        // Gradient should be set/ready when you open "Recents".
        mRecentsView.setScrimColors(systemColors, false);

        // Notify of the next draw
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(mRecentsDrawnEventListener);

        // If Recents was restarted, then it should complete the enter animation with partially
        // reset launch state with dock, app and home set to false
        Object isRelaunching = getLastNonConfigurationInstance();
        if (isRelaunching != null && isRelaunching instanceof Boolean && (boolean) isRelaunching) {
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            launchState.launchedViaDockGesture = false;
            launchState.launchedFromApp = false;
            launchState.launchedFromHome = false;
            onEnterAnimationComplete();
        }
        mRecentsStartRequested = false;
    }

    @Override
    public void onColorsChanged(ColorExtractor colorExtractor, int which) {
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            // Recents doesn't care about the wallpaper being visible or not, it always
            // wants to scrim with wallpaper colors
            ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                    WallpaperManager.FLAG_SYSTEM,
                    ColorExtractor.TYPE_DARK, true /* ignoreVis */);
            boolean darkText = colors.supportsDarkText();
            if (darkText != mUsingDarkText) {
                mUsingDarkText = darkText;
                setTheme(mUsingDarkText ? R.style.RecentsTheme_Wallpaper_Light
                        : R.style.RecentsTheme_Wallpaper);
                mRecentsView.reevaluateStyles();
            }
            mRecentsView.setScrimColors(colors, true /* animated */);
        }
    }

    /**
     * Reloads the stack views upon launching Recents.
     */
    private void reloadStackView() {
        // If the Recents component has preloaded a load plan, then use that to prevent
        // reconstructing the task stack
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan loadPlan = RecentsImpl.consumeInstanceLoadPlan();
        if (loadPlan == null) {
            loadPlan = new RecentsTaskLoadPlan(this);
        }

        // Start loading tasks according to the load plan
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        if (!loadPlan.hasTasks()) {
            loader.preloadTasks(loadPlan, launchState.launchedToTaskId);
        }

        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = launchState.launchedNumVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = launchState.launchedNumVisibleThumbnails;
        loader.loadTasks(loadPlan, loadOpts);
        TaskStack stack = loadPlan.getTaskStack();
        mRecentsView.onReload(stack, mIsVisible);

        // Update the nav bar scrim, but defer the animation until the enter-window event
        boolean animateNavBarScrim = !launchState.launchedViaDockGesture;
        mScrimViews.updateNavBarScrim(animateNavBarScrim, stack.getTaskCount() > 0, null);

        // If this is a new instance relaunched by AM, without going through the normal mechanisms,
        // then we have to manually trigger the enter animation state
        boolean wasLaunchedByAm = !launchState.launchedFromHome &&
                !launchState.launchedFromApp;
        if (wasLaunchedByAm) {
            EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());
        }

        // Keep track of whether we launched from the nav bar button or via alt-tab
        if (launchState.launchedWithAltTab) {
            MetricsLogger.count(this, "overview_trigger_alttab", 1);
        } else {
            MetricsLogger.count(this, "overview_trigger_nav_btn", 1);
        }

        // Keep track of whether we launched from an app or from home
        if (launchState.launchedFromApp) {
            Task launchTarget = stack.getLaunchTarget();
            int launchTaskIndexInStack = launchTarget != null
                    ? stack.indexOfTask(launchTarget)
                    : 0;
            MetricsLogger.count(this, "overview_source_app", 1);
            // If from an app, track the stack index of the app in the stack (for affiliated tasks)
            MetricsLogger.histogram(this, "overview_source_app_index", launchTaskIndexInStack);
        } else {
            MetricsLogger.count(this, "overview_source_home", 1);
        }

        // Keep track of the total stack task count
        int taskCount = mRecentsView.getStack().getTaskCount();
        MetricsLogger.histogram(this, "overview_task_count", taskCount);

        // After we have resumed, set the visible state until the next onStop() call
        mIsVisible = true;
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        EventBus.getDefault().send(new EnterRecentsWindowAnimationCompletedEvent());

        // Workaround for b/64694148: The animation started callback is not made (see
        // RecentsImpl.getThumbnailTransitionActivityOptions) so reset the transition-waiting state
        // once the enter animation has completed.
        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(false));
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        mIgnoreAltTabRelease = false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Notify of the config change
        Configuration newDeviceConfiguration = Utilities.getAppConfiguration(this);
        int numStackTasks = mRecentsView.getStack().getTaskCount();
        EventBus.getDefault().send(new ConfigurationChangedEvent(false /* fromMultiWindow */,
                mLastConfig.orientation != newDeviceConfiguration.orientation,
                mLastConfig.densityDpi != newDeviceConfiguration.densityDpi, numStackTasks > 0));

        mLastConfig.updateFrom(newDeviceConfiguration);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // Set the window background
        mRecentsView.updateBackgroundScrim(getWindow(), isInMultiWindowMode);

        // Reload the task stack view if we are still visible to pick up the change in tasks that
        // result from entering/exiting multi-window
        if (mIsVisible) {
            reloadTaskStack(isInMultiWindowMode, true /* sendConfigChangedEvent */);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Notify that recents is now hidden
        mIsVisible = false;
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, false));
        MetricsLogger.hidden(this, MetricsEvent.OVERVIEW_ACTIVITY);
        Recents.getTaskLoader().getHighResThumbnailLoader().setVisible(false);

        // When recents starts again before onStop, do not reset launch flags so entrance animation
        // can run
        if (!isChangingConfigurations() && !mRecentsStartRequested) {
            // Workaround for b/22542869, if the RecentsActivity is started again, but without going
            // through SystemUI, we need to reset the config launch flags to ensure that we do not
            // wait on the system to send a signal that was never queued.
            RecentsConfiguration config = Recents.getConfiguration();
            RecentsActivityLaunchState launchState = config.getLaunchState();
            launchState.reset();
        }

        // Force a gc to attempt to clean up bitmap references more quickly (b/38258699)
        Recents.getSystemServices().gc();
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
                    if (backward) {
                        EventBus.getDefault().send(new FocusPreviousTaskViewEvent());
                    } else {
                        EventBus.getDefault().send(new FocusNextTaskViewEvent());
                    }
                    mLastTabKeyEventTime = SystemClock.elapsedRealtime();

                    // In the case of another ALT event, don't ignore the next release
                    if (event.isAltPressed()) {
                        mIgnoreAltTabRelease = false;
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                final Direction direction = NavigateTaskViewEvent.getDirectionFromKeyCode(keyCode);
                EventBus.getDefault().send(new NavigateTaskViewEvent(direction));
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                if (event.getRepeatCount() <= 0) {
                    EventBus.getDefault().send(new DismissFocusedTaskViewEvent());

                    // Keep track of deletions by keyboard
                    MetricsLogger.histogram(this, "overview_task_dismissed_source",
                            Constants.Metrics.DismissSourceKeyboard);
                    return true;
                }
            }
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        EventBus.getDefault().send(mUserInteractionEvent);
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

    public final void onBusEvent(RecentsActivityStartingEvent event) {
        mRecentsStartRequested = true;
    }

    public final void onBusEvent(HideRecentsEvent event) {
        if (event.triggeredFromAltTab) {
            // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
            if (!mIgnoreAltTabRelease) {
                dismissRecentsToFocusedTaskOrHome();
            }
        } else if (event.triggeredFromHomeKey) {
            dismissRecentsToHome(true /* animateTaskViews */);

            // Cancel any pending dozes
            EventBus.getDefault().send(mUserInteractionEvent);
        } else {
            // Do nothing
        }
    }

    public final void onBusEvent(EnterRecentsWindowLastAnimationFrameEvent event) {
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
        mRecentsView.invalidate();
    }

    public final void onBusEvent(ExitRecentsWindowFirstAnimationFrameEvent event) {
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
        mRecentsView.invalidate();
    }

    public final void onBusEvent(DockedFirstAnimationFrameEvent event) {
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
        mRecentsView.invalidate();
    }

    public final void onBusEvent(CancelEnterRecentsWindowAnimationEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        int launchToTaskId = launchState.launchedToTaskId;
        if (launchToTaskId != -1 &&
                (event.launchTask == null || launchToTaskId != event.launchTask.key.id)) {
            ActivityManagerWrapper am = ActivityManagerWrapper.getInstance();
            am.cancelWindowTransition(launchState.launchedToTaskId);
        }
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

    public final void onBusEvent(ShowIncompatibleAppOverlayEvent event) {
        if (mIncompatibleAppOverlay == null) {
            mIncompatibleAppOverlay = Utilities.findViewStubById(this,
                    R.id.incompatible_app_overlay_stub).inflate();
            mIncompatibleAppOverlay.setWillNotDraw(false);
            mIncompatibleAppOverlay.setVisibility(View.VISIBLE);
        }
        mIncompatibleAppOverlay.animate()
                .alpha(1f)
                .setDuration(INCOMPATIBLE_APP_ALPHA_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .start();
    }

    public final void onBusEvent(HideIncompatibleAppOverlayEvent event) {
        if (mIncompatibleAppOverlay != null) {
            mIncompatibleAppOverlay.animate()
                    .alpha(0f)
                    .setDuration(INCOMPATIBLE_APP_ALPHA_DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .start();
        }
    }

    public final void onBusEvent(DeleteTaskDataEvent event) {
        // Remove any stored data from the loader
        RecentsTaskLoader loader = Recents.getTaskLoader();
        loader.deleteTaskData(event.task, false);

        // Remove the task from activity manager
        ActivityManagerWrapper.getInstance().removeTask(event.task.key.id);
    }

    public final void onBusEvent(TaskViewDismissedEvent event) {
        mRecentsView.updateScrimOpacity();
    }

    public final void onBusEvent(AllTaskViewsDismissedEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasDockedTask()) {
            mRecentsView.showEmptyView(event.msgResId);
        } else {
            // Just go straight home (no animation necessary because there are no more task views)
            dismissRecentsToHome(false /* animateTaskViews */);
        }

        // Keep track of all-deletions
        MetricsLogger.count(this, "overview_task_all_dismissed", 1);
    }

    public final void onBusEvent(LaunchTaskSucceededEvent event) {
        MetricsLogger.histogram(this, "overview_task_launch_index", event.taskIndexFromStackFront);
    }

    public final void onBusEvent(LaunchTaskFailedEvent event) {
        // Return to Home
        dismissRecentsToHome(true /* animateTaskViews */);

        MetricsLogger.count(this, "overview_task_launch_failed", 1);
    }

    public final void onBusEvent(ScreenPinningRequestEvent event) {
        MetricsLogger.count(this, "overview_screen_pinned", 1);
    }

    public final void onBusEvent(StackViewScrolledEvent event) {
        // Once the user has scrolled while holding alt-tab, then we should ignore the release of
        // the key
        mIgnoreAltTabRelease = true;
    }

    public final void onBusEvent(final DockedTopTaskEvent event) {
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(mRecentsDrawnEventListener);
        mRecentsView.invalidate();
    }

    public final void onBusEvent(final ActivityUnpinnedEvent event) {
        if (mIsVisible) {
            // Skip the configuration change event as the PiP activity does not actually affect the
            // config of recents
            reloadTaskStack(isInMultiWindowMode(), false /* sendConfigChangedEvent */);
        }
    }

    private void reloadTaskStack(boolean isInMultiWindowMode, boolean sendConfigChangedEvent) {
        // Reload the task stack completely
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan loadPlan = new RecentsTaskLoadPlan(this);
        loader.preloadTasks(loadPlan, -1 /* runningTaskId */);

        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.numVisibleTasks = launchState.launchedNumVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = launchState.launchedNumVisibleThumbnails;
        loader.loadTasks(loadPlan, loadOpts);

        TaskStack stack = loadPlan.getTaskStack();
        int numStackTasks = stack.getTaskCount();
        boolean showDeferredAnimation = numStackTasks > 0;

        if (sendConfigChangedEvent) {
            EventBus.getDefault().send(new ConfigurationChangedEvent(true /* fromMultiWindow */,
                    false /* fromDeviceOrientationChange */, false /* fromDisplayDensityChange */,
                    numStackTasks > 0));
        }
        EventBus.getDefault().send(new MultiWindowStateChangedEvent(isInMultiWindowMode,
                showDeferredAnimation, stack));
    }

    @Override
    public boolean onPreDraw() {
        mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
        return true;
    }

    public void onPackageChanged(String packageName, int userId) {
        Recents.getTaskLoader().onPackageChanged(packageName);
        EventBus.getDefault().send(new PackagesChangedEvent(packageName, userId));
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        EventBus.getDefault().dump(prefix, writer);
        Recents.getTaskLoader().dump(prefix, writer);

        String id = Integer.toHexString(System.identityHashCode(this));

        writer.print(prefix); writer.print(TAG);
        writer.print(" visible="); writer.print(mIsVisible ? "Y" : "N");
        writer.print(" currentTime="); writer.print(System.currentTimeMillis());
        writer.print(" [0x"); writer.print(id); writer.print("]");
        writer.println();

        if (mRecentsView != null) {
            mRecentsView.dump(prefix, writer);
        }
    }
}
