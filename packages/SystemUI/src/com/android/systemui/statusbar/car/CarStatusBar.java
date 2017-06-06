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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * A status bar (and navigation bar) tailored for the automotive use case.
 */
public class CarStatusBar extends StatusBar implements
        CarBatteryController.BatteryViewHandler {
    private static final String TAG = "CarStatusBar";

    private TaskStackListenerImpl mTaskStackListener;

    private CarNavigationBarController mController;
    private FullscreenUserSwitcher mFullscreenUserSwitcher;

    private CarBatteryController mCarBatteryController;
    private BatteryMeterView mBatteryMeterView;
    private Drawable mNotificationPanelBackground;

    private ConnectedDeviceSignalController mConnectedDeviceSignalController;
    private CarNavigationBarView mNavigationBarView;

    @Override
    public void start() {
        super.start();
        mTaskStackListener = new TaskStackListenerImpl();
        SystemServicesProxy.getInstance(mContext).registerTaskStackListener(mTaskStackListener);
        registerPackageChangeReceivers();

        mStackScroller.setScrollingEnabled(true);

        createBatteryController();
        mCarBatteryController.startListening();
    }

    @Override
    public void destroy() {
        mCarBatteryController.stopListening();
        mConnectedDeviceSignalController.stopListening();

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
        if (mNavigationBarView != null) {
            return;
        }

        // SystemUI requires that the navigation bar view have a parent. Since the regular
        // StatusBar inflates navigation_bar_window as this parent view, use the same view for the
        // CarNavigationBarView.
        ViewGroup navigationBarWindow = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, null);
        View.inflate(mContext, R.layout.car_navigation_bar, navigationBarWindow);
        mNavigationBarView = (CarNavigationBarView) navigationBarWindow.getChildAt(0);

        mController = new CarNavigationBarController(mContext, mNavigationBarView,
                this /* ActivityStarter*/);
        mNavigationBarView.getBarTransitions().setAlwaysOpaque(true);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("CarNavigationBar");
        lp.windowAnimations = 0;

        mWindowManager.addView(navigationBarWindow, lp);
    }

    @Override
    public NavigationBarView getNavigationBarView() {
        return mNavigationBarView;
    }

    @Override
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        // Usually, a touch on the background window will dismiss the notification shade. However,
        // for the car use-case, the shade should remain unless the user switches to a different
        // facet (e.g. phone).
        return null;
    }

    /**
     * Returns the {@link com.android.systemui.SwipeHelper.LongPressListener} that will be
     * triggered when a notification card is long-pressed.
     */
    @Override
    protected SwipeHelper.LongPressListener getNotificationLongClicker() {
        // For the automative use case, we do not want to the user to be able to interact with
        // a notification other than a regular click. As a result, just return null for the
        // long click listener.
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

    private BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null || mController == null) {
                return;
            }
            String packageName = intent.getData().getSchemeSpecificPart();
            mController.onPackageChange(packageName);
        }
    };

    private void registerPackageChangeReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mPackageChangeReceiver, filter);
    }

    public boolean hasDockedTask() {
        return Recents.getSystemServices().hasDockedTask();
    }

    /**
     * An implementation of TaskStackListener, that listens for changes in the system task
     * stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getRunningTask();
            if (runningTaskInfo != null && runningTaskInfo.baseActivity != null) {
                mController.taskChanged(runningTaskInfo.baseActivity.getPackageName(),
                        runningTaskInfo.stackId);
            }
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
    public void userSwitched(int newUserId) {
        super.userSwitched(newUserId);
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

    private int startActivityWithOptions(Intent intent, Bundle options) {
        int result = ActivityManager.START_CANCELED;
        try {
            result = ActivityManager.getService().startActivityAsUser(null /* caller */,
                    mContext.getBasePackageName(),
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    null /* resultTo*/,
                    null /* resultWho*/,
                    0 /* requestCode*/,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    null /* profilerInfo*/,
                    options,
                    UserHandle.CURRENT.getIdentifier());
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to start activity", e);
        }

        return result;
    }

    public int startActivityOnStack(Intent intent, int stackId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchStackId(stackId);
        return startActivityWithOptions(intent, options.toBundle());
    }

    @Override
    protected boolean shouldPeek(NotificationData.Entry entry, StatusBarNotification sbn) {
        // Because space is usually constrained in the auto use-case, there should not be a
        // pinned notification when the shade has been expanded. Ensure this by not pinning any
        // notification if the shade is already opened.
        if (mPanelExpanded) {
            return false;
        }

        return super.shouldPeek(entry, sbn);
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
        mController.onDensityOrFontScaleChanged();

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
