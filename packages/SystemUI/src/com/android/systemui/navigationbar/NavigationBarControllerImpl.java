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

import static com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.DEBUG_MISSING_GESTURE_TAG;
import static com.android.systemui.shared.recents.utilities.Utilities.isLargeScreen;
import static com.android.wm.shell.Flags.enableTaskbarNavbarUnification;
import static com.android.wm.shell.Flags.enableTaskbarOnPhones;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManagerGlobal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.views.NavigationBar;
import com.android.systemui.navigationbar.views.NavigationBarView;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class NavigationBarControllerImpl implements
        ConfigurationController.ConfigurationListener,
        NavigationModeController.ModeChangedListener,
        Dumpable, NavigationBarController {

    private static final String TAG = NavigationBarControllerImpl.class.getSimpleName();

    private final Context mContext;
    private final Executor mExecutor;
    private final NavigationBarComponent.Factory mNavigationBarComponentFactory;
    private final SecureSettings mSecureSettings;
    private final DisplayTracker mDisplayTracker;
    private final DisplayManager mDisplayManager;
    private final TaskbarDelegate mTaskbarDelegate;
    private final NavBarHelper mNavBarHelper;
    private int mNavMode;
    /**
     * Indicates whether the active display is a large screen, e.g. tablets, foldable devices in
     * the unfolded state.
     */
    @VisibleForTesting boolean mIsLargeScreen;
    /**
     * Indicates whether the device is a phone, rather than everything else (e.g. foldables,
     * tablets) is considered not a handheld device.
     */
    @VisibleForTesting boolean mIsPhone;

    /** A displayId - nav bar maps. */
    @VisibleForTesting
    SparseArray<NavigationBar> mNavigationBars = new SparseArray<>();

    /** Local cache for {@link IWindowManager#hasNavigationBar(int)}. */
    private SparseBooleanArray mHasNavBar = new SparseBooleanArray();

    // Tracks config changes that will actually recreate the nav bar
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE
                    | ActivityInfo.CONFIG_UI_MODE);

    @Inject
    public NavigationBarControllerImpl(Context context,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            SysUiState sysUiFlagsContainer,
            CommandQueue commandQueue,
            @Main Executor mainExecutor,
            ConfigurationController configurationController,
            NavBarHelper navBarHelper,
            TaskbarDelegate taskbarDelegate,
            NavigationBarComponent.Factory navigationBarComponentFactory,
            DumpManager dumpManager,
            AutoHideController autoHideController,
            LightBarController lightBarController,
            TaskStackChangeListeners taskStackChangeListeners,
            Optional<Pip> pipOptional,
            Optional<BackAnimation> backAnimation,
            SecureSettings secureSettings,
            DisplayTracker displayTracker) {
        mContext = context;
        mExecutor = mainExecutor;
        mNavigationBarComponentFactory = navigationBarComponentFactory;
        mSecureSettings = secureSettings;
        mDisplayTracker = displayTracker;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        commandQueue.addCallback(mCommandQueueCallbacks);
        configurationController.addCallback(this);
        mConfigChanges.applyNewConfig(mContext.getResources());
        mNavMode = navigationModeController.addListener(this);
        mNavBarHelper = navBarHelper;
        mTaskbarDelegate = taskbarDelegate;
        mTaskbarDelegate.setDependencies(commandQueue, overviewProxyService,
                navBarHelper, navigationModeController, sysUiFlagsContainer,
                dumpManager, autoHideController, lightBarController, pipOptional,
                backAnimation.orElse(null), taskStackChangeListeners);
        mIsLargeScreen = isLargeScreen(mContext);
        mIsPhone =
                mContext.getResources().getIntArray(R.array.config_foldedDeviceStates).length == 0;
        dumpManager.registerDumpable(this);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        boolean isOldConfigLargeScreen = mIsLargeScreen;
        mIsLargeScreen = isLargeScreen(mContext);
        boolean willApplyConfig = mConfigChanges.applyNewConfig(mContext.getResources());
        boolean largeScreenChanged = mIsLargeScreen != isOldConfigLargeScreen;
        // TODO(b/332635834): Disable this logging once b/332635834 is fixed.
        Log.i(DEBUG_MISSING_GESTURE_TAG, "NavbarController: newConfig=" + newConfig
                + " mTaskbarDelegate initialized=" + mTaskbarDelegate.isInitialized()
                + " willApplyConfigToNavbars=" + willApplyConfig
                + " navBarCount=" + mNavigationBars.size());
        // If we folded/unfolded while in 3 button, show navbar in folded state, hide in unfolded
        if (largeScreenChanged && updateNavbarForTaskbar()) {
            return;
        }

        if (willApplyConfig) {
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

        mExecutor.execute(() -> {
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

    private boolean shouldCreateNavBarAndTaskBar(int displayId) {
        if (mHasNavBar.indexOfKey(displayId) > -1) {
            return mHasNavBar.get(displayId);
        }

        final IWindowManager wms = WindowManagerGlobal.getWindowManagerService();

        try {
            boolean hasNavigationBar = wms.hasNavigationBar(displayId);
            mHasNavBar.put(displayId, hasNavigationBar);
            return hasNavigationBar;
        } catch (RemoteException e) {
            // Cannot get wms, just return false with warning message.
            Log.w(TAG, "Cannot get WindowManager.");
            return false;
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
        boolean taskbarEnabled = supportsTaskbar() && shouldCreateNavBarAndTaskBar(
                mContext.getDisplayId());

        if (taskbarEnabled) {
            Trace.beginSection("NavigationBarController#initializeTaskbarIfNecessary");
            final int displayId = mContext.getDisplayId();
            // Hint to NavBarHelper if we are replacing an existing bar to skip extra work
            mNavBarHelper.setTogglingNavbarTaskbar(mNavigationBars.contains(displayId));
            // Remove navigation bar when taskbar is showing
            removeNavigationBar(displayId);
            mTaskbarDelegate.init(displayId);
            mNavBarHelper.setTogglingNavbarTaskbar(false);
            Trace.endSection();

        } else {
            mTaskbarDelegate.destroy();
        }
        return taskbarEnabled;
    }

    @VisibleForTesting
    boolean supportsTaskbar() {
        // Enable for tablets, unfolded state on a foldable device, (non handheld AND flag is set),
        // or handheld when enableTaskbarOnPhones() returns true.
        boolean foldedOrPhone = !mIsPhone || enableTaskbarOnPhones();
        return mIsLargeScreen || (foldedOrPhone && enableTaskbarNavbarUnification());
    }

    private final CommandQueue.Callbacks mCommandQueueCallbacks = new CommandQueue.Callbacks() {
        @Override
        public void onDisplayRemoved(int displayId) {
            removeNavigationBar(displayId);
            mHasNavBar.delete(displayId);
        }

        @Override
        public void onDisplayReady(int displayId) {
            Display display = mDisplayManager.getDisplay(displayId);
            mIsLargeScreen = isLargeScreen(mContext);
            createNavigationBar(display, null /* savedState */, null /* result */);
        }

        @Override
        public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
            final NavigationBar navigationBar = getNavigationBar(displayId);
            if (navigationBar != null) {
                navigationBar.setNavigationBarLumaSamplingEnabled(enable);
            }
        }

        @Override
        public void showPinningEnterExitToast(boolean entering) {
            int displayId = mContext.getDisplayId();
            final NavigationBarView navBarView = getNavigationBarView(displayId);
            if (navBarView != null) {
                navBarView.showPinningEnterExitToast(entering);
            }
        }

        @Override
        public void showPinningEscapeToast() {
            int displayId = mContext.getDisplayId();
            final NavigationBarView navBarView = getNavigationBarView(displayId);
            if (navBarView != null) {
                navBarView.showPinningEscapeToast();
            }
        }
    };

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

    @Override
    public void createNavigationBars(final boolean includeDefaultDisplay,
            RegisterStatusBarResult result) {
        // Don't need to create nav bar on the default display if we initialize TaskBar.
        final boolean shouldCreateDefaultNavbar = includeDefaultDisplay
                && !initializeTaskbarIfNecessary();
        Display[] displays = mDisplayTracker.getAllDisplays();
        for (Display display : displays) {
            if (shouldCreateDefaultNavbar
                    || display.getDisplayId() != mDisplayTracker.getDefaultDisplayId()) {
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
    void createNavigationBar(Display display, Bundle savedState,
            RegisterStatusBarResult result) {
        if (display == null) {
            return;
        }

        final int displayId = display.getDisplayId();
        final boolean isOnDefaultDisplay = displayId == mDisplayTracker.getDefaultDisplayId();

        if (!shouldCreateNavBarAndTaskBar(displayId)) {
            return;
        }

        // We may show TaskBar on the default display for large screen device. Don't need to create
        // navigation bar for this case.
        if (isOnDefaultDisplay && initializeTaskbarIfNecessary()) {
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
                    navBar.setImeWindowStatus(display.getDisplayId(), result.mImeWindowVis,
                            result.mImeBackDisposition, result.mShowImeSwitcher);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    @Override
    public void removeNavigationBar(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.destroyView();
            mNavigationBars.remove(displayId);
        }
    }

    @Override
    public void checkNavBarModes(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.checkNavBarModes();
        } else {
            mTaskbarDelegate.checkNavBarModes();
        }
    }

    @Override
    public void finishBarAnimations(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.finishBarAnimations();
        } else {
            mTaskbarDelegate.finishBarAnimations();
        }
    }

    @Override
    public void touchAutoDim(int displayId) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.touchAutoDim();
        } else {
            mTaskbarDelegate.touchAutoDim();
        }
    }

    @Override
    public void transitionTo(int displayId, @TransitionMode int barMode, boolean animate) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.transitionTo(barMode, animate);
        } else {
            mTaskbarDelegate.transitionTo(barMode, animate);
        }
    }

    @Override
    public void disableAnimationsDuringHide(int displayId, long delay) {
        NavigationBar navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.disableAnimationsDuringHide(delay);
        }
    }

    @Override
    public @Nullable NavigationBarView getDefaultNavigationBarView() {
        return getNavigationBarView(mDisplayTracker.getDefaultDisplayId());
    }

    @Override
    public @Nullable NavigationBarView getNavigationBarView(int displayId) {
        NavigationBar navBar = getNavigationBar(displayId);
        return (navBar == null) ? null : navBar.getView();
    }

    private @Nullable NavigationBar getNavigationBar(int displayId) {
        return mNavigationBars.get(displayId);
    }

    @Override
    public boolean isOverviewEnabled(int displayId) {
        final NavigationBarView navBarView = getNavigationBarView(displayId);
        if (navBarView != null) {
            return navBarView.isOverviewEnabled();
        } else {
            return mTaskbarDelegate.isOverviewEnabled();
        }
    }

    @Override
    @Nullable
    public NavigationBar getDefaultNavigationBar() {
        return mNavigationBars.get(mDisplayTracker.getDefaultDisplayId());
    }

    @NeverCompile
    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mIsLargeScreen=" + mIsLargeScreen);
        pw.println("mNavMode=" + mNavMode);
        for (int i = 0; i < mNavigationBars.size(); i++) {
            if (i > 0) {
                pw.println();
            }
            mNavigationBars.valueAt(i).dump(pw);
        }
    }
}
