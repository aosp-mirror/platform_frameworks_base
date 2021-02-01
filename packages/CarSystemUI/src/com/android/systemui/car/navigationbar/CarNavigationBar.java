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

package com.android.systemui.car.navigationbar;

import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.content.Context;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.SystemUI;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy;
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;

/** Navigation bars customized for the automotive use case. */
public class CarNavigationBar extends SystemUI implements CommandQueue.Callbacks {
    private final Resources mResources;
    private final CarNavigationBarController mCarNavigationBarController;
    private final SysuiDarkIconDispatcher mStatusBarIconController;
    private final WindowManager mWindowManager;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final CommandQueue mCommandQueue;
    private final AutoHideController mAutoHideController;
    private final ButtonSelectionStateListener mButtonSelectionStateListener;
    private final Handler mMainHandler;
    private final Executor mUiBgExecutor;
    private final IStatusBarService mBarService;
    private final Lazy<KeyguardStateController> mKeyguardStateControllerLazy;
    private final Lazy<PhoneStatusBarPolicy> mIconPolicyLazy;
    private final Lazy<StatusBarIconController> mIconControllerLazy;

    private final int mDisplayId;
    private final SystemBarConfigs mSystemBarConfigs;

    private StatusBarSignalPolicy mSignalPolicy;
    private ActivityManagerWrapper mActivityManagerWrapper;

    // If the nav bar should be hidden when the soft keyboard is visible.
    private boolean mHideTopBarForKeyboard;
    private boolean mHideLeftBarForKeyboard;
    private boolean mHideRightBarForKeyboard;
    private boolean mHideBottomBarForKeyboard;

    private boolean mBottomNavBarVisible;

    // Nav bar views.
    private ViewGroup mTopNavigationBarWindow;
    private ViewGroup mBottomNavigationBarWindow;
    private ViewGroup mLeftNavigationBarWindow;
    private ViewGroup mRightNavigationBarWindow;
    private CarNavigationBarView mTopNavigationBarView;
    private CarNavigationBarView mBottomNavigationBarView;
    private CarNavigationBarView mLeftNavigationBarView;
    private CarNavigationBarView mRightNavigationBarView;

    // To be attached to the navigation bars such that they can close the notification panel if
    // it's open.
    private boolean mDeviceIsSetUpForUser = true;
    private boolean mIsUserSetupInProgress = false;

    private AppearanceRegion[] mAppearanceRegions = new AppearanceRegion[0];
    @BarTransitions.TransitionMode
    private int mStatusBarMode;
    @BarTransitions.TransitionMode
    private int mNavigationBarMode;
    private boolean mStatusBarTransientShown;
    private boolean mNavBarTransientShown;

    @Inject
    public CarNavigationBar(Context context,
            @Main Resources resources,
            CarNavigationBarController carNavigationBarController,
            // TODO(b/156052638): Should not need to inject LightBarController
            LightBarController lightBarController,
            DarkIconDispatcher darkIconDispatcher,
            WindowManager windowManager,
            CarDeviceProvisionedController deviceProvisionedController,
            CommandQueue commandQueue,
            AutoHideController autoHideController,
            ButtonSelectionStateListener buttonSelectionStateListener,
            @Main Handler mainHandler,
            @UiBackground Executor uiBgExecutor,
            IStatusBarService barService,
            Lazy<KeyguardStateController> keyguardStateControllerLazy,
            Lazy<PhoneStatusBarPolicy> iconPolicyLazy,
            Lazy<StatusBarIconController> iconControllerLazy,
            SystemBarConfigs systemBarConfigs
    ) {
        super(context);
        mResources = resources;
        mCarNavigationBarController = carNavigationBarController;
        mStatusBarIconController = (SysuiDarkIconDispatcher) darkIconDispatcher;
        mWindowManager = windowManager;
        mCarDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mAutoHideController = autoHideController;
        mButtonSelectionStateListener = buttonSelectionStateListener;
        mMainHandler = mainHandler;
        mUiBgExecutor = uiBgExecutor;
        mBarService = barService;
        mKeyguardStateControllerLazy = keyguardStateControllerLazy;
        mIconPolicyLazy = iconPolicyLazy;
        mIconControllerLazy = iconControllerLazy;
        mSystemBarConfigs = systemBarConfigs;

        mDisplayId = context.getDisplayId();
    }

