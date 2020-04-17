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

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.navigationbar.car.hvac.HvacController;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/** A single class which controls the navigation bar views. */
@Singleton
public class CarNavigationBarController {

    private final Context mContext;
    private final NavigationBarViewFactory mNavigationBarViewFactory;
    private final ButtonSelectionStateController mButtonSelectionStateController;
    private final Lazy<HvacController> mHvacControllerLazy;

    private boolean mShowTop;
    private boolean mShowBottom;
    private boolean mShowLeft;
    private boolean mShowRight;

    private View.OnTouchListener mTopBarTouchListener;
    private View.OnTouchListener mBottomBarTouchListener;
    private View.OnTouchListener mLeftBarTouchListener;
    private View.OnTouchListener mRightBarTouchListener;
    private NotificationsShadeController mNotificationsShadeController;

    private CarNavigationBarView mTopView;
    private CarNavigationBarView mBottomView;
    private CarNavigationBarView mLeftView;
    private CarNavigationBarView mRightView;

    @Inject
    public CarNavigationBarController(Context context,
            NavigationBarViewFactory navigationBarViewFactory,
            ButtonSelectionStateController buttonSelectionStateController,
            Lazy<HvacController> hvacControllerLazy) {
        mContext = context;
        mNavigationBarViewFactory = navigationBarViewFactory;
        mButtonSelectionStateController = buttonSelectionStateController;
        mHvacControllerLazy = hvacControllerLazy;

        // Read configuration.
        mShowTop = mContext.getResources().getBoolean(R.bool.config_enableTopNavigationBar);
        mShowBottom = mContext.getResources().getBoolean(R.bool.config_enableBottomNavigationBar);
        mShowLeft = mContext.getResources().getBoolean(R.bool.config_enableLeftNavigationBar);
        mShowRight = mContext.getResources().getBoolean(R.bool.config_enableRightNavigationBar);
    }

    /**
     * Hides all navigation bars.
     */
    public void hideBars() {
        if (mTopView != null) {
            mTopView.setVisibility(View.GONE);
        }
        setBottomWindowVisibility(View.GONE);
        setLeftWindowVisibility(View.GONE);
        setRightWindowVisibility(View.GONE);
    }

    /**
     * Shows all navigation bars.
     */
    public void showBars() {
        if (mTopView != null) {
            mTopView.setVisibility(View.VISIBLE);
        }
        setBottomWindowVisibility(View.VISIBLE);
        setLeftWindowVisibility(View.VISIBLE);
        setRightWindowVisibility(View.VISIBLE);
    }

    /** Connect to hvac service. */
    public void connectToHvac() {
        mHvacControllerLazy.get().connectToCarService();
    }

    /** Clean up hvac. */
    public void removeAllFromHvac() {
        mHvacControllerLazy.get().removeAllComponents();
    }

    /** Gets the top window if configured to do so. */
    @Nullable
    public ViewGroup getTopWindow() {
        return mShowTop ? mNavigationBarViewFactory.getTopWindow() : null;
    }

    /** Gets the bottom window if configured to do so. */
    @Nullable
    public ViewGroup getBottomWindow() {
        return mShowBottom ? mNavigationBarViewFactory.getBottomWindow() : null;
    }

    /** Gets the left window if configured to do so. */
    @Nullable
    public ViewGroup getLeftWindow() {
        return mShowLeft ? mNavigationBarViewFactory.getLeftWindow() : null;
    }

    /** Gets the right window if configured to do so. */
    @Nullable
    public ViewGroup getRightWindow() {
        return mShowRight ? mNavigationBarViewFactory.getRightWindow() : null;
    }

    /** Toggles the bottom nav bar visibility. */
    public boolean setBottomWindowVisibility(@View.Visibility int visibility) {
        return setWindowVisibility(getBottomWindow(), visibility);
    }

    /** Toggles the left nav bar visibility. */
    public boolean setLeftWindowVisibility(@View.Visibility int visibility) {
        return setWindowVisibility(getLeftWindow(), visibility);
    }

    /** Toggles the right nav bar visibility. */
    public boolean setRightWindowVisibility(@View.Visibility int visibility) {
        return setWindowVisibility(getRightWindow(), visibility);
    }

    private boolean setWindowVisibility(ViewGroup window, @View.Visibility int visibility) {
        if (window == null) {
            return false;
        }

        if (window.getVisibility() == visibility) {
            return false;
        }

        window.setVisibility(visibility);
        return true;
    }

    /** Gets the top navigation bar with the appropriate listeners set. */
    @NonNull
    public CarNavigationBarView getTopBar(boolean isSetUp) {
        mTopView = mNavigationBarViewFactory.getTopBar(isSetUp);
        setupBar(mTopView, mTopBarTouchListener, mNotificationsShadeController);
        return mTopView;
    }

    /** Gets the bottom navigation bar with the appropriate listeners set. */
    @Nullable
    public CarNavigationBarView getBottomBar(boolean isSetUp) {
        if (!mShowBottom) {
            return null;
        }

        mBottomView = mNavigationBarViewFactory.getBottomBar(isSetUp);
        setupBar(mBottomView, mBottomBarTouchListener, mNotificationsShadeController);
        return mBottomView;
    }

