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

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.content.Context;
import android.graphics.PixelFormat;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.car.hvac.HvacController;
import com.android.systemui.statusbar.car.hvac.TemperatureView;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.Lazy;

/** Navigation bars customized for the automotive use case. */
public class CarNavigationBar extends SystemUI implements CommandQueue.Callbacks {

    private final NavigationBarViewFactory mNavigationBarViewFactory;
    private final WindowManager mWindowManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final Lazy<FacetButtonTaskStackListener> mFacetButtonTaskStackListener;
    private final Handler mMainHandler;
    private final Lazy<KeyguardStateController> mKeyguardStateController;
    private final Lazy<CarFacetButtonController> mFacetButtonController;
    private final Lazy<NavigationBarController> mNavigationBarController;
    private final Lazy<HvacController> mHvacController;

    private IStatusBarService mBarService;
    private CommandQueue mCommandQueue;
    private ActivityManagerWrapper mActivityManagerWrapper;

    // If the nav bar should be hidden when the soft keyboard is visible.
    private boolean mHideNavBarForKeyboard;
    private boolean mBottomNavBarVisible;

    // Nav bar views.
    private ViewGroup mNavigationBarWindow;
    private ViewGroup mLeftNavigationBarWindow;
    private ViewGroup mRightNavigationBarWindow;
    private CarNavigationBarView mNavigationBarView;
    private CarNavigationBarView mLeftNavigationBarView;
    private CarNavigationBarView mRightNavigationBarView;

    // To be attached to the navigation bars such that they can close the notification panel if
    // it's open.
    private boolean mDeviceIsSetUpForUser = true;

    // Configuration values for if nav bars should be shown.
    private boolean mShowBottom;
    private boolean mShowLeft;
    private boolean mShowRight;


    @Inject
    public CarNavigationBar(Context context,
            NavigationBarViewFactory navigationBarViewFactory,
            WindowManager windowManager,
            DeviceProvisionedController deviceProvisionedController,
            Lazy<FacetButtonTaskStackListener> facetButtonTaskStackListener,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler,
            Lazy<KeyguardStateController> keyguardStateController,
            Lazy<CarFacetButtonController> facetButtonController,
            Lazy<NavigationBarController> navigationBarController,
            Lazy<HvacController> hvacController) {
        super(context);
        mNavigationBarViewFactory = navigationBarViewFactory;
        mWindowManager = windowManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mFacetButtonTaskStackListener = facetButtonTaskStackListener;
        mMainHandler = mainHandler;
        mKeyguardStateController = keyguardStateController;
        mFacetButtonController = facetButtonController;
        mNavigationBarController = navigationBarController;
        mHvacController = hvacController;
    }

    @Override
    public void start() {
        // Set initial state.
        mHideNavBarForKeyboard = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_automotiveHideNavBarForKeyboard);
        mBottomNavBarVisible = false;

        // Read configuration.
        mShowBottom = mContext.getResources().getBoolean(R.bool.config_enableBottomNavigationBar);
        mShowLeft = mContext.getResources().getBoolean(R.bool.config_enableLeftNavigationBar);
        mShowRight = mContext.getResources().getBoolean(R.bool.config_enableRightNavigationBar);

