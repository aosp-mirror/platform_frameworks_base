/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SysUiTaskStackChangeListener;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.BatteryController;
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

    @Override
    public void start() {
        super.start();
        mTaskStackListener = new TaskStackListenerImpl();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

        mStackScroller.setScrollingEnabled(true);

        createBatteryController();
        mCarBatteryController.startListening();
    }

    @Override
    public void destroy() {
        mCarBatteryController.stopListening();
        mConnectedDeviceSignalController.stopListening();

        if (mNavigationBarWindow != null) {
            mWindowManager.removeViewImmediate(mNavigationBarWindow);
            mNavigationBarView = null;
        }

        if (mLeftNavigationBarWindow != null) {
            mWindowManager.removeViewImmediate(mLeftNavigationBarWindow);
            mLeftNavigationBarView = null;
        }

        if (mRightNavigationBarWindow != null) {
            mWindowManager.removeViewImmediate(mRightNavigationBarWindow);
            mRightNavigationBarView = null;
        }

        super.destroy();
    }

    @Override
    protected void makeStatusBarView() {
        super.makeStatusBarView();

        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);

        FragmentHostManager manager = FragmentHostManager.get(mStatusBarWindow);
        manager.addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
            mBatteryMeterView = fragment.getView().findViewById(R.id.battery);

            // By default, the BatteryMeterView should not be visible. It will be toggled
            // when a device has connected by bluetooth.
            mBatteryMeterView.setVisibility(View.GONE);

            ViewStub stub = fragment.getView().findViewById(R.id.connected_device_signals_stub);
            View signalsView = stub.inflate();

            // When a ViewStub if inflated, it does not respect the margins on the
            // inflated view.
            // As a result, manually add the ending margin.
            ((LinearLayout.LayoutParams) signalsView.getLayoutParams()).setMarginEnd(
                    mContext.getResources().getDimensionPixelOffset(
                            R.dimen.status_bar_connected_device_signal_margin_end));

            if (mConnectedDeviceSignalController != null) {
                mConnectedDeviceSignalController.stopListening();
            }
            mConnectedDeviceSignalController = new ConnectedDeviceSignalController(mContext,
                    signalsView);
            mConnectedDeviceSignalController.startListening();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "makeStatusBarView(). mBatteryMeterView: " + mBatteryMeterView);
            }
        });
    }

    private BatteryController createBatteryController() {
        mCarBatteryController = new CarBatteryController(mContext);
        mCarBatteryController.addBatteryViewHandler(this);
        return mCarBatteryController;
    }

    @Override
    protected void createNavigationBar() {
        mCarFacetButtonController = new CarFacetButtonController(mContext);
        if (mNavigationBarView != null) {
            return;
        }

        mShowBottom = mContext.getResources().getBoolean(R.bool.config_enableBottomNavigationBar);
        if (mShowBottom) {
            buildBottomBar();
        }

        int widthForSides = mContext.getResources().getDimensionPixelSize(
                R.dimen.navigation_bar_height_car_mode);


        mShowLeft = mContext.getResources().getBoolean(R.bool.config_enableLeftNavigationBar);

        if (mShowLeft) {
            buildLeft(widthForSides);
        }

        mShowRight = mContext.getResources().getBoolean(R.bool.config_enableRightNavigationBar);

        if (mShowRight) {
            buildRight(widthForSides);
        }

    }


    private void buildBottomBar() {
        // SystemUI requires that the navigation bar view have a parent. Since the regular
        // StatusBar inflates navigation_bar_window as this parent view, use the same view for the
        // CarNavigationBarView.
        mNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, null);
        if (mNavigationBarWindow == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.navigation_bar_window");
        }


        View.inflate(mContext, R.layout.car_navigation_bar, mNavigationBarWindow);
        mNavigationBarView = (CarNavigationBarView) mNavigationBarWindow.getChildAt(0);
        if (mNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build botom nav bar due to missing layout");
        }
        mNavigationBarView.setStatusBar(this);


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


        mCarFacetButtonController.addCarNavigationBar(mNavigationBarView);
        mWindowManager.addView(mNavigationBarWindow, lp);
    }

    private void buildLeft(int widthForSides) {
        mLeftNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, null);
        if (mLeftNavigationBarWindow == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.navigation_bar_window");
        }

        View.inflate(mContext, R.layout.car_left_navigation_bar, mLeftNavigationBarWindow);
        mLeftNavigationBarView = (CarNavigationBarView) mLeftNavigationBarWindow.getChildAt(0);
        if (mLeftNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build left nav bar due to missing layout");
        }
        mLeftNavigationBarView.setStatusBar(this);
        mCarFacetButtonController.addCarNavigationBar(mLeftNavigationBarView);

        WindowManager.LayoutParams leftlp = new WindowManager.LayoutParams(
                widthForSides, LayoutParams.MATCH_PARENT,
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


    private void buildRight(int widthForSides) {
        mRightNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, null);
        if (mRightNavigationBarWindow == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.navigation_bar_window");
        }

        View.inflate(mContext, R.layout.car_right_navigation_bar, mRightNavigationBarWindow);
        mRightNavigationBarView = (CarNavigationBarView) mRightNavigationBarWindow.getChildAt(0);
        if (mRightNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build right nav bar due to missing layout");
        }
        mRightNavigationBarView.setStatusBar(this);
        mCarFacetButtonController.addCarNavigationBar(mRightNavigationBarView);

        WindowManager.LayoutParams rightlp = new WindowManager.LayoutParams(
                widthForSides, LayoutParams.MATCH_PARENT,
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

        pw.print("  mTaskStackListener="); pw.println(mTaskStackListener);
        pw.print("  mCarFacetButtonController=");
        pw.println(mCarFacetButtonController);
        pw.print("  mFullscreenUserSwitcher="); pw.println(mFullscreenUserSwitcher);
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
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }
    }


    @Override
    public View getNavigationBarWindow() {
        return mNavigationBarWindow;
    }

    @Override
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        // Usually, a touch on the background window will dismiss the notification shade. However,
        // for the car use-case, the shade should remain unless the user switches to a different
        // facet (e.g. phone).
        return null;
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


    public boolean hasDockedTask() {
        return Recents.getSystemServices().hasDockedTask();
    }

    /**
     * An implementation of SysUiTaskStackChangeListener, that listens for changes in the system task
     * stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends SysUiTaskStackChangeListener {
        @Override
        public void onTaskStackChanged() {
            ActivityManager.RunningTaskInfo runningTaskInfo =
                    ActivityManagerWrapper.getInstance().getRunningTask();
            mCarFacetButtonController.taskChanged(runningTaskInfo);
        }
    }

    @Override
    protected void createUserSwitcher() {
        UserSwitcherController userSwitcherController =
                Dependency.get(UserSwitcherController.class);
        if (userSwitcherController.useFullscreenUserSwitcher()) {
            mFullscreenUserSwitcher = new FullscreenUserSwitcher(this,
                    userSwitcherController,
                    mStatusBarWindow.findViewById(R.id.fullscreen_user_switcher_stub));
        } else {
            super.createUserSwitcher();
        }
    }

    @Override
    public void onUserSwitched(int newUserId) {
        super.onUserSwitched(newUserId);
        if (mFullscreenUserSwitcher != null) {
            mFullscreenUserSwitcher.onUserSwitched(newUserId);
        }
    }

    @Override
    public void updateKeyguardState(boolean goingToFullShade, boolean fromShadeLocked) {
        super.updateKeyguardState(goingToFullShade, fromShadeLocked);
        if (mFullscreenUserSwitcher != null) {
            if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
                mFullscreenUserSwitcher.show();
            } else {
                mFullscreenUserSwitcher.hide();
            }
        }
    }

    @Override
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        // Do nothing, we don't want to display media art in the lock screen for a car.
    }


    @Override
    public void animateExpandNotificationsPanel() {
        // Because space is usually constrained in the auto use-case, there should not be a
        // pinned notification when the shade has been expanded. Ensure this by removing all heads-
        // up notifications.
        mHeadsUpManager.removeAllHeadsUpEntries();
        super.animateExpandNotificationsPanel();
    }

    /**
     * Ensures that relevant child views are appropriately recreated when the device's density
     * changes.
     */
    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
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
}
