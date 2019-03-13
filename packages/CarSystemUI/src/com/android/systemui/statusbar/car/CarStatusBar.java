/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.app.ActivityTaskManager;
import android.car.drivingstate.CarDrivingStateEvent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.CarSystemUIFactory;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.notifications.NotificationsUI;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.car.CarQSFragment;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.car.hvac.HvacController;
import com.android.systemui.statusbar.car.hvac.TemperatureView;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

/**
 * A status bar (and navigation bar) tailored for the automotive use case.
 */
public class CarStatusBar extends StatusBar implements
        CarBatteryController.BatteryViewHandler {
    private static final String TAG = "CarStatusBar";

    private TaskStackListenerImpl mTaskStackListener;

    private FullscreenUserSwitcher mFullscreenUserSwitcher;

    private CarBatteryController mCarBatteryController;
    private BatteryMeterView mBatteryMeterView;
    private Drawable mNotificationPanelBackground;

    private ConnectedDeviceSignalController mConnectedDeviceSignalController;
    private ViewGroup mNavigationBarWindow;
    private ViewGroup mLeftNavigationBarWindow;
    private ViewGroup mRightNavigationBarWindow;
    private CarNavigationBarView mNavigationBarView;
    private CarNavigationBarView mLeftNavigationBarView;
    private CarNavigationBarView mRightNavigationBarView;

    private final Object mQueueLock = new Object();
    private boolean mShowLeft;
    private boolean mShowRight;
    private boolean mShowBottom;
    private CarFacetButtonController mCarFacetButtonController;
    private ActivityManagerWrapper mActivityManagerWrapper;
    private DeviceProvisionedController mDeviceProvisionedController;
    private boolean mDeviceIsProvisioned = true;
    private HvacController mHvacController;
    private DrivingStateHelper mDrivingStateHelper;
    private SwitchToGuestTimer mSwitchToGuestTimer;

    @Override
    public void start() {
        // get the provisioned state before calling the parent class since it's that flow that
        // builds the nav bar
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mDeviceIsProvisioned = mDeviceProvisionedController.isDeviceProvisioned();
        super.start();
        mTaskStackListener = new TaskStackListenerImpl();
        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mActivityManagerWrapper.registerTaskStackListener(mTaskStackListener);

        mNotificationPanel.setScrollingEnabled(true);

        createBatteryController();
        mCarBatteryController.startListening();

        mHvacController.connectToCarService();

        CarSystemUIFactory factory = SystemUIFactory.getInstance();
        if (!mDeviceIsProvisioned) {
            mDeviceProvisionedController.addCallback(
                    new DeviceProvisionedController.DeviceProvisionedListener() {
                        @Override
                        public void onDeviceProvisionedChanged() {
                            mHandler.post(() -> {
                                // on initial boot we are getting a call even though the value
                                // is the same so we are confirming the reset is needed
                                boolean deviceProvisioned =
                                        mDeviceProvisionedController.isDeviceProvisioned();
                                if (mDeviceIsProvisioned != deviceProvisioned) {
                                    mDeviceIsProvisioned = deviceProvisioned;
                                    restartNavBars();
                                }
                            });
                        }
                    });
        }

        // Register a listener for driving state changes.
        mDrivingStateHelper = new DrivingStateHelper(mContext, this::onDrivingStateChanged);
        mDrivingStateHelper.connectToCarService();

        mSwitchToGuestTimer = new SwitchToGuestTimer(mContext);
    }

    /**
     * Remove all content from navbars and rebuild them. Used to allow for different nav bars
     * before and after the device is provisioned. . Also for change of density and font size.
     */
    private void restartNavBars() {
        // remove and reattach all hvac components such that we don't keep a reference to unused
        // ui elements
        mHvacController.removeAllComponents();
        addTemperatureViewToController(mStatusBarWindow);
        mCarFacetButtonController.removeAll();
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
        if (mIsKeyguard) {
            updateNavBarForKeyguardContent();
        }
    }

    private void addTemperatureViewToController(View v) {
        if (v instanceof TemperatureView) {
            Log.d(TAG, "addTemperatureViewToController: found ");
            mHvacController.addHvacTextView((TemperatureView) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addTemperatureViewToController(viewGroup.getChildAt(i));
            }
        }
    }

    /**
     * Allows for showing or hiding just the navigation bars. This is indented to be used when
     * the full screen user selector is shown.
     */
    void setNavBarVisibility(@View.Visibility int visibility) {
        if (mNavigationBarWindow != null) {
            mNavigationBarWindow.setVisibility(visibility);
        }
        if (mLeftNavigationBarWindow != null) {
            mLeftNavigationBarWindow.setVisibility(visibility);
        }
        if (mRightNavigationBarWindow != null) {
            mRightNavigationBarWindow.setVisibility(visibility);
        }
    }


    @Override
    public boolean hideKeyguard() {
        boolean result = super.hideKeyguard();
        if (mNavigationBarView != null) {
            mNavigationBarView.hideKeyguardButtons();
        }
        if (mLeftNavigationBarView != null) {
            mLeftNavigationBarView.hideKeyguardButtons();
        }
        if (mRightNavigationBarView != null) {
            mRightNavigationBarView.hideKeyguardButtons();
        }
        return result;
    }


    @Override
    public void showKeyguard() {
        super.showKeyguard();
        updateNavBarForKeyguardContent();
    }

    /**
     * Switch to the keyguard applicable content contained in the nav bars
     */
    private void updateNavBarForKeyguardContent() {
        getComponent(NotificationsUI.class).closeCarNotifications(0);
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
    protected void makeStatusBarView() {
        super.makeStatusBarView();
        mHvacController = new HvacController(mContext);

        CarSystemUIFactory factory = SystemUIFactory.getInstance();
        mCarFacetButtonController = factory.getCarDependencyComponent()
            .getCarFacetButtonController();
        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);

        FragmentHostManager manager = FragmentHostManager.get(mStatusBarWindow);
        manager.addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
            mBatteryMeterView = fragment.getView().findViewById(R.id.battery);

            // By default, the BatteryMeterView should not be visible. It will be toggled
            // when a device has connected by bluetooth.
            mBatteryMeterView.setVisibility(View.GONE);
        });
        addTemperatureViewToController(mStatusBarWindow);
        // The following are the ui elements that the user would call the status bar.
        // This will set the status bar so it they can make call backs.
        CarNavigationBarView topBar = mStatusBarWindow.findViewById(R.id.car_top_bar);
        topBar.setStatusBar(this);
        CarNavigationBarView qsTopBar = mStatusBarWindow.findViewById(R.id.qs_car_top_bar);
        qsTopBar.setStatusBar(this);
    }

    @Override
    protected QS createDefaultQSFragment() {
        return new CarQSFragment();
    }

    private BatteryController createBatteryController() {
        mCarBatteryController = new CarBatteryController(mContext);
        mCarBatteryController.addBatteryViewHandler(this);
        return mCarBatteryController;
    }

    @Override
    protected void createNavigationBar() {
        mShowBottom = mContext.getResources().getBoolean(R.bool.config_enableBottomNavigationBar);
        mShowLeft = mContext.getResources().getBoolean(R.bool.config_enableLeftNavigationBar);
        mShowRight = mContext.getResources().getBoolean(R.bool.config_enableRightNavigationBar);

        buildNavBarWindows();
        buildNavBarContent();
        attachNavBarWindows();

        // There has been a car customized nav bar on the default display, so just create nav bars
        // on external displays.
        mNavigationBarController.createNavigationBars(false /* includeDefaultDisplay */);
    }

    private void buildNavBarContent() {
        if (mShowBottom) {
            buildBottomBar((mDeviceIsProvisioned) ? R.layout.car_navigation_bar :
                    R.layout.car_navigation_bar_unprovisioned);
        }

        if (mShowLeft) {
            buildLeft((mDeviceIsProvisioned) ? R.layout.car_left_navigation_bar :
                    R.layout.car_left_navigation_bar_unprovisioned);
        }

        if (mShowRight) {
            buildRight((mDeviceIsProvisioned) ? R.layout.car_right_navigation_bar :
                    R.layout.car_right_navigation_bar_unprovisioned);
        }
    }

    private void buildNavBarWindows() {
        if (mShowBottom) {
            mNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }
        if (mShowLeft) {
            mLeftNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }
        if (mShowRight) {
            mRightNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }

    }

    private void attachNavBarWindows() {

        if (mShowBottom) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("CarNavigationBar");
            lp.windowAnimations = 0;
            mWindowManager.addView(mNavigationBarWindow, lp);
            mNavigationBarWindow.setOnTouchListener(getStatusBarWindowTouchListener());
        }
        if (mShowLeft) {
            int width = mContext.getResources().getDimensionPixelSize(
                    R.dimen.car_left_navigation_bar_width);
            WindowManager.LayoutParams leftlp = new WindowManager.LayoutParams(
                    width, LayoutParams.MATCH_PARENT,
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
                    width, LayoutParams.MATCH_PARENT,
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

    private void buildBottomBar(int layout) {
        // SystemUI requires that the navigation bar view have a parent. Since the regular
        // StatusBar inflates navigation_bar_window as this parent view, use the same view for the
        // CarNavigationBarView.
        View.inflate(mContext, layout, mNavigationBarWindow);
        mNavigationBarView = (CarNavigationBarView) mNavigationBarWindow.getChildAt(0);
        if (mNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build botom nav bar due to missing layout");
        }
        mNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mNavigationBarView);
    }

    private void buildLeft(int layout) {
        View.inflate(mContext, layout, mLeftNavigationBarWindow);
        mLeftNavigationBarView = (CarNavigationBarView) mLeftNavigationBarWindow.getChildAt(0);
        if (mLeftNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build left nav bar due to missing layout");
        }
        mLeftNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mLeftNavigationBarView);
    }


    private void buildRight(int layout) {
        View.inflate(mContext, layout, mRightNavigationBarWindow);
        mRightNavigationBarView = (CarNavigationBarView) mRightNavigationBarWindow.getChildAt(0);
        if (mRightNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build right nav bar due to missing layout");
        }
        mRightNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mRightNavigationBarView);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        //When executing dump() funciton simultaneously, we need to serialize them
        //to get mStackScroller's position correctly.
        synchronized (mQueueLock) {
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }

        pw.print("  mTaskStackListener=");
        pw.println(mTaskStackListener);
        pw.print("  mCarFacetButtonController=");
        pw.println(mCarFacetButtonController);
        pw.print("  mFullscreenUserSwitcher=");
        pw.println(mFullscreenUserSwitcher);
        pw.print("  mCarBatteryController=");
        pw.println(mCarBatteryController);
        pw.print("  mBatteryMeterView=");
        pw.println(mBatteryMeterView);
        pw.print("  mConnectedDeviceSignalController=");
        pw.println(mConnectedDeviceSignalController);
        pw.print("  mNavigationBarView=");
        pw.println(mNavigationBarView);

        if (KeyguardUpdateMonitor.getInstance(mContext) != null) {
            KeyguardUpdateMonitor.getInstance(mContext).dump(fd, pw, args);
        }

        FalsingManager.getInstance(mContext).dump(pw);
        FalsingLog.dump(pw);

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  ");
            pw.print(entry.getKey());
            pw.print("=");
            pw.println(entry.getValue());
        }
    }

    @Override
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        // Gets the car specific notification touch listener
        return getComponent(NotificationsUI.class).getDragDownListener();
    }

    @Override
    public void showBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "showBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void hideBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "hideBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.GONE);
        }
    }

    /**
     * An implementation of TaskStackChangeListener, that listens for changes in the system
     * task stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends TaskStackChangeListener {
        @Override
        public void onTaskStackChanged() {
            try {
                mCarFacetButtonController.taskChanged(
                        ActivityTaskManager.getService().getAllStackInfos());
            } catch (Exception e) {
                Log.e(TAG, "Getting StackInfo from activity manager failed", e);
            }
        }
    }

    private void onDrivingStateChanged(CarDrivingStateEvent notUsed) {
        // Check if we need to start the timer every time driving state changes.
        startSwitchToGuestTimerIfDrivingOnKeyguard();
    }

    private void startSwitchToGuestTimerIfDrivingOnKeyguard() {
        if (mDrivingStateHelper.isCurrentlyDriving() && mState != StatusBarState.SHADE) {
            // We're driving while keyguard is up.
            mSwitchToGuestTimer.start();
        } else {
            mSwitchToGuestTimer.cancel();
        }
    }

    @Override
    protected void createUserSwitcher() {
        UserSwitcherController userSwitcherController =
                Dependency.get(UserSwitcherController.class);
        if (userSwitcherController.useFullscreenUserSwitcher()) {
            mFullscreenUserSwitcher = new FullscreenUserSwitcher(this,
                    mStatusBarWindow.findViewById(R.id.fullscreen_user_switcher_stub), mContext);
        } else {
            super.createUserSwitcher();
        }
    }

    @Override
    public void setLockscreenUser(int newUserId) {
        super.setLockscreenUser(newUserId);
        // Try to dismiss the keyguard after every user switch.
        dismissKeyguardWhenUserSwitcherNotDisplayed();
    }

    @Override
    public void onStateChanged(int newState) {
        super.onStateChanged(newState);

        startSwitchToGuestTimerIfDrivingOnKeyguard();

        if (newState != StatusBarState.FULLSCREEN_USER_SWITCHER) {
            hideUserSwitcher();
        } else {
            dismissKeyguardWhenUserSwitcherNotDisplayed();
        }
    }

    /** Makes the full screen user switcher visible, if applicable. */
    public void showUserSwitcher() {
        if (mFullscreenUserSwitcher != null && mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            mFullscreenUserSwitcher.show(); // Makes the switcher visible.
        }
    }

    private void hideUserSwitcher() {
        if (mFullscreenUserSwitcher != null) {
            mFullscreenUserSwitcher.hide();
        }
    }

    // We automatically dismiss keyguard unless user switcher is being shown on the keyguard.
    private void dismissKeyguardWhenUserSwitcherNotDisplayed() {
        if (mFullscreenUserSwitcher == null) {
            return; // Not using the full screen user switcher.
        }

        if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER
                && !mFullscreenUserSwitcher.isVisible()) {
            // Current execution path continues to set state after this, thus we deffer the
            // dismissal to the next execution cycle.
            postDismissKeyguard(); // Dismiss the keyguard if switcher is not visible.
        }
    }

    public void postDismissKeyguard() {
        mHandler.post(this::dismissKeyguard);
    }

    /**
     * Dismisses the keyguard and shows bouncer if authentication is necessary.
     */
    public void dismissKeyguard() {
        executeRunnableDismissingKeyguard(null/* runnable */, null /* cancelAction */,
                true /* dismissShade */, true /* afterKeyguardGone */, true /* deferred */);
    }

    @Override
    public void animateExpandNotificationsPanel() {
        // Because space is usually constrained in the auto use-case, there should not be a
        // pinned notification when the shade has been expanded. Ensure this by removing all heads-
        // up notifications.
        mHeadsUpManager.releaseAllImmediately();
        super.animateExpandNotificationsPanel();
    }

    /**
     * Ensures that relevant child views are appropriately recreated when the device's density
     * changes.
     */
    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        restartNavBars();
        // Need to update the background on density changed in case the change was due to night
        // mode.
        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);
    }

    /**
     * Returns the {@link Drawable} that represents the wallpaper that the user has currently set.
     */
    private Drawable getDefaultWallpaper() {
        return mContext.getDrawable(com.android.internal.R.drawable.default_wallpaper);
    }

    public void toggleCarNotifications() {
        getComponent(NotificationsUI.class).toggleShowingCarNotifications();
    }

}
