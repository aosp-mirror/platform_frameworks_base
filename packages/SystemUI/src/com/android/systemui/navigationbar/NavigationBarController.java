/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dumpable;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;


/** A controller to handle navigation bars. */
@SysUISingleton
public class NavigationBarController implements Callbacks,
        ConfigurationController.ConfigurationListener,
        NavigationModeController.ModeChangedListener, Dumpable {

    private static final float TABLET_MIN_DPS = 600;

    private static final String TAG = NavigationBarController.class.getSimpleName();

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final AccessibilityManager mAccessibilityManager;
    private final AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final MetricsLogger mMetricsLogger;
    private final OverviewProxyService mOverviewProxyService;
    private final NavigationModeController mNavigationModeController;
    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final StatusBarStateController mStatusBarStateController;
    private final SysUiState mSysUiFlagsContainer;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    private final Optional<Pip> mPipOptional;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final Optional<Recents> mRecentsOptional;
    private final Lazy<StatusBar> mStatusBarLazy;
    private final ShadeController mShadeController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final SystemActions mSystemActions;
    private final UiEventLogger mUiEventLogger;
    private final Handler mHandler;
    private final NavigationBarA11yHelper mNavigationBarA11yHelper;
    private final DisplayManager mDisplayManager;
    private final NavigationBarOverlayController mNavBarOverlayController;
    private final TaskbarDelegate mTaskbarDelegate;
    private int mNavMode;
    private boolean mIsTablet;

    /** A displayId - nav bar maps. */
    @VisibleForTesting
    SparseArray<NavigationBar> mNavigationBars = new SparseArray<>();

    // Tracks config changes that will actually recreate the nav bar
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_UI_MODE);

    @Inject
    public NavigationBarController(Context context,
            WindowManager windowManager,
            Lazy<AssistManager> assistManagerLazy,
            AccessibilityManager accessibilityManager,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            DeviceProvisionedController deviceProvisionedController,
            MetricsLogger metricsLogger,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            StatusBarStateController statusBarStateController,
            SysUiState sysUiFlagsContainer,
            BroadcastDispatcher broadcastDispatcher,
            CommandQueue commandQueue,
            Optional<Pip> pipOptional,
            Optional<LegacySplitScreen> splitScreenOptional,
            Optional<Recents> recentsOptional,
            Lazy<StatusBar> statusBarLazy,
            ShadeController shadeController,
            NotificationRemoteInputManager notificationRemoteInputManager,
            SystemActions systemActions,
            @Main Handler mainHandler,
            UiEventLogger uiEventLogger,
            NavigationBarOverlayController navBarOverlayController,
            ConfigurationController configurationController,
            NavigationBarA11yHelper navigationBarA11yHelper) {
        mContext = context;
        mWindowManager = windowManager;
        mAssistManagerLazy = assistManagerLazy;
        mAccessibilityManager = accessibilityManager;
        mAccessibilityManagerWrapper = accessibilityManagerWrapper;
        mDeviceProvisionedController = deviceProvisionedController;
        mMetricsLogger = metricsLogger;
        mOverviewProxyService = overviewProxyService;
        mNavigationModeController = navigationModeController;
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
        mStatusBarStateController = statusBarStateController;
        mSysUiFlagsContainer = sysUiFlagsContainer;
        mBroadcastDispatcher = broadcastDispatcher;
        mCommandQueue = commandQueue;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mRecentsOptional = recentsOptional;
        mStatusBarLazy = statusBarLazy;
        mShadeController = shadeController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mSystemActions = systemActions;
        mUiEventLogger = uiEventLogger;
        mHandler = mainHandler;
        mNavigationBarA11yHelper = navigationBarA11yHelper;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        commandQueue.addCallback(this);
        configurationController.addCallback(this);
        mConfigChanges.applyNewConfig(mContext.getResources());
        mNavBarOverlayController = navBarOverlayController;
        mNavMode = mNavigationModeController.addListener(this);
        mNavigationModeController.addListener(this);
        mTaskbarDelegate = new TaskbarDelegate(mOverviewProxyService,
                navigationBarA11yHelper, mSysUiFlagsContainer);
        mIsTablet = isTablet(mContext.getResources().getConfiguration());
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        boolean isOldConfigTablet = mIsTablet;
        mIsTablet = isTablet(newConfig);
        boolean largeScreenChanged = mIsTablet != isOldConfigTablet;
        // If we folded/unfolded while in 3 button, show navbar in folded state, hide in unfolded
        if (largeScreenChanged && updateNavbarForTaskbar()) {
            return;
        }

        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            for (int i = 0; i < mNavigationBars.size(); i++) {
                recreateNavigationBar(mNavigationBars.keyAt(i));
            }
        } else {
            for (int i = 0; i < mNavigationBars.size(); i++) {
                mNavigationBars.valueAt(i).onConfigurationChanged(newConfig);
            }
        }
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        if (mNavMode == mode) {
            return;
        }
        final int oldMode = mNavMode;
        mNavMode = mode;
        mHandler.post(() -> {
            // create/destroy nav bar based on nav mode only in unfolded state
            if (oldMode != mNavMode) {
                updateNavbarForTaskbar();
            }
            for (int i = 0; i < mNavigationBars.size(); i++) {
                NavigationBar navBar = mNavigationBars.valueAt(i);
                if (navBar == null) {
                    continue;
                }
                navBar.getView().updateStates();
            }
        });
    }

    /**
     * @return {@code true} if navbar was added/removed, false otherwise
     */
    public boolean updateNavbarForTaskbar() {
        if (!isThreeButtonTaskbarFlagEnabled()) {
            return false;
        }

        if (mIsTablet && mNavMode == NAV_BAR_MODE_3BUTTON) {
            // Remove navigation bar when taskbar is showing, currently only for 3 button mode
            removeNavigationBar(mContext.getDisplayId());
            mCommandQueue.addCallback(mTaskbarDelegate);
            mTaskbarDelegate.init(mContext.getDisplayId());
        } else if (mNavigationBars.get(mContext.getDisplayId()) == null) {
            // Add navigation bar after taskbar goes away
            createNavigationBar(mContext.getDisplay(), null, null);
            mCommandQueue.removeCallback(mTaskbarDelegate);
            mTaskbarDelegate.destroy();
        }

        return true;
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        removeNavigationBar(displayId);
    }

    @Override
    public void onDisplayReady(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        mIsTablet = isTablet(mContext.getResources().getConfiguration());
        createNavigationBar(display, null /* savedState */, null /* result */);
    }

    @Override
    public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        final NavigationBarView navigationBarView = getNavigationBarView(displayId);
        if (navigationBarView != null) {
            navigationBarView.setNavigationBarLumaSamplingEnabled(enable);
        }
    }

    /**
     * Recreates the navigation bar for the given display.
     */
    private void recreateNavigationBar(int displayId) {
        // TODO: Improve this flow so that we don't need to create a new nav bar but just
        //       the view
        Bundle savedState = new Bundle();
        NavigationBar bar = mNavigationBars.get(displayId);
        if (bar != null) {
            bar.onSaveInstanceState(savedState);
        }
        removeNavigationBar(displayId);
        createNavigationBar(mDisplayManager.getDisplay(displayId), savedState, null /* result */);
    }

    // TODO(b/117478341): I use {@code includeDefaultDisplay} to make this method compatible to
    // CarStatusBar because they have their own nav bar. Think about a better way for it.
    /**
     * Creates navigation bars when car/status bar initializes.
     *
     * @param includeDefaultDisplay {@code true} to create navigation bar on default display.
     */
    public void createNavigationBars(final boolean includeDefaultDisplay,
            RegisterStatusBarResult result) {
        if (updateNavbarForTaskbar()) {
            return;
        }

        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            if (includeDefaultDisplay || display.getDisplayId() != DEFAULT_DISPLAY) {
                createNavigationBar(display, null /* savedState */, result);
            }
        }
    }

    /**
     * Adds a navigation bar on default display or an external display if the display supports
     * system decorations.
     *
     * @param display the display to add navigation bar on.
     */
    @VisibleForTesting
    void createNavigationBar(Display display, Bundle savedState, RegisterStatusBarResult result) {
        if (display == null) {
            return;
        }

        if (isThreeButtonTaskbarEnabled()) {
            return;
        }

        final int displayId = display.getDisplayId();
        final boolean isOnDefaultDisplay = displayId == DEFAULT_DISPLAY;
        final IWindowManager wms = WindowManagerGlobal.getWindowManagerService();

        try {
            if (!wms.hasNavigationBar(displayId)) {
                return;
            }
        } catch (RemoteException e) {
            // Cannot get wms, just return with warning message.
            Log.w(TAG, "Cannot get WindowManager.");
            return;
        }
        final Context context = isOnDefaultDisplay
                ? mContext
                : mContext.createDisplayContext(display);
        NavigationBar navBar = new NavigationBar(context,
                mWindowManager,
                mAssistManagerLazy,
                mAccessibilityManager,
                mAccessibilityManagerWrapper,
                mDeviceProvisionedController,
                mMetricsLogger,
                mOverviewProxyService,
                mNavigationModeController,
                mAccessibilityButtonModeObserver,
                mStatusBarStateController,
                mSysUiFlagsContainer,
                mBroadcastDispatcher,
                mCommandQueue,
                mPipOptional,
                mSplitScreenOptional,
                mRecentsOptional,
                mStatusBarLazy,
                mShadeController,
                mNotificationRemoteInputManager,
                mSystemActions,
                mHandler,
                mNavBarOverlayController,
                mUiEventLogger,
                mNavigationBarA11yHelper);
        mNavigationBars.put(displayId, navBar);

        View navigationBarView = navBar.createView(savedState);
        navigationBarView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (result != null) {
                    navBar.setImeWindowStatus(display.getDisplayId(), result.mImeToken,
                            result.mImeWindowVis, result.mImeBackDisposition,
                            result.mShowImeSwitcher);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    void removeNavigationBar(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.setAutoHideController(/* autoHideController */ null);
            navBar.destroyView();
            mNavigationBars.remove(displayId);
        }
    }

    /** @see NavigationBar#checkNavBarModes() */
    public void checkNavBarModes(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.checkNavBarModes();
        }
    }

    /** @see NavigationBar#finishBarAnimations() */
    public void finishBarAnimations(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.finishBarAnimations();
        }
    }

    /** @see NavigationBar#touchAutoDim() */
    public void touchAutoDim(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.touchAutoDim();
        }
    }

    /** @see NavigationBar#transitionTo(int, boolean) */
    public void transitionTo(int displayId, @TransitionMode int barMode, boolean animate) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.transitionTo(barMode, animate);
        }
    }

    /** @see NavigationBar#disableAnimationsDuringHide(long) */
    public void disableAnimationsDuringHide(int displayId, long delay) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.disableAnimationsDuringHide(delay);
        }
    }

    /** @return {@link NavigationBarView} on the default display. */
    public @Nullable NavigationBarView getDefaultNavigationBarView() {
        return getNavigationBarView(DEFAULT_DISPLAY);
    }

    /**
     * @param displayId the ID of display which Navigation bar is on
     * @return {@link NavigationBarView} on the display with {@code displayId}.
     *         {@code null} if no navigation bar on that display.
     */
    public @Nullable NavigationBarView getNavigationBarView(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        return (navBar == null) ? null : navBar.getView();
    }

    /** @return {@link NavigationBar} on the default display. */
    @Nullable
    public NavigationBar getDefaultNavigationBar() {
        return mNavigationBars.get(DEFAULT_DISPLAY);
    }

    private boolean isThreeButtonTaskbarEnabled() {
        return mIsTablet && mNavMode == NAV_BAR_MODE_3BUTTON &&
                isThreeButtonTaskbarFlagEnabled();
    }

    private boolean isThreeButtonTaskbarFlagEnabled() {
        return SystemProperties.getBoolean("persist.debug.taskbar_three_button", false);
    }

    private boolean isTablet(Configuration newConfig) {
        float density = Resources.getSystem().getDisplayMetrics().density;
        int size = Math.min((int) (density * newConfig.screenWidthDp),
                (int) (density* newConfig.screenHeightDp));
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        float densityRatio = (float) metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio) >= TABLET_MIN_DPS;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        for (int i = 0; i < mNavigationBars.size(); i++) {
            if (i > 0) {
                pw.println();
            }
            mNavigationBars.valueAt(i).dump(pw);
        }
    }
}
