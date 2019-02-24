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

package com.android.systemui.notifications;

import android.app.ActivityManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarNotificationView;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.policy.ConfigurationController;

/**
 * Standalone SystemUI for displaying Notifications that have been designed to be used in the car
 */
public class NotificationsUI extends SystemUI
        implements ConfigurationController.ConfigurationListener {

    private static final String TAG = "NotificationsUI";
    // used to calculate how fast to open or close the window
    private static final float DEFAULT_FLING_VELOCITY = 0;
    // max time a fling animation takes
    private static final float FLING_ANIMATION_MAX_TIME = 0.5f;
    // acceleration rate for the fling animation
    private static final float FLING_SPEED_UP_FACTOR = 0.6f;
    private CarNotificationListener mCarNotificationListener;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private NotificationClickHandlerFactory mClickHandlerFactory;
    private Car mCar;
    private ViewGroup mCarNotificationWindow;
    private NotificationViewController mNotificationViewController;
    private boolean mIsShowing;
    private boolean mIsTracking;
    private boolean mNotificationListAtBottom;
    private boolean mNotificationListAtBottomAtTimeOfTouch;
    private CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper =
            new CarUxRestrictionManagerWrapper();
    // Used in the Notification panel touch listener
    private GestureDetector mGestureDetector;
    // Used in scrollable content of the notifications
    private GestureDetector mScrollUpDetector;
    private View mContent;
    private View.OnTouchListener mOnTouchListener;
    private FlingAnimationUtils mFlingAnimationUtils;
    private static int sSettleOpenPercentage;
    private static int sSettleClosePercentage;

    /**
     * Inits the window that hosts the notifications and establishes the connections
     * to the car related services.
     */
    @Override
    public void start() {
        sSettleOpenPercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_open_percentage);
        sSettleClosePercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_close_percentage);
        WindowManager windowManager =
                (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mFlingAnimationUtils = new FlingAnimationUtils(mContext,
                FLING_ANIMATION_MAX_TIME, FLING_SPEED_UP_FACTOR);
        mCarNotificationListener = new CarNotificationListener();
        // create a notification click handler that closes the notification ui if the an activity
        // is launched successfully
        mClickHandlerFactory = new NotificationClickHandlerFactory(
                IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE)),
                launchResult -> {
                    if (launchResult == ActivityManager.START_TASK_TO_FRONT
                            || launchResult == ActivityManager.START_SUCCESS){
                        closeCarNotifications(DEFAULT_FLING_VELOCITY);
                    }
                });
        mCarNotificationListener.registerAsSystemService(mContext, mCarUxRestrictionManagerWrapper,
                mClickHandlerFactory);
        mCar = Car.createCar(mContext, mCarConnectionListener);
        mCar.connect();
        NotificationGestureListener gestureListener = new NotificationGestureListener();
        mGestureDetector = new GestureDetector(mContext, gestureListener);
        mScrollUpDetector = new GestureDetector(mContext, new ScrollUpDetector());
        mOnTouchListener = new NotificationPanelTouchListener();
        mCarNotificationWindow = (ViewGroup) View.inflate(new ContextThemeWrapper(mContext,
                        R.style.Theme_Notification),
                R.layout.navigation_bar_window, null);
        mCarNotificationWindow
                .setBackgroundColor(mContext.getColor(R.color.notification_shade_background_color));

        inflateNotificationContent();

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        layoutParams.setTitle("Car Notification Window");
        // start in the hidden state
        mCarNotificationWindow.setVisibility(View.GONE);
        windowManager.addView(mCarNotificationWindow, layoutParams);

        // Add this object to the SystemUI component registry such that the status bar
        // can get a reference to it.
        putComponent(NotificationsUI.class, this);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void inflateNotificationContent() {
        if (mNotificationViewController != null) {
            mNotificationViewController.disable();
        }
        mCarNotificationWindow.removeAllViews();

        mContent = View.inflate(new ContextThemeWrapper(mContext,
                        com.android.car.notification.R.style.Theme_Notification),
                R.layout.notification_center_activity,
                mCarNotificationWindow);
        // set the click handler such that we can dismiss the UI when a notification is clicked
        CarNotificationView noteView = mCarNotificationWindow.findViewById(R.id.notification_view);
        noteView.setClickHandlerFactory(mClickHandlerFactory);

        mContent.setOnTouchListener(mOnTouchListener);
        // set initial translation after size is calculated
        mContent.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mContent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (!mIsShowing && !mIsTracking) {
                            mContent.setTranslationY(mContent.getHeight() * -1);
                        }
                    }
                });

        RecyclerView notificationList = mCarNotificationWindow
                .findViewById(com.android.car.notification.R.id.recycler_view);
        // register a scroll listener so we can figure out if we are at the bottom of the
        // list of notifications
        notificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!notificationList.canScrollVertically(1)) {
                    mNotificationListAtBottom = true;
                    return;
                }
                mNotificationListAtBottom = false;
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
        });
        // add a touch listener such that when the user scrolls up and they are at the bottom
        // of the list we can start the closing of the view.
        notificationList.setOnTouchListener(new NotificationListTouchListener());

        // There's a view installed at a higher z-order such that we can intercept the ACTION_DOWN
        // to set the initial click state.
        mCarNotificationWindow.findViewById(R.id.glass_pane).setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mNotificationListAtBottomAtTimeOfTouch = mNotificationListAtBottom;
                // register the down event with the gesture detectors so then know where the down
                // started. This is needed because at this point we don't know which listener
                // is going to handle scroll and fling events.
                mGestureDetector.onTouchEvent(event);
                mScrollUpDetector.onTouchEvent(event);
            }
            return false;
        });

        mNotificationViewController = new NotificationViewController(
                mCarNotificationWindow
                        .findViewById(com.android.car.notification.R.id.notification_view),
                PreprocessingManager.getInstance(mContext),
                mCarNotificationListener,
                mCarUxRestrictionManagerWrapper);
        mNotificationViewController.enable();
    }

    // allows for day night switch
    @Override
    public void onConfigChanged(Configuration newConfig) {
        inflateNotificationContent();
    }

    public View.OnTouchListener getDragDownListener() {
        return mOnTouchListener;
    }

    /**
     * This listener is attached to the notification list UI to intercept gestures if the user
     * is scrolling up when the notification list is at the bottom
     */
    private class ScrollUpDetector extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            return distanceY > 0;
        }
    }

    private class NotificationListTouchListener implements View.OnTouchListener {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            // reset mNotificationListAtBottomAtTimeOfTouch here since the "glass pane" will not
            // get the up event
            if (event.getActionMasked() == MotionEvent.ACTION_UP ) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            boolean wasScrolledUp = mScrollUpDetector.onTouchEvent(event);

            if (mIsTracking
                    || (mNotificationListAtBottomAtTimeOfTouch && mNotificationListAtBottom
                    && wasScrolledUp)) {
                mOnTouchListener.onTouch(v, event);
                // touch event should not be propagated further
                return true;
            }
            return false;
        }
    }

    /**
     * Touch listener installed on the notification panel. It is also used by the Nav and StatusBar
     */
    private class NotificationPanelTouchListener implements View.OnTouchListener {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            boolean consumed = mGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            if (!mIsTracking || event.getActionMasked() != MotionEvent.ACTION_UP) {
                return false;
            }

            float percentFromBottom =
                    Math.abs(mContent.getTranslationY() / mContent.getHeight()) * 100;
            if (mIsShowing) {
                if (percentFromBottom < sSettleOpenPercentage) {
                    // panel started to close but did not cross minimum threshold thus we open
                    // it back up
                    openCarNotifications(DEFAULT_FLING_VELOCITY);
                    return true;
                }
                // panel was lifted more than the threshold thus we close it the rest of the way
                closeCarNotifications(DEFAULT_FLING_VELOCITY);
                return true;
            }

            if (percentFromBottom > sSettleClosePercentage) {
                // panel was only peeked at thus close it back up
                closeCarNotifications(DEFAULT_FLING_VELOCITY);
                return true;
            }
            // panel has been open more than threshold thus open it the rest of the way
            openCarNotifications(DEFAULT_FLING_VELOCITY);
            return true;

        }
    }

    /**
     * Listener called by mGestureDetector. This will be initiated from the
     * NotificationPanelTouchListener
     */
    private class NotificationGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_UP_MIN_DISTANCE = 75;
        private static final int SWIPE_DOWN_MIN_DISTANCE = 25;
        private static final int SWIPE_MAX_OFF_PATH = 75;
        private static final int SWIPE_THRESHOLD_VELOCITY = 200;

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            mIsTracking = true;
            mCarNotificationWindow.setVisibility(View.VISIBLE);

            mContent.setTranslationY(Math.min(mContent.getTranslationY() - distanceY, 0));
            if (mContent.getTranslationY() == 0) {
                mIsTracking = false;
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY){
                // swipe was not vertical or was not fast enough
                return false;
            }

            boolean isUp = velocityY < 0;
            float distanceDelta = Math.abs(event1.getY() - event2.getY());

            if (isUp && distanceDelta > SWIPE_UP_MIN_DISTANCE) {
                // fling up
                mIsTracking = false;
                closeCarNotifications(Math.abs(velocityY));
                return true;

            } else if (!isUp && distanceDelta > SWIPE_DOWN_MIN_DISTANCE) {
                // fling down
                mIsTracking = false;
                openCarNotifications(velocityY);
                return true;
            }

            return false;
        }
    }

    /**
     * Connection callback to establish UX Restrictions
     */
    private ServiceConnection mCarConnectionListener = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mCarUxRestrictionsManager = (CarUxRestrictionsManager) mCar.getCarManager(
                        Car.CAR_UX_RESTRICTION_SERVICE);
                mCarUxRestrictionManagerWrapper
                        .setCarUxRestrictionsManager(mCarUxRestrictionsManager);
                PreprocessingManager preprocessingManager = PreprocessingManager.getInstance(
                        mContext);
                preprocessingManager
                        .setCarUxRestrictionManagerWrapper(mCarUxRestrictionManagerWrapper);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car not connected in CarConnectionListener", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "Car service disconnected unexpectedly");
        }
    };

    /**
     * Toggles the visibility of the notifications
     */
    public void toggleShowingCarNotifications() {
        if (mCarNotificationWindow.getVisibility() == View.VISIBLE) {
            closeCarNotifications(DEFAULT_FLING_VELOCITY);
            return;
        }
        openCarNotifications(DEFAULT_FLING_VELOCITY);
    }

    /**
     * Hides the notifications
     */
    public void closeCarNotifications(float velocityY) {
        float closedTranslation = mContent.getHeight() * -1;
        ValueAnimator animator =
                ValueAnimator.ofFloat(mContent.getTranslationY(), closedTranslation);
        animator.addUpdateListener(
                animation -> mContent.setTranslationY((Float) animation.getAnimatedValue()));
        mFlingAnimationUtils.apply(
                animator, mContent.getTranslationY(), closedTranslation, velocityY);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mCarNotificationWindow.setVisibility(View.GONE);
            }
        });
        animator.start();
        mNotificationViewController.disable();
        mIsShowing = false;
        mIsTracking = false;
        RecyclerView notificationListView = mCarNotificationWindow.findViewById(
                com.android.car.notification.R.id.recycler_view);
        notificationListView.scrollToPosition(0);
    }

    /**
     * Sets the notifications to visible
     */
    public void openCarNotifications(float velocityY) {
        mCarNotificationWindow.setVisibility(View.VISIBLE);

        ValueAnimator animator = ValueAnimator.ofFloat(mContent.getTranslationY(), 0);
        animator.addUpdateListener(
                animation -> mContent.setTranslationY((Float) animation.getAnimatedValue()));
        mFlingAnimationUtils.apply(animator, mContent.getTranslationY(), 0, velocityY);
        animator.start();

        mNotificationViewController.enable();
        mIsShowing = true;
        mIsTracking = false;
    }
}