    @Override
    public void start() {
        // Set initial state.
        mHideTopBarForKeyboard = mSystemBarConfigs.getHideForKeyboardBySide(SystemBarConfigs.TOP);
        mHideBottomBarForKeyboard = mSystemBarConfigs.getHideForKeyboardBySide(
                SystemBarConfigs.BOTTOM);
        mHideLeftBarForKeyboard = mSystemBarConfigs.getHideForKeyboardBySide(SystemBarConfigs.LEFT);
        mHideRightBarForKeyboard = mSystemBarConfigs.getHideForKeyboardBySide(
                SystemBarConfigs.RIGHT);

        mBottomNavBarVisible = false;

        // Connect into the status bar manager service
        mCommandQueue.addCallback(this);

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        onSystemBarAppearanceChanged(mDisplayId, result.mAppearance, result.mAppearanceRegions,
                result.mNavbarColorManagedByIme);

        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(mDisplayId, result.mImeToken, result.mImeWindowVis,
                result.mImeBackDisposition, result.mShowImeSwitcher);

        // Set up the initial icon state
        int numIcons = result.mIcons.size();
        for (int i = 0; i < numIcons; i++) {
            mCommandQueue.setIcon(result.mIcons.keyAt(i), result.mIcons.valueAt(i));
        }

        mAutoHideController.setStatusBar(new AutoHideUiElement() {
            @Override
            public void synchronizeState() {
                // No op.
            }

            @Override
            public boolean isVisible() {
                return mStatusBarTransientShown;
            }

            @Override
            public void hide() {
                clearTransient();
            }
        });

        mAutoHideController.setNavigationBar(new AutoHideUiElement() {
            @Override
            public void synchronizeState() {
                // No op.
            }

            @Override
            public boolean isVisible() {
                return mNavBarTransientShown;
            }

            @Override
            public void hide() {
                clearTransient();
            }
        });

        mDeviceIsSetUpForUser = mCarDeviceProvisionedController.isCurrentUserSetup();
        mIsUserSetupInProgress = mCarDeviceProvisionedController.isCurrentUserSetupInProgress();
        mCarDeviceProvisionedController.addCallback(
                new CarDeviceProvisionedListener() {
                    @Override
                    public void onUserSetupInProgressChanged() {
                        mMainHandler.post(() -> restartNavBarsIfNecessary());
                    }

                    @Override
                    public void onUserSetupChanged() {
                        mMainHandler.post(() -> restartNavBarsIfNecessary());
                    }

                    @Override
                    public void onUserSwitched() {
                        mMainHandler.post(() -> restartNavBarsIfNecessary());
                    }
                });

        createNavigationBar(result);

        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mActivityManagerWrapper.registerTaskStackListener(mButtonSelectionStateListener);

        mUiBgExecutor.execute(mCarNavigationBarController::connectToHvac);

        // Lastly, call to the icon policy to install/update all the icons.
        // Must be called on the main thread due to the use of observeForever() in
        // mIconPolicy.init().
        mMainHandler.post(() -> {
            mIconPolicyLazy.get().init();
            mSignalPolicy = new StatusBarSignalPolicy(mContext, mIconControllerLazy.get());
        });
    }

    private void restartNavBarsIfNecessary() {
        boolean currentUserSetup = mCarDeviceProvisionedController.isCurrentUserSetup();
        boolean currentUserSetupInProgress = mCarDeviceProvisionedController
                .isCurrentUserSetupInProgress();
        if (mIsUserSetupInProgress != currentUserSetupInProgress
                || mDeviceIsSetUpForUser != currentUserSetup) {
            mDeviceIsSetUpForUser = currentUserSetup;
            mIsUserSetupInProgress = currentUserSetupInProgress;
            restartNavBars();
        }
    }

    /**
     * Remove all content from navbars and rebuild them. Used to allow for different nav bars
     * before and after the device is provisioned. . Also for change of density and font size.
     */
    private void restartNavBars() {
        // remove and reattach all components such that we don't keep a reference to unused ui
        // elements
        mCarNavigationBarController.removeAll();

        if (mTopNavigationBarWindow != null) {
            mTopNavigationBarWindow.removeAllViews();
            mTopNavigationBarView = null;
        }

        if (mBottomNavigationBarWindow != null) {
            mBottomNavigationBarWindow.removeAllViews();
            mBottomNavigationBarView = null;
        }

        if (mLeftNavigationBarWindow != null) {
            mLeftNavigationBarWindow.removeAllViews();
            mLeftNavigationBarView = null;
        }

        if (mRightNavigationBarWindow != null) {
            mRightNavigationBarWindow.removeAllViews();
            mRightNavigationBarView = null;
        }

        buildNavBarContent();
        // If the UI was rebuilt (day/night change or user change) while the keyguard was up we need
        // to correctly respect that state.
        if (mKeyguardStateControllerLazy.get().isShowing()) {
            mCarNavigationBarController.showAllKeyguardButtons(isDeviceSetupForUser());
        } else {
            mCarNavigationBarController.hideAllKeyguardButtons(isDeviceSetupForUser());
        }

        // Upon restarting the Navigation Bar, CarFacetButtonController should immediately apply the
        // selection state that reflects the current task stack.
        mButtonSelectionStateListener.onTaskStackChanged();
    }

