/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.navigationbar.car;

import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

import dagger.Lazy;

/** Navigation bars customized for the automotive use case. */
public class CarNavigationBar extends SystemUI implements CommandQueue.Callbacks {

    private final Resources mResources;
    private final CarNavigationBarController mCarNavigationBarController;
    private final WindowManager mWindowManager;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final CommandQueue mCommandQueue;
    private final AutoHideController mAutoHideController;
    private final ButtonSelectionStateListener mButtonSelectionStateListener;
    private final Handler mMainHandler;
    private final Lazy<KeyguardStateController> mKeyguardStateControllerLazy;
    private final Lazy<NavigationBarController> mNavigationBarControllerLazy;
    private final SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    private final ButtonSelectionStateController mButtonSelectionStateController;
    private final PhoneStatusBarPolicy mIconPolicy;
    private final StatusBarIconController mIconController;

    private final int mDisplayId;

    private StatusBarSignalPolicy mSignalPolicy;
    private IStatusBarService mBarService;
    private ActivityManagerWrapper mActivityManagerWrapper;

    // If the nav bar should be hidden when the soft keyboard is visible.
    private boolean mHideNavBarForKeyboard;
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

    private @BarTransitions.TransitionMode int mStatusBarMode;
    private @BarTransitions.TransitionMode int mNavigationBarMode;
    private boolean mStatusBarTransientShown;
    private boolean mNavBarTransientShown;

    @Inject
    public CarNavigationBar(Context context,
            @Main Resources resources,
            CarNavigationBarController carNavigationBarController,
            WindowManager windowManager,
            CarDeviceProvisionedController deviceProvisionedController,
            CommandQueue commandQueue,
            AutoHideController autoHideController,
            ButtonSelectionStateListener buttonSelectionStateListener,
            @Main Handler mainHandler,
            Lazy<KeyguardStateController> keyguardStateControllerLazy,
            Lazy<NavigationBarController> navigationBarControllerLazy,
            SuperStatusBarViewFactory superStatusBarViewFactory,
            ButtonSelectionStateController buttonSelectionStateController,
            PhoneStatusBarPolicy iconPolicy,
            StatusBarIconController iconController
    ) {
        super(context);
        mResources = resources;
        mCarNavigationBarController = carNavigationBarController;
        mWindowManager = windowManager;
        mCarDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mAutoHideController = autoHideController;
        mButtonSelectionStateListener = buttonSelectionStateListener;
        mMainHandler = mainHandler;
        mKeyguardStateControllerLazy = keyguardStateControllerLazy;
        mNavigationBarControllerLazy = navigationBarControllerLazy;
        mSuperStatusBarViewFactory = superStatusBarViewFactory;
        mButtonSelectionStateController = buttonSelectionStateController;
        mIconPolicy = iconPolicy;
        mIconController = iconController;

        mDisplayId = context.getDisplayId();
    }

    @Override
    public void start() {
        // Set initial state.
        mHideNavBarForKeyboard = mResources.getBoolean(
                com.android.internal.R.bool.config_automotiveHideNavBarForKeyboard);
        mBottomNavBarVisible = false;

        // Get bar service.
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        // Connect into the status bar manager service
        mCommandQueue.addCallback(this);

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

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

        mCarNavigationBarController.connectToHvac();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy.init();
        mSignalPolicy = new StatusBarSignalPolicy(mContext, mIconController);
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
        // remove and reattach all hvac components such that we don't keep a reference to unused
        // ui elements
        mCarNavigationBarController.removeAllFromHvac();
        mButtonSelectionStateController.removeAll();

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

        // There has been a car customized nav bar on the default display, so just create nav bars
        // on external displays.
        mNavigationBarControllerLazy.get().createNavigationBars(/* includeDefaultDisplay= */ false,
                result);
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
            mTopNavigationBarWindow.addView(mTopNavigationBarView);
        }

        mBottomNavigationBarView = mCarNavigationBarController.getBottomBar(isDeviceSetupForUser());
        if (mBottomNavigationBarView != null) {
            mBottomNavigationBarWindow.addView(mBottomNavigationBarView);
        }

        mLeftNavigationBarView = mCarNavigationBarController.getLeftBar(isDeviceSetupForUser());
        if (mLeftNavigationBarView != null) {
            mLeftNavigationBarWindow.addView(mLeftNavigationBarView);
        }

        mRightNavigationBarView = mCarNavigationBarController.getRightBar(isDeviceSetupForUser());
        if (mRightNavigationBarView != null) {
            mRightNavigationBarWindow.addView(mRightNavigationBarView);
        }
    }

    private void attachNavBarWindows() {
        if (mTopNavigationBarWindow != null) {
            int height = mResources.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height,
                    WindowManager.LayoutParams.TYPE_STATUS_BAR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                            | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    PixelFormat.TRANSLUCENT);
            lp.token = new Binder();
            lp.gravity = Gravity.TOP;
            lp.setFitInsetsTypes(0 /* types */);
            lp.setTitle("TopCarNavigationBar");
            lp.packageName = mContext.getPackageName();
            lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            mWindowManager.addView(mTopNavigationBarWindow, lp);
        }

        if (mBottomNavigationBarWindow != null && !mBottomNavBarVisible) {
            mBottomNavBarVisible = true;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("BottomCarNavigationBar");
            lp.windowAnimations = 0;
            mWindowManager.addView(mBottomNavigationBarWindow, lp);
        }

        if (mLeftNavigationBarWindow != null) {
            int width = mResources.getDimensionPixelSize(
                    R.dimen.car_left_navigation_bar_width);
            WindowManager.LayoutParams leftlp = new WindowManager.LayoutParams(
                    width, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            leftlp.setTitle("LeftCarNavigationBar");
            leftlp.windowAnimations = 0;
            leftlp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
            leftlp.gravity = Gravity.LEFT;
            leftlp.setFitInsetsTypes(0 /* types */);
            mWindowManager.addView(mLeftNavigationBarWindow, leftlp);
        }
        if (mRightNavigationBarWindow != null) {
            int width = mResources.getDimensionPixelSize(
                    R.dimen.car_right_navigation_bar_width);
            WindowManager.LayoutParams rightlp = new WindowManager.LayoutParams(
                    width, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            rightlp.setTitle("RightCarNavigationBar");
            rightlp.windowAnimations = 0;
            rightlp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
            rightlp.gravity = Gravity.RIGHT;
            rightlp.setFitInsetsTypes(0 /* types */);
            mWindowManager.addView(mRightNavigationBarWindow, rightlp);
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
        if (!mHideNavBarForKeyboard) {
            return;
        }

        if (mContext.getDisplayId() != displayId) {
            return;
        }

        boolean isKeyboardVisible = (vis & InputMethodService.IME_VISIBLE) != 0;
        mCarNavigationBarController.setBottomWindowVisibility(
                isKeyboardVisible ? View.GONE : View.VISIBLE);
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
