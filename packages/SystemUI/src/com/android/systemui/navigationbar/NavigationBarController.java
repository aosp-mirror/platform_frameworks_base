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

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.shared.recents.utilities.Utilities.isTablet;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManagerGlobal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.Optional;

import javax.inject.Inject;


/** A controller to handle navigation bars. */
@SysUISingleton
public class NavigationBarController implements
        Callbacks,
        ConfigurationController.ConfigurationListener,
        NavigationModeController.ModeChangedListener,
        Dumpable {

    private static final String TAG = NavigationBarController.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;
    private final NavigationBarComponent.Factory mNavigationBarComponentFactory;
    private final DisplayManager mDisplayManager;
    private final TaskbarDelegate mTaskbarDelegate;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private int mNavMode;
    @VisibleForTesting boolean mIsTablet;

    /** A displayId - nav bar maps. */
    @VisibleForTesting
    SparseArray<NavigationBar> mNavigationBars = new SparseArray<>();

    // Tracks config changes that will actually recreate the nav bar
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_UI_MODE);

    @Inject
    public NavigationBarController(Context context,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            SysUiState sysUiFlagsContainer,
            CommandQueue commandQueue,
            @Main Handler mainHandler,
            ConfigurationController configurationController,
            NavBarHelper navBarHelper,
            TaskbarDelegate taskbarDelegate,
            NavigationBarComponent.Factory navigationBarComponentFactory,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            DumpManager dumpManager,
            AutoHideController autoHideController,
            LightBarController lightBarController,
            Optional<Pip> pipOptional,
            Optional<BackAnimation> backAnimation) {
        mContext = context;
        mHandler = mainHandler;
        mNavigationBarComponentFactory = navigationBarComponentFactory;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        commandQueue.addCallback(this);
        configurationController.addCallback(this);
        mConfigChanges.applyNewConfig(mContext.getResources());
        mNavMode = navigationModeController.addListener(this);
        mTaskbarDelegate = taskbarDelegate;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mTaskbarDelegate.setDependencies(commandQueue, overviewProxyService,
                navBarHelper, navigationModeController, sysUiFlagsContainer,
                dumpManager, autoHideController, lightBarController, pipOptional,
                backAnimation.orElse(null));
        mIsTablet = isTablet(mContext);
        dumpManager.registerDumpable(this);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        boolean isOldConfigTablet = mIsTablet;
        mIsTablet = isTablet(mContext);
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
        updateAccessibilityButtonModeIfNeeded();

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

    private void updateAccessibilityButtonModeIfNeeded() {
        ContentResolver contentResolver = mContext.getContentResolver();
        final int mode = Settings.Secure.getIntForUser(contentResolver,
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, UserHandle.USER_CURRENT);

        // ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU is compatible under gestural or non-gestural
        // mode, so we don't need to update it.
        if (mode == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU) {
            return;
        }

        // ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR is incompatible under gestural mode. Need to
        // force update to ACCESSIBILITY_BUTTON_MODE_GESTURE.
        if (QuickStepContract.isGesturalMode(mNavMode)
                && mode == ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR) {
            Settings.Secure.putIntForUser(contentResolver,
                    Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_GESTURE,
                    UserHandle.USER_CURRENT);
            // ACCESSIBILITY_BUTTON_MODE_GESTURE is incompatible under non gestural mode. Need to
            // force update to ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR.
        } else if (!QuickStepContract.isGesturalMode(mNavMode)
                && mode == ACCESSIBILITY_BUTTON_MODE_GESTURE) {
            Settings.Secure.putIntForUser(contentResolver,
                    Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                    ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, UserHandle.USER_CURRENT);
        }
    }

    /** @see #initializeTaskbarIfNecessary() */
    private boolean updateNavbarForTaskbar() {
        boolean taskbarShown = initializeTaskbarIfNecessary();
        if (!taskbarShown && mNavigationBars.get(mContext.getDisplayId()) == null) {
            createNavigationBar(mContext.getDisplay(), null, null);
        }
        return taskbarShown;
    }

    /** @return {@code true} if taskbar is enabled, false otherwise */
    private boolean initializeTaskbarIfNecessary() {
        if (mIsTablet) {
            Trace.beginSection("NavigationBarController#initializeTaskbarIfNecessary");
            // Remove navigation bar when taskbar is showing
            removeNavigationBar(mContext.getDisplayId());
            mTaskbarDelegate.init(mContext.getDisplayId());
            Trace.endSection();
        } else {
            mTaskbarDelegate.destroy();
        }
        return mIsTablet;
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        removeNavigationBar(displayId);
    }

    @Override
    public void onDisplayReady(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        mIsTablet = isTablet(mContext);
        createNavigationBar(display, null /* savedState */, null /* result */);
    }

    @Override
    public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        final NavigationBar navigationBar = getNavigationBar(displayId);
        if (navigationBar != null) {
            navigationBar.setNavigationBarLumaSamplingEnabled(enable);
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
        updateAccessibilityButtonModeIfNeeded();

        // Don't need to create nav bar on the default display if we initialize TaskBar.
        final boolean shouldCreateDefaultNavbar = includeDefaultDisplay
                && !initializeTaskbarIfNecessary();
        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            if (shouldCreateDefaultNavbar || display.getDisplayId() != DEFAULT_DISPLAY) {
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

        final int displayId = display.getDisplayId();
        final boolean isOnDefaultDisplay = displayId == DEFAULT_DISPLAY;

        // We may show TaskBar on the default display for large screen device. Don't need to create
        // navigation bar for this case.
        if (mIsTablet && isOnDefaultDisplay) {
            return;
        }

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
        NavigationBarComponent component = mNavigationBarComponentFactory.create(
                context, savedState);
        NavigationBar navBar = component.getNavigationBar();
        navBar.init();
        mNavigationBars.put(displayId, navBar);

        navBar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
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
        NavigationBar navBar = getNavigationBar(displayId);
        return (navBar == null) ? null : navBar.getView();
    }

    private @Nullable NavigationBar getNavigationBar(int displayId) {
        return mNavigationBars.get(displayId);
    }

    public void showPinningEnterExitToast(int displayId, boolean entering) {
        final NavigationBarView navBarView = getNavigationBarView(displayId);
        if (navBarView != null) {
            navBarView.showPinningEnterExitToast(entering);
        } else if (displayId == DEFAULT_DISPLAY && mTaskbarDelegate.isInitialized()) {
            mTaskbarDelegate.showPinningEnterExitToast(entering);
        }
    }

    public void showPinningEscapeToast(int displayId) {
        final NavigationBarView navBarView = getNavigationBarView(displayId);
        if (navBarView != null) {
            navBarView.showPinningEscapeToast();
        } else if (displayId == DEFAULT_DISPLAY && mTaskbarDelegate.isInitialized()) {
            mTaskbarDelegate.showPinningEscapeToast();
        }
    }

    public boolean isOverviewEnabled(int displayId) {
        final NavigationBarView navBarView = getNavigationBarView(displayId);
        if (navBarView != null) {
            return navBarView.isOverviewEnabled();
        } else {
            return mTaskbarDelegate.isOverviewEnabled();
        }
    }

    /** @return {@link NavigationBar} on the default display. */
    @Nullable
    public NavigationBar getDefaultNavigationBar() {
        return mNavigationBars.get(DEFAULT_DISPLAY);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        for (int i = 0; i < mNavigationBars.size(); i++) {
            if (i > 0) {
                pw.println();
            }
            mNavigationBars.valueAt(i).dump(pw);
        }
    }
}