    private boolean isDeviceSetupForUser() {
        return mDeviceIsSetUpForUser && !mIsUserSetupInProgress;
    }

    private void createNavigationBar(RegisterStatusBarResult result) {
        buildNavBarWindows();
        buildNavBarContent();
        attachNavBarWindows();

        // Try setting up the initial state of the nav bar if applicable.
        if (result != null) {
            setImeWindowStatus(Display.DEFAULT_DISPLAY, result.mImeToken,
                    result.mImeWindowVis, result.mImeBackDisposition,
                    result.mShowImeSwitcher);
        }
    }

    private void buildNavBarWindows() {
        mTopNavigationBarWindow = mCarNavigationBarController.getTopWindow();
        mBottomNavigationBarWindow = mCarNavigationBarController.getBottomWindow();
        mLeftNavigationBarWindow = mCarNavigationBarController.getLeftWindow();
        mRightNavigationBarWindow = mCarNavigationBarController.getRightWindow();
    }

    private void buildNavBarContent() {
        mTopNavigationBarView = mCarNavigationBarController.getTopBar(isDeviceSetupForUser());
        if (mTopNavigationBarView != null) {
            mSystemBarConfigs.insetSystemBar(SystemBarConfigs.TOP, mTopNavigationBarView);
            mTopNavigationBarWindow.addView(mTopNavigationBarView);
        }

        mBottomNavigationBarView = mCarNavigationBarController.getBottomBar(isDeviceSetupForUser());
        if (mBottomNavigationBarView != null) {
            mSystemBarConfigs.insetSystemBar(SystemBarConfigs.BOTTOM, mBottomNavigationBarView);
            mBottomNavigationBarWindow.addView(mBottomNavigationBarView);
        }

        mLeftNavigationBarView = mCarNavigationBarController.getLeftBar(isDeviceSetupForUser());
        if (mLeftNavigationBarView != null) {
            mSystemBarConfigs.insetSystemBar(SystemBarConfigs.LEFT, mLeftNavigationBarView);
            mLeftNavigationBarWindow.addView(mLeftNavigationBarView);
        }

        mRightNavigationBarView = mCarNavigationBarController.getRightBar(isDeviceSetupForUser());
        if (mRightNavigationBarView != null) {
            mSystemBarConfigs.insetSystemBar(SystemBarConfigs.RIGHT, mRightNavigationBarView);
            mRightNavigationBarWindow.addView(mRightNavigationBarView);
        }
    }

    private void attachNavBarWindows() {
        mSystemBarConfigs.getSystemBarSidesByZOrder().forEach(this::attachNavBarBySide);
    }

    private void attachNavBarBySide(int side) {
        switch(side) {
            case SystemBarConfigs.TOP:
                if (mTopNavigationBarWindow != null) {
                    mWindowManager.addView(mTopNavigationBarWindow,
                            mSystemBarConfigs.getLayoutParamsBySide(SystemBarConfigs.TOP));
                }
                break;
            case SystemBarConfigs.BOTTOM:
                if (mBottomNavigationBarWindow != null && !mBottomNavBarVisible) {
                    mBottomNavBarVisible = true;

                    mWindowManager.addView(mBottomNavigationBarWindow,
                            mSystemBarConfigs.getLayoutParamsBySide(SystemBarConfigs.BOTTOM));
                }
                break;
            case SystemBarConfigs.LEFT:
                if (mLeftNavigationBarWindow != null) {
                    mWindowManager.addView(mLeftNavigationBarWindow,
                            mSystemBarConfigs.getLayoutParamsBySide(SystemBarConfigs.LEFT));
                }
                break;
            case SystemBarConfigs.RIGHT:
                if (mRightNavigationBarWindow != null) {
                    mWindowManager.addView(mRightNavigationBarWindow,
                            mSystemBarConfigs.getLayoutParamsBySide(SystemBarConfigs.RIGHT));
                }
                break;
            default:
                return;
        }
    }

    /**
     * We register for soft keyboard visibility events such that we can hide the navigation bar
     * giving more screen space to the IME. Note: this is optional and controlled by
     * {@code com.android.internal.R.bool.config_automotiveHideNavBarForKeyboard}.
     */
    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (mContext.getDisplayId() != displayId) {
            return;
        }

        boolean isKeyboardVisible = (vis & InputMethodService.IME_VISIBLE) != 0;

        if (mHideTopBarForKeyboard) {
            mCarNavigationBarController.setTopWindowVisibility(
                    isKeyboardVisible ? View.GONE : View.VISIBLE);
        }

