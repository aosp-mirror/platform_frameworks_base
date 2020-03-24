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

package com.android.systemui.car.notification;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarNotificationView;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationDataManager;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.window.OverlayViewController;
import com.android.systemui.window.OverlayViewGlobalStateController;

import javax.inject.Inject;
import javax.inject.Singleton;

/** View controller for the notification panel. */
@Singleton
public class NotificationPanelViewController extends OverlayViewController {

    // used to calculate how fast to open or close the window
    private static final float DEFAULT_FLING_VELOCITY = 0;
    // max time a fling animation takes
    private static final float FLING_ANIMATION_MAX_TIME = 0.5f;
    // acceleration rate for the fling animation
    private static final float FLING_SPEED_UP_FACTOR = 0.6f;

    private static final int SWIPE_DOWN_MIN_DISTANCE = 25;
    private static final int SWIPE_MAX_OFF_PATH = 75;
    private static final int SWIPE_THRESHOLD_VELOCITY = 200;
    private static final boolean DEBUG = true;
    private static final String TAG = "NotificationPanelViewController";

    private final Context mContext;
    private final Resources mResources;
    private final CarServiceProvider mCarServiceProvider;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final IStatusBarService mBarService;
    private final CommandQueue mCommandQueue;
    private final NotificationDataManager mNotificationDataManager;
    private final CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper;
    private final CarNotificationListener mCarNotificationListener;
    private final NotificationClickHandlerFactory mNotificationClickHandlerFactory;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final StatusBarStateController mStatusBarStateController;

    private final int mSettleClosePercentage;

    private float mOpeningVelocity = DEFAULT_FLING_VELOCITY;
    private float mClosingVelocity = DEFAULT_FLING_VELOCITY;

    private float mInitialBackgroundAlpha;
    private float mBackgroundAlphaDiff;

    private CarNotificationView mNotificationView;
    private View mHandleBar;
    private RecyclerView mNotificationList;
    private NotificationViewController mNotificationViewController;

    private boolean mIsTracking;
    private boolean mNotificationListAtBottom;
    private float mFirstTouchDownOnGlassPane;
    private boolean mNotificationListAtBottomAtTimeOfTouch;
    private boolean mIsSwipingVerticallyToClose;
    private int mPercentageFromBottom;
    private boolean mIsNotificationAnimating;
    private boolean mIsNotificationCardSwiping;
    private boolean mPanelExpanded = false;

    private View.OnTouchListener mTopNavBarNotificationTouchListener;
    private View.OnTouchListener mNavBarNotificationTouchListener;

    private OnUnseenCountUpdateListener mUnseenCountUpdateListener;

