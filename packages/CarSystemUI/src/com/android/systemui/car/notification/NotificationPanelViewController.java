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

import android.app.ActivityManager;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

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
import com.android.systemui.car.window.OverlayPanelViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/** View controller for the notification panel. */
@Singleton
public class NotificationPanelViewController extends OverlayPanelViewController
        implements CommandQueue.Callbacks {

    private static final boolean DEBUG = true;
    private static final String TAG = "NotificationPanelViewController";

    private final Context mContext;
    private final Resources mResources;
    private final CarServiceProvider mCarServiceProvider;
    private final IStatusBarService mBarService;
    private final CommandQueue mCommandQueue;
    private final Executor mUiBgExecutor;
    private final NotificationDataManager mNotificationDataManager;
    private final CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper;
    private final CarNotificationListener mCarNotificationListener;
    private final NotificationClickHandlerFactory mNotificationClickHandlerFactory;
    private final StatusBarStateController mStatusBarStateController;
    private final boolean mEnableHeadsUpNotificationWhenNotificationShadeOpen;
    private final NotificationVisibilityLogger mNotificationVisibilityLogger;
    private final int mNavBarHeight;

    private float mInitialBackgroundAlpha;
    private float mBackgroundAlphaDiff;

    private CarNotificationView mNotificationView;
    private View mHandleBar;
    private RecyclerView mNotificationList;
    private NotificationViewController mNotificationViewController;

    private boolean mNotificationListAtEnd;
    private float mFirstTouchDownOnGlassPane;
    private boolean mNotificationListAtEndAtTimeOfTouch;
    private boolean mIsSwipingVerticallyToClose;
    private boolean mIsNotificationCardSwiping;

    private OnUnseenCountUpdateListener mUnseenCountUpdateListener;

    @Inject
    public NotificationPanelViewController(
            Context context,
            @Main Resources resources,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            @UiBackground Executor uiBgExecutor,

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
            NotificationVisibilityLogger notificationVisibilityLogger,

            /* Things that need to be replaced */
            StatusBarStateController statusBarStateController
    ) {
        super(context, resources, R.id.notification_panel_stub, overlayViewGlobalStateController,
                flingAnimationUtilsBuilder, carDeviceProvisionedController);
        mContext = context;
        mResources = resources;
        mCarServiceProvider = carServiceProvider;
        mBarService = barService;
        mCommandQueue = commandQueue;
        mUiBgExecutor = uiBgExecutor;
        mNotificationDataManager = notificationDataManager;
        mCarUxRestrictionManagerWrapper = carUxRestrictionManagerWrapper;
        mCarNotificationListener = carNotificationListener;
        mNotificationClickHandlerFactory = notificationClickHandlerFactory;
        mStatusBarStateController = statusBarStateController;
        mNotificationVisibilityLogger = notificationVisibilityLogger;

        mNavBarHeight = mResources.getDimensionPixelSize(R.dimen.car_bottom_navigation_bar_height);

        mCommandQueue.addCallback(this);

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

        mEnableHeadsUpNotificationWhenNotificationShadeOpen = mResources.getBoolean(
                com.android.car.notification.R.bool
                        .config_enableHeadsUpNotificationWhenNotificationShadeOpen);
    }

    // CommandQueue.Callbacks

    @Override
    public void animateExpandNotificationsPanel() {
        if (!isPanelExpanded()) {
            toggle();
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        if (isPanelExpanded()) {
            toggle();
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (mContext.getDisplayId() != displayId) {
            return;
        }
        boolean isKeyboardVisible = (vis & InputMethodService.IME_VISIBLE) != 0;
        int bottomMargin = isKeyboardVisible ? 0 : mNavBarHeight;
        ViewGroup container = (ViewGroup) getLayout();
        if (container == null) {
            // Notification panel hasn't been inflated before. We shouldn't try to update the layout
            // params.
            return;
        }

        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, bottomMargin);
        container.setLayoutParams(params);
    }

    // OverlayViewController

    @Override
    protected void onFinishInflate() {
        reinflate();
    }

    @Override
    protected void hideInternal() {
        super.hideInternal();
        mNotificationVisibilityLogger.stop();
    }

    @Override
    protected boolean shouldShowNavigationBarInsets() {
        return true;
    }

    @Override
    protected boolean shouldShowStatusBarInsets() {
        return true;
    }

    @Override
    protected int getInsetTypesToFit() {
        return 0;
    }

    @Override
    protected boolean shouldShowHUN() {
        return mEnableHeadsUpNotificationWhenNotificationShadeOpen;
    }

    /** Reinflates the view. */
    public void reinflate() {
        // Do not reinflate the view if it has not been inflated at all.
        if (!isInflated()) return;

        ViewGroup container = (ViewGroup) getLayout();
        container.removeView(mNotificationView);

        mNotificationView = (CarNotificationView) LayoutInflater.from(mContext).inflate(
                R.layout.notification_center_activity, container,
                /* attachToRoot= */ false);
        mNotificationView.setKeyEventHandler(
                event -> {
                    if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                        return false;
                    }

                    if (event.getAction() == KeyEvent.ACTION_UP && isPanelExpanded()) {
                        toggle();
                    }
                    return true;
                });

        container.addView(mNotificationView);
        onNotificationViewInflated();
    }

    private void onNotificationViewInflated() {
        // Find views.
        mNotificationView = getLayout().findViewById(R.id.notification_view);
        setupHandleBar();
        setupNotificationPanel();

        mNotificationClickHandlerFactory.registerClickListener((launchResult, alertEntry) -> {
            if (launchResult == ActivityManager.START_TASK_TO_FRONT
                    || launchResult == ActivityManager.START_SUCCESS) {
                animateCollapsePanel();
            }
        });

        mNotificationDataManager.setOnUnseenCountUpdateListener(() -> {
            if (mUnseenCountUpdateListener != null) {
                mUnseenCountUpdateListener.onUnseenCountUpdate(
                        mNotificationDataManager.getUnseenNotificationCount());
            }
            mCarNotificationListener.setNotificationsShown(
                    mNotificationDataManager.getSeenNotifications());
            // This logs both when the notification panel is expanded and when the notification
            // panel is scrolled.
            mNotificationVisibilityLogger.log(isPanelExpanded());
        });

        mNotificationClickHandlerFactory.setNotificationDataManager(mNotificationDataManager);
        mNotificationView.setClickHandlerFactory(mNotificationClickHandlerFactory);
        mNotificationView.setNotificationDataManager(mNotificationDataManager);

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
    }

    private void setupHandleBar() {
        mHandleBar = mNotificationView.findViewById(R.id.handle_bar);
        GestureDetector handleBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new HandleBarCloseGestureListener());
        mHandleBar.setOnTouchListener((v, event) -> {
            handleBarCloseNotificationGestureDetector.onTouchEvent(event);
            maybeCompleteAnimation(event);
            return true;
        });
    }

    private void setupNotificationPanel() {
        View glassPane = mNotificationView.findViewById(R.id.glass_pane);
        mNotificationList = mNotificationView.findViewById(R.id.notifications);
        GestureDetector closeGestureDetector = new GestureDetector(mContext,
                new CloseGestureListener() {
                    @Override
                    protected void close() {
                        if (isPanelExpanded()) {
                            animateCollapsePanel();
                        }
                    }
                });

        // The glass pane is used to view touch events before passed to the notification list.
        // This allows us to initialize gesture listeners and detect when to close the notifications
        glassPane.setOnTouchListener((v, event) -> {
            if (isClosingAction(event)) {
                mNotificationListAtEndAtTimeOfTouch = false;
            }
            if (isOpeningAction(event)) {
                mFirstTouchDownOnGlassPane = event.getRawX();
                mNotificationListAtEndAtTimeOfTouch = mNotificationListAtEnd;
                // Reset the tracker when there is a touch down on the glass pane.
                setIsTracking(false);
                // Pass the down event to gesture detector so that it knows where the touch event
                // started.
                closeGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        mNotificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // Check if we can scroll vertically in the animation direction.
                if (!mNotificationList.canScrollVertically(mAnimateDirection)) {
                    mNotificationListAtEnd = true;
                    return;
                }
                mNotificationListAtEnd = false;
                mIsSwipingVerticallyToClose = false;
                mNotificationListAtEndAtTimeOfTouch = false;
            }
        });

        mNotificationList.setOnTouchListener((v, event) -> {
            mIsNotificationCardSwiping = Math.abs(mFirstTouchDownOnGlassPane - event.getRawX())
                    > SWIPE_MAX_OFF_PATH;
            if (mNotificationListAtEndAtTimeOfTouch && mNotificationListAtEnd) {
                // We need to save the state here as if notification card is swiping we will
                // change the mNotificationListAtEndAtTimeOfTouch. This is to protect
                // closing the notification shade while the notification card is being swiped.
                mIsSwipingVerticallyToClose = true;
            }

            // If the card is swiping we should not allow the notification shade to close.
            // Hence setting mNotificationListAtEndAtTimeOfTouch to false will stop that
            // for us. We are also checking for isTracking() because while swiping the
            // notification shade to close if the user goes a bit horizontal while swiping
            // upwards then also this should close.
            if (mIsNotificationCardSwiping && !isTracking()) {
                mNotificationListAtEndAtTimeOfTouch = false;
            }

            boolean handled = closeGestureDetector.onTouchEvent(event);
            boolean isTracking = isTracking();
            Rect rect = getLayout().getClipBounds();
            float clippedHeight = 0;
            if (rect != null) {
                clippedHeight = rect.bottom;
            }
            if (!handled && isClosingAction(event) && mIsSwipingVerticallyToClose) {
                if (getSettleClosePercentage() < getPercentageFromEndingEdge() && isTracking) {
                    animatePanel(DEFAULT_FLING_VELOCITY, false);
                } else if (clippedHeight != getLayout().getHeight() && isTracking) {
                    // this can be caused when user is at the end of the list and trying to
                    // fling to top of the list by scrolling down.
                    animatePanel(DEFAULT_FLING_VELOCITY, true);
                }
            }

            // Updating the mNotificationListAtEndAtTimeOfTouch state has to be done after
            // the event has been passed to the closeGestureDetector above, such that the
            // closeGestureDetector sees the up event before the state has changed.
            if (isClosingAction(event)) {
                mNotificationListAtEndAtTimeOfTouch = false;
            }
            return handled || isTracking;
        });
    }

    /** Called when the car power state is changed to ON. */
    public void onCarPowerStateOn() {
        if (mNotificationClickHandlerFactory != null) {
            mNotificationClickHandlerFactory.clearAllNotifications();
        }
        mNotificationDataManager.clearAll();
    }

    // OverlayPanelViewController

    @Override
    protected boolean shouldAnimateCollapsePanel() {
        return true;
    }

    @Override
    protected void onAnimateCollapsePanel() {
        // No op.
    }

    @Override
    protected boolean shouldAnimateExpandPanel() {
        return mCommandQueue.panelsEnabled();
    }

    @Override
    protected void onAnimateExpandPanel() {
        mNotificationList.scrollToPosition(0);
    }

    @Override
    protected void onCollapseAnimationEnd() {
        mNotificationViewController.onVisibilityChanged(false);
    }

    @Override
    protected void onExpandAnimationEnd() {
        mNotificationViewController.onVisibilityChanged(true);
        mNotificationView.setVisibleNotificationsAsSeen();
    }

    @Override
    protected void onPanelVisible(boolean visible) {
        super.onPanelVisible(visible);
        mUiBgExecutor.execute(() -> {
            try {
                if (visible) {
                    // When notification panel is open even just a bit, we want to clear
                    // notification effects.
                    boolean clearNotificationEffects =
                            mStatusBarStateController.getState() != StatusBarState.KEYGUARD;
                    mBarService.onPanelRevealed(clearNotificationEffects,
                            mNotificationDataManager.getVisibleNotifications().size());
                } else {
                    mBarService.onPanelHidden();
                }
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
                Log.e(TAG, String.format(
                        "Unable to notify StatusBarService of panel visibility: %s", visible));
            }
        });

    }

    @Override
    protected void onPanelExpanded(boolean expand) {
        super.onPanelExpanded(expand);

        if (expand && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setExpandedHeight");
            }
            clearNotificationEffects();
        }
        if (!expand) {
            mNotificationVisibilityLogger.log(isPanelExpanded());
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

    @Override
    protected void onOpenScrollStart() {
        mNotificationList.scrollToPosition(0);
    }

    @Override
    protected void onScroll(int y) {
        if (mHandleBar != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mHandleBar.getLayoutParams();
            // Adjust handlebar to new pointer position, and a little more depending on the
            // animate direction so the bar can be seen fully.
            if (mAnimateDirection > 0) {
                mHandleBar.setTranslationY(y - mHandleBar.getHeight() - lp.bottomMargin);
            } else {
                mHandleBar.setTranslationY(y + mHandleBar.getHeight() + lp.topMargin);
            }
        }

        if (mNotificationView.getHeight() > 0) {
            Drawable background = mNotificationView.getBackground().mutate();
            background.setAlpha((int) (getBackgroundAlpha(y) * 255));
            mNotificationView.setBackground(background);
        }
    }

    @Override
    protected boolean shouldAllowClosingScroll() {
        // Unless the notification list is at the end, the panel shouldn't be allowed to
        // collapse on scroll.
        return mNotificationListAtEndAtTimeOfTouch;
    }

    /**
     * Calculates the alpha value for the background based on how much of the notification
     * shade is visible to the user. When the notification shade is completely open then
     * alpha value will be 1.
     */
    private float getBackgroundAlpha(int y) {
        float fractionCovered =
                ((float) (mAnimateDirection > 0 ? y : mNotificationView.getHeight() - y))
                        / mNotificationView.getHeight();
        return mInitialBackgroundAlpha + fractionCovered * mBackgroundAlphaDiff;
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
     * To be installed on the handle bar.
     */
    private class HandleBarCloseGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromEndingEdge(event2.getRawY());
            // To prevent the jump in the clip bounds while closing the notification panel using
            // the handle bar we should calculate the height using the diff of event1 and event2.
            // This will help the notification shade to clip smoothly as the event2 value changes
            // as event1 value will be fixed.
            float diff = mAnimateDirection * (event1.getRawY() - event2.getRawY());
            float y = mAnimateDirection > 0
                    ? getLayout().getHeight() - diff
                    : diff;
            // Ensure the position is within the overlay panel.
            y = Math.max(0, Math.min(y, getLayout().getHeight()));
            setViewClipBounds((int) y);
            return true;
        }
    }
}