        if (mHideBottomBarForKeyboard) {
            mCarNavigationBarController.setBottomWindowVisibility(
                    isKeyboardVisible ? View.GONE : View.VISIBLE);
        }

        if (mHideLeftBarForKeyboard) {
            mCarNavigationBarController.setLeftWindowVisibility(
                    isKeyboardVisible ? View.GONE : View.VISIBLE);
        }
        if (mHideRightBarForKeyboard) {
            mCarNavigationBarController.setRightWindowVisibility(
                    isKeyboardVisible ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onSystemBarAppearanceChanged(
            int displayId,
            @WindowInsetsController.Appearance int appearance,
            AppearanceRegion[] appearanceRegions,
            boolean navbarColorManagedByIme) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean barModeChanged = updateStatusBarMode(
                mStatusBarTransientShown ? MODE_SEMI_TRANSPARENT : MODE_TRANSPARENT);
        int numStacks = appearanceRegions.length;
        boolean stackAppearancesChanged = mAppearanceRegions.length != numStacks;
        for (int i = 0; i < numStacks && !stackAppearancesChanged; i++) {
            stackAppearancesChanged |= !appearanceRegions[i].equals(mAppearanceRegions[i]);
        }
        if (stackAppearancesChanged || barModeChanged) {
            mAppearanceRegions = appearanceRegions;
            updateStatusBarAppearance();
        }
    }

    private void updateStatusBarAppearance() {
        int numStacks = mAppearanceRegions.length;
        int numLightStacks = 0;

        // We can only have maximum one light stack.
        int indexLightStack = -1;

        for (int i = 0; i < numStacks; i++) {
            if (isLight(mAppearanceRegions[i].getAppearance())) {
                numLightStacks++;
                indexLightStack = i;
            }
        }

        // If all stacks are light, all icons become dark.
        if (numLightStacks == numStacks) {
            mStatusBarIconController.setIconsDarkArea(null);
            mStatusBarIconController.getTransitionsController().setIconsDark(
                    /* dark= */ true, /* animate= */ false);
        } else if (numLightStacks == 0) {
            // If no one is light, all icons become white.
            mStatusBarIconController.getTransitionsController().setIconsDark(
                    /* dark= */ false, /* animate= */ false);
        } else {
            // Not the same for every stack, update icons in area only.
            mStatusBarIconController.setIconsDarkArea(
                    mAppearanceRegions[indexLightStack].getBounds());
            mStatusBarIconController.getTransitionsController().setIconsDark(
                    /* dark= */ true, /* animate= */ false);
        }
    }

    private static boolean isLight(int appearance) {
        return (appearance & APPEARANCE_LIGHT_STATUS_BARS) != 0;
    }

    @Override
    public void showTransient(int displayId, int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (containsType(types, ITYPE_STATUS_BAR)) {
            if (!mStatusBarTransientShown) {
                mStatusBarTransientShown = true;
                handleTransientChanged();
            }
        }
        if (containsType(types, ITYPE_NAVIGATION_BAR)) {
            if (!mNavBarTransientShown) {
                mNavBarTransientShown = true;
                handleTransientChanged();
            }
        }
    }

    @Override
    public void abortTransient(int displayId, int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR) && !containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mStatusBarTransientShown) {
            mStatusBarTransientShown = false;
            handleTransientChanged();
        }
        if (mNavBarTransientShown) {
            mNavBarTransientShown = false;
            handleTransientChanged();
        }
    }

    @VisibleForTesting
    boolean isStatusBarTransientShown() {
        return mStatusBarTransientShown;
    }

    @VisibleForTesting
    boolean isNavBarTransientShown() {
        return mNavBarTransientShown;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("  mTaskStackListener=");
        pw.println(mButtonSelectionStateListener);
        pw.print("  mBottomNavigationBarView=");
        pw.println(mBottomNavigationBarView);
    }

    private void handleTransientChanged() {
        updateStatusBarMode(mStatusBarTransientShown ? MODE_SEMI_TRANSPARENT : MODE_TRANSPARENT);
        updateNavBarMode(mNavBarTransientShown ? MODE_SEMI_TRANSPARENT : MODE_TRANSPARENT);
    }

    // Returns true if the status bar mode has changed.
    private boolean updateStatusBarMode(int barMode) {
        if (mStatusBarMode != barMode) {
            mStatusBarMode = barMode;
            mAutoHideController.touchAutoHide();
            return true;
        }
        return false;
    }

    // Returns true if the nav bar mode has changed.
    private boolean updateNavBarMode(int barMode) {
        if (mNavigationBarMode != barMode) {
            mNavigationBarMode = barMode;
            mAutoHideController.touchAutoHide();
            return true;
        }
        return false;
    }
}