    @Inject
    public NotificationPanelViewController(
            Context context,
            @Main Resources resources,
            OverlayViewGlobalStateController overlayViewGlobalStateController,

            /* Other things */
            CarServiceProvider carServiceProvider,
            CarDeviceProvisionedController carDeviceProvisionedController,

            /* Things needed for notifications */
            IStatusBarService barService,
            CommandQueue commandQueue,
            NotificationDataManager notificationDataManager,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            CarNotificationListener carNotificationListener,
            NotificationClickHandlerFactory notificationClickHandlerFactory,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,

            /* Things that need to be replaced */
            StatusBarStateController statusBarStateController
    ) {
        super(R.id.notification_panel_stub, overlayViewGlobalStateController);
        mContext = context;
        mResources = resources;
        mCarServiceProvider = carServiceProvider;
        mCarDeviceProvisionedController = carDeviceProvisionedController;
        mBarService = barService;
        mCommandQueue = commandQueue;
        mNotificationDataManager = notificationDataManager;
        mCarUxRestrictionManagerWrapper = carUxRestrictionManagerWrapper;
        mCarNotificationListener = carNotificationListener;
        mNotificationClickHandlerFactory = notificationClickHandlerFactory;
        mFlingAnimationUtils = flingAnimationUtilsBuilder
                .setMaxLengthSeconds(FLING_ANIMATION_MAX_TIME)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mStatusBarStateController = statusBarStateController;

        // Notification background setup.
        mInitialBackgroundAlpha = (float) mResources.getInteger(
                R.integer.config_initialNotificationBackgroundAlpha) / 100;
        if (mInitialBackgroundAlpha < 0 || mInitialBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect initial background alpha"
                            + " percentage");
        }
        float finalBackgroundAlpha = Math.max(
                mInitialBackgroundAlpha,
                (float) mResources.getInteger(
                        R.integer.config_finalNotificationBackgroundAlpha) / 100);
        if (finalBackgroundAlpha < 0 || finalBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect final background alpha"
                            + " percentage");
        }
        mBackgroundAlphaDiff = finalBackgroundAlpha - mInitialBackgroundAlpha;

        // Notification Panel param setup
        mSettleClosePercentage = mResources.getInteger(
                R.integer.notification_settle_close_percentage);

        // Attached to the top navigation bar (i.e. status bar) to detect pull down of the
        // notification shade.
        GestureDetector openGestureDetector = new GestureDetector(mContext,
                new OpenNotificationGestureListener() {
                    @Override
                    protected void openNotification() {
                        animateExpandNotificationsPanel();
                    }
                });

        // Attached to the NavBars to close the notification shade
        GestureDetector navBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new NavBarCloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            animateCollapsePanels();
                        }
                    }
                });

        mTopNavBarNotificationTouchListener = (v, event) -> {
            if (!isInflated()) {
                getOverlayViewGlobalStateController().inflateView(this);
            }
            if (!mCarDeviceProvisionedController.isCurrentUserFullySetup()) {
                return true;
            }

            boolean consumed = openGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            maybeCompleteAnimation(event);
            return true;
        };

        mNavBarNotificationTouchListener =
                (v, event) -> {
                    boolean consumed = navBarCloseNotificationGestureDetector.onTouchEvent(event);
                    if (consumed) {
                        return true;
                    }
                    maybeCompleteAnimation(event);
                    return true;
                };
    }

    @Override
    protected void onFinishInflate() {
        reinflate();
    }

    /** Reinflates the view. */
    public void reinflate() {
        ViewGroup container = (ViewGroup) getLayout();
        container.removeView(mNotificationView);

        mNotificationView = (CarNotificationView) LayoutInflater.from(mContext).inflate(
                R.layout.notification_center_activity, container,
                /* attachToRoot= */ false);

        container.addView(mNotificationView);
        onNotificationViewInflated();
    }

    private void onNotificationViewInflated() {
        // Find views.
        mNotificationView = getLayout().findViewById(R.id.notification_view);
        View glassPane = mNotificationView.findViewById(R.id.glass_pane);
        mHandleBar = mNotificationView.findViewById(R.id.handle_bar);
        mNotificationList = mNotificationView.findViewById(R.id.notifications);

        mNotificationClickHandlerFactory.registerClickListener((launchResult, alertEntry) -> {
            if (launchResult == ActivityManager.START_TASK_TO_FRONT
                    || launchResult == ActivityManager.START_SUCCESS) {
                animateCollapsePanels();
            }
        });

        mNotificationDataManager.setOnUnseenCountUpdateListener(() -> {
            if (mUnseenCountUpdateListener != null) {
                mUnseenCountUpdateListener.onUnseenCountUpdate(
                        mNotificationDataManager.getUnseenNotificationCount());
            }
        });
        mNotificationClickHandlerFactory.setNotificationDataManager(mNotificationDataManager);
        mNotificationView.setClickHandlerFactory(mNotificationClickHandlerFactory);
        mNotificationView.setNotificationDataManager(mNotificationDataManager);

        mNotificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!mNotificationList.canScrollVertically(1)) {
                    mNotificationListAtBottom = true;
                    return;
                }
                mNotificationListAtBottom = false;
                mIsSwipingVerticallyToClose = false;
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
        });

        // Attached to the notification ui to detect close request of the notification shade.
        GestureDetector closeGestureDetector = new GestureDetector(mContext,
                new CloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            animateCollapsePanels();
                        }
                    }
                });

        // Attached to the Handle bar to close the notification shade
        GestureDetector handleBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new HandleBarCloseNotificationGestureListener());

        // The glass pane is used to view touch events before passed to the notification list.
        // This allows us to initialize gesture listeners and detect when to close the notifications
        glassPane.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFirstTouchDownOnGlassPane = event.getRawX();
                mNotificationListAtBottomAtTimeOfTouch = mNotificationListAtBottom;
                // Reset the tracker when there is a touch down on the glass pane.
                mIsTracking = false;
                // Pass the down event to gesture detector so that it knows where the touch event
                // started.
                closeGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        mNotificationList.setOnTouchListener((v, event) -> {
            mIsNotificationCardSwiping = Math.abs(mFirstTouchDownOnGlassPane - event.getRawX())
                    > SWIPE_MAX_OFF_PATH;
            if (mNotificationListAtBottomAtTimeOfTouch && mNotificationListAtBottom) {
                // We need to save the state here as if notification card is swiping we will
                // change the mNotificationListAtBottomAtTimeOfTouch. This is to protect
                // closing the notification shade while the notification card is being swiped.
                mIsSwipingVerticallyToClose = true;
            }

            // If the card is swiping we should not allow the notification shade to close.
            // Hence setting mNotificationListAtBottomAtTimeOfTouch to false will stop that
            // for us. We are also checking for mIsTracking because while swiping the
            // notification shade to close if the user goes a bit horizontal while swiping
            // upwards then also this should close.
            if (mIsNotificationCardSwiping && !mIsTracking) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }

            boolean handled = closeGestureDetector.onTouchEvent(event);
            boolean isTracking = mIsTracking;
            Rect rect = mNotificationView.getClipBounds();
            float clippedHeight = 0;
            if (rect != null) {
                clippedHeight = rect.bottom;
            }
            if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP
                    && mIsSwipingVerticallyToClose) {
                if (mSettleClosePercentage < mPercentageFromBottom && isTracking) {
                    animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
                } else if (clippedHeight != mNotificationView.getHeight() && isTracking) {
                    // this can be caused when user is at the end of the list and trying to
                    // fling to top of the list by scrolling down.
                    animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
                }
            }

            // Updating the mNotificationListAtBottomAtTimeOfTouch state has to be done after
            // the event has been passed to the closeGestureDetector above, such that the
            // closeGestureDetector sees the up event before the state has changed.
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            return handled || isTracking;
        });

        mCarServiceProvider.addListener(car -> {
            CarUxRestrictionsManager carUxRestrictionsManager =
                    (CarUxRestrictionsManager)
                            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            mCarUxRestrictionManagerWrapper.setCarUxRestrictionsManager(
                    carUxRestrictionsManager);

            mNotificationViewController = new NotificationViewController(
                    mNotificationView,
                    PreprocessingManager.getInstance(mContext),
                    mCarNotificationListener,
                    mCarUxRestrictionManagerWrapper,
                    mNotificationDataManager);
            mNotificationViewController.enable();
        });

        mHandleBar.setOnTouchListener((v, event) -> {
            handleBarCloseNotificationGestureDetector.onTouchEvent(event);
            maybeCompleteAnimation(event);
            return true;
        });
    }

    /** Called when the car power state is changed to ON. */
    public void onCarPowerStateOn() {
        if (mNotificationClickHandlerFactory != null) {
            mNotificationClickHandlerFactory.clearAllNotifications();
        }
        mNotificationDataManager.clearAll();
    }

    View.OnTouchListener getTopNavBarNotificationTouchListener() {
        return mTopNavBarNotificationTouchListener;
    }

    View.OnTouchListener getNavBarNotificationTouchListener() {
        return mNavBarNotificationTouchListener;
    }

    private void maybeCompleteAnimation(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && mNotificationView.getVisibility() == View.VISIBLE) {
            if (mSettleClosePercentage < mPercentageFromBottom) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
            } else {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
        }
    }

    /**
     * Animates the notification shade from one position to other. This is used to either open or
     * close the notification shade completely with a velocity. If the animation is to close the
     * notification shade this method also makes the view invisible after animation ends.
     */
    private void animateNotificationPanel(float velocity, boolean isClosing) {
        float to = 0;
        if (!isClosing) {
            to = mNotificationView.getHeight();
        }

        Rect rect = mNotificationView.getClipBounds();
        if (rect != null && rect.bottom != to) {
            float from = rect.bottom;
            animate(from, to, velocity, isClosing);
            return;
        }

        // We will only be here if the shade is being opened programmatically or via button when
        // height of the layout was not calculated.
        ViewTreeObserver notificationTreeObserver = mNotificationView.getViewTreeObserver();
        notificationTreeObserver.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver obs = mNotificationView.getViewTreeObserver();
                        obs.removeOnGlobalLayoutListener(this);
                        float to = mNotificationView.getHeight();
                        animate(/* from= */ 0, to, velocity, isClosing);
                    }
                });
    }

    private void animateCollapsePanels() {
        if (!mPanelExpanded || mNotificationView.getVisibility() == View.INVISIBLE) {
            return;
        }
        getOverlayViewGlobalStateController().setWindowFocusable(false);
        animateNotificationPanel(mClosingVelocity, true);
    }

    private void animateExpandNotificationsPanel() {
        if (!mCommandQueue.panelsEnabled()
                || !mCarDeviceProvisionedController.isCurrentUserFullySetup()) {
            return;
        }
        // scroll to top
        mNotificationList.scrollToPosition(0);
        setPanelVisible(true);
        mNotificationView.setVisibility(View.VISIBLE);
        animateNotificationPanel(mOpeningVelocity, false);

        setPanelExpanded(true);
    }

    private void animate(float from, float to, float velocity, boolean isClosing) {
        if (mIsNotificationAnimating) {
            return;
        }
        mIsNotificationAnimating = true;
        mIsTracking = true;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.addUpdateListener(
                animation -> {
                    float animatedValue = (Float) animation.getAnimatedValue();
                    setNotificationViewClipBounds((int) animatedValue);
                });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mIsNotificationAnimating = false;
                mIsTracking = false;
                mOpeningVelocity = DEFAULT_FLING_VELOCITY;
                mClosingVelocity = DEFAULT_FLING_VELOCITY;
                if (isClosing) {
                    setPanelVisible(false);
                    mNotificationView.setVisibility(View.INVISIBLE);
                    mNotificationView.setClipBounds(null);
                    mNotificationViewController.onVisibilityChanged(false);
                    // let the status bar know that the panel is closed
                    setPanelExpanded(false);
                } else {
                    mNotificationViewController.onVisibilityChanged(true);
                    // let the status bar know that the panel is open
                    mNotificationView.setVisibleNotificationsAsSeen();
                    setPanelExpanded(true);
                }
            }
        });
        mFlingAnimationUtils.apply(animator, from, to, Math.abs(velocity));
        animator.start();
    }

    /**
     * Set the panel view to be visible.
     */
    public void setPanelVisible(boolean visible) {
        if (visible && !getOverlayViewGlobalStateController().isWindowVisible()) {
            getOverlayViewGlobalStateController().setWindowVisible(true);
        }
        if (!visible && getOverlayViewGlobalStateController().isWindowVisible()) {
            getOverlayViewGlobalStateController().setWindowVisible(false);
        }
        getLayout().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        getOverlayViewGlobalStateController().setWindowFocusable(visible);
    }

    /**
     * Set the panel state to expanded. This will expand or collapse the overlay window if
     * necessary.
     */
    public void setPanelExpanded(boolean expand) {
        mPanelExpanded = expand;

        if (expand && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setExpandedHeight");
            }
            clearNotificationEffects();
        }
    }

    /**
     * Clear Buzz/Beep/Blink.
     */
    private void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    private void setNotificationViewClipBounds(int height) {
        if (height > mNotificationView.getHeight()) {
            height = mNotificationView.getHeight();
        }
        Rect clipBounds = new Rect();
        clipBounds.set(0, 0, mNotificationView.getWidth(), height);
        // Sets the clip region on the notification list view.
        mNotificationView.setClipBounds(clipBounds);
        if (mHandleBar != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mHandleBar.getLayoutParams();
            mHandleBar.setTranslationY(height - mHandleBar.getHeight() - lp.bottomMargin);
        }
        if (mNotificationView.getHeight() > 0) {
            Drawable background = mNotificationView.getBackground().mutate();
            background.setAlpha((int) (getBackgroundAlpha(height) * 255));
            mNotificationView.setBackground(background);
        }
    }

    /**
     * Calculates the alpha value for the background based on how much of the notification
     * shade is visible to the user. When the notification shade is completely open then
     * alpha value will be 1.
     */
    private float getBackgroundAlpha(int height) {
        return mInitialBackgroundAlpha
                + ((float) height / mNotificationView.getHeight() * mBackgroundAlphaDiff);
    }

    private void calculatePercentageFromBottom(float height) {
        if (mNotificationView.getHeight() > 0) {
            mPercentageFromBottom = (int) Math.abs(
                    height / mNotificationView.getHeight() * 100);
        }
    }

    /** Toggles the visibility of the notification panel. */
    public void toggle() {
        if (!isInflated()) {
            getOverlayViewGlobalStateController().inflateView(this);
        }
        if (mPanelExpanded) {
            animateCollapsePanels();
        } else {
            animateExpandNotificationsPanel();
        }
    }

    /** Sets the unseen count listener. */
    public void setOnUnseenCountUpdateListener(OnUnseenCountUpdateListener listener) {
        mUnseenCountUpdateListener = listener;
    }

    /** Listener that is updated when the number of unseen notifications changes. */
    public interface OnUnseenCountUpdateListener {
        /**
         * This method is automatically called whenever there is an update to the number of unseen
         * notifications. This method can be extended by OEMs to customize the desired logic.
         */
        void onUnseenCountUpdate(int unseenNotificationCount);
    }

    /**
     * Only responsible for open hooks. Since once the panel opens it covers all elements
     * there is no need to merge with close.
     */
    private abstract class OpenNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {

            if (mNotificationView.getVisibility() == View.INVISIBLE) {
                // when the on-scroll is called for the first time to open.
                mNotificationList.scrollToPosition(0);
            }
            setPanelVisible(true);
            mNotificationView.setVisibility(View.VISIBLE);

            // clips the view for the notification shade when the user scrolls to open.
            setNotificationViewClipBounds((int) event2.getRawY());

            // Initially the scroll starts with height being zero. This checks protects from divide
            // by zero error.
            calculatePercentageFromBottom(event2.getRawY());

            mIsTracking = true;
            return true;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (velocityY > SWIPE_THRESHOLD_VELOCITY) {
                mOpeningVelocity = velocityY;
                openNotification();
                return true;
            }
            animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);

            return false;
        }

        protected abstract void openNotification();
    }

    /**
     * To be installed on the open panel notification panel
     */
    private abstract class CloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapUp(MotionEvent motionEvent) {
            if (mPanelExpanded) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            // should not clip while scroll to the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            float actualNotificationHeight =
                    mNotificationView.getHeight() - (event1.getRawY() - event2.getRawY());
            if (actualNotificationHeight > mNotificationView.getHeight()) {
                actualNotificationHeight = mNotificationView.getHeight();
            }
            if (mNotificationView.getHeight() > 0) {
                mPercentageFromBottom = (int) Math.abs(
                        actualNotificationHeight / mNotificationView.getHeight() * 100);
                boolean isUp = distanceY > 0;

                // This check is to figure out if onScroll was called while swiping the card at
                // bottom of the list. At that time we should not allow notification shade to
                // close. We are also checking for the upwards swipe gesture here because it is
                // possible if a user is closing the notification shade and while swiping starts
                // to open again but does not fling. At that time we should allow the
                // notification shade to close fully or else it would stuck in between.
                if (Math.abs(mNotificationView.getHeight() - actualNotificationHeight)
                        > SWIPE_DOWN_MIN_DISTANCE && isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                    mIsTracking = true;
                } else if (!isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                }
            }
            // if we return true the items in RV won't be scrollable.
            return false;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            // should not fling if the touch does not start when view is at the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY) {
                // swipe was not vertical or was not fast enough
                return false;
            }
            boolean isUp = velocityY < 0;
            if (isUp) {
                close();
                return true;
            } else {
                // we should close the shade
                animateNotificationPanel(velocityY, false);
            }
            return false;
        }

        protected abstract void close();
    }

    /**
     * To be installed on the nav bars.
     */
    private abstract class NavBarCloseNotificationGestureListener extends
            CloseNotificationGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            mClosingVelocity = DEFAULT_FLING_VELOCITY;
            if (mPanelExpanded) {
                close();
            }
            return super.onSingleTapUp(e);
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            setNotificationViewClipBounds((int) event2.getRawY());
            return true;
        }
    }

    /**
     * To be installed on the handle bar.
     */
    private class HandleBarCloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            // To prevent the jump in the clip bounds while closing the notification shade using
            // the handle bar we should calculate the height using the diff of event1 and event2.
            // This will help the notification shade to clip smoothly as the event2 value changes
            // as event1 value will be fixed.
            int clipHeight =
                    mNotificationView.getHeight() - (int) (event1.getRawY() - event2.getRawY());
            setNotificationViewClipBounds(clipHeight);
            return true;
        }
    }
}