        // Get bar service.
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        // Connect into the status bar manager service
        mCommandQueue = getComponent(CommandQueue.class);
        mCommandQueue.addCallback(this);

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        mDeviceIsSetUpForUser = mDeviceProvisionedController.isCurrentUserSetup();
        mDeviceProvisionedController.addCallback(
                new DeviceProvisionedController.DeviceProvisionedListener() {
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
        mActivityManagerWrapper.registerTaskStackListener(mFacetButtonTaskStackListener.get());

        mHvacController.get().connectToCarService();
    }

    private void restartNavBarsIfNecessary() {
        boolean currentUserSetup = mDeviceProvisionedController.isCurrentUserSetup();
        if (mDeviceIsSetUpForUser != currentUserSetup) {
            mDeviceIsSetUpForUser = currentUserSetup;
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
        mHvacController.get().removeAllComponents();
        mFacetButtonController.get().removeAll();

        if (mNavigationBarWindow != null) {
            mNavigationBarWindow.removeAllViews();
            mNavigationBarView = null;
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
        // If the UI was rebuilt (day/night change) while the keyguard was up we need to
        // correctly respect that state.
        if (mKeyguardStateController.get().isShowing()) {
            updateNavBarForKeyguardContent();
        }

        // CarFacetButtonController was reset therefore we need to re-add the status bar elements
        // to the controller.
        // TODO(hseog): Add facet buttons in status bar to controller.
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
        mNavigationBarController.get().createNavigationBars(/* includeDefaultDisplay= */ false,
                result);
    }

    private void buildNavBarWindows() {
        if (mShowBottom) {
            mNavigationBarWindow = mNavigationBarViewFactory.getBottomWindow();
        }

        if (mShowLeft) {
            mLeftNavigationBarWindow = mNavigationBarViewFactory.getLeftWindow();
        }

        if (mShowRight) {
            mRightNavigationBarWindow = mNavigationBarViewFactory.getRightWindow();
        }
    }

    private void buildNavBarContent() {
        if (mShowBottom) {
            mNavigationBarView = mNavigationBarViewFactory.getBottomBar(mDeviceIsSetUpForUser);
            mNavigationBarWindow.addView(mNavigationBarView);
            addTemperatureViewToController(mNavigationBarView);
        }

        if (mShowLeft) {
            mLeftNavigationBarView = mNavigationBarViewFactory.getLeftBar(mDeviceIsSetUpForUser);
            mLeftNavigationBarWindow.addView(mLeftNavigationBarView);
            addTemperatureViewToController(mLeftNavigationBarView);
        }

        if (mShowRight) {
            mRightNavigationBarView = mNavigationBarViewFactory.getRightBar(mDeviceIsSetUpForUser);
            mRightNavigationBarWindow.addView(mRightNavigationBarView);
            // Add ability to toggle notification center.
            addTemperatureViewToController(mRightNavigationBarView);
            // Add ability to close notification center on touch.
        }
    }

    private void addTemperatureViewToController(View v) {
        if (v instanceof TemperatureView) {
            mHvacController.get().addHvacTextView((TemperatureView) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addTemperatureViewToController(viewGroup.getChildAt(i));
            }
        }
    }

    private void attachNavBarWindows() {
        if (mShowBottom && !mBottomNavBarVisible) {
            mBottomNavBarVisible = true;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("CarNavigationBar");
            lp.windowAnimations = 0;
            mWindowManager.addView(mNavigationBarWindow, lp);
        }

        if (mShowLeft) {
            int width = mContext.getResources().getDimensionPixelSize(
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
            mWindowManager.addView(mLeftNavigationBarWindow, leftlp);
        }
        if (mShowRight) {
            int width = mContext.getResources().getDimensionPixelSize(
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

        if (mContext.getDisplay().getDisplayId() != displayId) {
            return;
        }

        boolean isKeyboardVisible = (vis & InputMethodService.IME_VISIBLE) != 0;
        showBottomNavBarWindow(isKeyboardVisible);
    }

    private void showBottomNavBarWindow(boolean isKeyboardVisible) {
        if (!mShowBottom) {
            return;
        }

        // If keyboard is visible and bottom nav bar not visible, this is the correct state, so do
        // nothing. Same with if keyboard is not visible and bottom nav bar is visible.
        if (isKeyboardVisible ^ mBottomNavBarVisible) {
            return;
        }

        mNavigationBarViewFactory.getBottomWindow().setVisibility(
                isKeyboardVisible ? View.GONE : View.VISIBLE);
        mBottomNavBarVisible = !isKeyboardVisible;
    }

    private void updateNavBarForKeyguardContent() {
        if (mNavigationBarView != null) {
            mNavigationBarView.showKeyguardButtons();
        }
        if (mLeftNavigationBarView != null) {
            mLeftNavigationBarView.showKeyguardButtons();
        }
        if (mRightNavigationBarView != null) {
            mRightNavigationBarView.showKeyguardButtons();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("  mTaskStackListener=");
        pw.println(mFacetButtonTaskStackListener.get());
        pw.print("  mNavigationBarView=");
        pw.println(mNavigationBarView);
    }
}