    /** Gets the left navigation bar with the appropriate listeners set. */
    @Nullable
    public CarNavigationBarView getLeftBar(boolean isSetUp) {
        if (!mShowLeft) {
            return null;
        }

        mLeftView = mNavigationBarViewFactory.getLeftBar(isSetUp);
        setupBar(mLeftView, mLeftBarTouchListener, mNotificationsShadeController);
        return mLeftView;
    }

    /** Gets the right navigation bar with the appropriate listeners set. */
    @Nullable
    public CarNavigationBarView getRightBar(boolean isSetUp) {
        if (!mShowRight) {
            return null;
        }

        mRightView = mNavigationBarViewFactory.getRightBar(isSetUp);
        setupBar(mRightView, mRightBarTouchListener, mNotificationsShadeController);
        return mRightView;
    }

    private void setupBar(CarNavigationBarView view, View.OnTouchListener statusBarTouchListener,
            NotificationsShadeController notifShadeController) {
        view.setStatusBarWindowTouchListener(statusBarTouchListener);
        view.setNotificationsPanelController(notifShadeController);
        mButtonSelectionStateController.addAllButtonsWithSelectionState(view);
        mHvacControllerLazy.get().addTemperatureViewToController(view);
    }

    /** Sets a touch listener for the top navigation bar. */
    public void registerTopBarTouchListener(View.OnTouchListener listener) {
        mTopBarTouchListener = listener;
        if (mTopView != null) {
            mTopView.setStatusBarWindowTouchListener(mTopBarTouchListener);
        }
    }

    /** Sets a touch listener for the bottom navigation bar. */
    public void registerBottomBarTouchListener(View.OnTouchListener listener) {
        mBottomBarTouchListener = listener;
        if (mBottomView != null) {
            mBottomView.setStatusBarWindowTouchListener(mBottomBarTouchListener);
        }
    }

    /** Sets a touch listener for the left navigation bar. */
    public void registerLeftBarTouchListener(View.OnTouchListener listener) {
        mLeftBarTouchListener = listener;
        if (mLeftView != null) {
            mLeftView.setStatusBarWindowTouchListener(mLeftBarTouchListener);
        }
    }

    /** Sets a touch listener for the right navigation bar. */
    public void registerRightBarTouchListener(View.OnTouchListener listener) {
        mRightBarTouchListener = listener;
        if (mRightView != null) {
            mRightView.setStatusBarWindowTouchListener(mRightBarTouchListener);
        }
    }

    /** Sets a notification controller which toggles the notification panel. */
    public void registerNotificationController(
            NotificationsShadeController notificationsShadeController) {
        mNotificationsShadeController = notificationsShadeController;
        if (mTopView != null) {
            mTopView.setNotificationsPanelController(mNotificationsShadeController);
        }
        if (mBottomView != null) {
            mBottomView.setNotificationsPanelController(mNotificationsShadeController);
        }
        if (mLeftView != null) {
            mLeftView.setNotificationsPanelController(mNotificationsShadeController);
        }
        if (mRightView != null) {
            mRightView.setNotificationsPanelController(mNotificationsShadeController);
        }
    }

    /**
     * Shows all of the keyguard specific buttons on the valid instances of
     * {@link CarNavigationBarView}.
     */
    public void showAllKeyguardButtons(boolean isSetUp) {
        checkAllBars(isSetUp);
        if (mTopView != null) {
            mTopView.showKeyguardButtons();
        }
        if (mBottomView != null) {
            mBottomView.showKeyguardButtons();
        }
        if (mLeftView != null) {
            mLeftView.showKeyguardButtons();
        }
        if (mRightView != null) {
            mRightView.showKeyguardButtons();
        }
    }

    /**
     * Hides all of the keyguard specific buttons on the valid instances of
     * {@link CarNavigationBarView}.
     */
    public void hideAllKeyguardButtons(boolean isSetUp) {
        checkAllBars(isSetUp);
        if (mTopView != null) {
            mTopView.hideKeyguardButtons();
        }
        if (mBottomView != null) {
            mBottomView.hideKeyguardButtons();
        }
        if (mLeftView != null) {
            mLeftView.hideKeyguardButtons();
        }
        if (mRightView != null) {
            mRightView.hideKeyguardButtons();
        }
    }

    /** Toggles whether the notifications icon has an unseen indicator or not. */
    public void toggleAllNotificationsUnseenIndicator(boolean isSetUp, boolean hasUnseen) {
        checkAllBars(isSetUp);
        if (mTopView != null) {
            mTopView.toggleNotificationUnseenIndicator(hasUnseen);
        }
        if (mBottomView != null) {
            mBottomView.toggleNotificationUnseenIndicator(hasUnseen);
        }
        if (mLeftView != null) {
            mLeftView.toggleNotificationUnseenIndicator(hasUnseen);
        }
        if (mRightView != null) {
            mRightView.toggleNotificationUnseenIndicator(hasUnseen);
        }
    }

    /** Interface for controlling the notifications shade. */
    public interface NotificationsShadeController {
        /** Toggles the visibility of the notifications shade. */
        void togglePanel();

        /** Returns {@code true} if the panel is open. */
        boolean isNotificationPanelOpen();
    }

    private void checkAllBars(boolean isSetUp) {
        mTopView = getTopBar(isSetUp);
        mBottomView = getBottomBar(isSetUp);
        mLeftView = getLeftBar(isSetUp);
        mRightView = getRightBar(isSetUp);
    }
}
